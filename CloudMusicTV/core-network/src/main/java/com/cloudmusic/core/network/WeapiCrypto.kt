package com.cloudmusic.core.network

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * weapi 协议加密，逐行对照 `tools/probe/probe.mjs` 移植：
 *
 * ```
 * params    = AES128CBC( AES128CBC(明文JSON, PRESET_KEY), 随机16位secret )
 * encSecKey = RSA_NO_PADDING( reverse(secret) )   // 固定 modulus/exponent，hex 补零到 256 位
 * ```
 *
 * 常量（IV / PRESET_KEY / MODULUS / PUB_KEY）是从探针原样抄过来的公开协议常量，
 * 不是猜的；`WeapiCryptoTest` 里还用 Node 版算出的向量做了逐字节比对。
 *
 * 用 `java.util.Base64` 而不是 `android.util.Base64`：core-network 不依赖 Android API，
 * 这样单测可以直接在电脑的 JVM 上跑。
 */
internal object WeapiCrypto {

    /** weapi 固定的 16 字节 IV。 */
    private const val IV = "0102030405060708"

    /** 第一轮 AES 用的固定 key。 */
    internal const val PRESET_KEY = "0CoJUm6Qyw8W8jud"

    /** RSA 公钥指数（hex）。 */
    private const val PUBLIC_EXPONENT_HEX = "010001"

    /** RSA modulus（hex），1024 bit。 */
    private const val MODULUS_HEX =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417" +
            "629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce" +
            "10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    /** 随机 secret 的字符表，和 Node 版一致（62 个字符）。 */
    private const val SECRET_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** secret 长度固定 16，正好是 AES-128 的 key 长度。 */
    const val SECRET_LENGTH = 16

    /** encSecKey 的 hex 长度（1024 bit / 4 bit per hex char）。 */
    const val ENC_SEC_KEY_LENGTH = 256

    private val secureRandom = SecureRandom()
    private val modulus = BigInteger(MODULUS_HEX, 16)
    private val publicExponent = BigInteger(PUBLIC_EXPONENT_HEX, 16)

    /** 生成 16 位随机 secret（只含 [SECRET_ALPHABET] 里的字符）。 */
    fun randomSecret(): String {
        val chars = CharArray(SECRET_LENGTH)
        repeat(SECRET_LENGTH) { index ->
            chars[index] = SECRET_ALPHABET[secureRandom.nextInt(SECRET_ALPHABET.length)]
        }
        return String(chars)
    }

    /**
     * 对明文 JSON 做完整的两轮 AES + RSA，返回可以直接放进表单的 `params` / `encSecKey`。
     *
     * @param plainText 明文 JSON（调用方保证 key 顺序与预期一致）
     * @param secret 第二轮 AES 的随机 key；默认随机生成，测试里可以传固定值做逐字节比对
     */
    fun encrypt(plainText: String, secret: String = randomSecret()): WeapiPayload {
        require(secret.length == SECRET_LENGTH) { "weapi secret 必须是 $SECRET_LENGTH 位，实际 ${secret.length}" }
        return WeapiPayload(
            params = aesEncrypt(aesEncrypt(plainText, PRESET_KEY), secret),
            encSecKey = rsaEncrypt(secret),
            secretKey = secret,
        )
    }

    /** AES-128-CBC/PKCS5Padding + Base64，等价于 Node 的 `crypto.createCipheriv('aes-128-cbc', key, IV)`。 */
    fun aesEncrypt(plainText: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)))
    }

    /** [aesEncrypt] 的逆运算，单测用它把 `params` 解回明文。 */
    fun aesDecrypt(cipherTextBase64: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        return String(cipher.doFinal(Base64.getDecoder().decode(cipherTextBase64)), Charsets.UTF_8)
    }

    /**
     * 按 Node 版做法做 RSA：把 secret 反转后当大整数，用固定公钥做模幂，输出 256 位 hex。
     *
     * 注意用 `BigInteger(1, bytes)`（正数）而不是 `BigInteger(bytes)`：
     * 后者按二进制补码解释，反转后首字节 >= 0x80 时会得到负数，结果就不对了。
     */
    fun rsaEncrypt(secret: String): String {
        val reversed = StringBuilder(secret).reverse().toString()
        val plain = BigInteger(1, reversed.toByteArray(Charsets.UTF_8))
        return plain.modPow(publicExponent, modulus).toString(16).padStart(ENC_SEC_KEY_LENGTH, '0')
    }

    /** 把 `params` 解密回明文 JSON：AES(secret) 解一轮，再用 [PRESET_KEY] 解一轮。 */
    fun decryptParams(paramsBase64: String, secret: String): String =
        aesDecrypt(aesDecrypt(paramsBase64, secret), PRESET_KEY)
}

/** 一次 weapi 加密的产物；[secretKey] 只给测试解密用，不会发给服务端。 */
internal data class WeapiPayload(
    val params: String,
    val encSecKey: String,
    val secretKey: String,
)
