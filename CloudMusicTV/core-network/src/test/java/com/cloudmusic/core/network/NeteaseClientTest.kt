package com.cloudmusic.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 离线集成测试：用 MockWebServer 假装 music.163.com，验证
 * 1. 请求形态（路径 / 方法 / 请求头 / cookie / 表单体）；
 * 2. 请求体里的 `params` 能按 weapi 解密回**预期的 JSON**（secret 固定，故可解密）；
 * 3. 四类错误映射（传输 / HTTP / 业务 code / 解析）。
 *
 * 这组测试不需要真实网络，`gradlew :core-network:testDebugUnitTest` 一定跑得到。
 */
class NeteaseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: OkHttpEngine
    private lateinit var client: NeteaseClient

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = OkHttpEngine()
        client = dynamicClient()
    }

    @After
    fun tearDown() {
        engine.close()
        server.shutdown()
    }

    // ------------------------------------------------------------------ 正常路径

    @Test
    fun accountParsesProfileAndSendsWeapiPost() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"account":{"id":42},"profile":{"userId":42,"nickname":"芝麻凛的抚子",""" +
                    """"avatarUrl":"https://p1.music.126.net/x.jpg","signature":"抚子酱今天也很可爱","vipType":11}}""",
            ),
        )

        val account = client.account()

        assertNotNull("account() 不应吞掉 profile", account)
        assertEquals(42L, account?.id)
        assertEquals("芝麻凛的抚子", account?.nickname)
        assertEquals("https://p1.music.126.net/x.jpg", account?.avatarUrl)
        assertEquals("抚子酱今天也很可爱", account?.signature)

        val request = nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/weapi/nuser/account/get?csrf_token=csrf-abc", request.path)
        assertEquals("https://music.163.com/", request.getHeader("Referer"))
        assertEquals(Endpoints.USER_AGENT, request.getHeader("User-Agent"))
        assertEquals("https://music.163.com", request.getHeader("Origin"))
        assertTrue(request.getHeader("Cookie")!!.contains("MUSIC_U=fake-session"))
        assertTrue(
            "Content-Type 必须是 form-urlencoded",
            request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"),
        )

        val form = request.body.readUtf8()
        assertTrue("表单体应以 params= 开头，实际 ${form.take(20)}", form.startsWith("params="))
        assertEquals(
            "encSecKey 必须是固定 secret 的 RSA 结果",
            WeapiCrypto.rsaEncrypt(FIXED_SECRET),
            form.substringAfter("&encSecKey="),
        )
        assertEquals(
            "请求体解密后应是 {参数 + csrf_token}",
            json.parseToJsonElement("""{"csrf_token":"csrf-abc"}"""),
            decryptedJson(form),
        )
    }

    @Test
    fun anonymousAccountReturnsNull() = runBlocking {
        server.enqueue(jsonResponse("""{"code":200,"account":null,"profile":null}"""))

        assertNull("未登录时 user/account 的 profile 为 null，account() 应返回 null", client.account())
    }

    @Test
    fun songUrlSendsExpectedParamsAndParsesUrl() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"data":[{"id":2610618711,"url":"http://m704.music.126.net/a.mp3","br":320000,""" +
                    """"size":4359358,"md5":"99fb03d4b8be6d400cb1d344efe7a197","level":"exhigh","encodeType":"mp3",""" +
                    """"type":"mp3","code":200,"fee":1,"freeTrialInfo":null}]}""",
            ),
        )

        val songUrl = client.songUrl(2610618711, SongLevel.EXHIGH)

        assertNotNull(songUrl)
        assertEquals("http://m704.music.126.net/a.mp3", songUrl?.url)
        assertEquals(320000, songUrl?.br)
        assertEquals(4359358L, songUrl?.size)
        assertEquals("exhigh", songUrl?.level)
        assertEquals("mp3", songUrl?.encodeType)
        assertTrue(songUrl!!.isPlayable)
        assertFalse(songUrl.isTrialOnly)

        val request = nextRequest()
        assertEquals("/weapi/song/enhance/player/url/v1?csrf_token=csrf-abc", request.path)
        val payload = decryptedJson(request)
        assertEquals("[2610618711]", payload["ids"]?.jsonPrimitive?.content)
        assertEquals("exhigh", payload["level"]?.jsonPrimitive?.content)
        assertEquals("flac", payload["encodeType"]?.jsonPrimitive?.content)
        assertEquals("csrf-abc", payload["csrf_token"]?.jsonPrimitive?.content)
    }

    @Test
    fun unplayableSongUrlIsDataNotException() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"data":[{"id":1,"url":null,"br":0,"size":0,"code":404,"fee":1,""" +
                    """"freeTrialInfo":{"start":0,"end":30},"message":"无版权"}]}""",
            ),
        )

        val songUrl = client.songUrl(1, SongLevel.LOSSLESS)

        assertNotNull(songUrl)
        assertFalse(songUrl!!.isPlayable)
        assertTrue("freeTrialInfo 非空应判定为仅试听", songUrl.isTrialOnly)
        assertEquals(404, songUrl.code)
        assertEquals("无版权", songUrl.message)
    }

    @Test
    fun dailySongsParsesNestedDataAndSendsCsrf() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"data":{"fromCache":false,"dailySongs":[
                    {"id":403180,"name":"キミの記憶","ar":[{"id":1,"name":"川村ゆみ"}],
                     "al":{"id":39841,"name":"ペルソナ3 オリジナル・サウンドトラック","picUrl":"http://p1.music.126.net/a.jpg"},
                     "dt":369386,"fee":8,"alia":["Memories of You"]},
                    {"id":2123113332,"name":"Color Your Night","ar":[{"id":2,"name":"Lotus Juice"}],"al":{"id":3,"name":"P3R"},"dt":227240,"fee":1}
                  ]}}""",
            ),
        )

        val songs = client.dailySongs()

        assertEquals(2, songs.size)
        assertEquals("キミの記憶", songs[0].name)
        assertEquals("川村ゆみ", songs[0].artistText)
        assertEquals("ペルソナ3 オリジナル・サウンドトラック", songs[0].album?.name)
        assertEquals(369386L, songs[0].durationMs)
        assertEquals("キミの記憶 (Memories of You)", songs[0].displayName)
        assertEquals("首曲是 VIP 曲目（fee=8）", 8, songs[0].fee)

        val request = nextRequest()
        assertEquals("/weapi/v3/discovery/recommend/songs?csrf_token=csrf-abc", request.path)
        assertEquals(
            json.parseToJsonElement("""{"csrf_token":"csrf-abc"}"""),
            decryptedJson(request),
        )
    }

    @Test
    fun personalFmParsesFullSongFieldsAndUsesVerifiedPath() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"popAdjust":false,"tag":null,"data":[
                    {"name":"炎","id":429210891,"alias":[],"fee":1,"duration":309429,
                     "artists":[{"name":"FLOWER FLOWER","id":789241}],
                     "album":{"name":"宝物","id":34860972,"picUrl":"http://p2.music.126.net/a.jpg"}},
                    {"name":"白猫海賊船","id":1343016814,"alias":["白猫海賊船 (Shiro Neko Kaizokusen)"],
                     "fee":0,"duration":222000,"artists":[{"name":"ヨルシカ","id":1}],
                     "album":{"name":"幻燈","id":2,"picUrl":"https://p2.music.126.net/b.jpg"}}
                  ],"code":200}""",
            ),
        )

        val songs = client.personalFm()

        assertEquals(2, songs.size)
        assertEquals("炎", songs[0].name)
        assertEquals("FLOWER FLOWER", songs[0].artistText)
        assertEquals("宝物", songs[0].album?.name)
        assertEquals(309429L, songs[0].durationMs)
        assertEquals(1, songs[0].fee)
        assertEquals("白猫海賊船 (Shiro Neko Kaizokusen)", songs[1].alias.first())

        val request = nextRequest()
        assertEquals("/weapi/v1/radio/get?csrf_token=csrf-abc", request.path)
        assertEquals(
            json.parseToJsonElement("""{"csrf_token":"csrf-abc"}"""),
            decryptedJson(request),
        )
    }

    @Test
    fun dislikeSendsVerifiedQueryAndBody() = runBlocking {
        server.enqueue(jsonResponse("""{"songs":[],"count":0,"code":200}"""))

        client.dislikeSong(429210891L)

        val request = nextRequest()
        assertEquals(
            "/weapi/radio/trash/add?csrf_token=csrf-abc&alg=RT&songId=429210891&time=25",
            request.path,
        )
        val payload = decryptedJson(request)
        assertEquals("429210891", payload["songId"]?.jsonPrimitive?.content)
        assertEquals("csrf-abc", payload["csrf_token"]?.jsonPrimitive?.content)
    }

    @Test
    fun setLikedSendsRadioLikeAndRequiredRiskCookies() = runBlocking {
        server.enqueue(jsonResponse("""{"songs":[],"playlistId":751978189,"code":200}"""))

        client.setLiked(429210891L, true)

        val request = nextRequest()
        assertEquals("/weapi/radio/like?csrf_token=csrf-abc", request.path)
        val cookie = request.getHeader("Cookie")!!
        assertTrue("缺少风控字段会返回 -460", cookie.contains("os=pc"))
        assertTrue("缺少风控字段会返回 -460", cookie.contains("appver=2.7.1.198277"))
        assertTrue(cookie.contains("MUSIC_U=fake-session"))

        val payload = decryptedJson(request)
        assertEquals("itembased", payload["alg"]?.jsonPrimitive?.content)
        assertEquals("429210891", payload["trackId"]?.jsonPrimitive?.content)
        assertEquals("true", payload["like"]?.jsonPrimitive?.content)
        assertEquals("3", payload["time"]?.jsonPrimitive?.content)
    }

    @Test
    fun playlistsParsesList() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"more":false,"playlist":[
                    {"id":751978189,"name":"芝麻凛的抚子喜欢的音乐","coverImgUrl":"http://p1.music.126.net/i.jpg",
                     "trackCount":547,"playCount":6399,"specialType":5,"userId":500661721,
                     "creator":{"userId":500661721,"nickname":"芝麻凛的抚子"},"updateTime":1794106884214},
                    {"id":123,"name":"其他歌单","trackCount":10,"specialType":0,"userId":500661721}
                  ]}""",
            ),
        )

        val playlists = client.playlists(500661721)

        assertEquals(2, playlists.size)
        assertTrue(playlists[0].isLikedPlaylist)
        assertEquals("芝麻凛的抚子喜欢的音乐", playlists[0].name)
        assertEquals(547, playlists[0].trackCount)
        assertEquals("芝麻凛的抚子", playlists[0].creator?.nickname)
        assertFalse(playlists[1].isLikedPlaylist)

        val request = nextRequest()
        assertEquals("/weapi/user/playlist?csrf_token=csrf-abc", request.path)
        val payload = decryptedJson(request)
        assertEquals("500661721", payload["uid"]?.jsonPrimitive?.content)
        assertEquals("1000", payload["limit"]?.jsonPrimitive?.content)
        assertEquals("0", payload["offset"]?.jsonPrimitive?.content)
    }

    @Test
    fun playlistTracksFallsBackToSongDetailWhenTracksAreTruncated() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/weapi/v6/playlist/detail") -> jsonResponse(
                        """{"code":200,"playlist":{"id":7,"trackIds":[{"id":1},{"id":2},{"id":3}],
                            "tracks":[{"id":1,"name":"A","ar":[{"id":9,"name":"X"}],"al":{"id":10,"name":"AL"},"dt":1000,"fee":0}]}}""",
                    )
                    path.startsWith("/weapi/v3/song/detail") -> jsonResponse(
                        """{"code":200,"songs":[{"id":1,"name":"A"},{"id":2,"name":"B"},{"id":3,"name":"C"}]}""",
                    )
                    else -> MockResponse().setResponseCode(404).setBody("unexpected path: $path")
                }
            }
        }

        val tracks = client.playlistTracks(7)

        assertEquals(listOf("A", "B", "C"), tracks.map { it.name })

        val detailRequest = nextRequest()
        assertEquals("/weapi/v6/playlist/detail?csrf_token=csrf-abc", detailRequest.path)
        val detailPayload = decryptedJson(detailRequest)
        assertEquals("7", detailPayload["id"]?.jsonPrimitive?.content)
        assertEquals("100000", detailPayload["n"]?.jsonPrimitive?.content)
        assertEquals("8", detailPayload["s"]?.jsonPrimitive?.content)

        val songDetailRequest = nextRequest()
        assertEquals("/weapi/v3/song/detail?csrf_token=csrf-abc", songDetailRequest.path)
        assertEquals(
            """[{"id":1},{"id":2},{"id":3}]""",
            decryptedJson(songDetailRequest)["c"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun playlistTracksUsesTracksDirectlyWhenComplete() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"playlist":{"id":7,"trackIds":[{"id":1},{"id":2}],
                    "tracks":[{"id":1,"name":"A"},{"id":2,"name":"B"}]}}""",
            ),
        )

        val tracks = client.playlistTracks(7)

        assertEquals(listOf("A", "B"), tracks.map { it.name })
        assertEquals("只应发一次歌单详情请求", 1, server.requestCount)
    }

    @Test
    fun likeListUsesTheLikedPlaylist() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/weapi/user/playlist") -> jsonResponse(
                        """{"code":200,"playlist":[
                            {"id":9,"name":"普通歌单","specialType":0},
                            {"id":751978189,"name":"芝麻凛的抚子喜欢的音乐","specialType":5,"trackCount":2}]}""",
                    )
                    path.startsWith("/weapi/v6/playlist/detail") -> jsonResponse(
                        """{"code":200,"playlist":{"id":751978189,"trackIds":[{"id":11},{"id":12}],
                            "tracks":[{"id":11,"name":"喜欢1"},{"id":12,"name":"喜欢2"}]}}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val songs = client.likeList(500661721)

        assertEquals(listOf("喜欢1", "喜欢2"), songs.map { it.name })
        nextRequest() // user/playlist
        val detailRequest = nextRequest()
        assertEquals(
            "应取 specialType=5 的喜欢歌单",
            "751978189",
            decryptedJson(detailRequest)["id"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun lyricParsesOriginalAndTranslation() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"lrc":{"version":3,"lyric":"[00:07.109]We can only move forward"},""" +
                    """"tlyric":{"version":3,"lyric":"[00:07.109]我们只能向前"},"klyric":{"version":0,"lyric":""}}""",
            ),
        )

        val lyric = client.lyric(2610618711)

        assertEquals("[00:07.109]We can only move forward", lyric.text)
        assertEquals("[00:07.109]我们只能向前", lyric.translation)
        assertNull(lyric.roma)
        assertTrue(lyric.hasLyric)

        val request = nextRequest()
        assertEquals("/weapi/song/lyric?csrf_token=csrf-abc", request.path)
        val payload = decryptedJson(request)
        assertEquals("2610618711", payload["id"]?.jsonPrimitive?.content)
        assertEquals("-1", payload["lv"]?.jsonPrimitive?.content)
        assertEquals("-1", payload["tv"]?.jsonPrimitive?.content)
    }

    @Test
    fun cookieIsReadPerRequestAndNmtidIsAddedWhenMissing() = runBlocking {
        var session = "MUSIC_U=first"
        val dynamic = dynamicClient(cookieProvider = { session })

        server.enqueue(jsonResponse("""{"code":200,"profile":{"userId":1,"nickname":"A"}}"""))
        dynamic.account()
        val firstCookie = nextRequest().getHeader("Cookie")!!

        session = "MUSIC_U=second"
        server.enqueue(jsonResponse("""{"code":200,"profile":{"userId":1,"nickname":"A"}}"""))
        dynamic.account()
        val secondCookie = nextRequest().getHeader("Cookie")!!

        assertTrue(firstCookie.contains("MUSIC_U=first"))
        assertTrue("cookieProvider 必须每次请求都重新取", secondCookie.contains("MUSIC_U=second"))
        assertTrue("缺少 NMTID 时应自动补一个", secondCookie.matches(Regex(".*NMTID=[0-9a-f]{32}.*")))
    }

    // ------------------------------------------------------------------ 错误映射

    @Test
    fun httpErrorIsRetriedOnceThenThrowsHttpException() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server down"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("server down"))

        val error = assertThrows(HttpException::class.java) {
            runBlocking { client.account() }
        }

        assertEquals(500, error.statusCode)
        assertEquals("HTTP 状态码要带在异常里", 500, error.statusCode)
        assertTrue("异常信息应包含状态码", error.message!!.contains("HTTP 错误 500"))
        assertEquals("429/5xx 应重试一次，共 2 次请求", 2, server.requestCount)
    }

    @Test
    fun businessErrorThrowsApiExceptionWithCodeAndMessage() {
        server.enqueue(jsonResponse("""{"code":301,"message":"需要登录"}"""))

        val error = assertThrows(ApiException::class.java) {
            runBlocking { client.dailySongs() }
        }

        assertEquals(301, error.code)
        assertEquals("需要登录", error.apiMessage)
        assertTrue(error.message!!.contains("code=301"))
        assertTrue(error.message!!.contains("需要登录"))
        assertTrue("异常里应带上是哪个接口", error.message!!.contains("/api/v3/discovery/recommend/songs"))
        assertEquals("业务错误不该重试", 1, server.requestCount)
    }

    @Test
    fun htmlErrorPageThrowsParseException() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>502 Bad Gateway</body></html>"),
        )

        val error = assertThrows(ParseException::class.java) {
            runBlocking { client.account() }
        }

        assertTrue(error.message!!.contains("响应解析失败"))
        assertNotNull(error.responseBody)
    }

    @Test
    fun connectionFailureThrowsTransportException() {
        server.shutdown()

        val error = assertThrows(TransportException::class.java) {
            runBlocking { client.account() }
        }

        assertTrue(error.message!!.contains("网络请求失败"))
        assertNotNull("原始 IOException 必须作为 cause 保留", error.cause)
    }

    // ------------------------------------------------------------------ 辅助

    private fun dynamicClient(
        cookieProvider: () -> String = { "MUSIC_U=fake-session; __csrf=csrf-abc" },
    ): NeteaseClient = NeteaseClient(
        cookieProvider = cookieProvider,
        baseUrl = server.url("/").toString(),
        engine = engine,
        secretProvider = { FIXED_SECRET },
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    /** 取下一个请求；带超时，避免实现出错时测试永久阻塞。 */
    private fun nextRequest(): RecordedRequest =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "5 秒内没有收到预期的请求" }

    /** 把请求体里的 params 解密回 JSON，用来断言客户端到底发了什么。 */
    private fun decryptedJson(request: RecordedRequest): JsonObject = decryptedJson(request.body.readUtf8())

    private fun decryptedJson(form: String): JsonObject {
        val encoded = form.substringAfter("params=").substringBefore("&encSecKey=")
        val params = URLDecoder.decode(encoded, "UTF-8")
        val plain = WeapiCrypto.decryptParams(params, FIXED_SECRET)
        return json.parseToJsonElement(plain) as JsonObject
    }

    private companion object {
        /** 固定 secret，测试里才能把 params 解回明文；生产环境用 WeapiCrypto.randomSecret()。 */
        const val FIXED_SECRET = "abcdefghijklmnop"
    }
}
