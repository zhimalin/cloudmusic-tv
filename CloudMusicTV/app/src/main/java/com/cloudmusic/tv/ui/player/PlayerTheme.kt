package com.cloudmusic.tv.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cloudmusic.tv.ui.theme.CloudMusicColors

/**
 * 播放页尺寸。基准是**实测的 960×540 dp**（`wm size 1920×1080 px` / `density 320`）。
 *
 * 布局预算（540dp 高）：
 * ```
 *  控制栏 72
 *  ├─ 进度行 16
 *  └─ 控制行 56（播放按钮 56）
 *  内容区 468
 *    ├─ 上边距 24 / 下边距 8
 *    ├─ 右侧歌词栏：标题 54 + 歌词区 240（8 行 × 30，垂直居中）
 *    └─ 左侧唱片区：宽 400（=880 - 48 - 432）
 * ```
 * 歌词 8 行 × 30 = 240；有翻译的行再加 18dp，因此翻译密集的歌词可见行数会少一些。
 * 当前行放在歌词区 40% 高度处。
 */
object PlayerDimens {

    /** 播放页左右安全边距（方案要求 40dp，和主页面 64dp 区分开）。 */
    val SafeHorizontal = 40.dp

    /** 唱片区与歌词区的间距。 */
    val ContentGap = 48.dp

    /** 右侧歌词栏宽度：960 - 40*2 - 48 - 400 = 432。 */
    val LyricsWidth = 432.dp

    /** 底部控制栏高度。 */
    val ControlBarHeight = 72.dp

    /** 进度行高度（时间文字 + 4dp 进度条）。 */
    val ProgressRowHeight = 16.dp

    /** 控制行高度；播放按钮 56dp 正好占满。 */
    val TransportRowHeight = 56.dp

    /** 歌词单行高度（原文行）。 */
    val LyricLineHeight = 30.dp

    /** 有翻译时额外增加的高度。 */
    val LyricTranslationHeight = 18.dp

    /** 黑胶直径。 */
    val VinylSize = 260.dp

    /** 黑胶中央封面边长。 */
    val CoverSize = 160.dp

    /** 播放/暂停按钮直径。 */
    val PlayButtonSize = 56.dp

    /** 播放队列抽屉宽度。 */
    val QueueDrawerWidth = 320.dp

    /** 全局迷你播放条高度。 */
    val MiniPlayerHeight = 64.dp

    /** 歌词可视行数（产品规格）。 */
    const val LyricVisibleLines = 8

    /**
     * 歌词可视区高度 = 8 × 30dp = 240dp。
     *
     * 有翻译的行会额外占 18dp，所以「全翻译」歌词实际可见行数会少于 8；
     * 原文行（大多数中文歌词）严格 8 行。可行区在右侧栏里垂直居中，
     * 上下用渐隐遮罩收边。
     */
    val LyricsViewportHeight = LyricLineHeight * LyricVisibleLines.toFloat()

    /** 唱片转速：9 秒一圈。 */
    const val VinylRotationSeconds = 9f

    /** 背景主色切换的过渡时长。 */
    const val BackgroundTransitionMs = 600
}

/** 播放页专用色（其余沿用 [CloudMusicColors]）。 */
object PlayerColors {

    /** 没有封面主色时的默认背景（比页面底色略亮一点，避免纯黑）。 */
    val DefaultTint = Color(0xFF241F2A)

    /** 控制栏压在渐变上的半透明底色。 */
    val ControlBarScrim = Color(0x66000000)

    /** 抽屉 / 更多菜单面板。 */
    val Panel = Color(0xF218161D)

    val PanelBorder = Color(0x1FFFFFFF)

    val Error = Color(0xFFFF9A9A)

    val ChipBackground = Color(0x26EC4141)
}
