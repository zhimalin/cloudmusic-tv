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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.data.music.DailyState
import com.cloudmusic.core.data.music.DailyUiState
import com.cloudmusic.core.network.Song
import com.cloudmusic.tv.ui.components.TvButton
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicDimens
import kotlinx.coroutines.launch

/**
 * Entry point for the 每日推荐 tab. Owns load/collect; the UI below is a pure
 * function of [DailyUiState].
 */
@Composable
fun DailyRoute(
    dailyState: DailyState,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by dailyState.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(dailyState) {
        dailyState.load()
    }

    DailyScreen(
        state = state,
        onSongSelected = onSongSelected,
        onPlayAll = onPlayAll,
        onRetry = { scope.launch { dailyState.load() } },
        onNeedLogin = onNeedLogin,
        modifier = modifier,
    )
}

/**
 * 每日推荐: date + "播放全部" + one-column list.
 *
 * "播放全部" is a real focusable action but [onPlayAll] is only a placeholder
 * for the next round; no audio is started here.
 */
@Composable
fun DailyScreen(
    state: DailyUiState,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onRetry: () -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DailyUiState.Loading -> DailySkeleton(modifier = modifier)

        is DailyUiState.Ready -> DailyReady(
            state = state,
            onSongSelected = onSongSelected,
            onPlayAll = onPlayAll,
            modifier = modifier,
        )

        is DailyUiState.Empty -> MusicMessageScreen(
            title = "今天还没有推荐",
            message = "${formatDailyDate(state.date)} · 每日推荐是空的。",
            actionLabel = "刷新",
            onAction = onRetry,
            modifier = modifier,
        )

        DailyUiState.NeedLogin -> MusicMessageScreen(
            title = "请先登录",
            message = "登录状态已失效，重新登录后再查看每日推荐。",
            actionLabel = "重新登录",
            onAction = onNeedLogin,
            modifier = modifier,
        )

        is DailyUiState.Error -> MusicMessageScreen(
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
private fun DailyReady(
    state: DailyUiState.Ready,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playAllRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { playAllRequester.requestFocus() }
            .onFailure { Log.w("DailyScreen", "每日推荐播放全部焦点请求失败", it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = CloudMusicDimens.PageHorizontalPadding,
                    end = CloudMusicDimens.PageHorizontalPadding,
                    top = 22.dp,
                    bottom = 18.dp,
                ),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "每日推荐",
                    style = MaterialTheme.typography.headlineMedium,
                    color = CloudMusicColors.OnDark,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${formatDailyDate(state.date)} · 共 ${state.songs.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                    color = CloudMusicColors.NavUnselectedText,
                )
            }
            TvButton(
                label = "播放全部",
                onClick = { onPlayAll(state.songs) },
                focusRequester = playAllRequester,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CloudMusicDimens.PageHorizontalPadding,
                end = CloudMusicDimens.PageHorizontalPadding,
                bottom = 36.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexed(
                items = state.songs,
                key = { index, song -> "${song.id}-$index" },
            ) { index, song ->
                SongRow(
                    index = index,
                    song = song,
                    onClick = { onSongSelected(song) },
                )
            }
        }
    }
}
