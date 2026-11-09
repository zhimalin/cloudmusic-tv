package com.cloudmusic.tv.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.data.music.FmState
import com.cloudmusic.core.data.music.FmUiState
import com.cloudmusic.core.network.Song
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicDimens
import kotlinx.coroutines.launch

/**
 * 私人 FM 的启动/错误壳，不是独立播放界面。
 *
 * 方案 3.5：FM 就是播放页的 FM 模式。这里只负责：
 * 1. 首次进入/失败重试时拉一批 FM；
 * 2. 拿到 Playing 后把当前歌交给 app 层开始播放并切到 PlayerScreen；
 * 3. 负责 Loading / Empty / NeedLogin / Error 的兜底显示。
 */
@Composable
fun FmRoute(
    fmState: FmState,
    onEnsurePlaying: (Song) -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by fmState.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(fmState) {
        if (fmState.state.value !is FmUiState.Playing) {
            fmState.load()
        }
    }

    LaunchedEffect(state) {
        val playing = state as? FmUiState.Playing
        if (playing != null) {
            onEnsurePlaying(playing.song)
        }
    }

    when (val current = state) {
        FmUiState.Loading -> FmLoading(modifier = modifier)

        is FmUiState.Playing -> FmLoading(
            message = "正在打开「${current.song.name}」…",
            modifier = modifier,
        )

        FmUiState.Empty -> MusicMessageScreen(
            title = "暂时没有 FM 推荐",
            message = "服务端返回了空批次，稍后再试。",
            actionLabel = "重试",
            onAction = { scope.launch { fmState.load() } },
            modifier = modifier,
        )

        FmUiState.NeedLogin -> MusicMessageScreen(
            title = "请先登录",
            message = "登录状态已失效，重新登录后再听私人 FM。",
            actionLabel = "重新登录",
            onAction = onNeedLogin,
            modifier = modifier,
        )

        is FmUiState.Error -> MusicMessageScreen(
            title = "FM 加载失败",
            message = current.error.message,
            code = current.error.code,
            actionLabel = "重试",
            onAction = { scope.launch { fmState.load() } },
            modifier = modifier,
        )
    }
}

@Composable
private fun FmLoading(
    modifier: Modifier = Modifier,
    message: String = "正在为你准备私人 FM…",
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(CloudMusicDimens.PageHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = CloudMusicColors.OnDark,
            )
            Spacer(modifier = Modifier.height(18.dp))
            SkeletonBlock(
                modifier = Modifier
                    .width(280.dp)
                    .height(10.dp),
            )
        }
    }
}
