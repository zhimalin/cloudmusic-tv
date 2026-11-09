package com.cloudmusic.core.data

import android.content.Context

/**
 * User settings, backed by SharedPreferences.
 *
 * The player page consumes all three values now:
 * - [audioQuality] → `PlaybackQuality.fromSettingsLabel` → `MusicPlayer.defaultLevel`;
 * - [rotationEnabled] → 黑胶唱片是否旋转；
 * - [backgroundTintEnabled] → 播放页是否用封面主色做背景渐变。
 *
 * 只存字符串/布尔，不缓存 bitmap。
 */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 外观 / 唱片旋转 */
    var rotationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROTATION, true)
        set(value) = prefs.edit().putBoolean(KEY_ROTATION, value).apply()

    /** 外观 / 背景取色 */
    var backgroundTintEnabled: Boolean
        get() = prefs.getBoolean(KEY_BG_TINT, true)
        set(value) = prefs.edit().putBoolean(KEY_BG_TINT, value).apply()

    /**
     * 播放 / 默认音质。
     *
     * 文案直接作为 `song/url/v1` 档位的映射输入（见 `PlaybackQuality`）；
     * 改设置只影响下一首取地址，正在播的曲目不会中途换码率。
     */
    var audioQuality: String
        get() = prefs.getString(KEY_QUALITY, DEFAULT_QUALITY) ?: DEFAULT_QUALITY
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    /** 存储 / 缓存占用（字节）. Fixed at 0 this round, nothing is cached yet. */
    val cacheSizeBytes: Long
        get() = prefs.getLong(KEY_CACHE_BYTES, 0L)

    fun clearCachePlaceholder() {
        prefs.edit().putLong(KEY_CACHE_BYTES, 0L).apply()
    }

    private companion object {
        const val PREFS_NAME = "cloudmusic_settings"
        const val KEY_ROTATION = "appearance_rotation"
        const val KEY_BG_TINT = "appearance_bg_tint"
        const val KEY_QUALITY = "playback_quality"
        const val KEY_CACHE_BYTES = "storage_cache_bytes"
        const val DEFAULT_QUALITY = "极高"
    }
}
