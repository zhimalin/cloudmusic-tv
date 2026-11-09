package com.cloudmusic.core.data.music

import com.cloudmusic.core.network.Account
import com.cloudmusic.core.network.ApiException
import com.cloudmusic.core.network.HttpException
import com.cloudmusic.core.network.Lyric
import com.cloudmusic.core.network.NeteaseClient
import com.cloudmusic.core.network.ParseException
import com.cloudmusic.core.network.Playlist
import com.cloudmusic.core.network.Song
import com.cloudmusic.core.network.TransportException
import kotlinx.coroutines.CancellationException

/**
 * The single data entry point for the music UI.
 *
 * This class is deliberately Android-free: the only constructor input is a
 * configured [NeteaseClient] whose cookie comes from the caller. That keeps it
 * (and the state containers below) runnable in JVM unit tests against
 * MockWebServer.
 *
 * Every call returns [MusicResult]. Known network/protocol failures are mapped
 * to explicit result branches; cancellation is rethrown; unexpected exceptions
 * are not caught and therefore do not silently disappear.
 */
class MusicRepository(private val client: NeteaseClient) {

    /**
     * Playlists grouped for the three "my music" entrances.
     *
     * - [liked]: `specialType == 5` ("我喜欢的音乐")
     * - [created]: not subscribed and owned by [uid]
     * - [subscribed]: everything else (collected / followed playlists)
     */
    data class LibraryPlaylists(
        val liked: List<Playlist>,
        val created: List<Playlist>,
        val subscribed: List<Playlist>,
    ) {
        val isEmpty: Boolean
            get() = liked.isEmpty() && created.isEmpty() && subscribed.isEmpty()
    }

    /** `null` from the account endpoint means "not logged in", not an empty success. */
    suspend fun account(): MusicResult<Account> =
        call { client.account() }.orNeedLoginWhenNull()

    suspend fun playlists(uid: Long): MusicResult<LibraryPlaylists> = call {
        val all = client.playlists(uid)
        val liked = ArrayList<Playlist>()
        val created = ArrayList<Playlist>()
        val subscribed = ArrayList<Playlist>()
        for (playlist in all) {
            when {
                playlist.isLikedPlaylist -> liked += playlist
                isCreatedBy(playlist, uid) -> created += playlist
                else -> subscribed += playlist
            }
        }
        LibraryPlaylists(
            liked = liked.toList(),
            created = created.toList(),
            subscribed = subscribed.toList(),
        )
    }

    suspend fun likedSongs(uid: Long): MusicResult<List<Song>> =
        call { client.likeList(uid) }

    suspend fun playlistTracks(playlistId: Long): MusicResult<List<Song>> =
        call { client.playlistTracks(playlistId) }

    suspend fun dailySongs(): MusicResult<List<Song>> =
        call { client.dailySongs() }

    /** 私人 FM，一次一批（实测 3 首）。 */
    suspend fun personalFm(): MusicResult<List<Song>> =
        call { client.personalFm() }

    /** 私人 FM「不喜欢」；不可撤销。 */
    suspend fun dislikeSong(id: Long): MusicResult<Unit> =
        call { client.dislikeSong(id) }

    /** 红心 / 取消红心。 */
    suspend fun setLiked(id: Long, liked: Boolean): MusicResult<Unit> =
        call { client.setLiked(id, liked) }

    /**
     * 歌词（原文 + 中文翻译 + 罗马音）。
     *
     * 播放页只需要原文和翻译；罗马音保留在 [Lyric] 里备用。
     * 纯音乐 / 接口没有歌词时客户端返回 [Lyric.hasLyric] = false，这不算失败，
     * 由调用方渲染「暂无歌词」。
     */
    suspend fun lyric(songId: Long): MusicResult<Lyric> =
        call { client.lyric(songId) }

    private fun isCreatedBy(playlist: Playlist, uid: Long): Boolean {
        if (playlist.subscribed) return false
        return playlist.userId == uid || playlist.creator?.id == uid
    }

    /**
     * Runs one network call and maps the network layer's sealed exceptions.
     *
     * `CancellationException` is rethrown on purpose: leaving a screen must
     * cancel its request rather than showing a fake error.
     */
    private suspend fun <T> call(block: suspend () -> T): MusicResult<T> = try {
        MusicResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (transport: TransportException) {
        MusicResult.NetworkError(
            code = CODE_NETWORK,
            message = transport.message ?: "网络请求失败",
            cause = transport,
        )
    } catch (http: HttpException) {
        MusicResult.NetworkError(
            code = "HTTP_${http.statusCode}",
            message = http.message ?: "HTTP 错误 ${http.statusCode}",
            cause = http,
        )
    } catch (api: ApiException) {
        if (api.code == CODE_NEED_LOGIN) {
            MusicResult.NeedLogin
        } else {
            MusicResult.BusinessError(
                code = api.code,
                message = api.apiMessage.ifBlank { api.message ?: "接口业务错误" },
                apiPath = api.apiPath,
                cause = api,
            )
        }
    } catch (parse: ParseException) {
        MusicResult.ParseError(
            message = parse.message ?: "响应解析失败",
            cause = parse,
        )
    }

    private fun <T> MusicResult<T?>.orNeedLoginWhenNull(): MusicResult<T> = when (this) {
        is MusicResult.Success ->
            value?.let { MusicResult.Success(it) } ?: MusicResult.NeedLogin
        MusicResult.NeedLogin -> MusicResult.NeedLogin
        is MusicResult.NetworkError -> this
        is MusicResult.BusinessError -> this
        is MusicResult.ParseError -> this
    }

    private companion object {
        const val CODE_NETWORK = "NETWORK"
        const val CODE_NEED_LOGIN = 301
    }
}
