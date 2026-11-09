package com.cloudmusic.tv.ui.music

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.data.music.PlaylistDetailUiState
import com.cloudmusic.core.network.Playlist
import com.cloudmusic.core.network.Song
import com.cloudmusic.tv.ui.components.TvButton
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicDimens

/**
 * Playlist detail: cover header + a single-column, virtualized song list.
 *
 * The screen never owns playback: 「播放全部」hands the complete list to
 * [onPlayAll], and row activation hands one [Song] to [onSongSelected].
 * The list can hold 500+ [Song] objects; it is stored once in state and
 * `LazyColumn` only composes visible rows.
 */
@Composable
fun PlaylistDetailScreen(
    state: PlaylistDetailUiState,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onRetry: () -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        PlaylistDetailUiState.Loading -> PlaylistDetailSkeleton(modifier = modifier)

        is PlaylistDetailUiState.Ready -> PlaylistDetailReady(
            state = state,
            onSongSelected = onSongSelected,
            onPlayAll = onPlayAll,
            modifier = modifier,
        )

        is PlaylistDetailUiState.Empty -> MusicMessageScreen(
            title = "歌单里还没有歌曲",
            message = state.playlist.name,
            actionLabel = "刷新",
            onAction = onRetry,
            modifier = modifier,
        )

        PlaylistDetailUiState.NeedLogin -> MusicMessageScreen(
            title = "请先登录",
            message = "登录状态已失效，重新登录后再查看歌单。",
            actionLabel = "重新登录",
            onAction = onNeedLogin,
            modifier = modifier,
        )

        is PlaylistDetailUiState.Error -> MusicMessageScreen(
            title = "加载失败",
            message = state.error.message,
            code = state.error.code,
            actionLabel = "重试",
            onAction = onRetry,
            modifier = modifier,
        )
    }
}

@Composable
private fun PlaylistDetailReady(
    state: PlaylistDetailUiState.Ready,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstSongRequester = remember { FocusRequester() }
    val playAllRequester = remember { FocusRequester() }

    // 默认焦点仍是第一首歌；「播放全部」只在用户按上键时才拿到焦点。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstSongRequester.requestFocus() }
            .onFailure { Log.w("PlaylistDetailScreen", "歌单详情首曲焦点请求失败", it) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CloudMusicDimens.PageHorizontalPadding,
            end = CloudMusicDimens.PageHorizontalPadding,
            top = 20.dp,
            bottom = 36.dp,
        ),
    ) {
        item(key = "header") {
            PlaylistHeader(
                playlist = state.playlist,
                loadedTrackCount = state.songs.size,
                onPlayAll = { onPlayAll(state.songs) },
                playAllFocusRequester = playAllRequester,
                firstSongRequester = firstSongRequester,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "歌曲",
                    style = MaterialTheme.typography.titleMedium,
                    color = CloudMusicColors.OnDark,
                )
                Text(
                    text = "  ${state.songs.size} 首 · 按上下键选择，OK 选中",
                    style = MaterialTheme.typography.bodySmall,
                    color = CloudMusicColors.NavUnselectedText,
                )
            }
        }

        itemsIndexed(
            items = state.songs,
            key = { index, song -> "${song.id}-$index" },
        ) { index, song ->
            SongRow(
                index = index,
                song = song,
                onClick = { onSongSelected(song) },
                focusRequester = if (index == 0) firstSongRequester else null,
                upFocusRequester = if (index == 0) playAllRequester else null,
            )
            Spacer(modifier = Modifier.height(3.dp))
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: Playlist,
    loadedTrackCount: Int,
    onPlayAll: () -> Unit,
    playAllFocusRequester: FocusRequester,
    firstSongRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(
            url = playlist.coverImgUrl,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.headlineSmall,
                color = CloudMusicColors.OnDark,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "作者：${playlist.creator?.nickname ?: "网易云音乐"}",
                style = MaterialTheme.typography.bodyMedium,
                color = CloudMusicColors.NavUnselectedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "曲目数：${playlist.trackCount}（已加载 $loadedTrackCount）",
                style = MaterialTheme.typography.bodyMedium,
                color = CloudMusicColors.NavUnselectedText,
            )
            val description = playlist.description
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = CloudMusicColors.SectionHeader,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            TvButton(
                label = "播放全部（$loadedTrackCount）",
                onClick = onPlayAll,
                modifier = Modifier.focusProperties {
                    // 从按钮向下回到第一首；向上走顶部导航。
                    down = firstSongRequester
                },
                focusRequester = playAllFocusRequester,
            )
        }
    }
}
