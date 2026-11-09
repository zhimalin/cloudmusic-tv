package com.cloudmusic.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 后台播放 Service。
 *
 * - 持有 [PlaybackRuntime] 里的唯一 [MusicPlayer]，并把它包成 [MediaSession]；
 * - 通知由 media3 的 [DefaultMediaNotificationProvider] 生成（系统默认媒体样式，
 *   不自绘复杂布局），渠道在 [onCreate] 里显式创建；
 * - Android 14 的 `foregroundServiceType="mediaPlayback"` 与两条前台服务权限写在
 *   `core-player/src/main/AndroidManifest.xml`，由 manifest merger 带进 app；
 * - `POST_NOTIFICATIONS` 只声明，运行时申请由 app 层在用户点播放时发起。
 *
 * 生命周期：`onTaskRemoved`（用户划掉任务）和 `onDestroy` 都会**真正**
 * `release()` ExoPlayer、MediaSession 和音频焦点，不把播放器留成泄漏。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var musicPlayer: MusicPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commandSyncJob: Job? = null
    private var lastPlayerCommands: Player.Commands? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build(),
        )

        val player = PlaybackRuntime.player(this)
        musicPlayer = player

        val builder = MediaSession.Builder(this, player.player)
            .setCallback(
                object : MediaSession.Callback {
                    /**
                     * 连接时把 QueuePlayer 合成的「上一首/下一首」命令带给 controller。
                     *
                     * 不这么做的话，MediaSession 在收到 ExoPlayer 的
                     * `onAvailableCommandsChanged` 事件时会用 ExoPlayer 自己的命令集合
                     * （单条 MediaItem，没有 next/previous）覆盖掉 controller 的可用命令，
                     * 通知栏按钮就会点击无效。
                     */
                    override fun onConnect(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                    ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.accept(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                        session.player.availableCommands,
                    )
                },
            )
        // 点通知回到 App 的入口 Activity（不硬编码 app 的 Activity 类名，core 模块不依赖 app）。
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        mediaSession = builder.build()

        // 队列从空变为非空时，重新广播命令给已连接的 controller。
        commandSyncJob = serviceScope.launch {
            player.state.collect { syncSessionCommands() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从最近任务里划掉 App：按本轮要求直接停掉并释放播放器（不做后台常驻）。
     *
     * 文档允许不调用 super（父类默认行为是“正在播放就继续存活”），这里刻意选择相反行为。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        releasePlayback()
        stopSelf()
    }

    override fun onDestroy() {
        releasePlayback()
        super.onDestroy()
    }

    private fun releasePlayback() {
        commandSyncJob?.cancel()
        commandSyncJob = null
        lastPlayerCommands = null
        val session = mediaSession
        mediaSession = null
        session?.release()
        musicPlayer = null
        // 释放唯一实例；重复调用是幂等的。
        PlaybackRuntime.release()
        serviceScope.cancel()
    }

    /**
     * 把当前 Player 的命令集合（含 [QueuePlayer] 合成的 next/previous）同步给
     * 所有已连接的 controller。只在命令集合真正变化时广播，避免 500ms 进度更新刷屏。
     */
    private fun syncSessionCommands() {
        val session = mediaSession ?: return
        val commands = session.player.availableCommands
        if (commands == lastPlayerCommands) return
        lastPlayerCommands = commands
        for (controller in session.connectedControllers) {
            session.setAvailableCommands(
                controller,
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                commands,
            )
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** 通知渠道 id；[DefaultMediaNotificationProvider] 用同一个。 */
        const val CHANNEL_ID = "cloudmusic_playback"

        /**
         * 启动后台播放服务。
         *
         * 用 `startService` 而不是 `startForegroundService`：Service 只会在真正开始播放时
         * 由 MediaSessionService 提升为前台；如果提前用 startForegroundService，网络慢时
         * 5 秒内没进前台会触发 ForegroundServiceDidNotStartInTimeException。
         *
         * 必须在 Activity 处于前台时调用（用户点歌/点播放时）。
         */
        fun start(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java))
        }

        /** 显式停止并释放（一般不需要；UI 不调用 release 的替代入口）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
