package com.cloudmusic.tv.ui.player

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.data.SettingsStore
import com.cloudmusic.core.data.music.LikedIdsCache
import com.cloudmusic.core.data.music.MusicResult
import com.cloudmusic.core.player.MusicPlayer
import com.cloudmusic.core.player.PlaybackError
import com.cloudmusic.core.player.PlaybackQuality
import com.cloudmusic.core.player.PlaybackState
import com.cloudmusic.core.player.Track
import com.cloudmusic.tv.ui.components.TvButton
import com.cloudmusic.tv.ui.music.MusicImageLoader
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PlayerScreen"

/**
 * 播放页。
 *
 * 排版基准是 **960×540dp**（实测 1920×1080px / density 320），左右安全边距 40dp：
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │  黑胶 400dp            48   歌词 432dp                    │  468dp
 * │                                                          │
 * ├──────────────────────────────────────────────────────────┤
 * │  00:00 ───────────────●────────── 04:18                  │  16dp
 * │  顺序 ♡       ⏮   ▶    ⏭        ☰   ⋯                    │  56dp
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * 焦点：
 * - 进入页面自动落到播放/暂停；
 * - 上键可到进度条、再到歌词区；歌词区上下键逐行选择、确认键跳转、8 秒无操作自动归还焦点；
 * - 返回键：抽屉打开 → 关抽屉；歌词有焦点 → 退出歌词焦点；否则回上一页；
 * - 抽屉 / 更多菜单打开时，底层控件全部禁用焦点，避免焦点跑到遮罩后面。
 */
@Composable
fun PlayerScreen(
    player: MusicPlayer,
    state: PlaybackState,
    queueTracks: List<Track>,
    lyricsState: LyricsState,
    settings: SettingsStore,
    onAudioQualityChanged: (String) -> Unit,
    onBack: () -> Unit,
    /** 会话级喜欢 id 缓存；初始心形 = `track.id in cache`。 */
    likedIdsCache: LikedIdsCache,
    /** 缓存批量加载完成后递增，用来重新计算当前曲目的初始心形。 */
    likedCacheVersion: Int,
    modifier: Modifier = Modifier,
    /** 私人 FM 复用本页：隐藏上一首、进度不可拖、队列按钮换成「不喜欢」。 */
    isFm: Boolean = false,
    onFmNext: () -> Unit = { player.next() },
    onFmDislike: () -> Unit = {},
    /** 红心网络动作；UI 先乐观更新，失败回滚并显示错误码。 */
    onSetLiked: suspend (Long, Boolean) -> MusicResult<Unit> = { _, _ ->
        MusicResult.Success(Unit)
    },
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { MusicImageLoader.get(context) }
    val scope = rememberCoroutineScope()

    val lyrics by lyricsState.state.collectAsState()

    // ---- 播放页里可即时修改的设置（和设置页共用 SettingsStore） ----
    var rotationEnabled by remember { mutableStateOf(settings.rotationEnabled) }
    var backgroundTintEnabled by remember { mutableStateOf(settings.backgroundTintEnabled) }
    var qualityLabel by remember { mutableStateOf(settings.audioQuality) }
    // 初始心形直接来自缓存，避免首帧闪一下未收藏；切歌或缓存批量到达时重建。
    var favorite by remember(state.track?.id, likedIdsCache, likedCacheVersion) {
        mutableStateOf(state.track?.id?.let { likedIdsCache.contains(it) } ?: false)
    }
    var likeBusy by remember { mutableStateOf(false) }
    var likeError by remember { mutableStateOf<String?>(null) }

    // 进入播放页时把设置页里的音质同步给播放核心；之后每次改也立即同步。
    LaunchedEffect(qualityLabel) {
        player.defaultLevel = PlaybackQuality.fromSettingsLabel(qualityLabel)
    }

    // ---- 歌词 ----
    val trackId = state.track?.id
    LaunchedEffect(trackId) {
        if (trackId != null) lyricsState.load(trackId)
    }
    val lyricLines = (lyrics as? LyricsUiState.Ready)?.lines.orEmpty()
    val currentLineIndex = LyricCursor.indexAt(lyricLines, state.positionMs)
    val hasLyrics = lyricLines.isNotEmpty()

    // ---- 背景主色：封面 64×64 缩略图 → 8×8 均值 → 降饱和；600ms 过渡 ----
    val tintCache = remember { mutableMapOf<String, Color>() }
    var coverTint by remember { mutableStateOf(PlayerColors.DefaultTint) }
    LaunchedEffect(state.track?.coverUrl, backgroundTintEnabled) {
        val url = state.track?.coverUrl
        if (!backgroundTintEnabled || url.isNullOrBlank()) {
            coverTint = PlayerColors.DefaultTint
            return@LaunchedEffect
        }
        tintCache[url]?.let {
            coverTint = it
            return@LaunchedEffect
        }
        val extracted = try {
            CoverPalette.extract(context, imageLoader, url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 取色只是背景装饰：失败打日志并回退，不让播放页崩溃。
            Log.w(TAG, "封面主色提取失败：$url", error)
            null
        }
        if (extracted != null) {
            tintCache[url] = extracted
            coverTint = extracted
        } else {
            coverTint = PlayerColors.DefaultTint
        }
    }
    // ---- 焦点 / 覆盖层 ----
    val playFocusRequester = remember { FocusRequester() }
    val queueFocusRequester = remember { FocusRequester() }
    val moreFocusRequester = remember { FocusRequester() }
    val lyricsFocusRequester = remember { FocusRequester() }
    val progressFocusRequester = remember { FocusRequester() }

    var queueOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var lyricsFocused by remember { mutableStateOf(false) }
    var lyricsExitSignal by remember { mutableIntStateOf(0) }
    var initialFocusDone by remember { mutableStateOf(false) }

    LaunchedEffect(state.track?.id) {
        // 切歌时清掉上一首的“正在请求/失败”临时状态；心形初值由上面的 remember 计算。
        likeBusy = false
        likeError = null
    }

    LaunchedEffect(state.track?.id) {
        if (state.track != null && !initialFocusDone) {
            for (attempt in 0..2) {
                withFrameNanos { }
                val focused = runCatching { playFocusRequester.requestFocus() }
                focused.onFailure {
                    Log.w(TAG, "播放页初始播放按钮焦点请求失败（第 ${attempt + 1} 次）", it)
                }
                if (focused.isSuccess) {
                    initialFocusDone = true
                    break
                }
            }
        }
    }

    val overlayOpen = queueOpen || moreOpen

    /**
     * 乐观收藏：先翻心形，再发 setLiked；失败时翻回去并显示错误码。
     * 请求期间切歌则不回写新歌的 UI（用 player.state 的当前 track 判断）。
     */
    fun toggleFavorite(trackId: Long) {
        if (likeBusy) return
        val target = !favorite
        favorite = target
        likeBusy = true
        likeError = null
        scope.launch {
            val result: MusicResult<Unit>? = try {
                onSetLiked(trackId, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "收藏请求异常：songId=$trackId liked=$target", error)
                null
            }

            // 服务端成功才同步缓存；失败不污染缓存，UI 另做回滚。
            if (result is MusicResult.Success) {
                likedIdsCache.setLiked(trackId, target)
            }

            if (player.state.value.track?.id != trackId) return@launch
            if (result !is MusicResult.Success) {
                favorite = !target
                likeError = result?.likeErrorCode() ?: "UNKNOWN"
            }
            likeBusy = false
        }
    }

    fun closeQueue() {
        queueOpen = false
        scope.launch {
            // 等抽屉退出动画 + 重新组合完成（按钮从 enabled=false 恢复可聚焦）再归还焦点。
            delay(260)
            withFrameNanos { }
            runCatching { queueFocusRequester.requestFocus() }
                .onFailure { Log.w(TAG, "关闭队列后播放队列按钮焦点请求失败", it) }
        }
    }

    fun closeMore() {
        moreOpen = false
        scope.launch {
            delay(180)
            withFrameNanos { }
            runCatching { moreFocusRequester.requestFocus() }
                .onFailure { Log.w(TAG, "关闭更多菜单后更多按钮焦点请求失败", it) }
        }
    }

    BackHandler(enabled = true) {
        when {
            queueOpen -> closeQueue()
            moreOpen -> closeMore()
            lyricsFocused -> lyricsExitSignal++
            else -> onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PlayerBackground(tint = coverTint, modifier = Modifier.fillMaxSize())

        val track = state.track
        if (track == null) {
            EmptyPlayerState(
                error = state.error,
                onBack = onBack,
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(
                            start = PlayerDimens.SafeHorizontal,
                            end = PlayerDimens.SafeHorizontal,
                            top = 24.dp,
                            bottom = 8.dp,
                        ),
                ) {
                    VinylRecord(
                        coverUrl = track.coverUrl,
                        isPlaying = state.isPlaying,
                        rotationEnabled = rotationEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    Spacer(modifier = Modifier.width(PlayerDimens.ContentGap))
                    Column(
                        modifier = Modifier
                            .width(PlayerDimens.LyricsWidth)
                            .fillMaxHeight(),
                    ) {
                        TrackHeader(
                            track = track,
                            error = state.error,
                            isTrialOnly = state.isTrialOnly,
                            lyricsFocused = lyricsFocused,
                            likeError = likeError,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        LyricsPanel(
                            state = lyrics,
                            currentLineIndex = currentLineIndex,
                            onSeek = { player.seekTo(it) },
                            onFocusedChange = { lyricsFocused = it },
                            exitFocusSignal = lyricsExitSignal,
                            onRequestFocusElsewhere = {
                                runCatching { playFocusRequester.requestFocus() }
                                    .onFailure {
                                        Log.w(TAG, "歌词退出后播放按钮焦点请求失败", it)
                                    }
                            },
                            focusEnabled = !overlayOpen,
                            focusRequester = lyricsFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PlayerDimens.LyricsViewportHeight),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                PlayerControlBar(
                    state = state,
                    favorite = favorite,
                    enabled = !overlayOpen,
                    onSeek = { player.seekTo(it) },
                    onTogglePlay = { player.toggle() },
                    onPrevious = { player.previous() },
                    onNext = { if (isFm) onFmNext() else player.next() },
                    onCycleMode = { player.setMode(PlaybackModes.next(state.mode)) },
                    onToggleFavorite = { toggleFavorite(track.id) },
                    onOpenQueue = { queueOpen = true },
                    onOpenMore = { moreOpen = true },
                    isFm = isFm,
                    onDislike = onFmDislike,
                    seekEnabled = !isFm,
                    playFocusRequester = playFocusRequester,
                    queueFocusRequester = queueFocusRequester,
                    moreFocusRequester = moreFocusRequester,
                    lyricsFocusRequester = lyricsFocusRequester.takeIf { hasLyrics },
                    progressFocusRequester = progressFocusRequester,
                )
            }
        }

        // ---------------------------------------------------------- 播放队列抽屉
        AnimatedVisibility(
            visible = queueOpen,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 220),
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 200),
            ) + fadeOut(animationSpec = tween(durationMillis = 160)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PlaybackQueueDrawer(
                tracks = queueTracks,
                currentIndex = state.queueIndex,
                queueSize = state.queueSize,
                onSelect = { index ->
                    if (queueTracks.size == state.queueSize && index in queueTracks.indices) {
                        // core-player 的公开 API 没有“按下标跳播”，用同一份队列从该下标重播，
                        // 对顺序模式等价；随机模式会从该曲重新开始一轮随机历史。
                        runCatching { player.play(queueTracks, index) }
                            .onFailure { Log.w(TAG, "队列跳播失败：index=$index", it) }
                    }
                },
            )
        }

        // ---------------------------------------------------------- 更多（设置）
        AnimatedVisibility(
            visible = moreOpen,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = PlayerDimens.SafeHorizontal, bottom = 84.dp),
        ) {
            PlayerMoreMenu(
                qualityLabel = qualityLabel,
                rotationEnabled = rotationEnabled,
                tintEnabled = backgroundTintEnabled,
                onCycleQuality = {
                    qualityLabel = PlayerQuality.next(qualityLabel)
                    onAudioQualityChanged(qualityLabel)
                },
                onToggleRotation = {
                    rotationEnabled = !rotationEnabled
                    settings.rotationEnabled = rotationEnabled
                },
                onToggleTint = {
                    backgroundTintEnabled = !backgroundTintEnabled
                    settings.backgroundTintEnabled = backgroundTintEnabled
                },
                onClose = { closeMore() },
            )
        }
    }
}

/** 封面主色生成的深色渐变；无主色时 [PlayerColors.DefaultTint]。600ms 过渡在这里完成。 */
@Composable
private fun PlayerBackground(tint: Color, modifier: Modifier = Modifier) {
    // 动画状态收在这个 composable 里：600ms 过渡只重绘背景。
    // 注意这里不 by 解构：在 Canvas 的 draw lambda 里读 .value，避免每帧重组。
    val animatedTint = animateColorAsState(
        targetValue = tint,
        animationSpec = tween(
            durationMillis = PlayerDimens.BackgroundTransitionMs,
            easing = LinearEasing,
        ),
        label = "playerBackgroundTint",
    )

    Canvas(modifier = modifier) {
        val tintColor = animatedTint.value
        val top = lerp(tintColor, Color.White, 0.10f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(top, tintColor, CloudMusicColors.Background),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
        )
        // 左上角轻微提亮，避免整屏死黑。
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                center = Offset(size.width * 0.22f, size.height * 0.08f),
                radius = size.width * 0.60f,
            ),
        )
        // 四周压暗，把视线收进中间。
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                center = Offset(size.width * 0.5f, size.height * 0.45f),
                radius = size.width * 0.75f,
            ),
        )
    }
}

/** 右侧标题区：歌名 / 歌手 / 试听标记 / 错误码 / 歌词操作提示。 */
@Composable
private fun TrackHeader(
    track: Track,
    error: PlaybackError?,
    isTrialOnly: Boolean,
    lyricsFocused: Boolean,
    likeError: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (likeError != null) {
                Text(
                    text = "收藏失败：$likeError",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = PlayerColors.Error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (error != null) {
                Text(
                    text = "错误码：${error.errorCodeName}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = PlayerColors.Error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = if (lyricsFocused) "返回键退出歌词" else "↑ 选中歌词",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = CloudMusicColors.SectionHeader,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = track.artist.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = CloudMusicColors.NavUnselectedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isTrialOnly) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "试听片段",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = CloudMusicColors.NavAccent,
                    modifier = Modifier
                        .background(PlayerColors.ChipBackground, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun MusicResult<*>.likeErrorCode(): String = when (this) {
    is MusicResult.Success -> "OK"
    MusicResult.NeedLogin -> "NEED_LOGIN"
    is MusicResult.NetworkError -> code
    is MusicResult.BusinessError -> "API_$code"
    is MusicResult.ParseError -> "PARSE"
}

/** 无歌曲兜底：明确文案 + 可聚焦的返回按钮（避免 TV 焦点丢失）。 */
@Composable
private fun EmptyPlayerState(
    error: PlaybackError?,
    onBack: () -> Unit,
) {
    val backRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { backRequester.requestFocus() }
            .onFailure { Log.w(TAG, "无歌曲页返回按钮焦点请求失败", it) }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有播放中的歌曲",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "按返回键回到上一页，或从每日推荐 / 我的音乐里选一首",
                style = MaterialTheme.typography.bodyMedium,
                color = CloudMusicColors.NavUnselectedText,
            )
            if (error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "错误码：${error.errorCodeName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PlayerColors.Error,
                )
            }
            Spacer(modifier = Modifier.height(26.dp))
            TvButton(
                label = "返回",
                onClick = onBack,
                focusRequester = backRequester,
            )
        }
    }
}
