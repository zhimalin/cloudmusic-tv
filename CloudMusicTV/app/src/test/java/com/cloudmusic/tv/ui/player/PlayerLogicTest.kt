package com.cloudmusic.tv.ui.player

import com.cloudmusic.core.player.LrcLine
import com.cloudmusic.core.player.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放页纯逻辑单测（JVM，无 Android 依赖）。
 *
 * 覆盖需求里点名的三块：
 * 1. 歌词当前行索引（第一行前 / 最后一行后 / 正好等于某行时间）；
 * 2. 时间格式化成 `mm:ss`；
 * 3. 进度条 ±10 秒步进与 0..duration 钳制。
 *
 * 另外补播放模式循环和音质循环，防止按钮文案/顺序回归。
 */
class PlayerLogicTest {

    // ------------------------------------------------------------------ 歌词行号

    private val lyrics = listOf(
        LrcLine(timeMs = 1_000L, text = "第一句"),
        LrcLine(timeMs = 3_000L, text = "第二句"),
        LrcLine(timeMs = 5_000L, text = "第三句"),
        LrcLine(timeMs = 7_000L, text = "第四句"),
    )

    @Test
    fun `empty lyric list has no current line`() {
        assertEquals(-1, LyricCursor.indexAt(emptyList(), 0L))
        assertEquals(-1, LyricCursor.indexAt(emptyList(), 99_999L))
    }

    @Test
    fun `before first line returns minus one`() {
        assertEquals(-1, LyricCursor.indexAt(lyrics, 0L))
        assertEquals(-1, LyricCursor.indexAt(lyrics, 999L))
    }

    @Test
    fun `exact first line time selects first line`() {
        assertEquals(0, LyricCursor.indexAt(lyrics, 1_000L))
    }

    @Test
    fun `between lines keeps the previous line`() {
        assertEquals(0, LyricCursor.indexAt(lyrics, 2_999L))
        assertEquals(1, LyricCursor.indexAt(lyrics, 3_000L))
        assertEquals(1, LyricCursor.indexAt(lyrics, 4_999L))
    }

    @Test
    fun `exact middle line time selects that line`() {
        assertEquals(2, LyricCursor.indexAt(lyrics, 5_000L))
    }

    @Test
    fun `after last line stays on last line`() {
        assertEquals(3, LyricCursor.indexAt(lyrics, 7_000L))
        assertEquals(3, LyricCursor.indexAt(lyrics, 7_001L))
        assertEquals(3, LyricCursor.indexAt(lyrics, 10_000_000L))
    }

    @Test
    fun `negative progress is before the first line`() {
        assertEquals(-1, LyricCursor.indexAt(lyrics, -1L))
        assertEquals(-1, LyricCursor.indexAt(lyrics, Long.MIN_VALUE))
    }

    @Test
    fun `single line lyric works at every boundary`() {
        val one = listOf(LrcLine(timeMs = 2_000L, text = "只有一句"))
        assertEquals(-1, LyricCursor.indexAt(one, 1_999L))
        assertEquals(0, LyricCursor.indexAt(one, 2_000L))
        assertEquals(0, LyricCursor.indexAt(one, 2_001L))
    }

    @Test
    fun `duplicate timestamps resolve to the last matching line`() {
        val duplicate = listOf(
            LrcLine(timeMs = 1_000L, text = "原文"),
            LrcLine(timeMs = 1_000L, text = "重复"),
            LrcLine(timeMs = 2_000L, text = "下一句"),
        )
        assertEquals(1, LyricCursor.indexAt(duplicate, 1_000L))
        assertEquals(1, LyricCursor.indexAt(duplicate, 1_500L))
        assertEquals(2, LyricCursor.indexAt(duplicate, 2_000L))
    }

    // ------------------------------------------------------------------ 时间格式

    @Test
    fun `format zero milliseconds`() {
        assertEquals("00:00", PlaybackTime.format(0L))
    }

    @Test
    fun `format floors partial seconds`() {
        assertEquals("00:00", PlaybackTime.format(999L))
        assertEquals("00:01", PlaybackTime.format(1_000L))
        assertEquals("00:01", PlaybackTime.format(1_999L))
    }

    @Test
    fun `format minute boundaries`() {
        assertEquals("00:59", PlaybackTime.format(59_000L))
        assertEquals("01:00", PlaybackTime.format(60_000L))
        assertEquals("59:59", PlaybackTime.format(3_599_999L))
    }

    @Test
    fun `format keeps counting minutes past one hour`() {
        assertEquals("60:00", PlaybackTime.format(3_600_000L))
        assertEquals("75:30", PlaybackTime.format(4_530_000L))
    }

    @Test
    fun `format clamps negative input to zero`() {
        assertEquals("00:00", PlaybackTime.format(-1L))
        assertEquals("00:00", PlaybackTime.format(-60_000L))
    }

    // ------------------------------------------------------------------ 进度步进

    private val duration = 100_000L

    @Test
    fun `step forward is ten seconds`() {
        assertEquals(15_000L, SeekMath.step(5_000L, duration))
        assertEquals(95_000L, SeekMath.step(85_000L, duration))
    }

    @Test
    fun `step backward is ten seconds`() {
        assertEquals(0L, SeekMath.step(5_000L, duration, -SeekMath.STEP_MS))
        assertEquals(45_000L, SeekMath.step(55_000L, duration, -SeekMath.STEP_MS))
    }

    @Test
    fun `step clamps to zero at the start`() {
        assertEquals(0L, SeekMath.step(0L, duration, -SeekMath.STEP_MS))
        assertEquals(0L, SeekMath.step(3_000L, duration, -SeekMath.STEP_MS))
        assertEquals(0L, SeekMath.step(-5_000L, duration, -SeekMath.STEP_MS))
    }

    @Test
    fun `step clamps to duration at the end`() {
        assertEquals(100_000L, SeekMath.step(95_000L, duration))
        assertEquals(100_000L, SeekMath.step(100_000L, duration))
        assertEquals(100_000L, SeekMath.step(120_000L, duration))
        assertEquals(90_000L, SeekMath.step(120_000L, duration, -SeekMath.STEP_MS))
    }

    @Test
    fun `unknown duration only clamps to zero`() {
        assertEquals(15_000L, SeekMath.step(5_000L, 0L))
        assertEquals(0L, SeekMath.step(5_000L, 0L, -SeekMath.STEP_MS))
        // 负位置先夹到 0，再执行 +10 秒，所以是 10_000 而不是 0。
        assertEquals(10_000L, SeekMath.step(-1L, 0L))
    }

    @Test
    fun `zero delta keeps clamped position`() {
        assertEquals(0L, SeekMath.step(-5_000L, duration, 0L))
        assertEquals(100_000L, SeekMath.step(120_000L, duration, 0L))
        assertEquals(37_000L, SeekMath.step(37_000L, duration, 0L))
    }

    @Test
    fun `huge values saturate instead of overflowing`() {
        assertEquals(Long.MAX_VALUE, SeekMath.step(Long.MAX_VALUE, 0L, SeekMath.STEP_MS))
        assertEquals(0L, SeekMath.step(0L, 0L, Long.MIN_VALUE))
    }

    // ------------------------------------------------------------------ 按钮循环

    @Test
    fun `playback mode cycles sequential shuffle single`() {
        assertEquals(PlaybackMode.SHUFFLE, PlaybackModes.next(PlaybackMode.SEQUENTIAL))
        assertEquals(PlaybackMode.SINGLE_REPEAT, PlaybackModes.next(PlaybackMode.SHUFFLE))
        assertEquals(PlaybackMode.SEQUENTIAL, PlaybackModes.next(PlaybackMode.SINGLE_REPEAT))
    }

    @Test
    fun `playback mode labels are stable`() {
        assertEquals("顺序", PlaybackModes.label(PlaybackMode.SEQUENTIAL))
        assertEquals("随机", PlaybackModes.label(PlaybackMode.SHUFFLE))
        assertEquals("单曲", PlaybackModes.label(PlaybackMode.SINGLE_REPEAT))
    }

    @Test
    fun `quality cycles standard higher exhigh lossless`() {
        assertEquals(listOf("标准", "较高", "极高", "无损"), PlayerQuality.options)
        assertEquals("较高", PlayerQuality.next("标准"))
        assertEquals("极高", PlayerQuality.next("较高"))
        assertEquals("无损", PlayerQuality.next("极高"))
        assertEquals("标准", PlayerQuality.next("无损"))
    }

    @Test
    fun `unknown quality falls back to first option`() {
        assertEquals("标准", PlayerQuality.next("不知道"))
        assertEquals("标准", PlayerQuality.next(""))
    }
}
