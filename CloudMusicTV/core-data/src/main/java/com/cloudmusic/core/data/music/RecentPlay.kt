package com.cloudmusic.core.data.music

import com.cloudmusic.core.network.Album
import com.cloudmusic.core.network.Artist
import com.cloudmusic.core.network.Song

/**
 * 一条最近播放记录。
 *
 * 不直接存 [Song]：SharedPreferences 只适合小体积键值，且 [Song] 的嵌套结构会
 * 迫使本模块增加 JSON 存储。这里只保留播放/展示必需字段，UI 可 [toSong] 还原。
 */
data class RecentPlay(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val playedAtMs: Long,
) {
    /** 还原成播放/列表层认识的 [Song]；歌名/歌手/封面足以重建 [Song.toTrack]。 */
    fun toSong(): Song = Song(
        id = songId,
        name = title,
        artists = listOf(Artist(name = artist)),
        album = Album(name = album, picUrl = coverUrl),
        durationMs = durationMs,
    )
}

/**
 * 最近播放的本地存储抽象。
 *
 * 纯 Kotlin 的 [HomeState] 依赖这个接口；Android 的 SharedPreferences 实现在
 * `SharedPreferencesRecentPlayStore`，JVM 测试用内存 fake。
 */
interface RecentPlayStore {

    /** 最新在前。 */
    fun load(): List<RecentPlay>

    /** 记录一次播放；同 id 的旧记录会被顶到最前，最多保留 20 条。 */
    fun record(play: RecentPlay)
}
