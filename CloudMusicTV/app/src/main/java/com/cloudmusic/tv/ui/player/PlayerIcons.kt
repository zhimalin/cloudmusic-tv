package com.cloudmusic.tv.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.tv.ui.theme.CloudMusicColors

/**
 * 播放页的 D-pad 控件外壳：焦点 2dp 白描边 + 1.05 倍缩放 + 150ms ease-out。
 *
 * 和 `TvButton` 一样用 `clickable` 而不是自己处理确认键，保证 ENTER / DPAD_CENTER
 * 走标准 Compose 语义；长按连发由具体控件（进度条）自己处理。
 */
@Composable
fun PlayerControlButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    width: Dp = 44.dp,
    height: Dp = 44.dp,
    shape: Shape = RoundedCornerShape(10.dp),
    enabled: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "playerControlScale",
    )

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent, shape)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // 覆盖层打开时显式退出焦点搜索：仅靠 clickable(enabled=false) 在部分
            // Compose 版本里仍会把焦点停到按钮上。
            .focusProperties { canFocus = enabled }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

/**
 * 中央 56dp 播放/暂停圆钮。
 *
 * 焦点态用「白底 → 红底 + 内圈白描边 + 图标反色」表达：如果只画白描边，
 * 在白色圆钮上根本看不见。
 */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "playButtonScale",
    )

    Box(
        modifier = modifier
            .size(PlayerDimens.PlayButtonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { canFocus = enabled }
            .clip(CircleShape)
            .background(if (focused) CloudMusicColors.NavAccent else Color.White)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics { contentDescription = if (isPlaying) "暂停" else "播放" },
        contentAlignment = Alignment.Center,
    ) {
        if (focused) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White,
                    radius = size.minDimension / 2f - 2.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        PlayPauseIcon(
            isPlaying = isPlaying,
            color = if (focused) Color.White else Color(0xFF141216),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 播放/暂停图标（Canvas 绘制，不依赖字体 glyph）。 */
@Composable
fun PlayPauseIcon(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (isPlaying) {
            val barWidth = width * 0.22f
            val gap = width * 0.16f
            val left = width * 0.28f
            drawRoundRect(
                color = color,
                topLeft = Offset(left, height * 0.14f),
                size = androidx.compose.ui.geometry.Size(barWidth, height * 0.72f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.25f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(left + barWidth + gap, height * 0.14f),
                size = androidx.compose.ui.geometry.Size(barWidth, height * 0.72f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.25f),
            )
        } else {
            val path = Path().apply {
                moveTo(width * 0.28f, height * 0.12f)
                lineTo(width * 0.86f, height * 0.5f)
                lineTo(width * 0.28f, height * 0.88f)
                close()
            }
            drawPath(path, color)
        }
    }
}

/** 上一首：竖条 + 左向三角。 */
@Composable
fun PreviousIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(width * 0.16f, height * 0.16f),
            size = androidx.compose.ui.geometry.Size(width * 0.1f, height * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.04f),
        )
        val path = Path().apply {
            moveTo(width * 0.84f, height * 0.16f)
            lineTo(width * 0.84f, height * 0.84f)
            lineTo(width * 0.32f, height * 0.5f)
            close()
        }
        drawPath(path, color)
    }
}

/** 下一首：右向三角 + 竖条。 */
@Composable
fun NextIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val path = Path().apply {
            moveTo(width * 0.16f, height * 0.16f)
            lineTo(width * 0.16f, height * 0.84f)
            lineTo(width * 0.68f, height * 0.5f)
            close()
        }
        drawPath(path, color)
        drawRoundRect(
            color = color,
            topLeft = Offset(width * 0.74f, height * 0.16f),
            size = androidx.compose.ui.geometry.Size(width * 0.1f, height * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.04f),
        )
    }
}

/** 收藏心形；[filled] = 已收藏（本轮只做视觉态）。 */
@Composable
fun HeartIcon(
    color: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.86f)
            cubicTo(
                width * 0.08f, height * 0.60f,
                width * 0.10f, height * 0.20f,
                width * 0.32f, height * 0.20f,
            )
            cubicTo(
                width * 0.43f, height * 0.20f,
                width * 0.50f, height * 0.28f,
                width * 0.50f, height * 0.36f,
            )
            cubicTo(
                width * 0.50f, height * 0.28f,
                width * 0.57f, height * 0.20f,
                width * 0.68f, height * 0.20f,
            )
            cubicTo(
                width * 0.90f, height * 0.20f,
                width * 0.92f, height * 0.60f,
                width * 0.5f, height * 0.86f,
            )
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = if (filled) Fill else Stroke(width = 1.8.dp.toPx()),
        )
    }
}

/** 播放队列：三条横线 + 左侧小圆点。 */
@Composable
fun QueueIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val stroke = 1.8.dp.toPx()
        val ys = listOf(0.26f, 0.5f, 0.74f)
        ys.forEach { fraction ->
            val y = height * fraction
            drawCircle(color = color, radius = stroke * 0.9f, center = Offset(width * 0.14f, y))
            drawLine(
                color = color,
                start = Offset(width * 0.3f, y),
                end = Offset(width * 0.86f, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 更多：三个点。 */
@Composable
fun MoreIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension * 0.09f
        val y = size.height / 2f
        listOf(0.26f, 0.5f, 0.74f).forEach { fraction ->
            drawCircle(color = color, radius = radius, center = Offset(size.width * fraction, y))
        }
    }
}

/**
 * 播放模式按钮：文字胶囊（顺序 / 随机 / 单曲）。
 *
 * 用文字而不是自绘循环箭头：电视遥控器上两字中文比 20dp 的箭头更容易辨认。
 */
@Composable
fun PlaybackModeButton(
    modeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    PlayerControlButton(
        contentDescription = "播放模式：$modeLabel，按确认切换",
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        width = 64.dp,
        height = 40.dp,
        shape = RoundedCornerShape(20.dp),
        enabled = enabled,
    ) {
        androidx.tv.material3.Text(
            text = modeLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}
