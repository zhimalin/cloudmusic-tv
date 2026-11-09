package com.cloudmusic.tv.ui.music

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.network.Song
import com.cloudmusic.tv.ui.components.TvButton
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicDimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SkeletonShape = RoundedCornerShape(10.dp)
private val SongShape = RoundedCornerShape(8.dp)
private val DailyDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

/** `mm:ss`, or `h:mm:ss` for very long tracks. */
fun formatSongDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatDailyDate(date: LocalDate): String = date.format(DailyDateFormatter)

@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(SkeletonShape)
            .background(Color(0xFF24222A)),
    )
}

/** Loading state for the three "my music" entries. */
@Composable
fun LibraryEntriesSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        repeat(3) {
            SkeletonBlock(
                modifier = Modifier
                    .weight(1f)
                    .height(150.dp),
            )
        }
    }
}

/** Loading state for a 5-column cover wall. */
@Composable
fun PlaylistGridSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                repeat(5) {
                    SkeletonBlock(
                        modifier = Modifier
                            .weight(1f)
                            .height(132.dp),
                    )
                }
            }
        }
    }
}

/** Loading state for the header + song list of a playlist. */
@Composable
fun PlaylistDetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = CloudMusicDimens.PageHorizontalPadding,
                end = CloudMusicDimens.PageHorizontalPadding,
                top = 24.dp,
                bottom = 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            SkeletonBlock(modifier = Modifier.size(160.dp))
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .width(420.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth().height(34.dp))
                SkeletonBlock(modifier = Modifier.width(260.dp).height(22.dp))
                SkeletonBlock(modifier = Modifier.width(180.dp).height(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        repeat(5) {
            SkeletonBlock(modifier = Modifier.fillMaxWidth().height(58.dp))
        }
    }
}

/** Loading state for 每日推荐. */
@Composable
fun DailySkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = CloudMusicDimens.PageHorizontalPadding,
                end = CloudMusicDimens.PageHorizontalPadding,
                top = 24.dp,
                bottom = 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBlock(modifier = Modifier.width(220.dp).height(34.dp))
                SkeletonBlock(modifier = Modifier.width(180.dp).height(20.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            SkeletonBlock(modifier = Modifier.width(150.dp).height(52.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        repeat(6) {
            SkeletonBlock(modifier = Modifier.fillMaxWidth().height(58.dp))
        }
    }
}

/**
 * Full-page login / empty / error message.
 *
 * The action button requests focus on entry so the remote always has a target,
 * including on the "请先登录" screen.
 */
@Composable
fun MusicMessageScreen(
    title: String,
    message: String?,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    code: String? = null,
) {
    val actionRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { actionRequester.requestFocus() }
            .onFailure { Log.w("MusicCommon", "消息页操作按钮焦点请求失败", it) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(CloudMusicDimens.PageHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = CloudMusicColors.OnDark,
            )
            if (code != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "错误码：$code",
                    style = MaterialTheme.typography.titleMedium,
                    color = CloudMusicColors.NavAccent,
                )
            }
            if (!message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CloudMusicColors.NavUnselectedText,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            TvButton(
                label = actionLabel,
                onClick = onAction,
                focusRequester = actionRequester,
            )
        }
    }
}

/**
 * One focusable song row.
 *
 * Playback is owned by the app layer: [onClick] only forwards the selected
 * [Song] to its caller.
 */
@Composable
fun SongRow(
    index: Int,
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** 播放列表第一首可把「上」显式指向「播放全部」，避免焦点搜索跳去顶部导航。 */
    upFocusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SongShape)
            .background(
                if (focused) CloudMusicColors.RowFocusedBackground else Color.Transparent,
                SongShape,
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, SongShape) else Modifier)
            .then(
                if (upFocusRequester != null) {
                    Modifier.focusProperties { up = upFocusRequester }
                } else {
                    Modifier
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(Locale.US, index + 1),
            style = MaterialTheme.typography.bodyMedium,
            color = CloudMusicColors.SectionHeader,
            modifier = Modifier.width(44.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = CloudMusicColors.OnDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artistText.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodySmall,
                color = CloudMusicColors.NavUnselectedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatSongDuration(song.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = CloudMusicColors.NavUnselectedText,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
