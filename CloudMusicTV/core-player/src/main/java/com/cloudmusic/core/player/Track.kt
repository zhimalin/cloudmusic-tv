package com.cloudmusic.core.player

import com.cloudmusic.core.network.Song

/**
 * 播放器认识的曲目模型，和 UI / 网络层的 [Song] 解耦。
 *
 * 只保留播放、通知、播放页真正需要的字段；不缓存 bitmap，封面只存 URL。
 * UI 用 [Song.toTrack] 做转换。
 */
data class Track(
    /** 网易云歌曲 id（`song/url/v1` 的入参）。 */
    val id: Long,
    val title: String,
    val artist: String,
    val album: String = "",
    /** 封面 URL；核心层不会下载或缓存它。 */
    val coverUrl: String? = null,
    /** 歌曲时长（毫秒），来自接口的 `dt`。地址解析完成前用来先渲染进度条。 */
    val durationMs: Long = 0L,
)

/** 网络层 [Song] → 播放层 [Track]。 */
fun Song.toTrack(): Track = Track(
    id = id,
    title = name,
    artist = artistText,
    album = album?.name.orEmpty(),
    coverUrl = album?.picUrl,
    durationMs = durationMs,
)
