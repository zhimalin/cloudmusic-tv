package com.cloudmusic.tv.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.player.Track
import com.cloudmusic.tv.ui.music.PlaylistCover
import com.cloudmusic.tv.ui.theme.CloudMusicColors

private val MiniPlayerShape = RoundedCornerShape(8.dp)

/**
 * 全局迷你播放条（高 64dp，只在 MAIN 根页面底部显示）。
 *
 * - 左侧「封面 + 歌名 + 歌手」是焦点项，确认键进入播放页；
 * - 右侧播放/暂停是独立焦点项；
 * - 没有正在播放的曲目时**不渲染**（由 MainScreen 判断），所以不会顶掉页面高度。
 */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PlayerDimens.MiniPlayerHeight)
            .background(Color(0xF2141218))
            .drawBehind {
                drawLine(
                    color = Color.White.copy(alpha = 0.07f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 40.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayerInfo(
                track = track,
                onClick = onOpenPlayer,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(16.dp))
            PlayerControlButton(
                contentDescription = if (isPlaying) "暂停" else "播放",
                onClick = onTogglePlay,
                width = 44.dp,
                height = 44.dp,
            ) {
                PlayPauseIcon(
                    isPlaying = isPlaying,
                    color = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerInfo(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "miniPlayerScale",
    )

    Row(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(MiniPlayerShape)
            .background(
                color = if (focused) CloudMusicColors.RowFocusedBackground else Color.Transparent,
                shape = MiniPlayerShape,
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, MiniPlayerShape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = "打开播放页：${track.title}" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(
            url = track.coverUrl,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(6.dp)),
            requestWidthDp = 96,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = CloudMusicColors.OnDark,
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
    }
}
