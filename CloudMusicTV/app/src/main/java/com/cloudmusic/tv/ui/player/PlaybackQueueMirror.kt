package com.cloudmusic.tv.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cloudmusic.core.player.Track

/**
 * app 层保存的“当前提交给播放器的队列”。
 *
 * core-player 的公开 [com.cloudmusic.core.player.PlaybackState] 只有
 * `queueIndex / queueSize`，没有曲目列表；播放页的队列抽屉需要展示“接下来要播的歌”，
 * 所以在每次调用 `player.play(...)` 时同步记录一份。
 *
 * 一致性由 `queueSize` 校验：播放器若因为 Service 重建等原因换了队列，抽屉会显示
 * 「队列信息不可用」而不是展示过期列表。
 */
@Stable
class PlaybackQueueMirror {

    var tracks: List<Track> by mutableStateOf(emptyList())
        private set

    fun submit(tracks: List<Track>) {
        this.tracks = tracks.toList()
    }
}
