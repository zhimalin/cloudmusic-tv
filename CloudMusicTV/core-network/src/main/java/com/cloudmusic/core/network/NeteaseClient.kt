package com.cloudmusic.core.network

import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * 网易云 weapi 客户端（M2/M3 需要的 7 个接口）。
 *
 * 设计约束：
 * - 协程 + `suspend`，没有回调式 API；
 * - 不依赖任何 Android API（无 Context / SharedPreferences），cookie 由调用方以字符串传入；
 * - cookie 不落盘、不打日志；每次请求都通过 [cookieProvider] 现取，登录后无需重建客户端；
 * - 失败一律抛 [NeteaseException] 子类（[TransportException] / [HttpException] / [ApiException] / [ParseException]），
 *   不用 `runCatching { }.getOrNull()` 吞错误。
 *
 * 协议细节（weapi 两轮 AES + RSA、请求头、路由）逐行对照 `tools/probe/probe.mjs`。
 *
 * 用法：
 * ```
 * val client = NeteaseClient(cookieProvider = { sessionStore.readCookie() ?: "" })
 * val account = client.account()            // null = 未登录
 * val songs = client.dailySongs()
 * val url = client.songUrl(2610618711, SongLevel.EXHIGH)
 * ```
 */
class NeteaseClient internal constructor(
    private val cookieProvider: () -> String,
    private val baseUrl: String,
    private val engine: HttpEngine,
    /** 第二轮 AES 的随机 secret 来源；测试里注入固定值以逐字节校验请求体。 */
    private val secretProvider: () -> String,
) {

    /** 固定 cookie 字符串的便捷构造（cookie 由调用方从登录流程里拿到）。 */
    constructor(
        cookie: String,
        baseUrl: String = Endpoints.BASE_URL,
        engine: HttpEngine = OkHttpEngine(),
    ) : this({ cookie }, baseUrl, engine, { WeapiCrypto.randomSecret() })

    /** 动态 cookie：每次请求前调用 [cookieProvider]，适合登录态会变的 SessionStore。 */
    constructor(
        cookieProvider: () -> String = { "" },
        baseUrl: String = Endpoints.BASE_URL,
        engine: HttpEngine = OkHttpEngine(),
    ) : this(cookieProvider, baseUrl, engine, { WeapiCrypto.randomSecret() })

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 调用方没给 NMTID 时补一个，和 Node 探针启动时随机生成 NMTID（16 字节 → 32 位 hex）的行为一致。 */
    private val fallbackNmtid: String = buildString {
        repeat(NMTID_LENGTH) { append(NMTID_ALPHABET[secureRandom.nextInt(NMTID_ALPHABET.length)]) }
    }

    // ------------------------------------------------------------------ 公开接口

    /**
     * 校验登录态，返回昵称/uid。
     *
     * 未登录不是错误：接口仍返回 `code=200`，只是没有 `profile`，此时返回 null。
     * 网络/HTTP/业务码错误照常抛异常，方便和「未登录」区分。
     */
    suspend fun account(): Account? =
        requestObject(Endpoints.ACCOUNT)
            .decodeAs(AccountEnvelope.serializer(), Endpoints.ACCOUNT)
            .profile

    /** 每日推荐（需要登录态；未登录会抛 [ApiException] code=301）。 */
    suspend fun dailySongs(): List<Song> =
        requestObject(Endpoints.DAILY_SONGS)
            .decodeAs(DailySongsEnvelope.serializer(), Endpoints.DAILY_SONGS)
            .data?.dailySongs
            .orEmpty()

    /**
     * 私人 FM：一次返回一批（实测 3 首）。
     *
     * 返回的歌曲字段和每日推荐不同：`artists` / `album` / `duration` / `alias`。
     * 这里转换成统一的 [Song]，UI 和播放层无需感知两套协议。
     */
    suspend fun personalFm(): List<Song> =
        requestObject(Endpoints.PERSONAL_FM)
            .decodeAs(FmEnvelope.serializer(), Endpoints.PERSONAL_FM)
            .data
            .map { it.toSong() }

    /**
     * 把 FM 当前歌曲丢进垃圾桶。
     *
     * 已对真实账号验证：`code=200`，路径与参数见 [Endpoints.FM_TRASH]。
     * 该操作不可撤销，调用方需要确认这是用户主动「不喜欢」。
     */
    suspend fun dislikeSong(id: Long) {
        val params = buildJsonObject {
            put("songId", id.toString())
        }
        requestObject(
            apiPath = Endpoints.FM_TRASH,
            params = params,
            extraQuery = linkedMapOf(
                "alg" to "RT",
                "songId" to id.toString(),
                "time" to "25",
            ),
        )
    }

    /**
     * 红心 / 取消红心（`like=true` 收藏，`false` 取消）。
     *
     * 已对真实账号验证：请求必须额外带 `os=pc; appver=2.7.1.198277`，
     * 否则即使 cookie 有效也会被风控拒绝为 `code=-460`。
     */
    suspend fun setLiked(id: Long, liked: Boolean) {
        val params = buildJsonObject {
            put("alg", "itembased")
            put("trackId", id.toString())
            put("like", liked)
            put("time", "3")
        }
        requestObject(
            apiPath = Endpoints.LIKE,
            params = params,
            extraCookies = LIKE_EXTRA_COOKIES,
        )
    }

    /**
     * 取单曲播放地址。
     *
     * @param level 期望档位；服务端可能降级，实际档位看 [SongUrl.level]。
     * @return 找不到对应条目时返回 null；条目存在但 `url` 为空也返回对象，由 [SongUrl.isPlayable] 判断。
     */
    suspend fun songUrl(id: Long, level: SongLevel = SongLevel.EXHIGH): SongUrl? {
        val params = buildJsonObject {
            // 接口要的是「字符串化的 id 数组」，不是 JSON 数组本身
            put("ids", JsonArray(listOf(JsonPrimitive(id))).toString())
            put("level", level.value)
            put("encodeType", "flac")
        }
        val data = requestObject(Endpoints.SONG_URL_V1, params)
            .decodeAs(SongUrlEnvelope.serializer(), Endpoints.SONG_URL_V1)
            .data
        return data.firstOrNull { it.id == id } ?: data.firstOrNull()
    }

    /** 我的歌单（含「我喜欢的音乐」特殊歌单，[Playlist.isLikedPlaylist]）。 */
    suspend fun playlists(uid: Long): List<Playlist> {
        val params = buildJsonObject {
            put("uid", uid)
            put("limit", PLAYLIST_LIMIT)
            put("offset", 0)
        }
        return requestObject(Endpoints.USER_PLAYLIST, params)
            .decodeAs(PlaylistEnvelope.serializer(), Endpoints.USER_PLAYLIST)
            .playlist
    }

    /**
     * 歌单歌曲。
     *
     * 走 `playlist/detail`（v6）：`n=100000&s=8` 时服务端直接返回完整 `tracks`。
     * 超大歌单（服务端截断 `tracks`）时回退到 [songDetails]，按 `trackIds` 补齐。
     */
    suspend fun playlistTracks(playlistId: Long): List<Song> {
        val params = buildJsonObject {
            put("id", playlistId)
            put("n", PLAYLIST_TRACK_LIMIT)
            put("s", 8)
        }
        val detail = requestObject(Endpoints.PLAYLIST_DETAIL_V6, params)
            .decodeAs(PlaylistDetailEnvelope.serializer(), Endpoints.PLAYLIST_DETAIL_V6)
            .playlist
            ?: return emptyList()

        if (detail.tracks.size >= detail.trackIds.size) return detail.tracks
        return songDetails(detail.trackIds.map { it.id })
    }

    /**
     * 按 id 批量取歌曲详情（`song/detail` v3，参数 `c` 是 `[{"id":...}]` 的字符串化 JSON）。
     *
     * 每 [SONG_DETAIL_CHUNK] 个 id 发一次请求：50 个 id 约 110KB，分块避免单次响应过大。
     */
    suspend fun songDetails(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val songs = ArrayList<Song>(ids.size)
        for (chunk in ids.chunked(SONG_DETAIL_CHUNK)) {
            val c = buildJsonArray {
                chunk.forEach { id -> add(buildJsonObject { put("id", id) }) }
            }.toString()
            val params = buildJsonObject { put("c", c) }
            songs += requestObject(Endpoints.SONG_DETAIL_V3, params)
                .decodeAs(SongDetailEnvelope.serializer(), Endpoints.SONG_DETAIL_V3)
                .songs
        }
        return songs
    }

    /**
     * 「我喜欢的音乐」。
     *
     * 说明：`likelist`（`/weapi/song/like/list`）实测返回 `code=404 接口未找到！`（GET/weapi 两种方式都试过），
     * 因此改为：`user/playlist` 里找 `specialType=5` 的喜欢歌单 → 取这个歌单的歌曲。
     * 结果集和 likelist + song/detail 一致，只是请求次数更少。
     */
    suspend fun likeList(uid: Long): List<Song> {
        val liked = playlists(uid).firstOrNull { it.isLikedPlaylist } ?: return emptyList()
        return playlistTracks(liked.id)
    }

    /** 歌词（原文 + 中文翻译 + 罗马音；接口没给的为 null）。 */
    suspend fun lyric(songId: Long): Lyric {
        val params = buildJsonObject {
            put("id", songId)
            put("lv", -1)
            put("kv", -1)
            put("tv", -1)
        }
        val body = requestObject(Endpoints.LYRIC, params)
            .decodeAs(LyricEnvelope.serializer(), Endpoints.LYRIC)
        return Lyric(
            songId = songId,
            text = body.lrc?.lyric,
            translation = body.tlyric?.lyric,
            roma = body.romalrc?.lyric,
        )
    }

    // ------------------------------------------------------------------ 请求管线

    private suspend fun requestObject(
        apiPath: String,
        params: JsonObject = JsonObject(emptyMap()),
        /** 仅部分接口需要的额外 cookie（例如 like 的风控字段）。 */
        extraCookies: String? = null,
        /** 真实接口放在 URL query 里的参数（例如 FM 垃圾桶的 alg/songId/time）。 */
        extraQuery: Map<String, String> = emptyMap(),
    ): JsonObject {
        val cookie = cookieHeader()
        val csrf = csrfToken(cookie)
        val payload = buildJsonObject {
            params.forEach { (key, value) -> put(key, value) }
            put("csrf_token", csrf)
        }.toString()

        val path = buildString {
            append(Endpoints.weapiPath(apiPath))
            append("?csrf_token=").append(encode(csrf))
            extraQuery.forEach { (key, value) ->
                append('&').append(encode(key)).append('=').append(encode(value))
            }
        }
        val url = baseUrl.trimEnd('/') + path
        val headers = requestHeaders(cookie, extraCookies)

        var transportFailure: TransportException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            // 每次重试都重新加密（新的随机 secret），和 Node 版一致
            val encrypted = WeapiCrypto.encrypt(payload, secretProvider())
            val form = "params=" + encode(encrypted.params) + "&encSecKey=" + encode(encrypted.encSecKey)

            val result = try {
                engine.postForm(url, headers, form)
            } catch (io: IOException) {
                transportFailure = TransportException(
                    "网络请求失败（$apiPath）：${io.message ?: io.javaClass.simpleName}",
                    io,
                )
                null
            }

            if (result == null) {
                if (attempt < MAX_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                throw transportFailure!!
            }

            // 429/5xx 重试一次（Node 版同样策略），其余状态码直接抛
            if ((result.statusCode == 429 || result.statusCode >= 500) && attempt < MAX_ATTEMPTS) {
                delay(RETRY_DELAY_MS)
                continue
            }
            if (result.statusCode !in 200..299) {
                throw HttpException(result.statusCode, result.body, apiPath)
            }
            return parseObject(result.body, apiPath)
        }
        throw transportFailure ?: TransportException("网络请求失败（$apiPath）：未知原因")
    }

    private fun parseObject(body: String, apiPath: String): JsonObject {
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw ParseException(body, apiPath, e)
        } catch (e: IllegalArgumentException) {
            throw ParseException(body, apiPath, e)
        }
        val root = element as? JsonObject ?: throw ParseException(body, apiPath)
        val code = (root["code"] as? JsonPrimitive)?.intOrNull ?: CODE_MISSING
        if (code != CODE_OK) {
            val message = ((root["message"] ?: root["msg"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
            throw ApiException(code, message, apiPath)
        }
        return root
    }

    private fun <T> JsonObject.decodeAs(
        deserializer: DeserializationStrategy<T>,
        apiPath: String,
    ): T = try {
        json.decodeFromJsonElement(deserializer, this)
    } catch (e: SerializationException) {
        throw ParseException(toString().snippet(MAX_ERROR_BODY), apiPath, e)
    } catch (e: IllegalArgumentException) {
        throw ParseException(toString().snippet(MAX_ERROR_BODY), apiPath, e)
    }

    /** 请求头，和 Node 版逐项对齐。 */
    private fun requestHeaders(cookie: String, extraCookies: String? = null): Map<String, String> {
        val fullCookie = if (extraCookies.isNullOrBlank()) {
            cookie
        } else {
            "$cookie; $extraCookies"
        }
        return mapOf(
            "User-Agent" to Endpoints.USER_AGENT,
            "Referer" to Endpoints.REFERER,
            "Origin" to Endpoints.ORIGIN,
            "Accept" to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Cookie" to fullCookie,
        )
    }

    /**
     * 把调用方传入的 cookie 字符串整理成请求头。
     *
     * 容错处理：去掉可能带上的 `Cookie:` 前缀；没有 NMTID 时补一个。
     * 不做任何持久化，也不会写进日志。
     */
    private fun cookieHeader(): String {
        val raw = cookieProvider().trim().removePrefix("Cookie:").trim().trimEnd(';')
        if (raw.isEmpty()) return "NMTID=$fallbackNmtid"
        val hasNmtid = raw.split(';').any { it.trim().startsWith("NMTID=") }
        return if (hasNmtid) raw else "$raw; NMTID=$fallbackNmtid"
    }

    /** 从 cookie 里取 `__csrf`，和 Node 版 `jar.get('__csrf') || ''` 一致。 */
    private fun csrfToken(cookie: String): String =
        cookie.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("__csrf=") }
            ?.substringAfter('=')
            .orEmpty()

    /** 和 Node 的 `encodeURIComponent` 对 base64/hex 等价（URLEncoder 只多转义个别字符）。 */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val CODE_OK = 200

        /** 响应里没有 code 字段时用这个值，会走 [ApiException]。 */
        const val CODE_MISSING = 0

        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 500L
        const val MAX_ERROR_BODY = 2000
        const val SONG_DETAIL_CHUNK = 200
        const val PLAYLIST_LIMIT = 1000
        const val PLAYLIST_TRACK_LIMIT = 100000
        const val NMTID_ALPHABET = "0123456789abcdef"

        /** Node 版是 `randomBytes(16).toString('hex')`，即 32 位 hex。 */
        const val NMTID_LENGTH = 32

        /** 实测缺这两个字段时红心接口返回 -460（风控），必须随请求 cookie 带上。 */
        const val LIKE_EXTRA_COOKIES = "os=pc; appver=2.7.1.198277"

        val secureRandom = SecureRandom()
    }
}

// ---------------------------------------------------------------------- 响应信封
// 只在本文件用：把响应外层包一包，避免把这些「协议壳」塞进公开 API。

@Serializable
private data class AccountEnvelope(
    val code: Int = 0,
    val profile: Account? = null,
)

@Serializable
private data class DailySongsEnvelope(
    val code: Int = 0,
    val data: DailySongsData? = null,
)

@Serializable
private data class DailySongsData(
    val dailySongs: List<Song> = emptyList(),
)

@Serializable
private data class FmEnvelope(
    val code: Int = 0,
    val data: List<FmSong> = emptyList(),
)

/**
 * 私人 FM 的歌曲字段（`artists` / `album` / `duration` / `alias`），
 * 和每日推荐/歌单的 `ar` / `al` / `dt` / `alia` 不是一套；只在本文件内转换。
 */
@Serializable
private data class FmSong(
    val id: Long = 0,
    val name: String = "",
    val artists: List<Artist> = emptyList(),
    val album: Album? = null,
    val duration: Long = 0,
    val fee: Int = 0,
    val alias: List<String> = emptyList(),
) {
    fun toSong(): Song = Song(
        id = id,
        name = name,
        artists = artists,
        album = album,
        durationMs = duration,
        fee = fee,
        alias = alias,
    )
}

@Serializable
private data class PlaylistEnvelope(
    val code: Int = 0,
    val playlist: List<Playlist> = emptyList(),
)

@Serializable
private data class PlaylistDetailEnvelope(
    val code: Int = 0,
    val playlist: PlaylistDetailBody? = null,
)

@Serializable
private data class PlaylistDetailBody(
    val id: Long = 0,
    val tracks: List<Song> = emptyList(),
    val trackIds: List<TrackId> = emptyList(),
)

@Serializable
private data class TrackId(
    val id: Long = 0,
)

@Serializable
private data class SongUrlEnvelope(
    val code: Int = 0,
    val data: List<SongUrl> = emptyList(),
)

@Serializable
private data class SongDetailEnvelope(
    val code: Int = 0,
    val songs: List<Song> = emptyList(),
)

@Serializable
private data class LyricEnvelope(
    val code: Int = 0,
    val lrc: LyricLine? = null,
    val tlyric: LyricLine? = null,
    val romalrc: LyricLine? = null,
)

@Serializable
private data class LyricLine(
    val version: Int = 0,
    val lyric: String? = null,
)
