package com.cloudmusic.core.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * ExoPlayer 的展示层包装：
 *
 * ExoPlayer 内部永远只有**一个** MediaItem（当前曲目），所以它自己认为“没有下一首”。
 * 但队列其实在 [PlaybackQueue] 里。这个类把 `SEEK_TO_NEXT/PREVIOUS` 及其 media-item 变体
 * 伪装成可用命令，并转发到 [MusicPlayer.next] / [MusicPlayer.previous]，
 * 这样通知栏、系统媒体面板、蓝牙/遥控器媒体键的「上一首/下一首」才能真正切歌。
 *
 * 只做命令转发，不改变 ExoPlayer 的播放列表（队列仍是单一数据源 [PlaybackQueue]）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
internal class QueuePlayer(
    player: Player,
    private val controls: Controls,
) : ForwardingPlayer(player) {

    internal interface Controls {
        val hasNext: Boolean
        val hasPrevious: Boolean
        fun skipToNext()
        fun skipToPrevious()
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder = Player.Commands.Builder().addAll(super.getAvailableCommands())
        if (controls.hasNext) {
            builder.addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
        }
        if (controls.hasPrevious) {
            builder.addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        -> controls.hasNext

        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        -> controls.hasPrevious

        else -> super.isCommandAvailable(command)
    }

    override fun hasNext(): Boolean = controls.hasNext
    override fun hasNextMediaItem(): Boolean = controls.hasNext

    override fun hasPrevious(): Boolean = controls.hasPrevious
    override fun hasPreviousMediaItem(): Boolean = controls.hasPrevious

    override fun next() = controls.skipToNext()
    override fun seekToNext() = controls.skipToNext()
    override fun seekToNextMediaItem() = controls.skipToNext()

    override fun previous() = controls.skipToPrevious()
    override fun seekToPrevious() = controls.skipToPrevious()
    override fun seekToPreviousMediaItem() = controls.skipToPrevious()
}
