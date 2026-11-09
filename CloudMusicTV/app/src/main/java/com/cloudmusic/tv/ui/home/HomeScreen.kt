package com.cloudmusic.tv.ui.home

import android.util.Log
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.data.music.HomeDailySummary
import com.cloudmusic.core.data.music.HomeState
import com.cloudmusic.core.data.music.HomeUiState
import com.cloudmusic.core.data.music.RecentPlay
import com.cloudmusic.tv.ui.music.MusicMessageScreen
import com.cloudmusic.tv.ui.music.MyMusicCategory
import com.cloudmusic.tv.ui.music.PlaylistCover
import com.cloudmusic.tv.ui.music.SkeletonBlock
import com.cloudmusic.tv.ui.music.formatDailyDate
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicDimens
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(12.dp)

/**
 * 首页：每日推荐 / 私人 FM 两个大卡 + 我的音乐两行入口 + 最近播放一条。
 *
 * 每日推荐只显示摘要（日期 / 数量 / 前三首），点卡片切到每日推荐 tab；
 * 首页不加载完整 30 首列表，避免和 DailyState 重复占内存。
 */
@Composable
fun HomeRoute(
    homeState: HomeState,
    onOpenDaily: () -> Unit,
    onOpenFm: () -> Unit,
    onOpenMyMusic: (MyMusicCategory) -> Unit,
    onPlayRecent: (RecentPlay) -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by homeState.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(homeState) {
        homeState.load()
    }

    when (val current = state) {
        HomeUiState.Loading -> HomeSkeleton(modifier = modifier)

        is HomeUiState.Ready -> HomeScreen(
            state = current,
            onOpenDaily = onOpenDaily,
            onOpenFm = onOpenFm,
            onOpenMyMusic = onOpenMyMusic,
            onPlayRecent = onPlayRecent,
            modifier = modifier,
        )

        HomeUiState.NeedLogin -> MusicMessageScreen(
            title = "请先登录",
            message = "登录状态已失效，重新登录后再查看首页。",
            actionLabel = "重新登录",
            onAction = onNeedLogin,
            modifier = modifier,
        )

        is HomeUiState.Error -> MusicMessageScreen(
            title = "加载失败",
            message = current.error.message,
            code = current.error.code,
            actionLabel = "重试",
            onAction = { scope.launch { homeState.load() } },
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeScreen(
    state: HomeUiState.Ready,
    onOpenDaily: () -> Unit,
    onOpenFm: () -> Unit,
    onOpenMyMusic: (MyMusicCategory) -> Unit,
    onPlayRecent: (RecentPlay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstCardRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstCardRequester.requestFocus() }
            .onFailure { Log.w("HomeScreen", "首页首卡焦点请求失败", it) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CloudMusicDimens.PageHorizontalPadding,
            end = CloudMusicDimens.PageHorizontalPadding,
            top = 20.dp,
            bottom = 36.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = "CloudMusic TV",
                    style = MaterialTheme.typography.headlineMedium,
                    color = CloudMusicColors.OnDark,
                )
                Text(
                    text = "今天想听点什么？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CloudMusicColors.NavUnselectedText,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item(key = "heroes") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                DailyHeroCard(
                    summary = state.daily,
                    onClick = onOpenDaily,
                    modifier = Modifier.weight(1f),
                    focusRequester = firstCardRequester,
                )
                HomeHeroCard(
                    title = "私人 FM",
                    subtitle = "根据你的口味持续推荐",
                    accent = CloudMusicColors.TileGreen,
                    lines = listOf("没有列表，按 OK 直接开始", "不喜欢就换一首"),
                    onClick = onOpenFm,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item(key = "my_music_title") {
            SectionTitle("我的音乐")
        }

        item(key = "liked") {
            HomeEntryCard(
                title = "我喜欢的音乐",
                subtitle = "红心歌曲和喜欢的歌单",
                color = CloudMusicColors.NavAccent,
                onClick = { onOpenMyMusic(MyMusicCategory.LIKED) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "my_music_rows") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                HomeEntryCard(
                    title = "我创建的",
                    subtitle = "自己创建的歌单",
                    color = CloudMusicColors.TileBlue,
                    onClick = { onOpenMyMusic(MyMusicCategory.CREATED) },
                    modifier = Modifier.weight(1f),
                )
                HomeEntryCard(
                    title = "我收藏的",
                    subtitle = "收藏与关注的歌单",
                    color = CloudMusicColors.TileViolet,
                    onClick = { onOpenMyMusic(MyMusicCategory.SUBSCRIBED) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item(key = "recent_title") {
            SectionTitle("最近播放")
        }

        item(key = "recent") {
            if (state.recent.isEmpty()) {
                Text(
                    text = "还没有最近播放，先从每日推荐或私人 FM 开始吧。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CloudMusicColors.NavUnselectedText,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(
                        items = state.recent,
                        key = { it.songId },
                    ) { play ->
                        RecentPlayCard(
                            play = play,
                            onClick = { onPlayRecent(play) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = CloudMusicColors.OnDark,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun DailyHeroCard(
    summary: HomeDailySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val lines = if (summary.count == 0) {
        listOf("今天还没有推荐", "点进去刷新试试")
    } else {
        listOf("${summary.count} 首 · ${formatDailyDate(summary.date)}") +
            summary.preview.take(2)
    }
    HomeHeroCard(
        title = "每日推荐",
        subtitle = "每天 6:00 更新",
        accent = CloudMusicColors.TileRed,
        lines = lines,
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
    )
}

@Composable
private fun HomeHeroCard(
    title: String,
    subtitle: String,
    accent: Color,
    lines: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(176.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = CardDefaults.shape(shape = CardShape),
        colors = CardDefaults.colors(
            containerColor = accent.copy(alpha = 0.92f),
            contentColor = Color.White,
        ),
        scale = CardDefaults.scale(
            scale = 1f,
            focusedScale = CloudMusicDimens.TileFocusedScale,
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(CloudMusicDimens.TileFocusedBorder, Color.White),
                shape = CardShape,
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lines.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            lines.drop(1).take(2).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeEntryCard(
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(86.dp),
        shape = CardDefaults.shape(shape = CardShape),
        colors = CardDefaults.colors(
            containerColor = color.copy(alpha = 0.90f),
            contentColor = Color.White,
        ),
        scale = CardDefaults.scale(
            scale = 1f,
            focusedScale = CloudMusicDimens.TileFocusedScale,
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(CloudMusicDimens.TileFocusedBorder, Color.White),
                shape = CardShape,
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun RecentPlayCard(
    play: RecentPlay,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(232.dp)
            .height(88.dp),
        shape = CardDefaults.shape(shape = CardShape),
        colors = CardDefaults.colors(
            containerColor = CloudMusicColors.Surface,
            contentColor = CloudMusicColors.OnDark,
        ),
        scale = CardDefaults.scale(
            scale = 1f,
            focusedScale = CloudMusicDimens.TileFocusedScale,
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(CloudMusicDimens.TileFocusedBorder, Color.White),
                shape = CardShape,
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCover(
                url = play.coverUrl,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                requestWidthDp = 160,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = play.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CloudMusicColors.OnDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = play.artist.ifBlank { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    color = CloudMusicColors.NavUnselectedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = CloudMusicDimens.PageHorizontalPadding,
                vertical = 20.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SkeletonBlock(modifier = Modifier.width(260.dp).height(34.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SkeletonBlock(modifier = Modifier.weight(1f).height(176.dp))
            SkeletonBlock(modifier = Modifier.weight(1f).height(176.dp))
        }
        SkeletonBlock(modifier = Modifier.width(120.dp).height(22.dp))
        SkeletonBlock(modifier = Modifier.fillMaxWidth().height(86.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SkeletonBlock(modifier = Modifier.weight(1f).height(86.dp))
            SkeletonBlock(modifier = Modifier.weight(1f).height(86.dp))
        }
    }
}
