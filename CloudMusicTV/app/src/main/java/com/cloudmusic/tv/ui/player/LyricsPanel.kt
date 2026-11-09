package com.cloudmusic.tv.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.player.LrcLine
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import kotlinx.coroutines.delay

/**
 * 右侧歌词栏。
 *
 * ### 布局
 * - 固定 8 行视野（[PlayerDimens.LyricVisibleLines]），行高 30dp；有翻译时再加 18dp；
 * - 当前行/选中行的**中心**放在可视区 40% 高度处，其余行上下排布；
 * - 上下用 `Brush.verticalGradient` + `BlendMode.DstIn` 做渐隐遮罩；
 * - 只渲染选中行附近的十几行，不把整份歌词都塞进布局（2GB 电视）。
 *
 * ### 焦点
 * - 歌词栏本身是一个 focusable 区域，D-pad 上下 = 逐行移动选中行；
 * - 选中行左侧 3dp 白竖条 + 字号 18sp 加粗；
 * - 确认键 = `onSeek(lines[selected].timeMs)`；
 * - 8 秒无按键 → 调 [onRequestFocusElsewhere] 把焦点还给播放按钮（父组件负责）；
 * - 返回键由父组件的 BackHandler 处理：先退出歌词焦点，再退出播放页。
 *
 * @param exitFocusSignal 父组件递增它来要求歌词栏交出焦点（返回键 / 队列抽屉打开）。
 * @param focusEnabled 覆盖层（队列 / 更多）打开时为 false，避免焦点从遮罩后面被找到。
 */
@Composable
fun LyricsPanel(
    state: LyricsUiState,
    currentLineIndex: Int,
    onSeek: (Long) -> Unit,
    onFocusedChange: (Boolean) -> Unit,
    exitFocusSignal: Int,
    onRequestFocusElsewhere: () -> Unit,
    focusEnabled: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val lines = (state as? LyricsUiState.Ready)?.lines.orEmpty()
    val hasLyrics = lines.isNotEmpty()

    var focused by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var interactionTick by remember { mutableIntStateOf(0) }

    // 换歌后把选中行收敛到新歌词范围内。
    LaunchedEffect(lines) {
        if (lines.isNotEmpty()) {
            selectedIndex = currentLineIndex.coerceIn(0, lines.lastIndex)
        }
    }

    // 8 秒无操作自动失焦。每次按键 interactionTick +1，定时器重来。
    LaunchedEffect(focused, interactionTick) {
        if (!focused) return@LaunchedEffect
        delay(LYRIC_FOCUS_IDLE_MS)
        onRequestFocusElsewhere()
    }

    LaunchedEffect(exitFocusSignal) {
        if (exitFocusSignal > 0 && focused) {
            onRequestFocusElsewhere()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                if (focused) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.60f),
                        topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                        size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            .onFocusChanged { focusState ->
                val gained = focusState.isFocused
                if (gained == focused) return@onFocusChanged
                focused = gained
                onFocusedChange(gained)
                if (gained && lines.isNotEmpty()) {
                    selectedIndex = currentLineIndex.coerceIn(0, lines.lastIndex)
                    interactionTick++
                }
            }
            .onKeyEvent { event ->
                if (!focused || lines.isEmpty()) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        interactionTick++
                        true
                    }

                    Key.DirectionDown -> {
                        selectedIndex = (selectedIndex + 1).coerceAtMost(lines.lastIndex)
                        interactionTick++
                        true
                    }

                    Key.DirectionCenter, Key.Enter -> {
                        onSeek(lines[selectedIndex].timeMs)
                        interactionTick++
                        true
                    }

                    else -> false
                }
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(enabled = hasLyrics && focusEnabled),
    ) {
        when (state) {
            LyricsUiState.Idle, LyricsUiState.Loading -> LyricsPlaceholder("歌词加载中…")

            is LyricsUiState.Empty -> LyricsPlaceholder("暂无歌词")

            is LyricsUiState.Failed -> LyricsPlaceholder(
                title = "暂无歌词",
                detail = "歌词接口：${state.code}",
            )

            is LyricsUiState.Ready -> LyricsContent(
                lines = state.lines,
                activeIndex = if (focused) selectedIndex else currentLineIndex,
                selectedIndex = if (focused) selectedIndex else -1,
                currentIndex = currentLineIndex,
            )
        }
    }
}

/** 固定 8 行视野的歌词内容；只组合窗口内的行。 */
@Composable
private fun LyricsContent(
    lines: List<LrcLine>,
    activeIndex: Int,
    selectedIndex: Int,
    currentIndex: Int,
) {
    if (lines.isEmpty()) {
        LyricsPlaceholder("暂无歌词")
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val anchorIndex = activeIndex.coerceIn(0, lines.lastIndex)
        val windowStart = (anchorIndex - LYRIC_WINDOW_BEFORE).coerceAtLeast(0)
        val windowEnd = (anchorIndex + LYRIC_WINDOW_AFTER).coerceAtMost(lines.lastIndex)

        var heightBeforeAnchor = 0.dp
        for (index in windowStart until anchorIndex) {
            heightBeforeAnchor += lyricRowHeight(lines[index])
        }
        val anchorHeight = lyricRowHeight(lines[anchorIndex])

        // 让「当前行中心」落在可视区 40% 处。
        val targetTop = maxHeight * LYRIC_ANCHOR_FRACTION - heightBeforeAnchor - anchorHeight / 2f
        val animatedTop by animateDpAsState(
            targetValue = targetTop,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "lyricsScroll",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // 上下渐隐：DstIn 用 alpha 擦除边缘，中间保留。
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.10f to Color.Black,
                            0.90f to Color.Black,
                            1.00f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, animatedTop.roundToPx()) },
            ) {
                for (index in windowStart..windowEnd) {
                    LyricRow(
                        line = lines[index],
                        isCurrent = index == currentIndex,
                        isSelected = index == selectedIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricRow(
    line: LrcLine,
    isCurrent: Boolean,
    isSelected: Boolean,
) {
    val highlighted = isCurrent || isSelected
    val rowHeight = lyricRowHeight(line)
    val mainFontSize = if (highlighted) 18.sp else 15.sp
    val translationFontSize = if (highlighted) 14.sp else 12.sp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3dp 竖条：只有「焦点选中行」才有，当前播放行靠字号/颜色区分。
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(rowHeight - 8.dp)
                .then(
                    if (isSelected) {
                        Modifier.background(Color.White, RoundedCornerShape(2.dp))
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(modifier = Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = mainFontSize,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                    lineHeight = 24.sp,
                ),
                color = if (highlighted) Color.White else CloudMusicColors.NavUnselectedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp),
            )
            // LrcLine 来自 core-player 模块，Kotlin 不能跨模块 smart cast 可空属性，
            // 先落到局部变量再判空。
            val translation = line.translation
            if (!translation.isNullOrBlank()) {
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = translationFontSize,
                        lineHeight = 16.sp,
                    ),
                    color = if (highlighted) {
                        Color.White.copy(alpha = 0.78f)
                    } else {
                        CloudMusicColors.SectionHeader
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LyricsPlaceholder(title: String, detail: String? = null) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = CloudMusicColors.NavUnselectedText,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = CloudMusicColors.SectionHeader,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun lyricRowHeight(line: LrcLine): Dp =
    if (line.translation.isNullOrBlank()) {
        PlayerDimens.LyricLineHeight
    } else {
        PlayerDimens.LyricLineHeight + PlayerDimens.LyricTranslationHeight
    }

private const val LYRIC_FOCUS_IDLE_MS = 8_000L

/** 当前行上方预渲染的行数（可视区约 3.2 行，留余量）。 */
private const val LYRIC_WINDOW_BEFORE = 6

/** 当前行下方预渲染的行数（可视区约 7.4 行，留余量）。 */
private const val LYRIC_WINDOW_AFTER = 10

/** 当前行中心在歌词区的高度比例。 */
private const val LYRIC_ANCHOR_FRACTION = 0.40f
