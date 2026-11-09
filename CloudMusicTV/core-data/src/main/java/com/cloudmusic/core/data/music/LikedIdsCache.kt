package com.cloudmusic.core.data.music

/**
 * 会话级「我喜欢的歌曲 id」缓存。
 *
 * 背景：网易云没有可用的单曲 like-check 接口，启动时只能通过 `likeList(uid)` 拉一次
 * 完整喜欢歌单。播放页用 [contains] 决定心形初值，收藏成功后用 [setLiked] 增量同步。
 *
 * 纯 Kotlin、无 Android 依赖；用 [LinkedHashSet] 保留插入顺序，达到上限时淘汰最旧的 id。
 * 用户量极大时最多保留 [maxSize] 个，避免无限增长。
 */
class LikedIdsCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
) {

    init {
        require(maxSize > 0) { "maxSize 必须大于 0，实际 $maxSize" }
    }

    private val ids = LinkedHashSet<Long>()

    /** 用一次完整的 likeList 结果替换缓存；超过上限时保留迭代顺序中靠后的 id。 */
    @Synchronized
    fun replaceAll(newIds: Collection<Long>) {
        ids.clear()
        for (id in newIds) {
            putLocked(id)
        }
    }

    @Synchronized
    fun contains(id: Long): Boolean = ids.contains(id)

    /**
     * 收藏成功后的增量同步。
     *
     * @param liked true=加入（并移到最新），false=移除。
     */
    @Synchronized
    fun setLiked(id: Long, liked: Boolean) {
        if (liked) {
            putLocked(id)
        } else {
            ids.remove(id)
        }
    }

    @Synchronized
    fun size(): Int = ids.size

    private fun putLocked(id: Long) {
        ids.remove(id)
        ids.add(id)
        while (ids.size > maxSize) {
            val oldest = ids.firstOrNull() ?: break
            ids.remove(oldest)
        }
    }

    companion object {
        /** 目前实测账号 547 首；留足空间，同时防止异常数据把内存拖大。 */
        const val DEFAULT_MAX_SIZE: Int = 5000
    }
}
