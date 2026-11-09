package com.cloudmusic.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * weapi 加密单测。
 *
 * 重点不是「自己和自己对得上」，而是和仓库里已验证的 Node 参考实现
 * `tools/probe/probe.mjs` 逐字节对齐：
 * 固定 secret + 固定明文 → 固定的 params / encSecKey（向量由 Node 版算出后抄进来）。
 */
class WeapiCryptoTest {

    @Test
    fun `encrypt matches the node reference vectors`() {
        // node -e "weapi() with secret 'abcdefghijklmnop'"
        val first = WeapiCrypto.encrypt(
            plainText = """{"ids":"[2610618711]","level":"exhigh","encodeType":"flac","csrf_token":""}""",
            secret = "abcdefghijklmnop",
        )
        assertEquals(
            "747/do+MMLZyPVmU0AvjB9Tghr07U6A7Q3q14xXeSfW++wzOsVVFmkHDLtPKPv0OJ+34QmYH6vqlYLMOroiLSpO7v5NW0eq15kaR0YzJ5MHYp+sjzUkDGLxZqcWB1Sz3WrTVWtTyZaAvWzQn3LXaOQ==",
            first.params,
        )
        assertEquals(
            "d15a1683c992095d0c234c19966605c5c5964911268bbeda8cb8d08d834913e59d53b32358903a121b5fca784c1f5ae4" +
                "4951fd02524df58ecc98e52cc7cf8689b42c2e93ddf05b0592512d87f5960467e2f086c018849d76014d323500e30f1" +
                "3ef4cafbb0cf5a66731a3f1776c75ca35d0062dac70a3e33245afabcf47938487",
            first.encSecKey,
        )

        // 第二组向量含中文/日文，验证 UTF-8 处理和 Node 的 Buffer.from(str,'utf8') 一致
        val second = WeapiCrypto.encrypt(
            plainText = """{"name":"キミの記憶","alias":"你给的记忆","csrf_token":""}""",
            secret = "Zz9Qw8Er7Ty6Ui5P",
        )
        assertEquals(
            "ERWgwM7AzUb9/fUJf4i8QTPxs0YOXu6HPp6TV8JZPBTI5YSKxJ6OhrL7FzQd+Q6A+W15B6tpCsks7ppO5ryYfKNDyvc4o4x" +
                "HbavtsxKNHR1yx1RTUSW+NopSQ+LYrWZT5TyEzdE/4PwJkQOPIz6Glg==",
            second.params,
        )
        assertEquals(
            "a68184c152d0bcbdee4ca4d33124db141a56a4c042b73a24390f19296b9d9bd505248517bed9b9c99e00a55220261f2" +
                "eb3b05c7b5ab2e96f120d939fbd2a9273c74766c95b106d8ccf7376418d5fdef36ccb5774d4d869033c41c5185b2345" +
                "f000b6d4297f5545860c7c87eb6c6076fec2639e5dadd0516cf11a353a62122a73",
            second.encSecKey,
        )
    }

    @Test
    fun `params can be decrypted back with the same algorithm`() {
        val plain = """{"uid":500661721,"limit":1000,"offset":0,"csrf_token":""}"""
        val payload = WeapiCrypto.encrypt(plain)

        assertEquals(plain, WeapiCrypto.decryptParams(payload.params, payload.secretKey))

        // 两轮 AES：只解 first 轮（secret）拿到的还是 PRESET_KEY 的密文，不是明文
        val afterFirstRound = WeapiCrypto.aesDecrypt(payload.params, payload.secretKey)
        assertNotEquals(plain, afterFirstRound)
        assertEquals(plain, WeapiCrypto.aesDecrypt(afterFirstRound, WeapiCrypto.PRESET_KEY))
    }

    @Test
    fun `same plain text encrypts differently because the secret is random`() {
        val plain = """{"id":2610618711,"lv":-1,"kv":-1,"tv":-1,"csrf_token":""}"""
        val first = WeapiCrypto.encrypt(plain)
        val second = WeapiCrypto.encrypt(plain)

        assertNotEquals("两次加密的 secret 必须不同", first.secretKey, second.secretKey)
        assertNotEquals("随机 key 必须让 params 不同", first.params, second.params)
        assertNotEquals("随机 key 必须让 encSecKey 不同", first.encSecKey, second.encSecKey)

        // 但两份密文都解得回同一份明文
        assertEquals(plain, WeapiCrypto.decryptParams(first.params, first.secretKey))
        assertEquals(plain, WeapiCrypto.decryptParams(second.params, second.secretKey))
    }

    @Test
    fun `params is valid base64 and encSecKey is 256 hex chars`() {
        repeat(8) { attempt ->
            val payload = WeapiCrypto.encrypt("""{"attempt":$attempt,"csrf_token":""}""")

            val decoded = Base64.getDecoder().decode(payload.params)
            assertTrue(
                "AES/CBC/PKCS5 输出长度必须是 16 的整数倍，实际 ${decoded.size}",
                decoded.size % 16 == 0,
            )
            assertEquals(
                "params 必须是合法 base64（解码后重新编码一致）",
                payload.params,
                Base64.getEncoder().encodeToString(decoded),
            )

            assertEquals(WeapiCrypto.ENC_SEC_KEY_LENGTH, payload.encSecKey.length)
            assertTrue(
                "encSecKey 必须是 256 位小写 hex，实际 ${payload.encSecKey}",
                payload.encSecKey.matches(Regex("[0-9a-f]{256}")),
            )
        }
    }

    @Test
    fun `random secret is 16 chars from the node alphabet`() {
        val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toSet()
        repeat(200) {
            val secret = WeapiCrypto.randomSecret()
            assertEquals(WeapiCrypto.SECRET_LENGTH, secret.length)
            assertTrue("secret 含协议字符表以外的字符: $secret", secret.all { char -> char in allowed })
        }
    }
}
