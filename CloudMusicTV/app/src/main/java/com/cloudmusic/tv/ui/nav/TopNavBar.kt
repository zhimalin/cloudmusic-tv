package com.cloudmusic.tv.ui.nav

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.tv.R
import com.cloudmusic.tv.ui.MainTab
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import com.cloudmusic.tv.ui.theme.CloudMusicDimens

/**
 * Top navigation: five D-pad targets (four tabs + the settings gear).
 *
 * Focus policy: the first tab requests focus when the bar enters the
 * composition, so entering the main screen (or coming back from settings)
 * always lands on an operable item.
 */
@Composable
fun TopNavBar(
    tabs: List<MainTab>,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstTabRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstTabRequester.requestFocus() }
            .onFailure { Log.w("TopNavBar", "顶部导航首个 tab 焦点请求失败", it) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CloudMusicDimens.NavBarHeight)
            .padding(horizontal = CloudMusicDimens.PageHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            NavItem(
                label = tab.label,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = if (index == 0) {
                    Modifier.focusRequester(firstTabRequester)
                } else {
                    Modifier
                },
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        SettingsEntry(onClick = onOpenSettings)
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        label = "navItemScale",
    )
    val shape = RoundedCornerShape(10.dp)

    val textColor = when {
        focused -> Color.White
        selected -> CloudMusicColors.OnDark
        else -> CloudMusicColors.NavUnselectedText
    }
    // Selected = red accent bar; focused-but-not-selected = white bar so the
    // D-pad position is always visible.
    val indicatorColor = when {
        selected -> CloudMusicColors.NavAccent
        focused -> Color.White
        else -> Color.Transparent
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) CloudMusicColors.NavFocusedBackground else Color.Transparent, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(CloudMusicDimens.NavIndicatorHeight)
                .background(indicatorColor, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun SettingsEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        label = "settingsEntryScale",
    )
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) CloudMusicColors.NavFocusedBackground else Color.Transparent, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp)
            .semantics { contentDescription = "设置" },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_settings),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                if (focused) Color.White else CloudMusicColors.NavUnselectedText,
            ),
            modifier = Modifier.size(26.dp),
        )
    }
}
