package com.cloudmusic.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * 队列行为契约。这些用例就是下一轮 UI / 播放器可以依赖的行为说明：
 * 每个场景都同时覆盖「自动播完」和「用户手动切歌」两条路径。
 */
class PlaybackQueueTest {

    private fun tracks(vararg ids: Long): List<Track> =
        ids.map { Track(id = it, title = "track-$it", artist = "artist-$it") }

    // ------------------------------------------------------------------ 顺序模式

    @Test
    fun sequentialAutoPlayStopsAtQueueTail() {
        val queue = PlaybackQueue()
        queue.setQueue(tracks(1, 2, 3))

        assertEquals(1L, queue.current?.id)
        assertEquals(2L, queue.next(manual = false)?.id)
        assertEquals(3L, queue.next(manual = false)?.id)

        assertNull("自动播到队尾应停止，而不是回绕", queue.next(manual = false))
        assertEquals("停在最后一首", 3L, queue.current?.id)
        assertEquals(2, queue.currentIndex)
        assertEquals(3, queue.size)
    }

    @Test
    fun sequentialManualNextWrapsToFirstAtTail() {
        val queue = PlaybackQueue()
        queue.setQueue(tracks(1, 2, 3), startIndex = 2)
        assertEquals(3L, queue.current?.id)

        assertEquals("手动下一首在队尾回绕到第一首", 1L, queue.next(manual = true)?.id)
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun previousWrapsToLastAtHead() {
        val queue = PlaybackQueue()
        queue.setQueue(tracks(1, 2, 3), startIndex = 0)

        assertEquals("第一首按上一首回绕到最后一首", 3L, queue.previous()?.id)
        assertEquals(3L, queue.current?.id)
        assertEquals(2L, queue.previous()?.id)
        assertEquals(1L, queue.previous()?.id)
        assertEquals("再次从第一首回绕", 3L, queue.previous()?.id)
    }

    // ------------------------------------------------------------------ 单曲循环

    @Test
    fun singleRepeatAutoKeepsCurrentButManualNextMovesOn() {
        val queue = PlaybackQueue()
        queue.setQueue(tracks(1, 2, 3), startIndex = 1)
        queue.setMode(PlaybackMode.SINGLE_REPEAT)

        assertEquals("自动播完重播当前曲", 2L, queue.next(manual = false)?.id)
        assertEquals("自动路径不应改变下标", 1, queue.currentIndex)
        assertEquals(2L, queue.next(manual = false)?.id)

        assertEquals("手动下一首必须正常跳走", 3L, queue.next(manual = true)?.id)
        assertEquals("手动下一首在队尾回绕", 1L, queue.next(manual = true)?.id)
        assertEquals("单曲循环下上一首也正常走", 3L, queue.previous()?.id)
    }

    // ------------------------------------------------------------------ 随机模式

    @Test
    fun shufflePlaysEveryTrackOncePerRound() {
        val queue = PlaybackQueue(Random(42))
        queue.setQueue(tracks(1, 2, 3, 4, 5, 6, 7, 8), startIndex = 0)
        queue.setMode(PlaybackMode.SHUFFLE)

        val played = mutableListOf(queue.current!!.id)
        repeat(7) { played += queue.next(manual = false)!!.id }

        assertEquals("一轮内不应重复", 8, played.distinct().size)
        assertEquals("一轮应覆盖全部曲目", (1L..8L).toSet(), played.toSet())

        val nextRound = queue.next(manual = false)!!.id
        assertNotEquals("新一轮第一首不应等于上一轮最后一首", played.last(), nextRound)
    }

    @Test
    fun shufflePreviousWalksBackAndAutoNextReplaysHistory() {
        val queue = PlaybackQueue(Random(7))
        queue.setQueue(tracks(1, 2, 3, 4, 5, 6), startIndex = 0)
        queue.setMode(PlaybackMode.SHUFFLE)

        val first = queue.current!!.id
        val second = queue.next(manual = false)!!.id
        val third = queue.next(manual = false)!!.id

        assertEquals("上一首沿历史栈回退", second, queue.previous()!!.id)
        assertEquals(first, queue.previous()!!.id)
        assertNull("历史栈栈底返回 null（由播放器重播当前曲）", queue.previous())

        assertEquals("按过上一首后自动播完应沿历史向前，而不是重新随机", second, queue.next(manual = false)!!.id)
        assertEquals(third, queue.next(manual = false)!!.id)
    }

    @Test
    fun shuffleManualNextDropsForwardHistory() {
        val queue = PlaybackQueue(Random(7))
        queue.setQueue(tracks(1, 2, 3, 4, 5, 6), startIndex = 0)
        queue.setMode(PlaybackMode.SHUFFLE)

        val second = queue.next(manual = false)!!.id
        val third = queue.next(manual = false)!!.id
        queue.previous() // 回到 second
        assertEquals(second, queue.current!!.id)

        val manualNext = queue.next(manual = true)!!.id
        assertNotEquals("手动下一首应丢弃“未来”的 third，重新随机", third, manualNext)
        assertEquals("历史栈里 third 已被丢弃，上一首回到 second", second, queue.previous()!!.id)
    }

    // ------------------------------------------------------------------ 清空 / 下标 / 模式切换

    @Test
    fun clearResetsQueueButKeepsMode() {
        val queue = PlaybackQueue(Random(1))
        queue.setQueue(tracks(1, 2, 3), startIndex = 1)
        queue.setMode(PlaybackMode.SHUFFLE)
        queue.clear()

        assertNull(queue.current)
        assertEquals(-1, queue.currentIndex)
        assertEquals(0, queue.size)
        assertNull(queue.next(manual = true))
        assertNull(queue.next(manual = false))
        assertNull(queue.previous())
        assertEquals("清空队列不应重置用户的播放模式", PlaybackMode.SHUFFLE, queue.mode)
    }

    @Test
    fun setQueueClampsStartIndexAndHandlesEmpty() {
        val queue = PlaybackQueue()

        assertEquals(3L, queue.setQueue(tracks(1, 2, 3), startIndex = 99)?.id)
        assertEquals(2, queue.currentIndex)

        assertEquals(1L, queue.setQueue(tracks(1, 2, 3), startIndex = -5)?.id)
        assertEquals(0, queue.currentIndex)

        assertNull(queue.setQueue(emptyList()))
        assertEquals(-1, queue.currentIndex)
        assertEquals(0, queue.size)
    }

    @Test
    fun switchingModeKeepsCurrentTrackAndResetsShuffleHistory() {
        val queue = PlaybackQueue(Random(3))
        queue.setQueue(tracks(1, 2, 3, 4, 5), startIndex = 2)
        queue.setMode(PlaybackMode.SHUFFLE)
        assertEquals(3L, queue.current?.id)

        queue.next(manual = false)
        queue.next(manual = false)
        val current = queue.current!!.id

        queue.setMode(PlaybackMode.SINGLE_REPEAT)
        assertEquals("切模式不应换歌", current, queue.current?.id)

        queue.setMode(PlaybackMode.SEQUENTIAL)
        assertEquals(current, queue.current?.id)

        queue.setMode(PlaybackMode.SHUFFLE)
        assertNull("重新进入随机模式时历史栈从当前曲目重新开始", queue.previous())
        assertEquals(current, queue.current?.id)
    }

    @Test
    fun emptyQueueHasNoNextOrPrevious() {
        val queue = PlaybackQueue()
        assertNull(queue.next(manual = true))
        assertNull(queue.next(manual = false))
        assertNull(queue.previous())
        assertEquals(0, queue.size)
        assertEquals(-1, queue.currentIndex)
    }

    @Test
    fun snapshotAndTrackAtAreReadOnlyViews() {
        val queue = PlaybackQueue()
        queue.setQueue(tracks(1, 2, 3), startIndex = 0)

        val snapshot = queue.snapshot()
        assertEquals(listOf(1L, 2L, 3L), snapshot.map { it.id })
        snapshot.drop(1) // 修改引用本身不会影响队列（List 是副本）

        assertEquals(2L, queue.trackAt(1)?.id)
        assertNull(queue.trackAt(-1))
        assertNull(queue.trackAt(3))
        assertEquals(3, queue.snapshot().size)
    }
}
