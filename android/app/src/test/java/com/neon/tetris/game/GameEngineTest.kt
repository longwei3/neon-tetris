package com.neon.tetris.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 游戏规则回归测试。
 *
 * [GameEngine] 刻意做成不依赖任何 Android / Compose API 的纯 Kotlin 类，
 * 所以这些规则可以在 JVM 上以毫秒级速度跑完，不需要模拟器或真机。
 */
class GameEngineTest {

    private fun engine(seed: Int = 42): GameEngine =
        GameEngine(Random(seed)).also { it.start() }

    /** 只装备好出块队列、不生成方块，用于观察原始随机器序列。 */
    private fun freshEngine(seed: Int): GameEngine =
        GameEngine(Random(seed)).also { it.reset() }

    private val bottomRow = GameEngine.TOTAL_ROWS - 1

    // ------------------------------------------------------------ 随机器

    @Test
    fun `七个方块构成一袋且互不重复`() {
        val e = freshEngine(7)
        val bag = (0 until 7).map { e.nextAt(it) }
        assertTrue("队列不应为空", bag.all { it != null })
        assertEquals("一袋里 7 种方块各出现一次", 7, bag.toSet().size)
    }

    @Test
    fun `十四次抽取中每种方块恰好出现两次`() {
        val e = freshEngine(11)
        val counts = (0 until 14).mapNotNull { e.nextAt(it) }.groupingBy { it }.eachCount()
        assertEquals(Piece.entries.size, counts.size)
        assertTrue("每种方块应为 2 次，实际 $counts", counts.values.all { it == 2 })
    }

    // ------------------------------------------------------------ 棋盘读写

    @Test
    fun `棋盘行列索引可正确往返`() {
        val e = engine()
        e.setCell(0, 0, Piece.T)
        e.setCell(9, bottomRow, Piece.S)
        assertEquals(Piece.T, e.cellAt(0, 0))
        assertEquals(Piece.S, e.cellAt(9, bottomRow))
        assertNull(e.cellAt(5, 10))
    }

    // ------------------------------------------------------------ 下落与锁定

    @Test
    fun `重力作用下方块随时间下落`() {
        val e = engine()
        e.spawnPiece(Piece.O)
        val y0 = e.current!!.y
        e.update(GameEngine.GRAVITY_MS[0])
        assertEquals("一级下落一格", y0 + 1, e.current!!.y)
    }

    @Test
    fun `硬降后方块停在底部`() {
        val e = engine()
        e.spawnPiece(Piece.O)
        e.hardDrop()

        assertEquals(Piece.O, e.cellAt(4, bottomRow))
        assertEquals(Piece.O, e.cellAt(5, bottomRow))
        assertEquals(Piece.O, e.cellAt(4, bottomRow - 1))
        assertEquals(Piece.O, e.cellAt(5, bottomRow - 1))
    }

    @Test
    fun `幽灵方块给出真实落点`() {
        val e = engine()
        e.spawnPiece(Piece.O)
        val ghost = e.ghostY()
        // 幽灵位置再下移一格必然碰撞
        assertTrue(e.collides(Piece.O, 0, e.current!!.x, ghost + 1))
        assertFalse(e.collides(Piece.O, 0, e.current!!.x, ghost))
    }

    // ------------------------------------------------------------ 旋转与踢墙

    @Test
    fun `贴右墙旋转时踢墙后仍在界内`() {
        val e = engine()
        e.spawnPiece(Piece.I)
        e.rotate(1) // 竖起来
        repeat(12) { e.move(1) } // 顶到最右

        assertTrue("贴墙旋转应通过踢墙完成", e.rotate(1))

        val c = e.current!!
        val shape = Pieces.SHAPES[c.piece.ordinal][c.rot]
        var i = 0
        while (i < shape.size) {
            val x = c.x + shape[i]
            assertTrue("列 $x 越界", x in 0 until GameEngine.COLS)
            i += 2
        }
    }

    @Test
    fun `每种方块四个旋转态都落在包围盒内`() {
        for (piece in Piece.entries) {
            val box = Pieces.BOX[piece.ordinal]
            for (rot in 0 until 4) {
                val shape = Pieces.SHAPES[piece.ordinal][rot]
                assertEquals("$piece 旋转态 $rot 应有 4 个格", 8, shape.size)
                var i = 0
                while (i < shape.size) {
                    assertTrue("$piece/$rot 的 x=${shape[i]} 超出包围盒", shape[i] in 0 until box)
                    assertTrue("$piece/$rot 的 y=${shape[i + 1]} 超出包围盒", shape[i + 1] in 0 until box)
                    i += 2
                }
            }
        }
    }

    // ------------------------------------------------------------ 消行与计分

    @Test
    fun `消掉一行后计分并移除该行`() {
        val e = engine()
        // 底行只留第 4、5 列
        for (x in 0 until GameEngine.COLS) {
            if (x != 4 && x != 5) e.setCell(x, bottomRow, Piece.I)
        }
        e.spawnPiece(Piece.O)
        e.hardDrop()

        assertFalse("锁定后应进入消行动画", e.clearingRows.isEmpty())

        e.update(GameEngine.CLEAR_MS + 1)

        assertTrue("动画结束后应清空待消行", e.clearingRows.isEmpty())
        assertEquals(1, e.lines)
        // 硬降 18 格 = 36 分，单行消除 = 100 分
        assertEquals(136, e.score)
        // 上半截 O 随之上移一行落到底
        assertEquals(Piece.O, e.cellAt(4, bottomRow))
        assertEquals(Piece.O, e.cellAt(5, bottomRow))
    }

    @Test
    fun `连击计数随连续消行累加`() {
        val e = engine()
        repeat(2) {
            for (x in 0 until GameEngine.COLS) {
                if (x != 4 && x != 5) e.setCell(x, bottomRow, Piece.I)
            }
            e.spawnPiece(Piece.O)
            e.hardDrop()
            e.update(GameEngine.CLEAR_MS + 1)
        }
        assertEquals(2, e.lines)
        assertEquals("连续两次消行，连击应为 1", 1, e.combo)
    }

    @Test
    fun `每十行提升一级`() {
        val e = engine()
        repeat(10) {
            for (x in 0 until GameEngine.COLS) {
                if (x != 4 && x != 5) e.setCell(x, bottomRow, Piece.I)
            }
            e.spawnPiece(Piece.O)
            e.hardDrop()
            e.update(GameEngine.CLEAR_MS + 1)
        }
        assertEquals(10, e.lines)
        assertEquals("消满 10 行应升到 2 级", 2, e.level)
    }

    @Test
    fun `一次消四行额外记录 Tetris`() {
        val e = engine()
        // 底四行只留第 0 列，等一根竖 I 填进去
        for (y in bottomRow - 3..bottomRow) {
            for (x in 1 until GameEngine.COLS) e.setCell(x, y, Piece.I)
        }
        e.spawnPiece(Piece.I)
        e.rotate(1)
        repeat(GameEngine.COLS) { e.move(-1) }
        e.hardDrop()
        e.update(GameEngine.CLEAR_MS + 1)

        assertEquals(4, e.lines)
        assertEquals(1, e.tetrisCount)
        // 四行消除基础分 800
        assertTrue("四行消除得分应远高于单行，实际 ${e.score}", e.score >= 800)
    }

    // ------------------------------------------------------------ T-spin

    @Test
    fun `三角规则能识别 T-spin 单行`() {
        val e = engine()
        // 底行只留第 4 列
        for (x in 0 until GameEngine.COLS) {
            if (x != 4) e.setCell(x, bottomRow, Piece.I)
        }
        // 再补一个角，使 T 的四个斜角中有三个被占
        e.setCell(3, bottomRow - 2, Piece.I)

        e.spawnPiece(Piece.T)
        e.placeCurrent(rot = 2, x = 3, y = bottomRow - 2)
        e.markLastActionRotate()

        // 正下方被占，硬降距离为 0，直接锁定
        e.hardDrop()

        assertEquals("T-spin 不应被当成普通消除", 1, e.tspinCount)
        assertEquals(1, e.lines)
        // T-spin 单行基础分 800，且不计入硬降分
        assertEquals(800, e.score)
    }

    @Test
    fun `没有旋转就不会判定为 T-spin`() {
        val e = engine()
        for (x in 0 until GameEngine.COLS) {
            if (x != 4) e.setCell(x, bottomRow, Piece.I)
        }
        e.setCell(3, bottomRow - 2, Piece.I)

        e.spawnPiece(Piece.T)
        e.placeCurrent(rot = 2, x = 3, y = bottomRow - 2)
        // 故意不标记旋转
        e.hardDrop()

        assertEquals(0, e.tspinCount)
        assertEquals(1, e.lines)
        assertEquals("普通单行应为 100 分", 100, e.score)
    }

    // ------------------------------------------------------------ Hold

    @Test
    fun `暂存会交换方块且每个方块只能用一次`() {
        val e = engine()
        val first = e.current!!.piece

        e.hold()
        assertEquals(first, e.holdPiece)
        assertFalse("暂存后本回合不能再用", e.canHold)

        val second = e.current!!.piece
        e.hold() // 应当无效
        assertEquals(second, e.current!!.piece)
        assertEquals(first, e.holdPiece)
    }

    @Test
    fun `锁定后重新允许暂存`() {
        val e = engine()
        e.hold()
        assertFalse(e.canHold)

        e.spawnPiece(Piece.O)
        e.hardDrop()
        e.update(GameEngine.CLEAR_MS + 1)

        assertTrue("新方块出现后应恢复暂存能力", e.canHold)
    }

    // ------------------------------------------------------------ 结束判定

    @Test
    fun `出生点被占则游戏结束`() {
        val e = engine()
        for (y in 0 until GameEngine.HIDDEN + 3) {
            for (x in 0 until GameEngine.COLS) e.setCell(x, y, Piece.I)
        }
        e.spawnPiece(Piece.O)

        assertEquals(Phase.GAME_OVER, e.phase)
        assertNull(e.current)
        assertTrue(e.drainEvents().contains(GameEvent.GAME_OVER))
    }

    @Test
    fun `暂停时棋盘不再推进`() {
        val e = engine()
        e.spawnPiece(Piece.O)
        val y0 = e.current!!.y
        e.pause()
        e.update(5000)
        assertEquals("暂停期间不应下落", y0, e.current!!.y)
        e.resume()
        e.update(GameEngine.GRAVITY_MS[0])
        assertTrue("恢复后应继续下落", e.current!!.y > y0)
    }

    // ------------------------------------------------------------ 锁定延迟

    @Test
    fun `落地后要等满锁定延迟才固定`() {
        val e = engine()
        e.spawnPiece(Piece.O)

        // 用小步长把它送到地面（一级重力 1000ms 一格，共需下落 18 格）
        var guard = 0
        while (e.current!!.y < GameEngine.ROWS && guard++ < 500) e.update(50)
        assertEquals("方块应已触底", GameEngine.ROWS, e.current!!.y)
        e.drainEvents()

        // 刚触底，锁定计时从零开始
        e.update(200)
        assertFalse("锁定延迟内不应固定", e.drainEvents().contains(GameEvent.LOCK))
        e.update(150)
        assertFalse("累计 350ms 仍不应固定", e.drainEvents().contains(GameEvent.LOCK))

        // 累计 550ms，超过 500ms 的锁定延迟
        e.update(200)
        assertTrue("超过锁定延迟后应固定", e.drainEvents().contains(GameEvent.LOCK))
    }

    @Test
    fun `触底后仍可左右微调`() {
        val e = engine()
        e.spawnPiece(Piece.O)
        var guard = 0
        while (e.current!!.y < GameEngine.ROWS && guard++ < 500) e.update(50)

        val x0 = e.current!!.x
        assertTrue("触底后仍应能移动", e.move(-1))
        assertEquals(x0 - 1, e.current!!.x)
    }

    // ------------------------------------------------------------ 事件

    @Test
    fun `旋转与硬降会产出对应事件`() {
        val e = engine()
        e.spawnPiece(Piece.O)
        e.drainEvents()

        e.rotate(1)
        e.hardDrop()

        val events = e.drainEvents()
        assertTrue("应包含旋转事件", events.contains(GameEvent.ROTATE))
        assertTrue("应包含硬降事件", events.contains(GameEvent.HARD_DROP))
        assertTrue("应包含锁定事件", events.contains(GameEvent.LOCK))
    }

    @Test
    fun `移动事件在水平位移时产生`() {
        val e = engine()
        e.spawnPiece(Piece.O)
        e.drainEvents()
        assertTrue(e.move(1))
        assertTrue(e.drainEvents().contains(GameEvent.MOVE))

        e.drainEvents()
        e.move(0)
        assertTrue("原地不动不应产生事件", e.drainEvents().isEmpty())
    }
}
