package com.cloudmusic.core.data.music

import com.cloudmusic.core.network.NeteaseClient
import com.cloudmusic.core.network.OkHttpEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class HomeFmStateTest {

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

    // ------------------------------------------------------------------ FM

    @Test
    fun fmLoadsBatchAndReusesItBeforeFetchingAgain() = runBlocking {
        server.enqueue(jsonResponse(fmBatch(1, 2, 3)))

        val fm = FmState(repository)
        fm.load()

        val first = fm.state.value
        assertTrue("首批应进入 Playing，实际 $first", first is FmUiState.Playing)
        assertEquals("歌曲 1", (first as FmUiState.Playing).song.name)
        assertEquals("批内剩余 2 首", 2, first.queuedInBatch)

        fm.advance()
        assertEquals("歌曲 2", (fm.state.value as FmUiState.Playing).song.name)
        fm.advance()
        assertEquals("歌曲 3", (fm.state.value as FmUiState.Playing).song.name)
        assertEquals("批内已耗尽", 0, (fm.state.value as FmUiState.Playing).queuedInBatch)
        assertEquals("批内切歌不应产生网络请求", 1, server.requestCount)

        server.enqueue(jsonResponse(fmBatch(4, 5, 6)))
        fm.advance()
        assertEquals("批空后应取下一批", "歌曲 4", (fm.state.value as FmUiState.Playing).song.name)
        assertEquals(2, server.requestCount)

        assertEquals("/weapi/v1/radio/get?csrf_token=csrf-abc", nextRequest().path)
        assertEquals("/weapi/v1/radio/get?csrf_token=csrf-abc", nextRequest().path)
    }

    @Test
    fun fmDislikeCallsTrashThenAdvancesWithinBatch() = runBlocking {
        server.enqueue(jsonResponse(fmBatch(11, 12)))
        server.enqueue(jsonResponse("""{"songs":[],"count":0,"code":200}"""))

        val fm = FmState(repository)
        fm.load()
        val dislikedId = (fm.state.value as FmUiState.Playing).song.id

        val result = fm.dislikeCurrent()

        assertTrue("dislike 应成功，实际 $result", result is MusicResult.Success)
        assertEquals("歌曲 12", (fm.state.value as FmUiState.Playing).song.name)

        assertEquals("/weapi/v1/radio/get?csrf_token=csrf-abc", nextRequest().path)
        val trashRequest = nextRequest()
        assertEquals(
            "/weapi/radio/trash/add?csrf_token=csrf-abc&alg=RT&songId=$dislikedId&time=25",
            trashRequest.path,
        )
    }

    @Test
    fun fmDislikeFailureDoesNotAdvance() = runBlocking {
        server.enqueue(jsonResponse(fmBatch(21, 22)))
        server.enqueue(jsonResponse("""{"code":500,"message":"服务开小差"}"""))

        val fm = FmState(repository)
        fm.load()
        val before = (fm.state.value as FmUiState.Playing).song

        val result = fm.dislikeCurrent()

        assertTrue("dislike 应返回业务错误，实际 $result", result is MusicResult.BusinessError)
        assertEquals("失败时不能切歌", before.id, (fm.state.value as FmUiState.Playing).song.id)
        assertEquals("FM 加载 + 失败的 trash 请求", 2, server.requestCount)
    }

    @Test
    fun fmEmptyAndNeedLoginAreDistinct() = runBlocking {
        server.enqueue(jsonResponse("""{"code":200,"data":[]}"""))
        val empty = FmState(repository)
        empty.load()
        assertSame(FmUiState.Empty, empty.state.value)

        server.enqueue(jsonResponse("""{"code":301,"message":"需要登录"}"""))
        val login = FmState(repository)
        login.load()
        assertSame(FmUiState.NeedLogin, login.state.value)
    }

    @Test
    fun fmHttpFailureBecomesErrorWithCode() = runBlocking {
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("server down"))
        }

        val fm = FmState(repository)
        fm.load()

        val state = fm.state.value
        assertTrue("网络失败应为 Error，实际 $state", state is FmUiState.Error)
        assertEquals("HTTP_500", (state as FmUiState.Error).error.code)
    }

    // ------------------------------------------------------------------ Home

    @Test
    fun homeLoadsDailySummaryAndRecentPlays() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"code":200,"data":{"dailySongs":[
                    {"id":1,"name":"第一首","ar":[{"id":1,"name":"歌手 A"}],"al":{"id":1,"name":"专辑"},"dt":1000},
                    {"id":2,"name":"第二首","ar":[{"id":2,"name":"歌手 B"}],"al":{"id":2,"name":"专辑"},"dt":2000},
                    {"id":3,"name":"第三首","ar":[{"id":3,"name":"歌手 C"}],"al":{"id":3,"name":"专辑"},"dt":3000},
                    {"id":4,"name":"第四首","ar":[{"id":4,"name":"歌手 D"}],"al":{"id":4,"name":"专辑"},"dt":4000}
                  ]}}""",
            ),
        )
        val store = FakeRecentPlayStore(
            mutableListOf(
                recent(10, "最近一首"),
                recent(9, "更早一首"),
            ),
        )
        val fixedDate = LocalDate.of(2026, 11, 8)
        val home = HomeState(repository, store, dateProvider = { fixedDate })

        home.load()

        val state = home.state.value
        assertTrue("首页应进入 Ready，实际 $state", state is HomeUiState.Ready)
        val ready = state as HomeUiState.Ready
        assertEquals(fixedDate, ready.daily.date)
        assertEquals(4, ready.daily.count)
        assertEquals(3, ready.daily.preview.size)
        assertEquals("第一首 · 歌手 A", ready.daily.preview[0])
        assertEquals(listOf("最近一首", "更早一首"), ready.recent.map { it.title })

        store.items.add(0, recent(11, "刚刚播放"))
        home.refreshRecent()
        assertEquals("刚刚播放", (home.state.value as HomeUiState.Ready).recent.first().title)
    }

    @Test
    fun homeEmptyDailyIsStillReadySummary() = runBlocking {
        server.enqueue(
            jsonResponse("""{"code":200,"data":{"dailySongs":[]}}"""),
        )
        val home = HomeState(repository, FakeRecentPlayStore(mutableListOf()))

        home.load()

        val state = home.state.value
        assertTrue("空每日推荐也应有首页摘要，实际 $state", state is HomeUiState.Ready)
        assertEquals(0, (state as HomeUiState.Ready).daily.count)
        assertTrue(state.daily.preview.isEmpty())
    }

    @Test
    fun homeNeedLoginAndHttpErrorAreDistinct() = runBlocking {
        server.enqueue(jsonResponse("""{"code":301,"message":"需要登录"}"""))
        val login = HomeState(repository, FakeRecentPlayStore(mutableListOf()))
        login.load()
        assertSame(HomeUiState.NeedLogin, login.state.value)

        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("server down"))
        }
        val error = HomeState(repository, FakeRecentPlayStore(mutableListOf()))
        error.load()
        val state = error.state.value
        assertTrue("HTTP 500 应为 Error，实际 $state", state is HomeUiState.Error)
        assertEquals("HTTP_500", (state as HomeUiState.Error).error.code)
    }

    @Test
    fun recentPlayCodecRoundTripsAndSkipsMalformedLines() {
        val original = RecentPlay(
            songId = 42,
            title = "有\tTAB\n和换行",
            artist = "歌手",
            album = "",
            coverUrl = null,
            durationMs = 1234,
            playedAtMs = 99,
        )

        val decoded = RecentPlayCodec.decode(RecentPlayCodec.encode(original))

        assertEquals(42L, decoded?.songId)
        assertEquals("有 TAB 和换行", decoded?.title)
        assertEquals("", decoded?.album)
        assertEquals(null, decoded?.coverUrl)
        assertEquals(1234L, decoded?.durationMs)
        assertEquals(99L, decoded?.playedAtMs)
        assertEquals(null, RecentPlayCodec.decode("not-a-record"))
        assertEquals(null, RecentPlayCodec.decode("1\t2\t3"))
    }

    // ------------------------------------------------------------------ helpers

    private fun fmBatch(vararg ids: Long): String =
        """{"popAdjust":false,"code":200,"data":[
            ${ids.mapIndexed { index, id -> fmSong(id, index + 1) }.joinToString(",")}
          ]}"""

    private fun fmSong(id: Long, index: Int): String =
        """{"name":"歌曲 $id","id":$id,"alias":[],"fee":1,"duration":${index * 1000},
            "artists":[{"name":"FM 歌手 $id","id":$index}],
            "album":{"name":"FM 专辑 $id","id":$index,"picUrl":"http://p1.music.126.net/$index.jpg"}}"""

    private fun recent(id: Long, title: String): RecentPlay = RecentPlay(
        songId = id,
        title = title,
        artist = "歌手",
        album = "专辑",
        coverUrl = "http://p1.music.126.net/$id.jpg",
        durationMs = 1000L,
        playedAtMs = id,
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun nextRequest() =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "5 秒内没有收到请求" }

    private class FakeRecentPlayStore(val items: MutableList<RecentPlay>) : RecentPlayStore {
        override fun load(): List<RecentPlay> = items.toList()

        override fun record(play: RecentPlay) {
            items.removeAll { it.songId == play.songId }
            items.add(0, play)
        }
    }
}
