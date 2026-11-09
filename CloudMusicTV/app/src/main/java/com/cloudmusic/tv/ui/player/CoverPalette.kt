package com.cloudmusic.tv.ui.player

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.cloudmusic.tv.ui.music.secureImageUrl

/**
 * 从封面提取一个低饱和度的深色主色，给播放页背景渐变用。
 *
 * 刻意**不引入 Palette 库**：
 * 1. 依赖能省就省，2GB 电视的内存也不太适合再挂一套色彩量化；
 * 2. 需求只要「一个平均色 + 降饱和」，Coil 已经是现成的图片加载器。
 *
 * 流程：Coil 以 64×64 解码 → 缩到 8×8 → 跳过透明像素求 RGB 均值 → HSL 降饱和/压暗。
 * 中途产生的 bitmap 最大只有 64×64（16KB），不会进内存缓存之外的地方。
 */
object CoverPalette {

    /** 请求尺寸：64×64 足够代表封面，又比原图小两个数量级。 */
    private const val THUMB_PX = 64

    /** 均值采样网格。 */
    private const val SAMPLE_PX = 8

    private const val MIN_ALPHA = 32

    /**
     * @return 可用的主色；封面地址为空、加载失败、图像全透明时返回 null，
     *         由调用方回退到 [PlayerColors.DefaultTint]。
     */
    suspend fun extract(
        context: Context,
        loader: ImageLoader,
        coverUrl: String?,
    ): Color? {
        val secureUrl = secureImageUrl(coverUrl) ?: return null
        val request = ImageRequest.Builder(context)
            .data(secureUrl)
            .size(THUMB_PX, THUMB_PX)
            // 硬件 bitmap 不能 getPixels；显式要求软件 bitmap。
            .allowHardware(false)
            .build()

        val result = loader.execute(request)
        val drawable = (result as? SuccessResult)?.drawable ?: return null
        // androidx.core 的 toBitmap 会新建一张软件 bitmap（不会复用 drawable 内部状态），
        // 因此这里回收是安全的；两张图最大 64×64 / 8×8。
        val decoded = try {
            drawable.toBitmap(THUMB_PX, THUMB_PX, Bitmap.Config.ARGB_8888)
        } catch (error: IllegalArgumentException) {
            // 极少见：drawable 没有固有尺寸且不能转 bitmap。
            return null
        }

        val sample = Bitmap.createScaledBitmap(decoded, SAMPLE_PX, SAMPLE_PX, true)
        val average = averageArgb(sample)
        if (sample !== decoded) sample.recycle()
        decoded.recycle()

        return average?.let(::mute)
    }

    private fun averageArgb(bitmap: Bitmap): Int? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < MIN_ALPHA) continue
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
            count++
        }
        if (count == 0) return null
        return Color(
            red = (red / count).toInt(),
            green = (green / count).toInt(),
            blue = (blue / count).toInt(),
        ).toArgb()
    }

    /** 降饱和 + 压暗到适合做深色背景的范围；色相保持不变。 */
    private fun mute(argb: Int): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(argb, hsl)
        hsl[1] = hsl[1].coerceAtMost(0.32f)
        hsl[2] = hsl[2].coerceIn(0.14f, 0.30f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}
