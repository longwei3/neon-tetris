package com.neon.tetris.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.neon.tetris.game.GameEngine
import com.neon.tetris.game.Piece
import com.neon.tetris.game.Pieces

/**
 * 全部绘制逻辑。走 [DrawScope] 直绘，不生成任何组合项，
 * 因此每帧重绘的代价只有 GPU 光栅化，没有重组开销。
 *
 * 颜色工具 [lighten] / [darken] 定义在 Theme.kt，供本文件与界面共用。
 */

/** 幽灵方块的不透明度。棋盘底色变亮后要相应提高，否则看不清落点。 */
private const val GHOST_ALPHA = 0.42f

/**
 * 整个页面的氛围背景：深靛蓝底 + 三处彩色光晕。
 *
 * 这层不读任何状态，只在首次布局与尺寸变化时绘制一次，不参与每帧重绘。
 * 光晕给得比较重是有意的 —— 底色本身偏暗，靠这几层彩光撑起整体的色彩感。
 */
fun DrawScope.drawAmbientBackground() {
    val w = size.width
    val h = size.height

    drawRect(Brush.verticalGradient(listOf(Neon.BgTop, Neon.BgDeep)))

    // 左上青色光晕
    drawRect(
        Brush.radialGradient(
            colors = listOf(Neon.Cyan.copy(alpha = 0.42f), Color.Transparent),
            center = Offset(w * 0.10f, h * -0.02f),
            radius = w * 1.15f
        )
    )
    // 右下紫色光晕
    drawRect(
        Brush.radialGradient(
            colors = listOf(Neon.Purple.copy(alpha = 0.48f), Color.Transparent),
            center = Offset(w * 0.94f, h * 0.80f),
            radius = w * 1.25f
        )
    )
    // 左下品红，补暖色避免整屏偏冷
    drawRect(
        Brush.radialGradient(
            colors = listOf(Neon.Pink.copy(alpha = 0.30f), Color.Transparent),
            center = Offset(w * 0.0f, h * 1.02f),
            radius = w * 1.05f
        )
    )
    // 右上再来一层蓝色，让四个角都有颜色
    drawRect(
        Brush.radialGradient(
            colors = listOf(Neon.Blue.copy(alpha = 0.26f), Color.Transparent),
            center = Offset(w * 1.0f, h * 0.08f),
            radius = w * 0.95f
        )
    )
}

/**
 * 棋盘背后的光晕。
 *
 * 画在棋盘外面（父容器尺寸），所以不会被棋盘的圆角裁掉 ——
 * 作用是让深色棋盘从同样是深色的背景里「浮」起来，而不是糊成一片。
 */
fun DrawScope.drawBoardHalo() {
    drawRect(
        Brush.radialGradient(
            colors = listOf(
                Neon.Cyan.copy(alpha = 0.22f),
                Neon.Purple.copy(alpha = 0.14f),
                Color.Transparent
            ),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.height * 0.45f
        )
    )
}

/**
 * 画一个方块。
 *
 * [glow] 是外辉光强度（0 = 不发光）。正在下落的方块给 1，已落定的给较小的值，
 * 这样整摞方块会互相映照出霓虹感，而不是一堆孤立的色块。
 * [ghost] 为 true 时只画半透明轮廓（落点预览）。
 */
fun DrawScope.drawBlock(
    left: Float,
    top: Float,
    size: Float,
    color: Color,
    ghost: Boolean = false,
    alpha: Float = 1f,
    glow: Float = 0f
) {
    val pad = size * 0.045f
    val x = left + pad
    val y = top + pad
    val s = size - pad * 2f
    val r = size * 0.16f

    if (ghost) {
        drawRoundRect(
            color = color.copy(alpha = GHOST_ALPHA * alpha),
            topLeft = Offset(x, y),
            size = Size(s, s),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = size * 0.09f)
        )
        return
    }

    // 外辉光：先铺一层放大的同色圆角矩形
    if (glow > 0f) {
        val g = size * 0.11f * glow
        drawRoundRect(
            color = color.copy(alpha = 0.30f * glow * alpha),
            topLeft = Offset(x - g, y - g),
            size = Size(s + g * 2f, s + g * 2f),
            cornerRadius = CornerRadius(r + g, r + g)
        )
    }

    // 主体：亮顶 -> 饱和中段 -> 暗底，三段渐变让方块有体积感
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(color.lighten(0.38f), color, color.darken(0.48f)),
            startY = y,
            endY = y + s
        ),
        topLeft = Offset(x, y),
        size = Size(s, s),
        cornerRadius = CornerRadius(r, r),
        alpha = alpha
    )

    // 顶部高光
    drawRoundRect(
        color = Color.White.copy(alpha = 0.46f * alpha),
        topLeft = Offset(x + s * 0.15f, y + s * 0.11f),
        size = Size(s * 0.70f, s * 0.20f),
        cornerRadius = CornerRadius(r * 0.6f, r * 0.6f)
    )

    // 内描边：一圈更亮的同色细边，让方块边缘「起光」
    drawRoundRect(
        color = color.lighten(0.62f).copy(alpha = 0.55f * alpha),
        topLeft = Offset(x, y),
        size = Size(s, s),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = size * 0.045f)
    )
}

/** 绘制整个棋盘：渐变底、网格、已固定方块、消行闪光、幽灵与当前方块。 */
fun DrawScope.drawBoard(engine: GameEngine) {
    val cols = GameEngine.COLS
    val rows = GameEngine.ROWS
    val hidden = GameEngine.HIDDEN
    val cell = size.width / cols
    val w = size.width
    val h = size.height

    drawRect(Brush.verticalGradient(listOf(Neon.BoardTop, Neon.BoardBottom)))

    val gridColor = Neon.GridLine.copy(alpha = 0.20f)
    for (x in 1 until cols) {
        val px = x * cell
        drawLine(gridColor, Offset(px, 0f), Offset(px, h), strokeWidth = 1f)
    }
    for (y in 1 until rows) {
        val py = y * cell
        drawLine(gridColor, Offset(0f, py), Offset(w, py), strokeWidth = 1f)
    }

    // 已固定的方块
    for (y in hidden until GameEngine.TOTAL_ROWS) {
        val rowBase = y * cols
        val top = (y - hidden) * cell
        for (x in 0 until cols) {
            val v = engine.cells[rowBase + x]
            if (v != 0) drawBlock(x * cell, top, cell, pieceColor(v - 1), glow = 0.45f)
        }
    }

    // 消行白光
    if (engine.clearingRows.isNotEmpty()) {
        val k = (1f - engine.clearingElapsed / GameEngine.CLEAR_MS.toFloat()).coerceIn(0f, 1f)
        for (row in engine.clearingRows) {
            drawRect(
                color = Color.White.copy(alpha = k),
                topLeft = Offset(0f, (row - hidden) * cell),
                size = Size(w, cell)
            )
            // 消行时横向扫出的青色光带
            drawRect(
                color = Neon.Cyan.copy(alpha = k * 0.7f),
                topLeft = Offset(0f, (row - hidden) * cell),
                size = Size(w, cell * 0.35f)
            )
        }
    }

    // 幽灵 + 当前方块
    val cur = engine.current
    if (cur != null && engine.clearingRows.isEmpty()) {
        val color = pieceColor(cur.piece.ordinal)
        val shape = Pieces.SHAPES[cur.piece.ordinal][cur.rot]

        val ghostY = engine.ghostY()
        if (ghostY != cur.y) {
            var i = 0
            while (i < shape.size) {
                val y = ghostY + shape[i + 1]
                if (y >= hidden) {
                    drawBlock(
                        (cur.x + shape[i]) * cell, (y - hidden) * cell, cell,
                        color, ghost = true
                    )
                }
                i += 2
            }
        }

        var i = 0
        while (i < shape.size) {
            val y = cur.y + shape[i + 1]
            if (y >= hidden) {
                drawBlock(
                    (cur.x + shape[i]) * cell, (y - hidden) * cell, cell,
                    color, glow = 1f
                )
            }
            i += 2
        }
    }

    // 霓虹边框：多层由外向内递减的描边，模拟从边缘内散的光晕
    val corner = w * 0.06f
    val rim = listOf(0.6f to 0.95f, 2.6f to 0.42f, 5.5f to 0.22f, 9.5f to 0.10f)
    for ((inset, a) in rim) {
        val cr = (corner - inset * 0.8f).coerceAtLeast(0f)
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Neon.Cyan, Neon.Purple)),
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2f, h - inset * 2f),
            cornerRadius = CornerRadius(cr, cr),
            style = Stroke(width = 1.6f),
            alpha = a
        )
    }
}

/** 在指定矩形内居中绘制一个方块（旋转态 0），用于 HOLD / NEXT 预览。 */
fun DrawScope.drawPieceInBox(
    piece: Piece,
    left: Float,
    top: Float,
    boxWidth: Float,
    boxHeight: Float,
    cell: Float
) {
    val shape = Pieces.SHAPES[piece.ordinal][0]
    var minX = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var minY = Int.MAX_VALUE
    var maxY = Int.MIN_VALUE
    var i = 0
    while (i < shape.size) {
        val x = shape[i]
        val y = shape[i + 1]
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        i += 2
    }
    val pw = (maxX - minX + 1) * cell
    val ph = (maxY - minY + 1) * cell
    val ox = left + (boxWidth - pw) / 2f - minX * cell
    val oy = top + (boxHeight - ph) / 2f - minY * cell

    var j = 0
    while (j < shape.size) {
        drawBlock(
            ox + shape[j] * cell, oy + shape[j + 1] * cell, cell,
            pieceColor(piece.ordinal), glow = 0.5f
        )
        j += 2
    }
}

/** 横向排列引擎里接下来的 [count] 个方块，用于顶部 NEXT 预览。 */
fun DrawScope.drawNextStrip(engine: GameEngine, count: Int) {
    if (count <= 0) return
    val slot = size.width / count
    val cell = minOf(slot, size.height) / 4.4f
    for (index in 0 until count) {
        val piece = engine.nextAt(index) ?: continue
        drawPieceInBox(piece, index * slot, 0f, slot, size.height, cell)
    }
}

/** 单个方块的预览框（HOLD 用）。 */
fun DrawScope.drawSinglePreview(piece: Piece?) {
    if (piece == null) return
    val cell = minOf(size.width, size.height) / 4.4f
    drawPieceInBox(piece, 0f, 0f, size.width, size.height, cell)
}
