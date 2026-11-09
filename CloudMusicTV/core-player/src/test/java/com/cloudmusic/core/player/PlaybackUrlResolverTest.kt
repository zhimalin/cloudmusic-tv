package com.cloudmusic.core.player

import com.cloudmusic.core.network.HttpException
import com.cloudmusic.core.network.NeteaseClient
import com.cloudmusic.core.network.OkHttpEngine
import com.cloudmusic.core.network.SongLevel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 降级链路离线测试：MockWebServer 扮演 `song/url/v1`，逐档返回空/试听/完整地址，
 * 验证 [PlaybackUrlResolver] 的降级顺序、试听标记与全空报错。
 */
class PlaybackUrlResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: OkHttpEngine
    private lateinit var resolver: PlaybackUrlResolver

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = OkHttpEngine()
        val client = NeteaseClient(
            cookie = "",
            baseUrl = server.url("/").toString().trimEnd('/'),
            engine = engine,
        )
        resolver = PlaybackUrlResolver(client)
    }

    @After
    fun tearDown() {
        engine.close()
        server.shutdown()
    }

    // ------------------------------------------------------------------ 降级链路

    @Test
    fun exhighEmptyFallsBackToHigher() = runBlocking {
        server.enqueue(response(urlJson(url = null, level = "exhigh")))
        server.enqueue(response(urlJson(url = "https://cdn.example/higher.mp3", level = "higher", br = 192_000)))

        val resolved = resolver.resolve(songId = 1L, preferred = SongLevel.EXHIGH)

        assertEquals("https://cdn.example/higher.mp3", resolved.url)
        assertEquals(SongLevel.HIGHER, resolved.resolvedLevel)
        assertEquals(SongLevel.EXHIGH, resolved.requestedLevel)
        assertTrue("降级应被标记出来", resolved.usedFallback)
        assertFalse(resolved.isTrialOnly)
        assertEquals(192_000, resolved.br)
        assertEquals(2, resolved.attempts.size)
        assertEquals(UrlOutcome.EMPTY_URL, resolved.attempts[0].outcome)
        assertEquals(SongLevel.EXHIGH, resolved.attempts[0].level)
        assertEquals(UrlOutcome.SUCCESS, resolved.attempts[1].outcome)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun losslessTrialFallsBackToFullExhigh() = runBlocking {
        server.enqueue(
            response(urlJson(url = "https://cdn.example/trial.flac", level = "lossless", trial = true)),
        )
        server.enqueue(
            response(urlJson(url = "https://cdn.example/full.mp3", level = "exhigh", br = 320_000)),
        )

        val resolved = resolver.resolve(songId = 1L, preferred = SongLevel.LOSSLESS)

        assertEquals("试听档不能算成功，应继续降到完整地址", "https://cdn.example/full.mp3", resolved.url)
        assertEquals(SongLevel.EXHIGH, resolved.resolvedLevel)
        assertFalse(resolved.isTrialOnly)
        assertTrue(resolved.usedFallback)
        assertEquals(UrlOutcome.TRIAL_ONLY, resolved.attempts[0].outcome)
        assertEquals(UrlOutcome.SUCCESS, resolved.attempts[1].outcome)
    }

    @Test
    fun missingEntryFallsBackToNextLevel() = runBlocking {
        server.enqueue(response("""{"code":200,"data":[]}"""))
        server.enqueue(response(urlJson(url = "https://cdn.example/higher.mp3", level = "higher")))

        val resolved = resolver.resolve(songId = 1L, preferred = SongLevel.EXHIGH)

        assertEquals(SongLevel.HIGHER, resolved.resolvedLevel)
        assertEquals(UrlOutcome.MISSING, resolved.attempts[0].outcome)
        assertEquals(UrlOutcome.SUCCESS, resolved.attempts[1].outcome)
    }

    @Test
    fun allLevelsEmptyThrowsWithAttemptDetails() {
        repeat(3) { server.enqueue(response(urlJson(url = null, level = "exhigh"))) }

        val error = assertThrows(PlaybackUrlException::class.java) {
            runBlocking { resolver.resolve(songId = 77L, preferred = SongLevel.EXHIGH) }
        }

        assertEquals(77L, error.songId)
        assertEquals(SongLevel.EXHIGH, error.requestedLevel)
        assertEquals("三档都要试过", 3, error.attempts.size)
        assertEquals(
            listOf(SongLevel.EXHIGH, SongLevel.HIGHER, SongLevel.STANDARD),
            error.attempts.map { it.level },
        )
        assertTrue(error.attempts.all { it.outcome == UrlOutcome.EMPTY_URL })
        assertTrue("异常信息里要带每档结果", error.message!!.contains("exhigh:EMPTY_URL"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun allLevelsTrialOnlyReturnsHighestTrialAndFlagsIt() = runBlocking {
        server.enqueue(response(urlJson(url = "https://cdn.example/exhigh.trial", level = "exhigh", trial = true)))
        server.enqueue(response(urlJson(url = "https://cdn.example/higher.trial", level = "higher", trial = true)))
        server.enqueue(response(urlJson(url = "https://cdn.example/standard.trial", level = "standard", trial = true)))

        val resolved = resolver.resolve(songId = 1L, preferred = SongLevel.EXHIGH)

        assertTrue("全试听时仍要返回可播地址并标记试听", resolved.isTrialOnly)
        assertEquals(SongLevel.EXHIGH, resolved.resolvedLevel)
        assertEquals("https://cdn.example/exhigh.trial", resolved.url)
        assertFalse(resolved.usedFallback)
        assertEquals(
            listOf(UrlOutcome.TRIAL_ONLY, UrlOutcome.TRIAL_ONLY, UrlOutcome.TRIAL_ONLY),
            resolved.attempts.map { it.outcome },
        )
    }

    @Test
    fun targetLevelIsTheTopOfFallbackChain() {
        assertEquals(
            listOf(SongLevel.LOSSLESS, SongLevel.EXHIGH, SongLevel.HIGHER, SongLevel.STANDARD),
            PlaybackUrlResolver.fallbackChain(SongLevel.LOSSLESS),
        )
        assertEquals(
            listOf(SongLevel.EXHIGH, SongLevel.HIGHER, SongLevel.STANDARD),
            PlaybackUrlResolver.fallbackChain(SongLevel.EXHIGH),
        )
        assertEquals(
            listOf(SongLevel.HIGHER, SongLevel.STANDARD),
            PlaybackUrlResolver.fallbackChain(SongLevel.HIGHER),
        )
        assertEquals(
            listOf(SongLevel.STANDARD),
            PlaybackUrlResolver.fallbackChain(SongLevel.STANDARD),
        )
    }

    @Test
    fun networkErrorPropagatesInsteadOfBeingSwallowed() {
        // NeteaseClient 对 5xx 重试一次，所以两次 500 后才会抛 HttpException。
        repeat(2) { server.enqueue(MockResponse().setResponseCode(500).setBody("upstream boom")) }

        val error = assertThrows(HttpException::class.java) {
            runBlocking { resolver.resolve(songId = 1L, preferred = SongLevel.EXHIGH) }
        }

        assertEquals(500, error.statusCode)
        assertEquals("异常必须原样抛给调用方，不能被“降级”吞掉", 2, server.requestCount)
    }

    // ------------------------------------------------------------------ 响应构造

    private fun response(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun urlJson(
        url: String?,
        level: String,
        br: Int = 320_000,
        trial: Boolean = false,
    ): String {
        val urlValue = if (url == null) "null" else "\"$url\""
        val trialValue = if (trial) """{"start":0,"end":30000}""" else "null"
        return """
            {"code":200,"data":[{
              "id":1,
              "url":$urlValue,
              "br":$br,
              "size":123456,
              "md5":"0123456789abcdef",
              "level":"$level",
              "encodeType":"mp3",
              "type":"mp3",
              "code":200,
              "fee":1,
              "freeTrialInfo":$trialValue,
              "message":null
            }]}
        """.trimIndent()
    }
}
