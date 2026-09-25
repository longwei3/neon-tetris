package com.neon.tetris.game

/**
 * 游戏循环与 UI 之间的适配层：负责推进引擎、产出事件、并把引擎状态同步成一份
 * 「可以直接渲染的快照」。
 *
 * 为什么单独抽出来，而不是写在 ViewModel 里：
 *
 * [GameEngine.hardDrop] 和 [GameEngine.hold] 会被 UI 直接调用（按钮与手势），
 * 它们可能在两次 [tick] 之间就让引擎进入 [Phase.GAME_OVER]。
 * 如果 [tick] 写成「引擎不是 RUNNING 就提前 return」，那么这次状态变化永远同步不出去，
 * 表现为：游戏结束遮罩不出现、画面永久冻结、分数停在旧值。
 *
 * 所以这里**每一帧都无条件同步**，并且不依赖任何 Android / Compose API，
 * 可以在 JVM 上直接做回归测试。
 */
class GamePresenter(val engine: GameEngine = GameEngine()) {

    var frame: Int = 0
        private set

    var score: Int = 0
        private set

    var lines: Int = 0
        private set

    var level: Int = 1
        private set

    var combo: Int = -1
        private set

    var phase: Phase = Phase.READY
        private set

    /** 本帧是否刚刚结束游戏。用于让上层只持久化一次最高分。 */
    var justGameOver: Boolean = false
        private set

    private var gameOverAnnounced = false

    init {
        sync()
    }

    fun newGame() {
        engine.start()
        gameOverAnnounced = false
        justGameOver = false
        sync()
        frame++
    }

    fun pause() {
        engine.pause()
        sync()
    }

    fun resume() {
        engine.resume()
        sync()
        frame++
    }

    /**
     * 推进一帧并返回本帧事件。
     *
     * 注意：无论引擎是否真的推进了，最后都会同步一次状态。
     */
    fun tick(dtMs: Long): List<GameEvent> {
        justGameOver = false

        if (engine.phase == Phase.RUNNING) {
            engine.update(dtMs)
            frame++
        }

        val phaseChanged = engine.phase != phase
        sync()
        // 状态切换后至少要重绘一次，否则棋盘上会残留上一帧的当前方块
        if (phaseChanged) frame++

        if (engine.phase == Phase.GAME_OVER && !gameOverAnnounced) {
            gameOverAnnounced = true
            justGameOver = true
        }

        return engine.drainEvents()
    }

    private fun sync() {
        score = engine.score
        lines = engine.lines
        level = engine.level
        combo = engine.combo
        phase = engine.phase
    }
}
