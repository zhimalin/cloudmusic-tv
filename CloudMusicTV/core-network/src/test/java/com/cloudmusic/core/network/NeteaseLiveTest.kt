package com.cloudmusic.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 真实网络集成测试：直接连 `https://music.163.com`，用真实登录态验证 7 个接口。
 *
 * cookie 从 `tools/probe/cookie.json` 读取（文件里是 `cookies.MUSIC_U`），
 * 路径通过系统属性传入：
 *
 * ```
 * gradlew :core-network:testDebugUnitTest -DprobeCookie=C:\...\tools\probe\cookie.json
 * ```
 *
 * 没有传、文件不存在或没有 MUSIC_U 时用 [Assume] 跳过，并**打印跳过原因**（不是静默通过）。
 * 测试日志只打印昵称 / 条数 / url 前 40 字符，不打印完整 url，更不打印 cookie。
 */
class NeteaseLiveTest {

    private lateinit var client: NeteaseClient

    @Before
    fun setUp() {
        val path = System.getProperty(PROPERTY)
        if (path.isNullOrBlank()) {
            println("[LIVE] 跳过原因：没有传 -DprobeCookie=<tools/probe/cookie.json 路径>，真实网络集成测试未执行")
            Assume.assumeTrue("跳过原因：没有传 -DprobeCookie=<cookie.json 路径>", false)
        }

        val file = File(path!!)
        if (!file.isFile) {
            println("[LIVE] 跳过原因：cookie 文件不存在：$path")
            Assume.assumeTrue("跳过原因：cookie 文件不存在，检查 -DprobeCookie 路径", false)
        }

        val cookies = readCookies(file)
        if (cookies["MUSIC_U"].isNullOrBlank()) {
            println("[LIVE] 跳过原因：cookie 文件里没有 MUSIC_U（登录态缺失）")
            Assume.assumeTrue("跳过原因：cookie 文件里没有 MUSIC_U", false)
        }

        client = NeteaseClient(cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        println("[LIVE] 已从 cookie.json 加载 ${cookies.size} 项 cookie（含 MUSIC_U），目标 ${Endpoints.BASE_URL}")
    }

    @Test
    fun accountReturnsNicknameAndUid() = runBlocking {
        val (account, elapsed) = timed { client.account() }

        assertNotNull("[LIVE] account() 返回 null，登录态可能已失效", account)
        assertTrue("[LIVE] account().id 应为正数，实际 ${account!!.id}", account.id > 0)
        assertTrue("[LIVE] account().nickname 不应为空", account.nickname.isNotBlank())

        println("[LIVE] account(): nickname=${account.nickname} uid=${account.id} 耗时=${elapsed}ms")
    }

    @Test
    fun dailySongsReturnsSongs() = runBlocking {
        val (songs, elapsed) = timed { client.dailySongs() }

        assertTrue("[LIVE] dailySongs() 条数应 > 0，实际 ${songs.size}", songs.isNotEmpty())
        assertTrue("[LIVE] 首曲名字不应为空", songs.first().name.isNotBlank())

        println("[LIVE] dailySongs(): ${songs.size} 首，首曲=${songs.first().name} / ${songs.first().artistText} 耗时=${elapsed}ms")
    }

    @Test
    fun threeSongsHaveExhighUrls() = runBlocking {
        for (id in REQUIRED_SONGS) {
            val (songUrl, elapsed) = timed { client.songUrl(id, SongLevel.EXHIGH) }

            assertNotNull("[LIVE] songUrl($id, exhigh) 没有返回条目", songUrl)
            val url = songUrl!!.url
            assertNotNull(
                "[LIVE] songUrl($id, exhigh) 的 url 为空（单曲 code=${songUrl.code} message=${songUrl.message}）",
                url,
            )
            assertTrue("[LIVE] songUrl($id, exhigh) 的 url 不是 http(s) 地址：${url!!.take(12)}", url.startsWith("http"))
            assertTrue("[LIVE] songUrl($id, exhigh) 应判定为可播放", songUrl.isPlayable)

            println(
                "[LIVE] songUrl($id, exhigh): url 前 40 字符=${url.take(40)}… （总长 ${url.length}）" +
                    " level=${songUrl.level} br=${songUrl.br} 耗时=${elapsed}ms",
            )
        }
    }

    @Test
    fun playlistTracksReturnsSongsForFirstPlaylist() = runBlocking {
        val account = client.account()
        assertNotNull("[LIVE] account() 返回 null，无法拿到 uid", account)

        val (playlists, listElapsed) = timed { client.playlists(account!!.id) }
        assertTrue("[LIVE] playlists() 返回空列表", playlists.isNotEmpty())

        val first = playlists.first()
        val (tracks, elapsed) = timed { client.playlistTracks(first.id) }
        assertTrue("[LIVE] 歌单「${first.name}」(${first.id}) 返回 0 首", tracks.isNotEmpty())

        println(
            "[LIVE] playlists(): ${playlists.size} 个歌单（${listElapsed}ms）；" +
                "playlistTracks() 首个歌单「${first.name}」id=${first.id} trackCount=${first.trackCount}" +
                " 返回 ${tracks.size} 首，首曲=${tracks.first().name} 耗时=${elapsed}ms",
        )
    }

    @Test
    fun personalFmReturnsBatchWithFullSongFields() = runBlocking {
        val (songs, elapsed) = timed { client.personalFm() }

        assertTrue("[LIVE] personalFm() 返回 0 首", songs.isNotEmpty())
        val first = songs.first()
        assertTrue("[LIVE] FM 首曲名称为空", first.name.isNotBlank())
        assertTrue("[LIVE] FM 首曲歌手为空", first.artistText.isNotBlank())
        assertTrue("[LIVE] FM 首曲时长为 0", first.durationMs > 0L)

        println(
            "[LIVE] personalFm(): ${songs.size} 首，ids=${songs.joinToString { "${it.id}:${it.name}" }}，" +
                "首曲=${first.name} / ${first.artistText} / ${first.durationMs}ms / fee=${first.fee} " +
                "耗时=${elapsed}ms",
        )
    }

    @Test
    fun setLikedTogglesLikeListAndRestoresOriginalState() = runBlocking {
        val account = client.account()
        assertNotNull("[LIVE] account() 返回 null，无法验证 like", account)
        val song = client.personalFm().first()
        val originalLiked = client.likeList(account!!.id).any { it.id == song.id }
        println("[LIVE] 收藏测试歌曲：${song.id}:${song.name}，原始收藏状态=$originalLiked")

        try {
            client.setLiked(song.id, false)
            val afterUnlike = client.likeList(account.id)
            assertTrue(
                "[LIVE] setLiked(false) 后 likeList 仍包含 ${song.id}",
                afterUnlike.none { it.id == song.id },
            )

            client.setLiked(song.id, true)
            val afterLike = client.likeList(account.id)
            assertTrue(
                "[LIVE] setLiked(true) 后 likeList 不包含 ${song.id}",
                afterLike.any { it.id == song.id },
            )

            println(
                "[LIVE] setLiked 验证通过：unlike 后 ${afterUnlike.size} 首（不含），" +
                    "like 后 ${afterLike.size} 首（包含），最终恢复=$originalLiked",
            )
        } finally {
            client.setLiked(song.id, originalLiked)
            println("[LIVE] 已恢复 ${song.id} 的原始收藏状态=$originalLiked")
        }
    }

    @Test
    fun dislikeSongIsAcceptedAndNextBatchExcludesIt() = runBlocking {
        val batch = client.personalFm()
        assertTrue("[LIVE] dislike 前 personalFm() 返回 0 首", batch.isNotEmpty())
        val victim = batch.first()

        val (_, elapsed) = timed { client.dislikeSong(victim.id) }
        val nextBatch = client.personalFm()
        val presentAgain = nextBatch.any { it.id == victim.id }

        assertTrue("[LIVE] dislike 后 personalFm() 返回 0 首", nextBatch.isNotEmpty())
        assertFalse(
            "[LIVE] dislike 后下一批仍出现被丢弃的歌曲 ${victim.id}，垃圾桶可能未生效",
            presentAgain,
        )
        println(
            "[LIVE] dislikeSong(${victim.id}:${victim.name}) 服务端已接受（code=200），耗时=${elapsed}ms；" +
                "下一批 ${nextBatch.size} 首，ids=${nextBatch.joinToString { it.id.toString() }}，" +
                "被丢歌曲是否再次出现=$presentAgain（该接口不可撤销）",
        )
    }

    @Test
    fun likeListReturnsSongs() = runBlocking {
        val account = client.account()
        assertNotNull("[LIVE] account() 返回 null，无法拿到 uid", account)

        val (songs, elapsed) = timed { client.likeList(account!!.id) }
        assertTrue("[LIVE] likeList() 返回 0 首", songs.isNotEmpty())

        println("[LIVE] likeList(): ${songs.size} 首（喜欢歌单），首曲=${songs.first().name} 耗时=${elapsed}ms")
    }

    @Test
    fun lyricReturnsLrcAndTranslation() = runBlocking {
        val (lyric, elapsed) = timed { client.lyric(REQUIRED_SONGS.first()) }

        assertTrue("[LIVE] lyric() 原文为空", lyric.hasLyric)
        val firstLine = lyric.text!!.lineSequence().first()

        println(
            "[LIVE] lyric(${REQUIRED_SONGS.first()}): 原文 ${lyric.text.length} 字符，" +
                "翻译 ${lyric.translation?.length ?: 0} 字符，首行=${firstLine} 耗时=${elapsed}ms",
        )
    }

    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
        val startedAt = System.currentTimeMillis()
        val value = block()
        return value to (System.currentTimeMillis() - startedAt)
    }

    /** 兼容 `{savedAt, cookies:{...}}` 和扁平的 `{MUSIC_U:...}` 两种写法（和 Node 版一致）。 */
    private fun readCookies(file: File): Map<String, String> {
        val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        val container: JsonObject = (root["cookies"] as? JsonObject) ?: root
        return container.entries
            .mapNotNull { (name, element) ->
                val primitive = element as? JsonPrimitive ?: return@mapNotNull null
                if (primitive.isString) name to primitive.content else null
            }
            .toMap()
    }

    private companion object {
        const val PROPERTY = "probeCookie"

        /** 题目指定的三首歌。 */
        val REQUIRED_SONGS = listOf(2610618711L, 2123113332L, 2610618710L)
    }
}
