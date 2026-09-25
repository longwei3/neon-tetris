package com.neon.tetris.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

    /** 标题与主按钮的高光渐变。 */
    val title = Brush.linearGradient(listOf(Cyan, Purple, Pink))
}

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
