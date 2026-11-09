package com.cloudmusic.tv.ui.music

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
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
import com.cloudmusic.core.data.music.LibraryState
import com.cloudmusic.core.data.music.LibraryUiState
import com.cloudmusic.core.data.music.MusicRepository
import com.cloudmusic.core.data.music.PlaylistDetailState
import com.cloudmusic.core.network.Playlist
import com.cloudmusic.core.network.Song
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicDimens
import kotlinx.coroutines.launch

/** The three entries on the "我的音乐" landing page. */
enum class MyMusicCategory(val title: String) {
    LIKED("我喜欢的"),
    CREATED("我创建的"),
    SUBSCRIBED("我收藏的"),
}

private val CardShape = RoundedCornerShape(10.dp)

/**
 * 我的音乐 navigation: landing -> 5-column cover wall -> playlist detail.
 *
 * Back always goes one level up. Switching tabs removes this whole subtree, so
 * the next visit starts at the landing page again.
 */
@Composable
fun MyMusicFlow(
    repository: MusicRepository,
    libraryState: LibraryState,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
    initialCategory: MyMusicCategory? = null,
    onInitialCategoryConsumed: () -> Unit = {},
) {
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    val state by libraryState.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(libraryState) {
        libraryState.load()
    }

    LaunchedEffect(initialCategory) {
        if (initialCategory != null) {
            category = initialCategory
            onInitialCategoryConsumed()
        }
    }

    BackHandler(enabled = selectedPlaylist != null || category != null) {
        if (selectedPlaylist != null) {
            selectedPlaylist = null
        } else {
            category = null
        }
    }

    when {
        selectedPlaylist != null -> {
            val ready = state as? LibraryUiState.Ready
            PlaylistDetailRoute(
                repository = repository,
                playlist = selectedPlaylist!!,
                uid = ready?.uid,
                onSongSelected = onSongSelected,
                onPlayAll = onPlayAll,
                onNeedLogin = onNeedLogin,
                modifier = modifier,
            )
        }

        category != null -> {
            LibraryGridPage(
                category = category!!,
                state = state,
                onPlaylistSelected = { selectedPlaylist = it },
                onRetry = { scope.launch { libraryState.load() } },
                onNeedLogin = onNeedLogin,
                modifier = modifier,
            )
        }

        else -> {
            MyMusicHome(
                state = state,
                onOpenCategory = { category = it },
                onRetry = { scope.launch { libraryState.load() } },
                onNeedLogin = onNeedLogin,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun MyMusicHome(
    state: LibraryUiState,
    onOpenCategory: (MyMusicCategory) -> Unit,
    onRetry: () -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LibraryUiState.Loading -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = CloudMusicDimens.PageHorizontalPadding,
                        vertical = 24.dp,
                    ),
            ) {
                LibraryHeader()
                Spacer(modifier = Modifier.height(32.dp))
                LibraryEntriesSkeleton()
            }
        }

        is LibraryUiState.Ready -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = CloudMusicDimens.PageHorizontalPadding,
                        vertical = 24.dp,
                    ),
            ) {
                LibraryHeader()
                Spacer(modifier = Modifier.height(32.dp))
                LibraryEntries(
                    state = state,
                    onOpenCategory = onOpenCategory,
                )
            }
        }

        LibraryUiState.Empty -> MusicMessageScreen(
            title = "还没有歌单",
            message = "账号里没有我喜欢的、创建的或收藏的歌单。",
            actionLabel = "刷新",
            onAction = onRetry,
            modifier = modifier,
        )

        LibraryUiState.NeedLogin -> MusicMessageScreen(
            title = "请先登录",
            message = "登录状态已失效，重新登录后再查看我的音乐。",
            actionLabel = "重新登录",
            onAction = onNeedLogin,
            modifier = modifier,
        )

        is LibraryUiState.Error -> MusicMessageScreen(
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
private fun LibraryHeader() {
    Column {
        Text(
            text = "我的音乐",
            style = MaterialTheme.typography.headlineMedium,
            color = CloudMusicColors.OnDark,
        )
        Text(
            text = "我喜欢的 / 我创建的 / 我收藏的",
            style = MaterialTheme.typography.bodyMedium,
            color = CloudMusicColors.NavUnselectedText,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LibraryEntries(
    state: LibraryUiState.Ready,
    onOpenCategory: (MyMusicCategory) -> Unit,
) {
    val firstRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
            .onFailure { Log.w("MyMusicScreen", "我的音乐入口焦点请求失败", it) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        MyMusicCategory.entries.forEachIndexed { index, category ->
            MyMusicEntryCard(
                category = category,
                count = state.playlistsFor(category).size,
                onClick = { onOpenCategory(category) },
                modifier = Modifier.weight(1f),
                focusRequester = if (index == 0) firstRequester else null,
            )
        }
    }
}

@Composable
private fun MyMusicEntryCard(
    category: MyMusicCategory,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val containerColor = when (category) {
        MyMusicCategory.LIKED -> CloudMusicColors.NavAccent
        MyMusicCategory.CREATED -> CloudMusicColors.TileBlue
        MyMusicCategory.SUBSCRIBED -> CloudMusicColors.TileViolet
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .height(150.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = CardDefaults.shape(shape = CardShape),
        colors = CardDefaults.colors(
            containerColor = containerColor,
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
            Text(
                text = category.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$count 个歌单",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun LibraryGridPage(
    category: MyMusicCategory,
    state: LibraryUiState,
    onPlaylistSelected: (Playlist) -> Unit,
    onRetry: () -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LibraryUiState.Loading -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = CloudMusicDimens.PageHorizontalPadding,
                        vertical = 24.dp,
                    ),
            ) {
                GridHeader(category = category, count = null)
                Spacer(modifier = Modifier.height(28.dp))
                PlaylistGridSkeleton()
            }
        }

        is LibraryUiState.Ready -> {
            val playlists = state.playlistsFor(category)
            if (playlists.isEmpty()) {
                Column(modifier = modifier.fillMaxSize()) {
                    GridHeader(
                        category = category,
                        count = 0,
                        modifier = Modifier.padding(
                            start = CloudMusicDimens.PageHorizontalPadding,
                            end = CloudMusicDimens.PageHorizontalPadding,
                            top = 24.dp,
                        ),
                    )
                    MusicMessageScreen(
                        title = "这个分类还没有歌单",
                        message = null,
                        actionLabel = "刷新",
                        onAction = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(modifier = modifier.fillMaxSize()) {
                    GridHeader(
                        category = category,
                        count = playlists.size,
                        modifier = Modifier.padding(
                            start = CloudMusicDimens.PageHorizontalPadding,
                            end = CloudMusicDimens.PageHorizontalPadding,
                            top = 24.dp,
                        ),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    PlaylistCoverGrid(
                        playlists = playlists,
                        onPlaylistSelected = onPlaylistSelected,
                    )
                }
            }
        }

        LibraryUiState.Empty -> MusicMessageScreen(
            title = "还没有歌单",
            message = "账号里没有可显示的歌单。",
            actionLabel = "刷新",
            onAction = onRetry,
            modifier = modifier,
        )

        LibraryUiState.NeedLogin -> MusicMessageScreen(
            title = "请先登录",
            message = "登录状态已失效，重新登录后再查看歌单。",
            actionLabel = "重新登录",
            onAction = onNeedLogin,
            modifier = modifier,
        )

        is LibraryUiState.Error -> MusicMessageScreen(
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
private fun GridHeader(
    category: MyMusicCategory,
    count: Int?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = category.title,
            style = MaterialTheme.typography.headlineMedium,
            color = CloudMusicColors.OnDark,
        )
        Text(
            text = if (count == null) "加载中…" else "$count 个歌单 · 按返回键逐层返回",
            style = MaterialTheme.typography.bodyMedium,
            color = CloudMusicColors.NavUnselectedText,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PlaylistCoverGrid(
    playlists: List<Playlist>,
    onPlaylistSelected: (Playlist) -> Unit,
) {
    val firstRequester = remember { FocusRequester() }

    LaunchedEffect(playlists) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
            .onFailure { Log.w("MyMusicScreen", "歌单封面墙首项焦点请求失败", it) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CloudMusicDimens.PageHorizontalPadding,
            end = CloudMusicDimens.PageHorizontalPadding,
            top = 4.dp,
            bottom = 40.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        itemsIndexed(
            items = playlists,
            key = { _, playlist -> playlist.id },
        ) { index, playlist ->
            PlaylistCoverCard(
                playlist = playlist,
                onClick = { onPlaylistSelected(playlist) },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = if (index == 0) firstRequester else null,
            )
        }
    }
}

@Composable
private fun PlaylistCoverCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                ),
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
            PlaylistCover(
                url = playlist.coverImgUrl,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(9.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = CloudMusicColors.OnDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.trackCount} 首",
            style = MaterialTheme.typography.bodySmall,
            color = CloudMusicColors.NavUnselectedText,
            maxLines = 1,
        )
    }
}

private fun LibraryUiState.Ready.playlistsFor(category: MyMusicCategory): List<Playlist> =
    when (category) {
        MyMusicCategory.LIKED -> liked
        MyMusicCategory.CREATED -> created
        MyMusicCategory.SUBSCRIBED -> subscribed
    }

@Composable
private fun PlaylistDetailRoute(
    repository: MusicRepository,
    playlist: Playlist,
    uid: Long?,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onNeedLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detailState = remember(playlist.id, uid) {
        PlaylistDetailState(repository, playlist, uid)
    }
    val state by detailState.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(detailState) {
        detailState.load()
    }

    PlaylistDetailScreen(
        state = state,
        onSongSelected = onSongSelected,
        onPlayAll = onPlayAll,
        onRetry = { scope.launch { detailState.load() } },
        onNeedLogin = onNeedLogin,
        modifier = modifier,
    )
}
