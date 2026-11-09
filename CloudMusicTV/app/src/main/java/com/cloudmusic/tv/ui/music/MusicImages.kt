package com.cloudmusic.tv.ui.music

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.cloudmusic.tv.ui.theme.CloudMusicColors

/**
 * One process-wide Coil loader, bounded for the 2 GB TV.
 *
 * - memory cache: 64 MB
 * - disk cache: 128 MB under `cacheDir/image_cache`
 * - every cover request is decoded at 360 dp wide (square)
 */
object MusicImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: create(appContext).also { instance = it }
        }
    }

    private fun create(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(MEMORY_CACHE_BYTES)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(DISK_CACHE_DIR))
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .crossfade(true)
        .build()

    /** Coil 2's MemoryCache API takes an Int byte count; 64 MiB still fits. */
    const val MEMORY_CACHE_BYTES: Int = 64 * 1024 * 1024
    const val DISK_CACHE_BYTES: Long = 128L * 1024 * 1024
    const val REQUEST_WIDTH_DP: Int = 360
    private const val DISK_CACHE_DIR = "image_cache"
}

private val CoverPlaceholder = ColorPainter(CloudMusicColors.Surface)

/**
 * Cover with a bounded request.
 *
 * Netease image URLs are sometimes emitted as `http://p1.music.126.net/...`.
 * Android 9+ blocks cleartext by default, so upgrade those CDN URLs to HTTPS
 * instead of weakening the app-wide network policy.
 */
@Composable
fun PlaylistCover(
    url: String?,
    modifier: Modifier = Modifier,
    /**
     * 请求边长（dp）。列表默认 360dp；播放页 160dp 封面只请求 180dp，
     * 迷你条 42dp 封面只请求 96dp，避免为了小图解码 720px 的 bitmap。
     */
    requestWidthDp: Int = MusicImageLoader.REQUEST_WIDTH_DP,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val loader = remember(context) { MusicImageLoader.get(context) }
    val widthPx = with(density) { requestWidthDp.dp.roundToPx() }
    val secureUrl = remember(url) { secureImageUrl(url) }

    Box(modifier = modifier.background(CloudMusicColors.Surface)) {
        if (secureUrl.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF2A2730)))
        } else {
            val request = remember(secureUrl, widthPx, context) {
                ImageRequest.Builder(context)
                    .data(secureUrl)
                    .size(widthPx, widthPx)
                    .crossfade(180)
                    .build()
            }
            AsyncImage(
                model = request,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = CoverPlaceholder,
                error = CoverPlaceholder,
                fallback = CoverPlaceholder,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun String.toHttps(): String =
    if (startsWith("http://", ignoreCase = true)) {
        "https://" + substringAfter("://")
    } else {
        this
    }

/**
 * 把网易云 CDN 常见的 `http://` 封面地址升级成 `https://`。
 *
 * Android 9+ 默认禁止明文 HTTP；这里只做 URL 归一化，不放宽全局网络安全策略。
 * 播放页取主色（[CoverPalette]）也复用同一份规则，否则同一张封面会出现
 * 「列表能显示、背景取不到色」的不一致。
 */
internal fun secureImageUrl(url: String?): String? =
    url?.takeIf { it.isNotBlank() }?.toHttps()
