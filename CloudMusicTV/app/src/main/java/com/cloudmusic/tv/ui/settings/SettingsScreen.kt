package com.cloudmusic.tv.ui.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.core.data.SettingsStore
import com.cloudmusic.tv.BuildConfig
import com.cloudmusic.tv.ui.player.PlayerQuality
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicDimens
import java.util.Locale

private val RowShape = RoundedCornerShape(10.dp)

/**
 * Settings page — single column with section headers.
 *
 * This round the values are actually consumed:
 * - 默认音质: cycles 标准/较高/极高/无损, writes [SettingsStore] and the caller
 *   syncs `MusicPlayer.defaultLevel` (takes effect on the next track);
 * - 唱片旋转 / 背景取色: read by the now-playing screen (and also editable there).
 *
 * Back behaviour is owned by the caller (CloudMusicApp) via BackHandler.
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    accountLabel: String,
    onAudioQualityChanged: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var quality by remember { mutableStateOf(settings.audioQuality) }
    var rotationEnabled by remember { mutableStateOf(settings.rotationEnabled) }
    var backgroundTintEnabled by remember { mutableStateOf(settings.backgroundTintEnabled) }
    var cacheBytes by remember { mutableStateOf(settings.cacheSizeBytes) }

    val firstRowRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRowRequester.requestFocus() }
            .onFailure { Log.w("SettingsScreen", "设置页首个开关焦点请求失败", it) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CloudMusicDimens.PageHorizontalPadding,
            end = CloudMusicDimens.PageHorizontalPadding,
            top = 24.dp,
            bottom = 64.dp,
        ),
    ) {
        item {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = CloudMusicColors.OnDark,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // ---- 播放 ----
        item { SectionHeader("播放") }
        item {
            ChoiceRow(
                label = "默认音质",
                value = quality,
                hint = "按确认切换，下一首生效",
                onClick = {
                    quality = PlayerQuality.next(quality)
                    onAudioQualityChanged(quality)
                },
                focusRequester = firstRowRequester,
            )
        }

        // ---- 外观 ----
        item { SectionHeader("外观") }
        item {
            ToggleRow(
                label = "唱片旋转",
                checked = rotationEnabled,
                onToggle = {
                    rotationEnabled = !rotationEnabled
                    settings.rotationEnabled = rotationEnabled
                },
            )
        }
        item {
            ToggleRow(
                label = "背景取色",
                checked = backgroundTintEnabled,
                onToggle = {
                    backgroundTintEnabled = !backgroundTintEnabled
                    settings.backgroundTintEnabled = backgroundTintEnabled
                },
            )
        }

        // ---- 存储 ----
        item { SectionHeader("存储") }
        item { InfoRow(label = "缓存", value = formatBytes(cacheBytes)) }
        item {
            ActionRow(
                label = "清理缓存",
                hint = "占位",
                onClick = {
                    settings.clearCachePlaceholder()
                    cacheBytes = settings.cacheSizeBytes
                },
            )
        }

        // ---- 账号 ----
        item { SectionHeader("账号") }
        item { InfoRow(label = "当前账号", value = accountLabel) }
        item { ActionRow(label = "退出登录", hint = null, onClick = onLogout) }

        // ---- 关于 ----
        item { SectionHeader("关于") }
        item { InfoRow(label = "版本", value = BuildConfig.VERSION_NAME) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = CloudMusicColors.SectionHeader,
        modifier = Modifier.padding(top = 28.dp, bottom = 8.dp, start = 8.dp),
    )
}

/** Non-interactive row: shows a value, never takes focus. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = CloudMusicColors.OnDark,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = CloudMusicColors.NavUnselectedText,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    SettingRow(
        focusRequester = focusRequester,
        onClick = onToggle,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = CloudMusicColors.OnDark,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (checked) "开" else "关",
            style = MaterialTheme.typography.bodyLarge,
            color = CloudMusicColors.NavUnselectedText,
            modifier = Modifier.padding(end = 16.dp),
        )
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (checked) CloudMusicColors.NavAccent else Color(0xFF3A3640)),
        ) {
            Box(
                modifier = Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(3.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/** 可循环切换值的设置行（默认音质）。 */
@Composable
private fun ChoiceRow(
    label: String,
    value: String,
    hint: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    SettingRow(
        focusRequester = focusRequester,
        onClick = onClick,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = CloudMusicColors.OnDark,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = CloudMusicColors.SectionHeader,
                modifier = Modifier.padding(end = 14.dp),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = CloudMusicColors.NavUnselectedText,
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = CloudMusicColors.NavUnselectedText,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    hint: String?,
    onClick: () -> Unit,
) {    SettingRow(onClick = onClick) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = CloudMusicColors.OnDark,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = CloudMusicColors.SectionHeader,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = CloudMusicColors.NavUnselectedText,
        )
    }
}

/**
 * Shared focusable row container: background + 2dp outline when focused.
 * The focus ring is what makes the D-pad position visible on a TV.
 */
@Composable
private fun SettingRow(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    var rowModifier: Modifier = Modifier
        .fillMaxWidth()
        .clip(RowShape)
        .background(
            if (focused) CloudMusicColors.RowFocusedBackground else Color.Transparent,
            RowShape,
        )
        .then(if (focused) Modifier.border(2.dp, Color(0x66FFFFFF), RowShape) else Modifier)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )

    Row(
        modifier = rowModifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
