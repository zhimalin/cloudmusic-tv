package com.cloudmusic.core.player

import com.cloudmusic.core.network.SongLevel

/** 设置页音质文案 ↔ `song/url/v1` 档位。 */
object PlaybackQuality {

    /** 未知文案回退到 [SongLevel.EXHIGH]（设置页默认「极高」）。 */
    fun fromSettingsLabel(label: String): SongLevel = when (label.trim().lowercase()) {
        "无损", "lossless" -> SongLevel.LOSSLESS
        "极高", "exhigh" -> SongLevel.EXHIGH
        "较高", "higher" -> SongLevel.HIGHER
        "标准", "standard" -> SongLevel.STANDARD
        else -> SongLevel.EXHIGH
    }
}
