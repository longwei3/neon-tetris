package com.neon.tetris.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.neon.tetris.game.Piece

/**
 * 全局配色。
 *
 * 整体走「霓虹」路线：底色是深靛蓝而不是纯黑，靠多层彩色光晕撑起氛围；
 * 方块颜色取高亮度高饱和的色相，叠上辉光后互相映照，避免整屏发闷。
 */
object Neon {
    // 背景与棋盘。
    // 这几个值刻意比「近黑」亮一档：屏幕大部分面积是它们，压太暗的话
    // 即使方块很鲜艳，整体第一眼仍然是灰暗的。
    val BgTop = Color(0xFF1E2E60)
    val BgDeep = Color(0xFF0B1024)
    val BoardTop = Color(0xFF24376F)
    val BoardBottom = Color(0xFF141D45)
    val GridLine = Color(0xFF7BA4FF)

    // 面板
    val PanelTop = Color(0x1AFFFFFF)
    val PanelBottom = Color(0x08FFFFFF)
    val Border = Color(0x24FFFFFF)
    val TextPrimary = Color(0xFFF2F5FC)
    val TextMuted = Color(0xFF98A6C8)

    // 霓虹主色
    val Cyan = Color(0xFF22D3EE)
    val Purple = Color(0xFFA855F7)
    val Pink = Color(0xFFF472B6)
    val Green = Color(0xFF4ADE80)
    val Amber = Color(0xFFFBBF24)
    val Blue = Color(0xFF3B82F6)
    val Red = Color(0xFFFB7185)
    val Yellow = Color(0xFFFDE047)

    // 马卡龙色系：高明度、**低饱和**的奶油质感。
    //
    // 色相刻意和方块有重叠（薰衣草对紫 T、薄荷对绿 S、天蓝对蓝 J、蜜桃对橙 L），
    // 但靠两点和方块区分开：
    //   1. 饱和度低得多 —— 方块是高饱和 + 亮面渐变 + 辉光，这里是纯色哑光；
    //   2. 形状是胶囊，方块是圆角方块。
    // 这样色相虽然相邻，一眼看过去仍是「界面控件」而不是「游戏棋子」。
    val MacaronLavender = Color(0xFFE4DAF7)
    val MacaronMint = Color(0xFFCDEEDC)
    val MacaronSky = Color(0xFFCFE3F7)
    val MacaronPeach = Color(0xFFFBE0C8)

    /** 马卡龙底色上的深色墨，保证对比度。 */
    val MacaronInk = Color(0xFF2B2450)

    /** 标题与主按钮的高光渐变。 */
    val title = Brush.linearGradient(listOf(Cyan, Purple, Pink))
}

/** 向白色靠拢。 */
internal fun Color.lighten(fraction: Float): Color = lerp(this, Color.White, fraction)

/** 向黑色靠拢。 */
internal fun Color.darken(fraction: Float): Color = lerp(this, Color.Black, fraction)

/** 方块序号（Piece.ordinal）到颜色。取亮色系，叠辉光后更「霓虹」。 */
fun pieceColor(ordinal: Int): Color = when (ordinal) {
    Piece.I.ordinal -> Neon.Cyan
    Piece.J.ordinal -> Color(0xFF4F8DFD)
    Piece.L.ordinal -> Color(0xFFFB923C)
    Piece.O.ordinal -> Neon.Yellow
    Piece.S.ordinal -> Neon.Green
    Piece.T.ordinal -> Color(0xFFC084FC)
    else -> Neon.Red
}

@Composable
fun NeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Cyan,
            secondary = Neon.Purple,
            background = Neon.BgDeep,
            surface = Neon.BgDeep,
            onPrimary = Color(0xFF04121A),
            onBackground = Neon.TextPrimary,
            onSurface = Neon.TextPrimary,
        ),
        content = content
    )
}
