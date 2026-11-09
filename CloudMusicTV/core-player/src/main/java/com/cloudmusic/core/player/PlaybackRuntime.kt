package com.cloudmusic.core.player

import android.content.Context
import com.cloudmusic.core.data.SessionStore
import com.cloudmusic.core.data.SettingsStore
import com.cloudmusic.core.network.NeteaseClient

/**
 * 进程内播放器宿主。
 *
 * 整个 App 只有一个 [MusicPlayer]：UI 和 [PlaybackService] 拿到的是同一个实例，
 * 避免“UI 播一个、通知控制另一个”的双播放器问题。
 *
 * 为什么不是 UI 自己 new：ExoPlayer + MediaSession 必须由前台 Service 持有才能在后台存活；
 * 而下一轮播放页又需要直接调用 [MusicPlayer] 的强类型 API（队列/模式/试听标记），
 * 用 [androidx.media3.session.MediaController] 反而要额外做一层命令映射。
 *
 * 规则：
 * - **懒创建**：只有第一次 [player] 调用才 new ExoPlayer；登录页/无播放的主界面不调用；
 * - **主线程**调用 [player] / [release]；
 * - UI 不要调用 [release]，由 [PlaybackService] 在 `onTaskRemoved` / `onDestroy` 里释放；
 * - Service 被销毁后，旧 [MusicPlayer] 已 release，UI 重新调用 [player] 会拿到新实例。
 */
object PlaybackRuntime {

    /**
     * 外部「下一首」请求钩子（通知栏 / 系统媒体键 / MediaSession controller）。
     *
     * 由 app 在私人 FM 模式下注册；返回 true 表示已经接管，[QueuePlayer] 不再走
     * [MusicPlayer.next]。传 null 恢复普通队列行为。回调通常在主线程，禁止阻塞；
     * 需要 suspend 的工作由注册方自己 launch。
     */
    @Volatile
    var onExternalNext: (() -> Boolean)? = null

    private val lock = Any()

    @Volatile
    private var player: MusicPlayer? = null

    /** OkHttp 连接池复用；cookie 通过 SessionStore 动态读取，换账号不需要重建。 */
    @Volatile
    private var client: NeteaseClient? = null

    /** 进程内创建过的实例次数；只增不减，供启动冒烟测试断言“没创建过”。 */
    @Volatile
    private var createdInstanceCount: Int = 0

    /**
     * 拿当前播放器；没有就创建一个。必须在主线程调用。
     *
     * 线程安全的懒初始化：调用前没有任何副作用，只有真正需要播放
     * （点歌/整队播放/FM）时才由 app 调用。
     */
    fun player(context: Context): MusicPlayer {
        val appContext = context.applicationContext
        return synchronized(lock) {
            player ?: create(appContext).also {
                player = it
                createdInstanceCount += 1
            }
        }
    }

    /** 只偷看，不创建；给日志/调试/启动时接管已有实例用。 */
    fun peek(): MusicPlayer? = player

    /** 当前是否已有 ExoPlayer 实例。只读，不创建。 */
    fun isCreated(): Boolean = player != null

    /** 进程生命周期内创建过多少次实例（release 不清零）。测试可见。 */
    fun playerInstanceCount(): Int = createdInstanceCount

    /** 释放并清空单例。由 [PlaybackService] 调用；幂等。 */
    fun release() {
        val existing = synchronized(lock) {
            player.also { player = null }
        }
        existing?.release()
    }

    private fun create(context: Context): MusicPlayer {
        val session = SessionStore(context)
        val settings = SettingsStore(context)
        val netease = client ?: NeteaseClient(
            cookieProvider = { session.readCookie().orEmpty() },
        ).also { client = it }

        return MusicPlayer(
            context = context,
            client = netease,
            initialLevel = PlaybackQuality.fromSettingsLabel(settings.audioQuality),
        )
    }
}
