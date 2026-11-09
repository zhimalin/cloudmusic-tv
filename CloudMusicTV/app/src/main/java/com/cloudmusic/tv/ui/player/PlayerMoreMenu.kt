package com.cloudmusic.tv.ui.player

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.tv.ui.theme.CloudMusicColors

private val MenuRowShape = RoundedCornerShape(8.dp)

/**
 * 「更多」面板：把设置页里真正影响播放页的三项搬到这里，改完立即生效。
 *
 * - 默认音质：循环「标准 / 较高 / 极高 / 无损」，写入 [com.cloudmusic.core.data.SettingsStore]，
 *   并同步 `MusicPlayer.defaultLevel`（下一首取地址时生效）；
 * - 唱片旋转：直接驱动黑胶的旋转协程；
 * - 背景取色：关闭后播放页不请求封面缩略图，固定用默认深色渐变。
 *
 * 面板本身不处理返回键，由 PlayerScreen 的 BackHandler 关闭并归还焦点。
 */
@Composable
fun PlayerMoreMenu(
    qualityLabel: String,
    rotationEnabled: Boolean,
    tintEnabled: Boolean,
    onCycleQuality: () -> Unit,
    onToggleRotation: () -> Unit,
    onToggleTint: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstRowRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        for (attempt in 0..2) {
            withFrameNanos { }
            val focused = runCatching { firstRowRequester.requestFocus() }
            focused.onFailure {
                Log.w("PlayerMoreMenu", "更多菜单首项焦点请求失败（第 ${attempt + 1} 次）", it)
            }
            if (focused.isSuccess) break
        }
    }

    Column(
        modifier = modifier
            .width(300.dp)
            .background(PlayerColors.Panel, RoundedCornerShape(12.dp))
            .border(1.dp, PlayerColors.PanelBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "播放设置",
            style = MaterialTheme.typography.titleSmall,
            color = CloudMusicColors.SectionHeader,
            modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp),
        )

        MenuRow(
            label = "默认音质",
            value = qualityLabel,
            hint = "下一首生效",
            onClick = onCycleQuality,
            focusRequester = firstRowRequester,
        )
        MenuRow(
            label = "唱片旋转",
            value = if (rotationEnabled) "开" else "关",
            hint = null,
            onClick = onToggleRotation,
        )
        MenuRow(
            label = "背景取色",
            value = if (tintEnabled) "开" else "关",
            hint = null,
            onClick = onToggleTint,
        )

        Spacer(modifier = Modifier.height(4.dp))
        MenuRow(
            label = "关闭",
            value = null,
            hint = "返回键也可关闭",
            onClick = onClose,
        )
    }
}

@Composable
private fun MenuRow(
    label: String,
    value: String?,
    hint: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(
                color = if (focused) CloudMusicColors.RowFocusedBackground else Color.Transparent,
                shape = MenuRowShape,
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, MenuRowShape) else Modifier)
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
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = CloudMusicColors.OnDark,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = CloudMusicColors.SectionHeader,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = CloudMusicColors.NavUnselectedText,
            )
        }
    }
}
