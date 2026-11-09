package com.cloudmusic.core.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 音频焦点封装（API 26+ 的 [AudioFocusRequest]）。
 *
 * 采用 `AUDIOFOCUS_GAIN` + `willPauseWhenDucked = true`：
 * - 任何打断（通话、其他播放器、导航播报）都先暂停，不做压低音量的 duck；
 * - 短暂丢失（`LOSS_TRANSIENT`）后如果系统回 `GAIN`，由 [MusicPlayer] 决定是否续播；
 * - 永久丢失（`LOSS`）后必须重新 request，不会自动恢复。
 *
 * 注意：ExoPlayer 自身的音频焦点处理是关闭的（`setAudioAttributes(attrs, false)`），
 * 焦点只由这一个控制器管理，避免两套逻辑互相抢。
 */
internal class AudioFocusController(
    context: Context,
    private val callbacks: Callbacks,
) {

    internal interface Callbacks {
        /** 焦点丢失；[transient] 为 true 表示系统稍后会回 GAIN。 */
        fun onFocusLost(transient: Boolean)

        /** 焦点恢复（只在 transient 丢失后可能发生）。 */
        fun onFocusGained()
    }

    private val audioManager: AudioManager = checkNotNull(
        context.applicationContext.getSystemService(AudioManager::class.java),
    ) { "系统没有 AudioManager，无法申请音频焦点" }

    private val handler = Handler(Looper.getMainLooper())

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> handleFocusChange(change) }, handler)
        .build()

    private var held = false

    /** 申请焦点；已经持有则直接返回 true。 */
    fun request(): Boolean {
        if (held) return true
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    /** 主动归还焦点（用户暂停/释放播放器时调用）。 */
    fun abandon() {
        if (!held) return
        held = false
        audioManager.abandonAudioFocusRequest(request)
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                held = true
                callbacks.onFocusGained()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久丢失：系统不会再自动回 GAIN，下一次播放要重新 request。
                held = false
                callbacks.onFocusLost(transient = false)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // willPauseWhenDucked=true 时系统会把 CAN_DUCK 转成 LOSS_TRANSIENT；
                // 两个分支都按“暂停”处理，符合系统对音乐类 App 的建议。
                held = true
                callbacks.onFocusLost(transient = true)
            }
        }
    }
}
