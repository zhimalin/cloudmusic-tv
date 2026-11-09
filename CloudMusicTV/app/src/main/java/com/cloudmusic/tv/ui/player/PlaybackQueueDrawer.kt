package com.cloudmusic.tv.ui.player

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.player.Track
import com.cloudmusic.tv.ui.theme.CloudMusicColors

private val QueueRowShape = RoundedCornerShape(8.dp)

/**
 * 右侧播放队列抽屉（宽 320dp）。
 *
 * 队列内容由 app 层的 [PlaybackQueueMirror] 提供：core-player 的公开状态里只有
 * `queueIndex/queueSize`，没有曲目列表；UI 在每次 `player.play(...)` 时记录一份，
 * 并用 `queueSize` 校验，避免显示过期的队列。
 *
 * 随机模式下这里按**队列原始顺序**显示（下一首由播放核心随机决定，UI 无法预知），
 * 行尾只标出当前曲目，不伪造“接下来”的顺序。
 *
 * 焦点：打开后自动落到当前项（[QueueRow.autoFocus]），确认键跳播，返回键由父组件关闭。
 */
@Composable
fun PlaybackQueueDrawer(
    tracks: List<Track>,
    currentIndex: Int,
    queueSize: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val consistent = tracks.isNotEmpty() && tracks.size == queueSize
    val safeCurrentIndex = currentIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
    // 抽屉只在打开时进入组合；initialFirstVisibleItemIndex 每次打开都重新计算。
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (safeCurrentIndex - 2).coerceAtLeast(0),
    )
    var focusConsumed by remember(tracks) { mutableStateOf(false) }
    val requesters = remember(tracks) {
        List(tracks.size) { FocusRequester() }
    }

    Column(
        modifier = modifier
            .width(PlayerDimens.QueueDrawerWidth)
            .fillMaxHeight()
            .background(PlayerColors.Panel)
            .drawBehind {
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "播放队列",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (consistent) {
                    "${safeCurrentIndex + 1}/$queueSize"
                } else {
                    "--/$queueSize"
                },
                style = MaterialTheme.typography.bodySmall,
                color = CloudMusicColors.NavUnselectedText,
            )
        }

        if (!consistent) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "队列信息不可用\n返回后重新点歌可恢复",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CloudMusicColors.NavUnselectedText,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    items = tracks,
                    key = { index, track -> "${track.id}-$index" },
                ) { index, track ->
                    val autoFocus = !focusConsumed && index == safeCurrentIndex
                    QueueRow(
                        index = index,
                        track = track,
                        isCurrent = index == safeCurrentIndex,
                        autoFocus = autoFocus,
                        onClick = { onSelect(index) },
                        focusRequester = requesters.getOrNull(index),
                        onAutoFocusConsumed = {
                            focusConsumed = true
                        },
                    )
                }
            }
        }

        Text(
            text = "按确认键跳播 · 返回键关闭",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = CloudMusicColors.SectionHeader,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun QueueRow(
    index: Int,
    track: Track,
    isCurrent: Boolean,
    autoFocus: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onAutoFocusConsumed: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    LaunchedEffect(autoFocus) {
        if (autoFocus && focusRequester != null) {
            // LazyColumn 的首帧可能还没把当前项真正 attach 到焦点系统，多试两帧。
            for (attempt in 0..2) {
                withFrameNanos { }
                val focused = runCatching { focusRequester.requestFocus() }
                focused.onFailure {
                    Log.w("PlaybackQueueDrawer", "队列当前项焦点请求失败（第 ${attempt + 1} 次）", it)
                }
                if (focused.isSuccess) break
            }
            onAutoFocusConsumed()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(
                color = when {
                    focused -> CloudMusicColors.RowFocusedBackground
                    isCurrent -> Color(0x1FEC4141)
                    else -> Color.Transparent
                },
                shape = QueueRowShape,
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, QueueRowShape) else Modifier)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 当前项左侧红色竖条；其他行保留同宽占位，避免文字左右抖动。
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .background(
                    color = if (isCurrent) CloudMusicColors.NavAccent else Color.Transparent,
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = (index + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.bodySmall,
            color = CloudMusicColors.SectionHeader,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (isCurrent) Color.White else CloudMusicColors.OnDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = CloudMusicColors.NavUnselectedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = PlaybackTime.format(track.durationMs),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = CloudMusicColors.SectionHeader,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
