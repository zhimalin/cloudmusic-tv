package com.cloudmusic.tv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.cloudmusic.core.data.SessionStore
import com.cloudmusic.core.data.SettingsStore
import com.cloudmusic.core.data.SharedPreferencesRecentPlayStore
import com.cloudmusic.core.data.music.DailyState
import com.cloudmusic.core.data.music.FmState
import com.cloudmusic.core.data.music.HomeState
import com.cloudmusic.core.data.music.LibraryState
import com.cloudmusic.core.data.music.LikedIdsCache
import com.cloudmusic.core.data.music.MusicRepository
import com.cloudmusic.core.data.music.MusicResult
import com.cloudmusic.core.data.music.RecentPlay
import com.cloudmusic.core.network.NeteaseClient
import com.cloudmusic.core.network.Song
import com.cloudmusic.core.player.MusicPlayer
import com.cloudmusic.core.player.PlaybackQuality
import com.cloudmusic.core.player.PlaybackRuntime
import com.cloudmusic.core.player.PlaybackService
import com.cloudmusic.core.player.PlaybackState
import com.cloudmusic.core.player.PlaybackStatus
import com.cloudmusic.core.player.toTrack
import com.cloudmusic.tv.ui.home.HomeRoute
import com.cloudmusic.tv.ui.login.LoginScreen
import com.cloudmusic.tv.ui.music.DailyRoute
import com.cloudmusic.tv.ui.music.FmRoute
import com.cloudmusic.tv.ui.music.MyMusicCategory
import com.cloudmusic.tv.ui.music.MyMusicFlow
import com.cloudmusic.tv.ui.nav.TopNavBar
import com.cloudmusic.tv.ui.player.LyricsState
import com.cloudmusic.tv.ui.player.MiniPlayer
import com.cloudmusic.tv.ui.player.PlaybackQueueMirror
import com.cloudmusic.tv.ui.player.PlayerScreen
import com.cloudmusic.tv.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CloudMusicApp"

/** Tabs of the top navigation. Order here == order on screen. */
enum class MainTab(val label: String) {
    HOME("首页"),
    DAILY("每日推荐"),
    RADIO("私人 FM"),
    MY_MUSIC("我的音乐"),
}

/**
 * Root screens. A deliberately tiny state machine — the spec says no
 * Navigation-Compose.
 *
 * Back behaviour:
 *  - LOGIN    : no handler -> Activity default -> finish() (exits the app)
 *  - MAIN     : no handler -> Activity default -> finish() (exits the app);
 *               the 我的音乐 subtree installs its own BackHandler for
 *               detail -> grid -> landing.
 *  - PLAYER   : PlayerScreen's BackHandler -> back to MAIN (never exits)
 *  - SETTINGS : BackHandler -> back to MAIN (does not exit)
 */
private enum class RootScreen { LOGIN, MAIN, PLAYER, SETTINGS }

private val MainTabs: List<MainTab> = listOf(
    MainTab.HOME,
    MainTab.DAILY,
    MainTab.RADIO,
    MainTab.MY_MUSIC,
)

@Composable
fun CloudMusicApp() {
    val context = LocalContext.current
    val session = remember(context) { SessionStore(context) }
    val settings = remember(context) { SettingsStore(context) }
    val recentPlayStore = remember(context) { SharedPreferencesRecentPlayStore(context) }
    val appScope = rememberCoroutineScope()

    var root by rememberSaveable {
        mutableStateOf(if (session.hasCookie()) RootScreen.MAIN else RootScreen.LOGIN)
    }
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var myMusicInitialCategory by rememberSaveable {
        mutableStateOf<MyMusicCategory?>(null)
    }
    // FM 从哪个 tab 进来，播放页返回时就回哪个 tab（避免用户从每日推荐进 FM 后被丢到首页）。
    var fmReturnTab by remember { mutableStateOf(MainTab.HOME) }

    // Rebuild the client + state containers across sessions. The cookie provider
    // itself is dynamic, but stale Ready/Error state from the previous account
    // must not leak into the next login.
    var sessionGeneration by remember { mutableStateOf(0) }
    val client = remember(sessionGeneration) {
        NeteaseClient(cookieProvider = { session.readCookie().orEmpty() })
    }
    val repository = remember(client) { MusicRepository(client) }
    val libraryState = remember(repository) { LibraryState(repository) }
    val dailyState = remember(repository) { DailyState(repository) }
    val homeState = remember(repository, recentPlayStore) {
        HomeState(repository, recentPlayStore)
    }
    val fmState = remember(repository) { FmState(repository) }
    val likedIdsCache = remember(repository) { LikedIdsCache() }
    var likedCacheVersion by remember(repository) { mutableStateOf(0) }

    // 进程内唯一播放器：先只接管已经存在的实例（例如 Activity 重建时 Service 还活着），
    // 不在登录页/无播放的 MAIN 首帧创建 ExoPlayer。真正点歌时才调用 ensurePlayer()。
    var player by remember { mutableStateOf<MusicPlayer?>(PlaybackRuntime.peek()) }

    // 没有播放器时给 UI 一个空状态；播放器出现后才在对应分支订阅它的 StateFlow。
    // collectAsState() 直接以 StateFlow.value 为初值，等价于之前的 produceState 逻辑，
    // 同时避开 lint 对 producer 内 return@ + collect 赋值的误判。
    val activePlayer = player
    val playbackState = activePlayer?.state?.collectAsState()?.value ?: PlaybackState()
    val queueMirror = remember { PlaybackQueueMirror() }
    val lyricsState = remember(repository) { LyricsState(repository) }

    // FM 模式是 app 级状态：播放页与迷你条共用；进普通歌曲时退出 FM 模式。
    var isFmMode by remember { mutableStateOf(false) }
    // FM 的自动推进（播完）和手动下一首/不喜欢共用这个门闩，避免双触发。
    var fmActionInFlight by remember { mutableStateOf(false) }

    // Android 13+ 的通知权限：核心层只声明权限 + 建渠道，运行时申请放在 app 层。
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Log.w(TAG, "POST_NOTIFICATIONS denied: playback continues, notification is hidden")
        }
    }
    // 点歌/整队播放前：确保后台 Service 已启动，并在需要时申请一次通知权限。
    val preparePlayback: () -> Unit = {
        PlaybackService.start(context)
        if (!notificationPermissionRequested &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 真正的懒创建入口：只有播放动作（点歌/播放全部/FM 开始或推进）会调用。
     *
     * Service 可能先一步创建实例（preparePlayback 之后），这里通过 [PlaybackRuntime.player]
     * 拿到的仍是同一个单例；如果 UI 先创建，Service 之后也会接管同一个。
     */
    val ensurePlayer: () -> MusicPlayer = {
        player ?: PlaybackRuntime.player(context).also { player = it }
    }

    /**
     * 启动后后台拉一次完整喜欢歌单，用于播放页心形初值。
     *
     * 不阻塞首屏：LaunchedEffect 在首帧后启动，网络挂起不占主线程；失败只打日志，
     * 缓存保持空（心形按未收藏显示），不弹错误。会话切换时 repository 变化会重新拉。
     */
    LaunchedEffect(repository) {
        if (!session.hasCookie()) return@LaunchedEffect

        val account = withContext(Dispatchers.IO) { repository.account() }
        when (account) {
            is MusicResult.Success -> {
                val liked = withContext(Dispatchers.IO) {
                    repository.likedSongs(account.value.id)
                }
                when (liked) {
                    is MusicResult.Success -> {
                        likedIdsCache.replaceAll(liked.value.map { it.id })
                        likedCacheVersion += 1
                        Log.i(TAG, "liked-id cache loaded: ${likedIdsCache.size()}")
                    }

                    else -> Log.w(
                        TAG,
                        "liked-id cache load failed: ${liked.errorLabel()}",
                    )
                }
            }

            else -> Log.w(TAG, "liked-id cache needs account: ${account.errorLabel()}")
        }
    }

    // 播放器每次切歌就记一条最近播放；封面/歌手直接取 Track，不额外请求。
    LaunchedEffect(playbackState.track?.id) {
        val track = playbackState.track ?: return@LaunchedEffect
        recentPlayStore.record(
            RecentPlay(
                songId = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                coverUrl = track.coverUrl,
                durationMs = track.durationMs,
                playedAtMs = System.currentTimeMillis(),
            ),
        )
        homeState.refreshRecent()
    }

    /**
     * 用 FM 的当前歌曲开始/继续播放，并进入播放页。
     *
     * 如果播放器已经在这首歌上（暂停/播放中），只打开播放页，不重播。
     */
    val startFmSong: (Song) -> Unit = { song ->
        isFmMode = true
        val current = playbackState.track
        if (current?.id != song.id ||
            playbackState.status == PlaybackStatus.ENDED ||
            playbackState.status == PlaybackStatus.ERROR
        ) {
            val tracks = listOf(song.toTrack())
            queueMirror.submit(tracks)
            preparePlayback()
            ensurePlayer().play(tracks)
        }
        root = RootScreen.PLAYER
    }

    val onSongSelected: (Song) -> Unit = { song ->
        isFmMode = false
        Log.i(TAG, "play song: id=${song.id}, name=${song.name}, artists=${song.artistText}")
        val tracks = listOf(song.toTrack())
        queueMirror.submit(tracks)
        preparePlayback()
        ensurePlayer().play(tracks)
        root = RootScreen.PLAYER
    }
    val onPlayAll: (List<Song>) -> Unit = { songs ->
        if (songs.isEmpty()) {
            Log.w(TAG, "play-all ignored: empty queue")
        } else {
            isFmMode = false
            Log.i(TAG, "play-all: count=${songs.size}, first=${songs.first().name}")
            val tracks = songs.map { it.toTrack() }
            queueMirror.submit(tracks)
            preparePlayback()
            ensurePlayer().play(tracks, startIndex = 0)
            root = RootScreen.PLAYER
        }
    }

    /** FM 手动下一首：批内直接切，批空时由 FmState 拉新批次。 */
    val onFmNext: () -> Unit = {
        if (!fmActionInFlight) {
            fmActionInFlight = true
            appScope.launch {
                try {
                    fmState.advance()
                    playFmCurrentIfPresent(
                        fmState = fmState,
                        preparePlayback = preparePlayback,
                        queueMirror = queueMirror,
                        ensurePlayer = ensurePlayer,
                    )
                } finally {
                    fmActionInFlight = false
                }
            }
        }
    }

    /** FM「不喜欢」：服务端确认成功后才停止当前歌并切到下一首。 */
    val onFmDislike: () -> Unit = {
        if (!fmActionInFlight) {
            fmActionInFlight = true
            appScope.launch {
                try {
                    when (val result = fmState.dislikeCurrent()) {
                        is MusicResult.Success -> playFmCurrentIfPresent(
                            fmState = fmState,
                            preparePlayback = preparePlayback,
                            queueMirror = queueMirror,
                            ensurePlayer = ensurePlayer,
                        )

                        else -> Log.w(
                            TAG,
                            "FM dislike failed: ${result.errorLabel()}",
                        )
                    }
                } finally {
                    fmActionInFlight = false
                }
            }
        }
    }

    /**
     * 系统通知 / 媒体键的“下一首”钩子。
     *
     * 只在 FM 模式注册：回调返回 true，QueuePlayer 不再执行普通队列 next()；
     * 非 FM 置空，原有通知/媒体键行为完全不变。onFmNext 内部自己 launch，不阻塞。
     */
    DisposableEffect(isFmMode, fmState) {
        if (isFmMode) {
            PlaybackRuntime.onExternalNext = {
                // 不在这里直接碰 Compose 状态；即使回调来自非主线程也先切回 appScope。
                appScope.launch { onFmNext() }
                true
            }
        } else {
            PlaybackRuntime.onExternalNext = null
        }
        onDispose {
            if (isFmMode) {
                PlaybackRuntime.onExternalNext = null
            }
        }
    }

    // FM 单曲队列播完（没有下一首可自动跳）时由 app 驱动拉下一首。
    // 注意：这里不能把 fmState.currentSong?.id 放进 key。
    // 自动推进时 FmState 会先变成 Loading 再发网络请求；把它作为 key 会在请求
    // 挂起期间改变 key，从而取消整个 LaunchedEffect，下一首永远播不出来。
    LaunchedEffect(isFmMode, playbackState.status, playbackState.track?.id) {
        if (!isFmMode ||
            playbackState.status != PlaybackStatus.ENDED ||
            fmActionInFlight
        ) {
            return@LaunchedEffect
        }
        val endedId = playbackState.track?.id ?: return@LaunchedEffect
        if (fmState.currentSong?.id != endedId) return@LaunchedEffect

        fmActionInFlight = true
        try {
            fmState.advance()
            playFmCurrentIfPresent(
                fmState = fmState,
                preparePlayback = preparePlayback,
                queueMirror = queueMirror,
                ensurePlayer = ensurePlayer,
            )
        } finally {
            fmActionInFlight = false
        }
    }

    // 设置页和播放页「更多」共用的音质应用逻辑：写偏好 + 同步给播放核心。
    // 正在播放的曲目不会中途换码率，下一次解析播放地址时生效。
    val applyAudioQuality: (String) -> Unit = { label ->
        settings.audioQuality = label
        // 播放器还没创建时只写偏好；PlaybackRuntime.create() 会从 SettingsStore 读初始音质。
        player?.defaultLevel = PlaybackQuality.fromSettingsLabel(label)
    }

    val onNeedLogin: () -> Unit = {
        session.clear()
        clearWebViewCookies()
        tab = MainTab.HOME
        myMusicInitialCategory = null
        isFmMode = false
        sessionGeneration += 1
        root = RootScreen.LOGIN
    }

    when (root) {
        RootScreen.LOGIN -> LoginScreen(
            onLoggedIn = { cookie ->
                session.saveCookie(cookie)
                tab = MainTab.HOME
                myMusicInitialCategory = null
                isFmMode = false
                sessionGeneration += 1
                root = RootScreen.MAIN
            },
        )

        RootScreen.MAIN -> MainScreen(
            tab = tab,
            onTabSelected = { selected ->
                if (selected == MainTab.RADIO && tab != MainTab.RADIO) {
                    fmReturnTab = tab
                }
                if (selected == MainTab.MY_MUSIC) myMusicInitialCategory = null
                tab = selected
            },
            onOpenSettings = { root = RootScreen.SETTINGS },
            repository = repository,
            libraryState = libraryState,
            dailyState = dailyState,
            homeState = homeState,
            fmState = fmState,
            playbackState = playbackState,
            myMusicInitialCategory = myMusicInitialCategory,
            onMyMusicCategoryConsumed = { myMusicInitialCategory = null },
            onOpenMyMusicCategory = { category ->
                myMusicInitialCategory = category
                tab = MainTab.MY_MUSIC
            },
            onPlayRecent = { recent -> onSongSelected(recent.toSong()) },
            onEnsureFmPlaying = startFmSong,
            onTogglePlay = { player?.toggle() },
            onOpenPlayer = { if (player != null) root = RootScreen.PLAYER },
            onSongSelected = onSongSelected,
            onPlayAll = onPlayAll,
            onNeedLogin = onNeedLogin,
        )

        RootScreen.PLAYER -> {
            val activePlayer = player
            if (activePlayer == null) {
                // 进程重建后 root 可能是保存的 PLAYER，但播放器已不存在；不创建空的 ExoPlayer。
                LaunchedEffect(Unit) { root = RootScreen.MAIN }
            } else {
                PlayerScreen(
                    player = activePlayer,
                    state = playbackState,
                    queueTracks = queueMirror.tracks,
                    lyricsState = lyricsState,
                    settings = settings,
                    onAudioQualityChanged = applyAudioQuality,
                    onBack = {
                        if (isFmMode) tab = fmReturnTab
                        root = RootScreen.MAIN
                    },
                    isFm = isFmMode,
                    onFmNext = onFmNext,
                    onFmDislike = onFmDislike,
                    onSetLiked = { id, liked -> repository.setLiked(id, liked) },
                    likedIdsCache = likedIdsCache,
                    likedCacheVersion = likedCacheVersion,
                )
            }
        }

        RootScreen.SETTINGS -> {
            // Back from settings goes home, it does not exit the app.
            BackHandler { root = RootScreen.MAIN }

            SettingsScreen(
                settings = settings,
                accountLabel = session.accountHint() ?: "已登录",
                onAudioQualityChanged = applyAudioQuality,
                onLogout = {
                    session.clear()
                    clearWebViewCookies()
                    tab = MainTab.HOME
                    myMusicInitialCategory = null
                    isFmMode = false
                    sessionGeneration += 1
                    root = RootScreen.LOGIN
                },
            )
        }
    }
}

@Composable
private fun MainScreen(
    tab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    onOpenSettings: () -> Unit,
    repository: MusicRepository,
    libraryState: LibraryState,
    dailyState: DailyState,
    homeState: HomeState,
    fmState: FmState,
    playbackState: PlaybackState,
    myMusicInitialCategory: MyMusicCategory?,
    onMyMusicCategoryConsumed: () -> Unit,
    onOpenMyMusicCategory: (MyMusicCategory) -> Unit,
    onPlayRecent: (RecentPlay) -> Unit,
    onEnsureFmPlaying: (Song) -> Unit,
    onTogglePlay: () -> Unit,
    onOpenPlayer: () -> Unit,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onNeedLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopNavBar(
            tabs = MainTabs,
            selected = tab,
            onSelect = onTabSelected,
            onOpenSettings = onOpenSettings,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (tab) {
                MainTab.HOME -> HomeRoute(
                    homeState = homeState,
                    onOpenDaily = { onTabSelected(MainTab.DAILY) },
                    onOpenFm = { onTabSelected(MainTab.RADIO) },
                    onOpenMyMusic = onOpenMyMusicCategory,
                    onPlayRecent = onPlayRecent,
                    onNeedLogin = onNeedLogin,
                )

                MainTab.DAILY -> DailyRoute(
                    dailyState = dailyState,
                    onSongSelected = onSongSelected,
                    onPlayAll = onPlayAll,
                    onNeedLogin = onNeedLogin,
                )

                MainTab.RADIO -> FmRoute(
                    fmState = fmState,
                    onEnsurePlaying = onEnsureFmPlaying,
                    onNeedLogin = onNeedLogin,
                )

                MainTab.MY_MUSIC -> MyMusicFlow(
                    repository = repository,
                    libraryState = libraryState,
                    onSongSelected = onSongSelected,
                    onPlayAll = onPlayAll,
                    onNeedLogin = onNeedLogin,
                    initialCategory = myMusicInitialCategory,
                    onInitialCategoryConsumed = onMyMusicCategoryConsumed,
                )
            }
        }

        // 全局迷你播放条：只在 MAIN 下、且确实有曲目时显示。
        val track = playbackState.track
        if (track != null) {
            MiniPlayer(
                track = track,
                isPlaying = playbackState.isPlaying,
                onOpenPlayer = onOpenPlayer,
                onTogglePlay = onTogglePlay,
            )
        }
    }
}

/**
 * FM 推进后统一从这里开始播放下一首；没有下一首（空批/错误）时只打日志，
 * 播放页不会假装在播一首不存在的歌。
 */
private fun playFmCurrentIfPresent(
    fmState: FmState,
    preparePlayback: () -> Unit,
    queueMirror: PlaybackQueueMirror,
    ensurePlayer: () -> MusicPlayer,
) {
    val next = fmState.currentSong
    if (next == null) {
        Log.w(TAG, "FM advance produced no song: state=${fmState.state.value}")
        return
    }
    val tracks = listOf(next.toTrack())
    queueMirror.submit(tracks)
    preparePlayback()
    ensurePlayer().play(tracks)
}

private fun MusicResult<*>.errorLabel(): String = when (this) {
    is MusicResult.Success -> "OK"
    MusicResult.NeedLogin -> "NEED_LOGIN"
    is MusicResult.NetworkError -> code
    is MusicResult.BusinessError -> "API_$code"
    is MusicResult.ParseError -> "PARSE"
}

/**
 * Logout cookie wipe.
 *
 * Guarded because [CookieManager.getInstance] itself touches the WebView
 * provider; on a TV without a working WebView the SharedPreferences wipe in
 * SessionStore must still succeed.
 */
private fun clearWebViewCookies() {
    try {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        Log.i(TAG, "WebView cookies cleared")
    } catch (throwable: Throwable) {
        Log.w(TAG, "clearing WebView cookies failed", throwable)
    }
}
