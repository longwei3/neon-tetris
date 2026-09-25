package com.neon.tetris.game

/** 七种方块。ordinal 用于棋盘编码与颜色索引。 */
enum class Piece { I, J, L, O, S, T, Z }

/**
 * 标准 SRS（Super Rotation System）方块定义与踢墙表。
 *
 * 每种方块有 4 个旋转态，每个旋转态用扁平 `IntArray` 表示 4 个格的偏移：
 * `[x0,y0, x1,y1, x2,y2, x3,y3]`，坐标是包围盒内的格偏移，**y 向下为正**。
 * 用扁平数组而非对象列表，是为了在 60fps 的碰撞检测里做到零分配。
 *
 * 踢墙表同样遵循 SRS 官方数据。原始资料以 y 向上为正，这里已统一取反，
 * 因此表里的 y 与方块坐标同向（向下为正）。
 */
object Pieces {

    @JvmField
    val SHAPES: Array<Array<IntArray>> = arrayOf(
        // I —— 使用 4x4 包围盒
        arrayOf(
            intArrayOf(0, 1, 1, 1, 2, 1, 3, 1),
            intArrayOf(2, 0, 2, 1, 2, 2, 2, 3),
            intArrayOf(0, 2, 1, 2, 2, 2, 3, 2),
            intArrayOf(1, 0, 1, 1, 1, 2, 1, 3)
        ),
        // J
        arrayOf(
            intArrayOf(0, 0, 0, 1, 1, 1, 2, 1),
            intArrayOf(1, 0, 2, 0, 1, 1, 1, 2),
            intArrayOf(0, 1, 1, 1, 2, 1, 2, 2),
            intArrayOf(1, 0, 1, 1, 0, 2, 1, 2)
        ),
        // L
        arrayOf(
            intArrayOf(2, 0, 0, 1, 1, 1, 2, 1),
            intArrayOf(1, 0, 1, 1, 1, 2, 2, 2),
            intArrayOf(0, 1, 1, 1, 2, 1, 0, 2),
            intArrayOf(0, 0, 1, 0, 1, 1, 1, 2)
        ),
        // O —— 四个旋转态形状相同，旋转时视觉上无变化
        arrayOf(
            intArrayOf(1, 0, 2, 0, 1, 1, 2, 1),
            intArrayOf(1, 0, 2, 0, 1, 1, 2, 1),
            intArrayOf(1, 0, 2, 0, 1, 1, 2, 1),
            intArrayOf(1, 0, 2, 0, 1, 1, 2, 1)
        ),
        // S
        arrayOf(
            intArrayOf(1, 0, 2, 0, 0, 1, 1, 1),
            intArrayOf(1, 0, 1, 1, 2, 1, 2, 2),
            intArrayOf(1, 1, 2, 1, 0, 2, 1, 2),
            intArrayOf(0, 0, 0, 1, 1, 1, 1, 2)
        ),
        // T
        arrayOf(
            intArrayOf(1, 0, 0, 1, 1, 1, 2, 1),
            intArrayOf(1, 0, 1, 1, 2, 1, 1, 2),
            intArrayOf(0, 1, 1, 1, 2, 1, 1, 2),
            intArrayOf(1, 0, 0, 1, 1, 1, 1, 2)
        ),
        // Z
        arrayOf(
            intArrayOf(0, 0, 1, 0, 1, 1, 2, 1),
            intArrayOf(2, 0, 1, 1, 2, 1, 1, 2),
            intArrayOf(0, 1, 1, 1, 1, 2, 2, 2),
            intArrayOf(1, 0, 0, 1, 1, 1, 0, 2)
        )
    )

    /** 各方块包围盒边长，索引与 [Piece.ordinal] 对应。 */
    @JvmField
    val BOX = intArrayOf(4, 3, 3, 3, 3, 3, 3)

    /**
     * JLSTZ 的踢墙表，用 `[起始旋转态][目标旋转态]` 索引。
     * 大部分组合是非法转向（旋转只能 ±1），用只含 (0,0) 的占位表填充。
     */
    private val KICKS_JLSTZ: Array<Array<IntArray>> = arrayOf(
        // from 0
        arrayOf(
            intArrayOf(0, 0),
            intArrayOf(0, 0, -1, 0, -1, -1, 0, 2, -1, 2),
            intArrayOf(0, 0),
            intArrayOf(0, 0, 1, 0, 1, -1, 0, 2, 1, 2)
        ),
        // from 1
        arrayOf(
            intArrayOf(0, 0, 1, 0, 1, 1, 0, -2, 1, -2),
            intArrayOf(0, 0),
            intArrayOf(0, 0, 1, 0, 1, 1, 0, -2, 1, -2),
            intArrayOf(0, 0)
        ),
        // from 2
        arrayOf(
            intArrayOf(0, 0),
            intArrayOf(0, 0, -1, 0, -1, -1, 0, 2, -1, 2),
            intArrayOf(0, 0),
            intArrayOf(0, 0, 1, 0, 1, -1, 0, 2, 1, 2)
        ),
        // from 3
        arrayOf(
            intArrayOf(0, 0, -1, 0, -1, 1, 0, -2, -1, -2),
            intArrayOf(0, 0),
            intArrayOf(0, 0, -1, 0, -1, 1, 0, -2, -1, -2),
            intArrayOf(0, 0)
        )
    )

    /** I 方块有独立的踢墙表，位移更大。 */
    private val KICKS_I: Array<Array<IntArray>> = arrayOf(
        // from 0
        arrayOf(
            intArrayOf(0, 0),
            intArrayOf(0, 0, -2, 0, 1, 0, -2, 1, 1, -2),
            intArrayOf(0, 0),
            intArrayOf(0, 0, -1, 0, 2, 0, -1, -2, 2, 1)
        ),
        // from 1
        arrayOf(
            intArrayOf(0, 0, 2, 0, -1, 0, 2, -1, -1, 2),
            intArrayOf(0, 0),
            intArrayOf(0, 0, -1, 0, 2, 0, -1, -2, 2, 1),
            intArrayOf(0, 0)
        ),
        // from 2
        arrayOf(
            intArrayOf(0, 0),
            intArrayOf(0, 0, 1, 0, -2, 0, 1, 2, -2, -1),
            intArrayOf(0, 0),
            intArrayOf(0, 0, 2, 0, -1, 0, 2, -1, -1, 2)
        ),
        // from 3
        arrayOf(
            intArrayOf(0, 0, 1, 0, -2, 0, 1, 2, -2, -1),
            intArrayOf(0, 0),
            intArrayOf(0, 0, -2, 0, 1, 0, -2, 1, 1, -2),
            intArrayOf(0, 0)
        )
    )

    /** 返回从 [from] 转到 [to] 时需要依次尝试的偏移，格式同方块定义（扁平 x,y 交替）。 */
    fun kicks(piece: Piece, from: Int, to: Int): IntArray =
        if (piece == Piece.I) KICKS_I[from][to] else KICKS_JLSTZ[from][to]
}
