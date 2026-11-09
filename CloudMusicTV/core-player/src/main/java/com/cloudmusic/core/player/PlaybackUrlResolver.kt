package com.cloudmusic.core.player

import com.cloudmusic.core.network.NeteaseClient
import com.cloudmusic.core.network.SongLevel
import com.cloudmusic.core.network.SongUrl

/** 某一档音质的尝试结果，用于排查“为什么降级/为什么全失败”。 */
enum class UrlOutcome {
    /** 拿到完整可播放地址。 */
    SUCCESS,

    /** 接口返回了条目，但 `url` 为空。 */
    EMPTY_URL,

    /** 接口 `data[]` 里没有这个歌曲 id 的条目。 */
    MISSING,

    /** 拿到了地址，但只有试听片段（`freeTrialInfo` 非空）。 */
    TRIAL_ONLY,
}

/** 单档音质的尝试记录。 */
data class UrlAttempt(
    val level: SongLevel,
    val outcome: UrlOutcome,
    /** 接口单曲级 code（`SongUrl.code`），没有条目时为 null。 */
    val code: Int? = null,
    val message: String? = null,
)

/**
 * 解析成功后的播放地址。
 *
 * [resolvedLevel] 是实际拿到地址的档位，可能低于 [requestedLevel]（[usedFallback]）。
 * [isTrialOnly] 为 true 时地址只能播试听片段，UI 应显示「试听」标记。
 */
data class ResolvedPlayback(
    val url: String,
    val br: Int,
    val requestedLevel: SongLevel,
    val resolvedLevel: SongLevel,
    /** 服务端自报的实际档位字符串（`level` 字段），可能为 null。 */
    val serverLevel: String?,
    val isTrialOnly: Boolean,
    /** 按尝试顺序记录的所有档位，成功时最后一项是 SUCCESS。 */
    val attempts: List<UrlAttempt>,
) {
    val usedFallback: Boolean get() = resolvedLevel != requestedLevel
}

/** 所有档位都拿不到完整地址、也没有可用试听片段时抛出。 */
class PlaybackUrlException(
    val songId: Long,
    val requestedLevel: SongLevel,
    val attempts: List<UrlAttempt>,
) : Exception(
    buildString {
        append("无法获取播放地址：songId=").append(songId)
        append("，目标音质=").append(requestedLevel.value)
        append("，降级链=")
        append(attempts.joinToString(separator = " → ") { "${it.level.value}:${it.outcome}" })
    },
)

/**
 * 播放地址解析 + 逐档降级。
 *
 * 降级顺序固定为 `lossless → exhigh → higher → standard`，从目标档位开始向下试：
 * - 目标档 [SongUrl] 为 null / url 为空 / 只有试听 → 继续试下一档；
 * - 某一档拿到完整地址 → 立即返回；
 * - 所有档位都只有试听 → 返回最高档的那个试听地址，并标记 [ResolvedPlayback.isTrialOnly]；
 * - 全部为空 → 抛 [PlaybackUrlException]，[PlaybackUrlException.attempts] 里带每一档的原因。
 *
 * 异常策略：[NeteaseClient.songUrl] 抛出的 `NeteaseException`（网络/HTTP/业务/解析）**不捕获**，
 * 直接向上抛，避免把“网络失败”伪装成“没有版权”。调用方（[MusicPlayer]）会把它写进
 * [PlaybackState.error]，并保留异常类型/错误码。
 */
class PlaybackUrlResolver(
    private val client: NeteaseClient,
) {

    suspend fun resolve(
        songId: Long,
        preferred: SongLevel = SongLevel.EXHIGH,
    ): ResolvedPlayback {
        val attempts = ArrayList<UrlAttempt>(4)
        var firstTrial: Pair<SongLevel, SongUrl>? = null

        for (level in fallbackChain(preferred)) {
            val songUrl = client.songUrl(songId, level)
            when {
                songUrl == null -> {
                    attempts += UrlAttempt(level, UrlOutcome.MISSING)
                }

                !songUrl.isPlayable -> {
                    attempts += UrlAttempt(level, UrlOutcome.EMPTY_URL, songUrl.code, songUrl.message)
                }

                songUrl.isTrialOnly -> {
                    attempts += UrlAttempt(level, UrlOutcome.TRIAL_ONLY, songUrl.code, songUrl.message)
                    if (firstTrial == null) firstTrial = level to songUrl
                }

                else -> {
                    attempts += UrlAttempt(level, UrlOutcome.SUCCESS, songUrl.code, songUrl.message)
                    return ResolvedPlayback(
                        url = requireNotNull(songUrl.url),
                        br = songUrl.br,
                        requestedLevel = preferred,
                        resolvedLevel = level,
                        serverLevel = songUrl.level,
                        isTrialOnly = false,
                        attempts = attempts.toList(),
                    )
                }
            }
        }

        val trial = firstTrial
        if (trial != null) {
            val (level, songUrl) = trial
            return ResolvedPlayback(
                url = requireNotNull(songUrl.url),
                br = songUrl.br,
                requestedLevel = preferred,
                resolvedLevel = level,
                serverLevel = songUrl.level,
                isTrialOnly = true,
                attempts = attempts.toList(),
            )
        }

        throw PlaybackUrlException(songId, preferred, attempts.toList())
    }

    companion object {
        /** 音质从高到低。 */
        private val QUALITY_ORDER = listOf(
            SongLevel.LOSSLESS,
            SongLevel.EXHIGH,
            SongLevel.HIGHER,
            SongLevel.STANDARD,
        )

        /**
         * 目标档位开始的降级链（只降不升）。
         *
         * 例如 EXHIGH → `[EXHIGH, HIGHER, STANDARD]`；STANDARD → `[STANDARD]`。
         */
        fun fallbackChain(preferred: SongLevel): List<SongLevel> {
            val start = QUALITY_ORDER.indexOf(preferred)
            return if (start < 0) listOf(preferred) else QUALITY_ORDER.drop(start)
        }
    }
}
