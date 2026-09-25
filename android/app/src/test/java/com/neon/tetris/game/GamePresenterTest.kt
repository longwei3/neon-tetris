package com.neon.tetris.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [GamePresenter] 的回归测试。
 *
 * 这里覆盖的是一个只在真机上才暴露出来的 bug：
 * 「硬降」「暂存」是 UI 线程直接调用引擎的，可能在两次 `tick` 之间就让引擎结束游戏。
 * 早期实现里 `tick` 写成「引擎不是 RUNNING 就提前 return」，导致这次状态变化同步不出去——
 * 游戏结束遮罩永远不出现、画面永久冻结、分数停在旧值。
 */
class GamePresenterTest {

    private fun presenter(seed: Int = 42) = GamePresenter(GameEngine(Random(seed)))

    /** 把出生区填满，让下一个方块的生成必然碰撞。留出第 0 列以免整行被填满触发消行。 */
    private fun blockSpawnArea(p: GamePresenter) {
        for (y in 0 until GameEngine.HIDDEN + 3) {
            for (x in 0 until GameEngine.COLS) {
                p.engine.setCell(x, y, Piece.I)
            }
        }
    }

    /**
     * 让「硬降」直接把游戏结束掉：下方留空但出生区被占，
     * 落定后生成下一个方块时必然碰撞。
     */
    private fun reachGameOverViaHardDrop(p: GamePresenter) {
        p.newGame()
        // 第 4 行往下填满第 1..9 列（第 0 列留空，因此没有任何一行是满的）
        for (y in 4 until GameEngine.TOTAL_ROWS) {
            for (x in 1 until GameEngine.COLS) {
                p.engine.setCell(x, y, Piece.I)
            }
        }
        p.engine.spawnPiece(Piece.O) // 固定在出生点，落不下去
        p.engine.hardDrop()          // 模拟 UI 上的 ⤓ 按钮
    }

    @Test
    fun `硬降导致游戏结束时状态必须同步出来`() {
        val p = presenter()
        reachGameOverViaHardDrop(p)

        assertEquals("引擎应已结束", Phase.GAME_OVER, p.engine.phase)
        assertEquals("presenter 还没 tick，快照仍是旧的", Phase.RUNNING, p.phase)

        val events = p.tick(16)

        assertEquals("tick 之后必须同步成 GAME_OVER", Phase.GAME_OVER, p.phase)
        assertTrue("应播报结束", p.justGameOver)
        assertTrue("应带出 GAME_OVER 事件", events.contains(GameEvent.GAME_OVER))
    }

    @Test
    fun `在 tick 之外直接调用引擎结束时状态也会同步`() {
        // hold() 内部也是直接调用 spawnPiece，走的正是这条路径
        val p = presenter(seed = 7)
        p.newGame()
        blockSpawnArea(p)

        p.engine.spawnPiece(Piece.T)
        assertEquals(Phase.GAME_OVER, p.engine.phase)
        assertEquals(Phase.RUNNING, p.phase)

        p.tick(16)
        assertEquals(Phase.GAME_OVER, p.phase)
    }

    @Test
    fun `游戏结束只播报一次`() {
        val p = presenter()
        reachGameOverViaHardDrop(p)

        p.tick(16)
        assertTrue("第一次 tick 应播报", p.justGameOver)

        p.tick(16)
        assertFalse("第二次 tick 不应重复播报", p.justGameOver)
        p.tick(16)
        assertFalse(p.justGameOver)
    }

    @Test
    fun `结束的那一帧会请求重绘`() {
        val p = presenter()
        reachGameOverViaHardDrop(p)

        val before = p.frame
        p.tick(16)
        assertTrue("状态切换后必须至少重绘一次，否则棋盘会残留上一帧的方块", p.frame > before)
    }

    @Test
    fun `重开一局后可以再次播报结束`() {
        val p = presenter()
        reachGameOverViaHardDrop(p)
        p.tick(16)
        assertTrue(p.justGameOver)

        p.newGame()
        assertEquals(Phase.RUNNING, p.phase)
        assertFalse("重开后应重置播报标记", p.justGameOver)

        reachGameOverViaHardDrop(p)
        p.tick(16)
        assertTrue("第二局结束同样要播报", p.justGameOver)
    }

    @Test
    fun `暂停后画面不再持续重绘`() {
        val p = presenter()
        p.newGame()
        p.tick(16)
        p.pause()

        val idle = p.frame
        p.tick(16)
        p.tick(16)
        p.tick(16)
        assertEquals("暂停时不应每帧重绘，否则白白耗电", idle, p.frame)
    }

    @Test
    fun `运行中每帧都推进并重绘`() {
        val p = presenter()
        p.newGame()

        val f0 = p.frame
        p.tick(16)
        p.tick(16)
        assertEquals("运行中每帧都应自增帧计数", f0 + 2, p.frame)
    }

    @Test
    fun `快照分数跟随引擎变化`() {
        val p = presenter()
        p.newGame()
        p.engine.spawnPiece(Piece.O)
        p.engine.hardDrop() // 硬降会加分

        assertTrue("引擎分数应已增加", p.engine.score > 0)
        p.tick(16)
        assertEquals("快照应同步到最新分数", p.engine.score, p.score)
    }

    @Test
    fun `恢复游戏后继续推进`() {
        val p = presenter()
        p.newGame()
        p.tick(16)
        p.pause()
        assertEquals(Phase.PAUSED, p.phase)

        p.resume()
        assertEquals(Phase.RUNNING, p.phase)
        val f = p.frame
        p.tick(16)
        assertTrue("恢复后应继续推进", p.frame > f)
    }
}
