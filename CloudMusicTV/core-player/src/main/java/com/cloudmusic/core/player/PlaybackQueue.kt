package com.cloudmusic.core.player

import kotlin.random.Random

/**
 * 播放模式。
 *
 * - [SEQUENTIAL] 顺序：按队列下标播放；自动播到最后一首后停止，手动「下一首」在尾部回绕到第一首。
 * - [SHUFFLE] 随机：一轮内不重复，维护历史栈供 [PlaybackQueue.previous] 回退；
 *   一轮播完后自动开始新一轮（新一轮第一首不会和上一轮最后一首相同，队列 >1 时）。
 * - [SINGLE_REPEAT] 单曲循环：自动播放结束重播当前曲；**手动**下一首/上一首仍然正常跳歌。
 */
enum class PlaybackMode {
    SEQUENTIAL,
    SHUFFLE,
    SINGLE_REPEAT,
}

/**
 * 纯 Kotlin 播放队列：队列 + 当前下标 + 播放模式。
 *
 * ### 行为约定（全部由 `PlaybackQueueTest` 写死）
 *
 * | 场景 | SEQUENTIAL | SHUFFLE | SINGLE_REPEAT |
 * | --- | --- | --- | --- |
 * | `next(manual = false)`（自动播完） | 下一首；尾部返回 `null` | 历史栈有“未来”就沿历史前进，否则随机未播曲目 | 当前曲（不换） |
 * | `next(manual = true)`（用户按下一首） | 下一首；尾部回绕第一首 | 丢弃“未来”历史后随机未播曲目 | 下一首；尾部回绕第一首 |
 * | `previous()` | 上一首；首部回绕最后一首 | 历史栈回退；已经在栈底返回 `null` | 上一首；首部回绕最后一首 |
 * | 队列清空 | `current == null`，`next/previous == null` | 同左 | 同左 |
 *
 * 随机模式的细节：
 * - `setQueue` / `setMode(SHUFFLE)` 时把当前曲目压入历史栈，作为这一轮的起点；
 * - 用户按「上一首」后，**自动**播完会沿历史栈向前回放（不会重新随机）；
 * - 用户按「上一首」后再**手动**按「下一首」，会丢弃历史栈里的“未来”部分重新随机，
 *   这是手动操作和自动推进唯一的行为差异；
 * - 一轮内用 `playedInRound` 保证不重复，一轮结束后清空并重置历史栈，避免历史无限增长。
 *
 * 线程约束：**不是线程安全的**，只允许在主线程（或单线程测试）里使用。
 * [PlaybackQueue] 不依赖 Android API，可以直接 JVM 单测。
 */
class PlaybackQueue(
    private val random: Random = Random.Default,
) {

    private val tracks = ArrayList<Track>()
    private var index = -1

    /** 随机模式下按播放顺序记录的下标；游标指向当前曲目。 */
    private val shuffleHistory = ArrayList<Int>()
    private var shuffleCursor = -1
    private val playedInRound = HashSet<Int>()

    /** 当前播放模式；切换用 [setMode]。 */
    var mode: PlaybackMode = PlaybackMode.SEQUENTIAL
        private set

    val size: Int get() = tracks.size

    val currentIndex: Int get() = index

    val current: Track? get() = tracks.getOrNull(index)

    /**
     * 是否还有“下一首”可走。
     *
     * 三种模式下手动下一首都不会落空（顺序/单曲循环尾部回绕，随机进入新一轮），
     * 所以只要队列非空就是 true。
     */
    val hasNext: Boolean get() = tracks.isNotEmpty()

    /**
     * 是否还有“上一首”可走。
     *
     * 顺序/单曲循环首部会回绕到最后一首；随机模式在历史栈栈底时 [previous] 返回 null，
     * 但调用方（[MusicPlayer]）会把它解释成“回到当前曲目开头”，按钮仍应可用。
     */
    val hasPrevious: Boolean get() = tracks.isNotEmpty()

    /**
     * 替换整个队列。
     *
     * @param startIndex 起始下标；越界会被夹到 `0..lastIndex`。空队列下忽略。
     * @return 新的当前曲目；空队列返回 null。
     */
    fun setQueue(queue: List<Track>, startIndex: Int = 0): Track? {
        tracks.clear()
        tracks.addAll(queue)
        index = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex)
        resetShuffleState()
        if (mode == PlaybackMode.SHUFFLE && index >= 0) seedShuffleAt(index)
        return current
    }

    /**
     * 切换播放模式。当前曲目不变；随机模式的历史栈会按新状态重置。
     */
    fun setMode(newMode: PlaybackMode) {
        if (mode == newMode) return
        mode = newMode
        resetShuffleState()
        if (mode == PlaybackMode.SHUFFLE && index >= 0) seedShuffleAt(index)
    }

    /**
     * 下一首。
     *
     * @param manual true = 用户主动按「下一首」；false = 当前曲目自然播放结束（由播放器调用）。
     * @return 新的当前曲目；顺序模式自动播到尾部时返回 null（表示应当停止）。
     */
    fun next(manual: Boolean = true): Track? {
        if (tracks.isEmpty()) return null
        if (index !in tracks.indices) index = 0
        return when (mode) {
            PlaybackMode.SEQUENTIAL -> {
                if (index < tracks.lastIndex) {
                    index++
                } else if (manual) {
                    index = 0
                } else {
                    return null
                }
                current
            }

            PlaybackMode.SINGLE_REPEAT -> {
                if (manual) {
                    index = if (index < tracks.lastIndex) index + 1 else 0
                }
                current
            }

            PlaybackMode.SHUFFLE -> nextShuffle(manual)
        }
    }

    /**
     * 上一首。
     *
     * @return 新的当前曲目；随机模式已经位于历史栈栈底时返回 null。
     */
    fun previous(): Track? {
        if (tracks.isEmpty()) return null
        if (index !in tracks.indices) index = 0
        return when (mode) {
            PlaybackMode.SEQUENTIAL, PlaybackMode.SINGLE_REPEAT -> {
                index = if (index > 0) index - 1 else tracks.lastIndex
                current
            }

            PlaybackMode.SHUFFLE -> {
                if (shuffleCursor <= 0) return null
                shuffleCursor--
                index = shuffleHistory[shuffleCursor]
                current
            }
        }
    }

    /** 清空队列：当前曲目、历史栈、游标全部重置；[mode] 保持用户选择。 */
    fun clear() {
        tracks.clear()
        index = -1
        resetShuffleState()
    }

    /** 按下标取曲目（越界返回 null），给 UI 展示队列用。 */
    fun trackAt(position: Int): Track? = tracks.getOrNull(position)

    /** 当前队列快照（副本，修改不影响队列）。 */
    fun snapshot(): List<Track> = tracks.toList()

    // ------------------------------------------------------------------ 随机模式

    private fun nextShuffle(manual: Boolean): Track? {
        if (shuffleHistory.isEmpty()) seedShuffleAt(index)

        // 自动推进：如果用户之前按过「上一首」，先沿历史栈向前回放，不重新随机。
        if (!manual && shuffleCursor < shuffleHistory.lastIndex) {
            shuffleCursor++
            index = shuffleHistory[shuffleCursor]
            return current
        }

        // 手动下一首：丢弃“未来”历史，然后重新随机。
        if (manual && shuffleCursor < shuffleHistory.lastIndex) {
            while (shuffleHistory.lastIndex > shuffleCursor) {
                shuffleHistory.removeAt(shuffleHistory.lastIndex)
            }
        }

        val candidates = tracks.indices.filter { it !in playedInRound }
        val pool = if (candidates.isNotEmpty()) {
            candidates
        } else {
            // 一轮播完：重置历史/已播集合，让 previous 不会无限往回穿越旧轮次。
            playedInRound.clear()
            shuffleHistory.clear()
            shuffleHistory.add(index)
            shuffleCursor = 0
            if (tracks.size > 1) tracks.indices.filter { it != index } else tracks.indices.toList()
        }

        val pick = pool[random.nextInt(pool.size)]
        shuffleHistory.add(pick)
        shuffleCursor = shuffleHistory.lastIndex
        playedInRound.add(pick)
        index = pick
        return current
    }

    private fun seedShuffleAt(position: Int) {
        shuffleHistory.clear()
        shuffleHistory.add(position)
        shuffleCursor = 0
        playedInRound.clear()
        playedInRound.add(position)
    }

    private fun resetShuffleState() {
        shuffleHistory.clear()
        shuffleCursor = -1
        playedInRound.clear()
    }
}
