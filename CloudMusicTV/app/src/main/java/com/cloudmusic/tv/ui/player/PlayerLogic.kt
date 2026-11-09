package com.cloudmusic.tv.ui.player

import com.cloudmusic.core.player.LrcLine
import com.cloudmusic.core.player.PlaybackMode
import java.util.Locale

/**
 * 播放页的纯 Kotlin 计算，**不引用任何 Android / Compose 类型**。
 *
 * 抽出来的目的：歌词行号、时间格式、进度步进这三块是最容易算错、又必须在电视上
 * 逐帧生效的部分，放在 JVM 单测里就能覆盖边界（见 `app/src/test/.../PlayerLogicTest.kt`）。
 */
object PlaybackTime {

    /**
     * 毫秒 → `mm:ss`。
     *
     * - 向下取整到秒（`59_999` → `00:59`），和 ExoPlayer 的进度显示一致；
     * - 负数按 0 处理；
     * - 超过 1 小时不进位到 `h:mm:ss`，分钟继续累加：`3_600_000` → `60:00`。
     *   电视上的进度条两端只放得下 5 个字符，`mm:ss` 是产品规格。
     */
    fun format(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * 歌词当前行 / 选中行的下标计算。
 *
 * 约定：
 * - 返回**最后一个** `timeMs <= positionMs` 的行下标；
 * - 第一行之前返回 `-1`（还没有任何歌词该高亮）；
 * - 最后一行之后保持最后一行（歌词不会“消失”）；
 * - 时间戳相同（LRC 里可能出现）返回最后一个，和后写入覆盖的直觉一致。
 */
object LyricCursor {

    fun indexAt(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        // 单调时间戳 → 二分；歌词最多几百行，二分只是避免每 500ms 全表扫描。
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

/**
 * 进度条左/右键步进。
 *
 * - 默认 ±10 秒；
 * - 位置先夹到 `0..duration`（duration 未知时只保证非负）；
 * - 步进结果再夹一次，所以不会出现负进度或超过时长的进度；
 * - 加减用饱和运算，极端值也不会溢出。
 */
object SeekMath {

    const val STEP_MS = 10_000L

    fun step(
        positionMs: Long,
        durationMs: Long,
        deltaMs: Long = STEP_MS,
    ): Long {
        val duration = durationMs.coerceAtLeast(0L)
        val position = if (duration > 0L) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        val raw = saturatedAdd(position, deltaMs)
        return if (duration > 0L) raw.coerceIn(0L, duration) else raw.coerceAtLeast(0L)
    }

    private fun saturatedAdd(a: Long, b: Long): Long = when {
        b > 0L && a > Long.MAX_VALUE - b -> Long.MAX_VALUE
        b < 0L && a < Long.MIN_VALUE - b -> Long.MIN_VALUE
        else -> a + b
    }
}

/** 播放模式循环：顺序 → 随机 → 单曲循环 → 顺序。 */
object PlaybackModes {

    fun next(mode: PlaybackMode): PlaybackMode = when (mode) {
        PlaybackMode.SEQUENTIAL -> PlaybackMode.SHUFFLE
        PlaybackMode.SHUFFLE -> PlaybackMode.SINGLE_REPEAT
        PlaybackMode.SINGLE_REPEAT -> PlaybackMode.SEQUENTIAL
    }

    /** 播放模式按钮上的两字文案。 */
    fun label(mode: PlaybackMode): String = when (mode) {
        PlaybackMode.SEQUENTIAL -> "顺序"
        PlaybackMode.SHUFFLE -> "随机"
        PlaybackMode.SINGLE_REPEAT -> "单曲"
    }
}

/**
 * 「默认音质」的可选项与循环顺序。
 *
 * 文案必须和 `PlaybackQuality.fromSettingsLabel` 的映射表一致：
 * 标准 → standard、较高 → higher、极高 → exhigh、无损 → lossless。
 */
object PlayerQuality {

    val options: List<String> = listOf("标准", "较高", "极高", "无损")

    fun next(current: String): String {
        val index = options.indexOf(current)
        // index = -1（未知文案）也落到第一项，避免越界。
        return options[(index + 1 + options.size) % options.size]
    }
}
