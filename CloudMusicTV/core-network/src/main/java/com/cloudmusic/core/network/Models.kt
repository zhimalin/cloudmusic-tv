package com.cloudmusic.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * 网易云接口的真实数据模型。
 *
 * 字段名和真实返回对齐（`ar` / `al` / `dt` / `alia` 这些短名是接口本来的写法），
 * 通过 [SerialName] 暴露成可读的 Kotlin 属性名；只建模 UI/播放器用得到的字段，
 * 其余字段靠 `ignoreUnknownKeys` 丢弃。
 */

/** 歌手。 */
@Serializable
data class Artist(
    val id: Long = 0,
    val name: String = "",
)

/** 专辑。 */
@Serializable
data class Album(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String? = null,
)

/**
 * 歌曲。
 *
 * 每日推荐、歌单详情、歌曲详情返回的歌曲对象都是这一套字段。
 */
@Serializable
data class Song(
    val id: Long = 0,
    val name: String = "",
    @SerialName("ar") val artists: List<Artist> = emptyList(),
    @SerialName("al") val album: Album? = null,
    /** 时长，毫秒。 */
    @SerialName("dt") val durationMs: Long = 0,
    /** 0 免费、1 VIP、8 低音质免费……取值见接口 `fee` 字段。 */
    val fee: Int = 0,
    @SerialName("alia") val alias: List<String> = emptyList(),
) {
    /** 播放器/列表页常用的一行歌手名。 */
    val artistText: String
        get() = artists.joinToString(" / ") { it.name }

    /** 带副标题的展示名，例如「キミの記憶 (Memories of You)」。 */
    val displayName: String
        get() = if (alias.isEmpty()) name else "$name (${alias.first()})"
}

/** 用户歌单。 */
@Serializable
data class Playlist(
    val id: Long = 0,
    val name: String = "",
    val coverImgUrl: String? = null,
    val trackCount: Int = 0,
    val playCount: Long = 0,
    /** 5 = 「我喜欢的音乐」这个特殊歌单。 */
    val specialType: Int = 0,
    val description: String? = null,
    val subscribed: Boolean = false,
    val updateTime: Long = 0,
    @SerialName("userId") val userId: Long = 0,
    val creator: User? = null,
) {
    /** 是否是「我喜欢的音乐」歌单。 */
    val isLikedPlaylist: Boolean
        get() = specialType == SPECIAL_TYPE_LIKED

    companion object {
        /** `specialType` 为该值的歌单是「我喜欢的音乐」。 */
        const val SPECIAL_TYPE_LIKED: Int = 5
    }
}

/** 用户（歌单创建者等）。 */
@Serializable
data class User(
    @SerialName("userId") val id: Long = 0,
    val nickname: String = "",
    val avatarUrl: String? = null,
)

/**
 * 登录账号信息，来自 `user/account` 的 `profile`。
 *
 * 未登录时接口不报错（`code=200`），只是 `profile` 为 null，所以 [NeteaseClient.account] 返回可空。
 */
@Serializable
data class Account(
    @SerialName("userId") val id: Long = 0,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val signature: String? = null,
    val vipType: Int = 0,
)

/**
 * 播放地址，来自 `song/url/v1` 的 `data[]`。
 *
 * `url` 为空表示这个档位拿不到地址（版权/VIP/下架），**不是**异常：
 * 播放器应该看 [isPlayable] / [isTrialOnly]，并把 [code] + [message] 写进失败排查日志。
 */
@Serializable
data class SongUrl(
    val id: Long = 0,
    val url: String? = null,
    /** 码率，例如 standard=128000、exhigh=320000。 */
    val br: Int = 0,
    val size: Long = 0,
    val md5: String? = null,
    /** 服务端实际给出的档位，可能低于请求档位。 */
    val level: String? = null,
    /** `mp3` / `flac`。 */
    val encodeType: String? = null,
    val type: String? = null,
    /** 单曲级 code，正常是 200。 */
    val code: Int = 0,
    /** 0 免费、1 VIP……用于判断为什么没地址。 */
    val fee: Int = 0,
    /** 试听区间（`{start,end}`）；完整地址时为 null。 */
    @SerialName("freeTrialInfo") val freeTrialInfo: JsonElement? = null,
    val message: String? = null,
) {
    /** 是否拿到了可播放的完整地址。 */
    val isPlayable: Boolean
        get() = !url.isNullOrBlank()

    /** 是否只有试听片段（`freeTrialInfo` 非空）。 */
    val isTrialOnly: Boolean
        get() = freeTrialInfo != null && freeTrialInfo !is JsonNull
}

/** 歌词；原文和翻译都是 LRC 文本，可能为空。 */
data class Lyric(
    val songId: Long,
    /** 原文歌词（LRC）。 */
    val text: String? = null,
    /** 中文翻译歌词（LRC）。 */
    val translation: String? = null,
    /** 罗马音歌词（LRC）。 */
    val roma: String? = null,
) {
    val hasLyric: Boolean
        get() = !text.isNullOrBlank()
}
