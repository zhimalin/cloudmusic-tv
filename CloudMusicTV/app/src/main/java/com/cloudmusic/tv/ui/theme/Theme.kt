package com.cloudmusic.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * App palette. The canvas color is fixed to `#0E0D11` by the product spec.
 *
 * NOTE: typography and shapes are still the tv-material defaults; the real
 * design pass has not happened yet.
 */
object CloudMusicColors {
    val Background = Color(0xFF0E0D11)

    /** Slightly lifted surface used for cards when a real image is not available yet. */
    val Surface = Color(0xFF17161C)

    val OnDark = Color(0xFFF5F4F7)

    // Top navigation.
    val NavAccent = Color(0xFFEC4141)
    val NavUnselectedText = Color(0xFFC7BFC4)
    val NavFocusedBackground = Color(0x1FFFFFFF)

    // Settings page.
    val SectionHeader = Color(0xFF8A8288)
    val RowFocusedBackground = Color(0xFF221F27)
    val Divider = Color(0x1FFFFFFF)

    // Temporary tile colors for the focus-system smoke test.
    val TileRed = Color(0xFFE8474B)
    val TileBlue = Color(0xFF3D7BFF)
    val TileGreen = Color(0xFF18B98A)
    val TileAmber = Color(0xFFE8A23B)
    val TileViolet = Color(0xFF8A5CF6)
    val TileTeal = Color(0xFF2BB3C0)
}

/** Shared layout metrics, so the safe margins stay in one place. */
object CloudMusicDimens {
    /** Horizontal safe margin for page content (spec: 64dp). */
    val PageHorizontalPadding = 64.dp

    /** Top navigation bar height (spec: 88dp). */
    val NavBarHeight = 88.dp

    /** Height of the accent bar under a nav item (spec: 3dp). */
    val NavIndicatorHeight = 3.dp

    /** Stroke width of the focused tile outline (spec: 2dp). */
    val TileFocusedBorder = 2.dp

    /** Focus scale of a tile (spec: 1.05x). */
    const val TileFocusedScale = 1.05f
}

private val CloudMusicDarkColorScheme = darkColorScheme(
    primary = CloudMusicColors.NavAccent,
    onPrimary = Color.White,
    background = CloudMusicColors.Background,
    onBackground = CloudMusicColors.OnDark,
    surface = CloudMusicColors.Surface,
    onSurface = CloudMusicColors.OnDark,
    surfaceVariant = CloudMusicColors.Surface,
    onSurfaceVariant = CloudMusicColors.OnDark,
    border = Color.White,
)

/**
 * TV-only Material3 theme (androidx.tv.material3).
 *
 * Deliberately dark-only: a TV is always used in a dim room and the product
 * spec pins the background to `#0E0D11`.
 */
@Composable
fun CloudMusicTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CloudMusicDarkColorScheme,
        content = content,
    )
}
