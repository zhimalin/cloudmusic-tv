package com.cloudmusic.core.data.music

import com.cloudmusic.core.network.NeteaseClient
import com.cloudmusic.core.network.OkHttpEngine
import com.cloudmusic.core.network.Playlist
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Offline JVM tests for the repository + the three state containers.
 *
 * NeteaseClient itself is already covered by core-network. These tests go one
 * layer up: real weapi requests against MockWebServer, real JSON parsing, and
 * assertions on the *state* each screen would render.
 */
class MusicStateTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: OkHttpEngine
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = OkHttpEngine()
        repository = MusicRepository(
            NeteaseClient(
                cookie = "MUSIC_U=fake-session; __csrf=csrf-abc",
                baseUrl = server.url("/").toString(),
                engine = engine,
            ),
        )
    }

    @After
    fun tearDown() {
        engine.close()
        server.shutdown()
    }

    // ------------------------------------------------------------------ library

    @Test
    fun libraryLoadsAccountThenSplitsPlaylists() = runBlocking {
        server.enqueue(jsonResponse(ACCOUNT_RESPONSE))
        server.enqueue(
            jsonResponse(
                """
                {"code":200,"more":false,"playlist":[
                  {"id":751978189,"name":"芝麻凛的抚子喜欢的音乐","coverImgUrl":"http://p1.music.126.net/liked.jpg",
                   "trackCount":547,"specialType":5,"userId":42,
                   "creator":{"userId":42,"nickname":"芝麻凛的抚子"},"subscribed":false},
                  {"id":201,"name":"我创建的歌单","coverImgUrl":"http://p1.music.126.net/created.jpg",
                   "trackCount":12,"specialType":0,"userId":42,
                   "creator":{"userId":42,"nickname":"芝麻凛的抚子"},"subscribed":false},
                  {"id":202,"name":"收藏的爵士歌单","coverImgUrl":"http://p1.music.126.net/jazz.jpg",
                   "trackCount":88,"specialType":0,"userId":99,
                   "creator":{"userId":99,"nickname":"Jazz Cat"},"subscribed":true}
                ]}
                """.trimIndent(),
            ),
        )

        val library = LibraryState(repository)
        library.load()

        val state = library.state.value
        assertTrue("有数据时应是 Ready，实际 $state", state is LibraryUiState.Ready)
        val ready = state as LibraryUiState.Ready
        assertEquals(42L, ready.uid)
        assertEquals(listOf("芝麻凛的抚子喜欢的音乐"), ready.liked.map { it.name })
        assertEquals(listOf("我创建的歌单"), ready.created.map { it.name })
        assertEquals(listOf("收藏的爵士歌单"), ready.subscribed.map { it.name })
        assertEquals("封面字段要保留", "http://p1.music.126.net/liked.jpg", ready.liked.single().coverImgUrl)
        assertEquals(547, ready.liked.single().trackCount)
        assertEquals("芝麻凛的抚子", ready.created.single().creator?.nickname)
        assertTrue("specialType=5 必须识别为喜欢歌单", ready.liked.single().isLikedPlaylist)

        val accountRequest = nextRequest()
        assertEquals("/weapi/nuser/account/get?csrf_token=csrf-abc", accountRequest.path)
        val playlistRequest = nextRequest()
        assertEquals("/weapi/user/playlist?csrf_token=csrf-abc", playlistRequest.path)
    }

    @Test
    fun libraryAccountNullBecomesNeedLogin() = runBlocking {
        server.enqueue(jsonResponse("""{"code":200,"account":null,"profile":null}"""))

        val library = LibraryState(repository)
        library.load()

        assertSame(LibraryUiState.NeedLogin, library.state.value)
        assertEquals("未登录时不应继续请求歌单", 1, server.requestCount)
    }

    @Test
    fun libraryEmptyListBecomesEmptyNotReady() = runBlocking {
        server.enqueue(jsonResponse(ACCOUNT_RESPONSE))
        server.enqueue(jsonResponse("""{"code":200,"more":false,"playlist":[]}"""))

        val library = LibraryState(repository)
        library.load()

        assertSame(LibraryUiState.Empty, library.state.value)
    }

    @Test
    fun libraryTransportFailureBecomesErrorWithCode() = runBlocking {
        // NeteaseClient retries a transport failure once, so two broken responses.
        repeat(2) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        }

        val library = LibraryState(repository, knownUid = 42L)
        library.load()

        val state = library.state.value
        assertTrue("网络失败应为 Error，实际 $state", state is LibraryUiState.Error)
        val error = (state as LibraryUiState.Error).error
        assertEquals("NETWORK", error.code)
        assertNotNull("异常必须作为 cause 保留，不能吞掉", error.cause)
    }

    @Test
    fun libraryHttpFailureKeepsHttpCode() = runBlocking {
        // NeteaseClient retries a 5xx once, so queue two.
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("server down"),
            )
        }

        val library = LibraryState(repository, knownUid = 42L)
        library.load()

        val state = library.state.value
        assertTrue("HTTP 失败应为 Error，实际 $state", state is LibraryUiState.Error)
        assertEquals("HTTP_500", (state as LibraryUiState.Error).error.code)
        assertEquals(2, server.requestCount)
    }

    // ------------------------------------------------------------------ playlist detail

    @Test
    fun playlistDetailParsesSongsAndFields() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {"code":200,"playlist":{"id":7,"trackIds":[{"id":1},{"id":2}],"tracks":[
                  {"id":1,"name":"キミの記憶","ar":[{"id":9,"name":"川村ゆみ"}],
                   "al":{"id":10,"name":"ペルソナ3 OST"},"dt":369386,"fee":8,"alia":["Memories of You"]},
                  {"id":2,"name":"Color Your Night","ar":[{"id":11,"name":"Lotus Juice"}],
                   "al":{"id":12,"name":"P3R"},"dt":227240,"fee":1}
                ]}}
                """.trimIndent(),
            ),
        )

        val playlist = Playlist(id = 7, name = "Persona 精选", trackCount = 2)
        val detail = PlaylistDetailState(repository, playlist)
        detail.load()

        val state = detail.state.value
        assertTrue("有曲目时应是 Ready，实际 $state", state is PlaylistDetailUiState.Ready)
        val ready = state as PlaylistDetailUiState.Ready
        assertEquals(2, ready.songs.size)
        assertEquals("キミの記憶", ready.songs[0].name)
        assertEquals("川村ゆみ", ready.songs[0].artistText)
        assertEquals("ペルソナ3 OST", ready.songs[0].album?.name)
        assertEquals(369386L, ready.songs[0].durationMs)
        assertEquals(8, ready.songs[0].fee)
        assertEquals("Persona 精选", ready.playlist.name)

        assertEquals("/weapi/v6/playlist/detail?csrf_token=csrf-abc", nextRequest().path)
    }

    @Test
    fun likedPlaylistDetailUsesLikedSongsFlow() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {"code":200,"playlist":[
                  {"id":99,"name":"我喜欢的音乐","specialType":5,"trackCount":2,"userId":42,
                   "creator":{"userId":42,"nickname":"芝麻凛的抚子"}}
                ]}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                {"code":200,"playlist":{"id":99,"trackIds":[{"id":11},{"id":12}],"tracks":[
                  {"id":11,"name":"喜欢 1","ar":[{"id":1,"name":"A"}],"al":{"id":1,"name":"AL"},"dt":61000,"fee":0},
                  {"id":12,"name":"喜欢 2","ar":[{"id":2,"name":"B"}],"al":{"id":1,"name":"AL"},"dt":62000,"fee":0}
                ]}}
                """.trimIndent(),
            ),
        )

        val likedPlaylist = Playlist(
            id = 99,
            name = "我喜欢的音乐",
            specialType = Playlist.SPECIAL_TYPE_LIKED,
            trackCount = 2,
        )
        val detail = PlaylistDetailState(repository, likedPlaylist, uid = 42L)
        detail.load()

        val state = detail.state.value
        assertTrue("喜欢歌单应走 likeList 路径，实际 $state", state is PlaylistDetailUiState.Ready)
        assertEquals(listOf("喜欢 1", "喜欢 2"), (state as PlaylistDetailUiState.Ready).songs.map { it.name })

        assertEquals("/weapi/user/playlist?csrf_token=csrf-abc", nextRequest().path)
        assertEquals("/weapi/v6/playlist/detail?csrf_token=csrf-abc", nextRequest().path)
    }

    @Test
    fun playlistDetailEmptyBecomesEmpty() = runBlocking {
        server.enqueue(
            jsonResponse("""{"code":200,"playlist":{"id":7,"trackIds":[],"tracks":[]}}"""),
        )

        val detail = PlaylistDetailState(repository, Playlist(id = 7, name = "空歌单"))
        detail.load()

        val state = detail.state.value
        assertTrue("空曲目应是 Empty，实际 $state", state is PlaylistDetailUiState.Empty)
        assertEquals(7L, (state as PlaylistDetailUiState.Empty).playlist.id)
    }

    // ------------------------------------------------------------------ daily

    @Test
    fun dailyParsesDateAndSongs() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {"code":200,"data":{"fromCache":false,"dailySongs":[
                  {"id":403180,"name":"キミの記憶","ar":[{"id":1,"name":"川村ゆみ"}],
                   "al":{"id":39841,"name":"ペルソナ3 オリジナル・サウンドトラック"},
                   "dt":369386,"fee":8,"alia":["Memories of You"]},
                  {"id":2123113332,"name":"Color Your Night","ar":[{"id":2,"name":"Lotus Juice"}],
                   "al":{"id":3,"name":"P3R"},"dt":227240,"fee":1}
                ]}}
                """.trimIndent(),
            ),
        )

        val fixedDate = LocalDate.of(2026, 11, 8)
        val daily = DailyState(repository, dateProvider = { fixedDate })
        daily.load()

        val state = daily.state.value
        assertTrue("有歌曲时应是 Ready，实际 $state", state is DailyUiState.Ready)
        val ready = state as DailyUiState.Ready
        assertEquals(fixedDate, ready.date)
        assertEquals(2, ready.songs.size)
        assertEquals("キミの記憶", ready.songs[0].name)
        assertEquals("川村ゆみ", ready.songs[0].artistText)
        assertEquals(369386L, ready.songs[0].durationMs)
        assertEquals(8, ready.songs[0].fee)
        assertEquals("キミの記憶 (Memories of You)", ready.songs[0].displayName)

        assertEquals("/weapi/v3/discovery/recommend/songs?csrf_token=csrf-abc", nextRequest().path)
    }

    @Test
    fun dailyApi301BecomesNeedLogin() = runBlocking {
        server.enqueue(jsonResponse("""{"code":301,"message":"需要登录"}"""))

        val daily = DailyState(repository, dateProvider = { LocalDate.of(2026, 11, 8) })
        daily.load()

        assertSame(DailyUiState.NeedLogin, daily.state.value)
        assertEquals("业务错误不该重试", 1, server.requestCount)
    }

    @Test
    fun dailyEmptyListBecomesEmptyNotReady() = runBlocking {
        server.enqueue(
            jsonResponse("""{"code":200,"data":{"fromCache":false,"dailySongs":[]}}"""),
        )

        val fixedDate = LocalDate.of(2026, 11, 8)
        val daily = DailyState(repository, dateProvider = { fixedDate })
        daily.load()

        val state = daily.state.value
        assertTrue("空列表应是 Empty，实际 $state", state is DailyUiState.Empty)
        assertEquals(fixedDate, (state as DailyUiState.Empty).date)
    }

    @Test
    fun dailyBusinessErrorKeepsApiCode() = runBlocking {
        server.enqueue(jsonResponse("""{"code":500,"message":"服务开小差"}"""))

        val daily = DailyState(repository)
        daily.load()

        val state = daily.state.value
        assertTrue("业务错误应为 Error，实际 $state", state is DailyUiState.Error)
        val error = (state as DailyUiState.Error).error
        assertEquals("API_500", error.code)
        assertEquals("服务开小差", error.message)
    }

    @Test
    fun dailyGarbageBodyBecomesParseError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>502 Bad Gateway</body></html>"),
        )

        val daily = DailyState(repository)
        daily.load()

        val state = daily.state.value
        assertTrue("HTML 响应应为 Error，实际 $state", state is DailyUiState.Error)
        val error = (state as DailyUiState.Error).error
        assertEquals("PARSE", error.code)
        assertNotNull(error.cause)
    }

    // ------------------------------------------------------------------ helpers

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun nextRequest() =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "5 秒内没有收到请求" }

    private companion object {
        const val ACCOUNT_RESPONSE =
            """{"code":200,"account":{"id":42},"profile":{"userId":42,"nickname":"芝麻凛的抚子","avatarUrl":"https://p1.music.126.net/x.jpg","vipType":11}}"""
    }
}
