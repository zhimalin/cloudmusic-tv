package com.cloudmusic.tv.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.cloudmusic.tv.ui.music.PlaylistCover

/**
 * 左侧黑胶唱片 + 唱针。
 *
 * 视觉全部用 Canvas 画（径向渐变盘面 + 30 多圈沟槽 + 高光 + 边缘），
 * **不贴任何大图**：唯一的一张图是中央 160dp 封面，由 Coil 按 360dp 请求后缩放。
 *
 * 旋转：
 * - 用 `withFrameNanos` 自己累计角度，9 秒一圈（[PlayerDimens.VinylRotationSeconds]）；
 * - 暂停时协程取消，角度**停在当前值**；继续播放从该角度接着转，不回 0；
 * - 设置项「唱片旋转」关闭时协程直接不启动，角度保持；
 * - 角度只在 `graphicsLayer` 的 lambda 里读取，旋转不触发重组，只更新图层。
 */
@Composable
fun VinylRecord(
    coverUrl: String?,
    isPlaying: Boolean,
    rotationEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, rotationEnabled) {
        if (!isPlaying || !rotationEnabled) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            // 应用退到后台再回来时帧时间戳会跳很大；夹到 100ms，避免唱片“瞬移”。
            val deltaSeconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
            previousFrame = frame
            angle = (angle + deltaSeconds * (360f / PlayerDimens.VinylRotationSeconds)) % 360f
        }
    }

    Box(modifier = modifier) {
        // 1. 静态投影：不能跟着唱片旋转，否则阴影会绕着中心晃。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = vinylCenter()
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = PlayerDimens.VinylSize.toPx() / 2f,
                center = center + Offset(0f, 10.dp.toPx()),
            )
        }

        // 2. 旋转体：盘面 + 封面 + 中心孔。
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 12.dp)
                .size(PlayerDimens.VinylSize)
                .graphicsLayer { rotationZ = angle },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawVinylDisc()
            }

            PlaylistCover(
                url = coverUrl,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(PlayerDimens.CoverSize)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp)),
                // 160dp 显示，只请求 180dp；比列表的 360dp 省一半以上解码内存。
                requestWidthDp = 180,
            )

            // 中心轴孔：圆形，所以旋转看不出来，但能让「这是一张唱片」更明确。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0B0A0D))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            )
        }

        // 3. 固定高光：真实唱片的反光相对光源固定，不随盘面转。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = vinylCenter()
            val radius = PlayerDimens.VinylSize.toPx() / 2f
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.045f),
                        Color.Transparent,
                        Color.White.copy(alpha = 0.025f),
                        Color.Transparent,
                    ),
                    center = center,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                    center = center + Offset(-radius * 0.35f, -radius * 0.42f),
                    radius = radius * 1.1f,
                ),
                radius = radius,
                center = center,
            )
        }

        // 4. 唱针：从右上方搭到盘面边缘；播放/暂停时绕支点轻微起落。
        val lift by animateFloatAsState(
            targetValue = if (isPlaying) 0f else 5.5f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            label = "tonearmLift",
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTonearm(center = vinylCenter(), liftDegrees = lift)
        }
    }
}

private fun DrawScope.vinylCenter(): Offset =
    Offset(
        x = size.width / 2f,
        y = size.height / 2f + 12.dp.toPx(),
    )

/** 深色盘面 + 细沟槽 + 边缘高光。 */
private fun DrawScope.drawVinylDisc() {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF2C2830),
                Color(0xFF19161C),
                Color(0xFF0C0B0E),
            ),
            center = center + Offset(-radius * 0.30f, -radius * 0.35f),
            radius = radius * 1.7f,
        ),
        radius = radius,
        center = center,
    )

    // 沟槽：34 圈，越靠外越密、亮度略有变化，制造纹路感。
    val grooveCount = 34
    for (index in 0 until grooveCount) {
        val fraction = index / (grooveCount - 1f)
        val grooveRadius = radius * (0.58f + 0.39f * fraction)
        val alpha = 0.028f + 0.022f * ((index % 3) / 2f)
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = grooveRadius,
            center = center,
            style = Stroke(width = 0.8.dp.toPx()),
        )
    }

    // 曲目之间的宽间隔：三条略粗的暗圈。
    listOf(0.70f, 0.84f, 0.935f).forEach { fraction ->
        drawCircle(
            color = Color.Black.copy(alpha = 0.30f),
            radius = radius * fraction,
            center = center,
            style = Stroke(width = 2.4.dp.toPx()),
        )
    }

    // 外缘一圈冷白高光，让圆盘和深色背景分离。
    drawCircle(
        color = Color.White.copy(alpha = 0.14f),
        radius = radius - 1.dp.toPx(),
        center = center,
        style = Stroke(width = 1.4.dp.toPx()),
    )
    drawCircle(
        color = Color.Black.copy(alpha = 0.55f),
        radius = radius + 1.dp.toPx(),
        center = center,
        style = Stroke(width = 2.dp.toPx()),
    )
}

/**
 * 唱针：支点在可视区右上角，臂尖落在盘面靠外的位置。
 *
 * 位置全部按当前容器尺寸算，不写死 px，换分辨率不会错位。
 */
private fun DrawScope.drawTonearm(center: Offset, liftDegrees: Float) {
    val radius = PlayerDimens.VinylSize.toPx() / 2f
    val pivot = Offset(size.width * 0.84f, size.height * 0.045f)
    val toCenter = center - pivot
    val distance = toCenter.getDistance()
    // 容器太窄（理论上不会发生）就不画，避免除零/越界。
    if (distance <= radius + 1f) return

    val direction = toCenter / distance
    val attach = center - direction * (radius * 0.86f)
    val armDirection = (attach - pivot) / (attach - pivot).getDistance()

    rotate(degrees = liftDegrees, pivot = pivot) {
        // 配重块（支点后方）
        val counterweight = pivot - armDirection * 26.dp.toPx()
        drawCircle(color = Color(0xFF0C0B0E), radius = 12.dp.toPx(), center = counterweight)
        drawCircle(color = Color(0xFF3A3640), radius = 10.5.dp.toPx(), center = counterweight)
        drawCircle(
            color = Color(0xFFB9B4BE),
            radius = 10.5.dp.toPx(),
            center = counterweight,
            style = Stroke(width = 1.dp.toPx()),
        )

        // 臂杆：先画一层深色描边，再叠细的金属色。
        drawLine(
            color = Color(0xFF0B0A0D),
            start = pivot,
            end = attach,
            strokeWidth = 7.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFFC9C4CC),
            start = pivot,
            end = attach,
            strokeWidth = 3.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = pivot + Offset(0f, -1.dp.toPx()),
            end = attach + Offset(0f, -1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // 唱头：尖端稍向盘心延伸。
        val shellStart = attach - armDirection * 10.dp.toPx()
        val shellEnd = attach + armDirection * 8.dp.toPx()
        drawLine(
            color = Color(0xFF141216),
            start = shellStart,
            end = shellEnd,
            strokeWidth = 13.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFF4A4550),
            start = shellStart,
            end = shellEnd,
            strokeWidth = 9.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Color(0xFFE6E1E8),
            radius = 2.2.dp.toPx(),
            center = shellEnd,
        )

        // 支点底座
        drawCircle(color = Color(0xFF0B0A0D), radius = 15.dp.toPx(), center = pivot)
        drawCircle(color = Color(0xFF2E2A33), radius = 13.dp.toPx(), center = pivot)
        drawCircle(
            color = Color(0xFF8F8A94),
            radius = 13.dp.toPx(),
            center = pivot,
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawCircle(color = Color(0xFFD9D4DC), radius = 4.dp.toPx(), center = pivot)
    }
}
