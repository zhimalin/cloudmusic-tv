package com.cloudmusic.tv.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.player.PlaybackState
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 底部控制栏（高 72dp，横跨整宽）。
 *
 * 结构：
 * - 第一行 16dp：当前时间 + 进度条 + 总时长（`mm:ss`）；
 * - 第二行 56dp：左（播放模式 / 收藏）、中（上一首 / 播放暂停 56dp / 下一首）、
 *   右（播放队列 / 更多）。
 *
 * 进度条焦点：
 * - 左右键单次 ±10 秒（[SeekMath.step]）；
 * - 按住 450ms 后每 90ms 连续步进，松手（KeyUp）/失焦立即停止；
 * - 拖动期间的显示位置用本地 preview 先行，不等播放器 500ms 的状态 tick。
 */
@Composable
fun PlayerControlBar(
    state: PlaybackState,
    favorite: Boolean,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMore: () -> Unit,
    playFocusRequester: FocusRequester,
    queueFocusRequester: FocusRequester,
    moreFocusRequester: FocusRequester,
    lyricsFocusRequester: FocusRequester?,
    progressFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    /** FM 模式：隐藏上一首/播放模式，进度不可拖，队列按钮换成「不喜欢」。 */
    isFm: Boolean = false,
    onDislike: () -> Unit = {},
    seekEnabled: Boolean = true,
) {
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var seeking by remember { mutableStateOf(false) }

    // 切歌时清掉上一首的 seek 预览，避免新歌开头短暂显示旧进度。
    LaunchedEffect(state.track?.id) {
        seekPreviewMs = null
        seeking = false
    }

    // 覆盖层打开（enabled=false）时强制结束拖动/预览，避免 seek 循环在遮罩后面继续跑。
    LaunchedEffect(enabled) {
        if (!enabled) seeking = false
    }

    // 松手 700ms 后让播放器的真实 position 接管显示（状态 tick 是 500ms）。
    LaunchedEffect(seekPreviewMs, seeking) {
        if (seekPreviewMs != null && !seeking) {
            delay(700)
            seekPreviewMs = null
        }
    }

    val displayPositionMs = seekPreviewMs ?: state.positionMs
    val progress = if (state.durationMs > 0L) {
        (displayPositionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(PlayerDimens.ControlBarHeight)
            .background(PlayerColors.ControlBarScrim)
            .drawBehind {
                drawLine(
                    color = Color.White.copy(alpha = 0.07f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        // ---------------------------------------------------------- 进度行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlayerDimens.ProgressRowHeight)
                .padding(horizontal = PlayerDimens.SafeHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PlaybackTime.format(displayPositionMs),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = CloudMusicColors.NavUnselectedText,
                textAlign = TextAlign.Start,
                modifier = Modifier.width(46.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            PlayerProgressBar(
                progress = progress,
                bufferedPercent = state.bufferedPercent,
                enabled = enabled,
                seekEnabled = seekEnabled,
                focusRequester = progressFocusRequester,
                upFocusRequester = lyricsFocusRequester,
                onStep = { direction ->
                    val base = seekPreviewMs ?: state.positionMs
                    val target = SeekMath.step(
                        positionMs = base,
                        durationMs = state.durationMs,
                        deltaMs = direction * SeekMath.STEP_MS,
                    )
                    seekPreviewMs = target
                    onSeek(target)
                },
                onSeekingChange = { seeking = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = PlaybackTime.format(state.durationMs),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = CloudMusicColors.NavUnselectedText,
                textAlign = TextAlign.End,
                modifier = Modifier.width(46.dp),
            )
        }

        // ---------------------------------------------------------- 控制行
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlayerDimens.TransportRowHeight)
                .padding(horizontal = PlayerDimens.SafeHorizontal),
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!isFm) {
                    PlaybackModeButton(
                        modeLabel = PlaybackModes.label(state.mode),
                        onClick = onCycleMode,
                        enabled = enabled,
                    )
                }
                PlayerControlButton(
                    contentDescription = if (favorite) "取消收藏" else "收藏",
                    onClick = onToggleFavorite,
                    enabled = enabled,
                ) {
                    HeartIcon(
                        color = if (favorite) CloudMusicColors.NavAccent else Color.White,
                        filled = favorite,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!isFm) {
                    PlayerControlButton(
                        contentDescription = "上一首",
                        onClick = onPrevious,
                        enabled = enabled,
                    ) {
                        PreviousIcon(color = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                PlayPauseButton(
                    isPlaying = state.isPlaying,
                    onClick = onTogglePlay,
                    focusRequester = playFocusRequester,
                    enabled = enabled,
                )
                PlayerControlButton(
                    contentDescription = "下一首",
                    onClick = onNext,
                    enabled = enabled,
                ) {
                    NextIcon(color = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isFm) {
                    PlayerControlButton(
                        contentDescription = "不喜欢这首",
                        onClick = onDislike,
                        focusRequester = queueFocusRequester,
                        enabled = enabled,
                        width = 84.dp,
                        height = 40.dp,
                    ) {
                        Text(
                            text = "不喜欢",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    }
                } else {
                    PlayerControlButton(
                        contentDescription = "播放队列",
                        onClick = onOpenQueue,
                        focusRequester = queueFocusRequester,
                        enabled = enabled,
                    ) {
                        QueueIcon(color = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                PlayerControlButton(
                    contentDescription = "更多",
                    onClick = onOpenMore,
                    focusRequester = moreFocusRequester,
                    enabled = enabled,
                ) {
                    MoreIcon(color = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * 可聚焦进度条。
 *
 * [onStep] 的入参是方向（-1 左 / +1 右），具体 ±10 秒和边界钳制由调用方
 * 用 [SeekMath.step] 完成；这样“长按重复”的节奏和“步长语义”解耦。
 */
@Composable
private fun PlayerProgressBar(
    progress: Float,
    bufferedPercent: Int,
    enabled: Boolean,
    seekEnabled: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onStep: (Int) -> Unit,
    onSeekingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scope = rememberCoroutineScope()

    var heldDirection by remember { mutableIntStateOf(0) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "progressBarScale",
    )

    fun stopHold() {
        holdJob?.cancel()
        holdJob = null
        if (heldDirection != 0) {
            heldDirection = 0
            onSeekingChange(false)
        }
    }

    fun startHold(direction: Int) {
        if (heldDirection == direction) return
        stopHold()
        heldDirection = direction
        onSeekingChange(true)
        onStep(direction)
        holdJob = scope.launch {
            // 单击走完第一步后稍等；确认是长按再连续步进。
            delay(450)
            while (isActive) {
                onStep(direction)
                delay(90)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { holdJob?.cancel() }
    }

    // 覆盖层打开或 FM 模式（seekEnabled=false）时立即结束长按循环。
    LaunchedEffect(enabled, seekEnabled) {
        if (!enabled || !seekEnabled) stopHold()
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                // 进度条几乎横跨整宽：只做纵向 1.05 倍强调，横向缩放会盖住两端时间。
                scaleY = scale
            }
            .onFocusChanged { focusState ->
                if (!focusState.isFocused) stopHold()
            }
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled && seekEnabled
                // 显式把「上」指到歌词区：仅靠几何搜索时歌词在右上角、间隔较大，
                // 部分遥控器/Compose 版本上会找不到焦点目标。
                if (upFocusRequester != null) up = upFocusRequester
            }
            .onKeyEvent { event ->
                if (!enabled || !seekEnabled) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val direction = if (event.key == Key.DirectionLeft) -1 else 1
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                startHold(direction)
                                true
                            }

                            KeyEventType.KeyUp -> {
                                stopHold()
                                true
                            }

                            else -> false
                        }
                    }

                    else -> false
                }
            }
            .focusable(enabled = enabled && seekEnabled, interactionSource = interaction)
            .drawBehind {                if (focused && seekEnabled) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.55f),
                        topLeft = Offset(0f, 2.dp.toPx()),
                        size = Size(size.width, size.height - 4.dp.toPx()),
                        cornerRadius = CornerRadius((size.height - 4.dp.toPx()) / 2f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barHeight = 4.dp.toPx()
            val top = (size.height - barHeight) / 2f
            val radius = CornerRadius(barHeight / 2f)
            val width = size.width

            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(0f, top),
                size = Size(width, barHeight),
                cornerRadius = radius,
            )

            val buffered = (bufferedPercent / 100f).coerceIn(0f, 1f)
            if (buffered > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.30f),
                    topLeft = Offset(0f, top),
                    size = Size(width * buffered, barHeight),
                    cornerRadius = radius,
                )
            }

            if (progress > 0f) {
                drawRoundRect(
                    color = CloudMusicColors.NavAccent,
                    topLeft = Offset(0f, top),
                    size = Size(width * progress, barHeight),
                    cornerRadius = radius,
                )
            }

            // 拖动点 / 播放位置点
            val thumbRadius = if (focused) 5.dp.toPx() else 3.5.dp.toPx()
            val thumbX = (width * progress).coerceIn(thumbRadius, width - thumbRadius)
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(thumbX, size.height / 2f),
            )
        }
    }
}
