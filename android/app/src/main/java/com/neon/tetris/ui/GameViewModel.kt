package com.neon.tetris.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.neon.tetris.game.GameEvent
import com.neon.tetris.game.GamePresenter
import com.neon.tetris.game.Phase

/**
 * 把纯 Kotlin 的 [GamePresenter] 接到 Compose 上。
 *
 * 这个类只做两件事：把 presenter 的快照镜像成 Compose 状态，以及持久化最高分与开关。
 * 所有游戏逻辑与状态同步都在 presenter 里，因此那部分可以被单元测试覆盖。
 *
 * 关键设计：[frame] 每帧自增一次，Canvas 只在**绘制阶段**读它，
 * 因此整局游戏的画面刷新不会触发任何重组，只触发重绘。
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("neon_tetris", Context.MODE_PRIVATE)
    private val presenter = GamePresenter()

    /** 供 UI 直接操作引擎（按钮与手势）。 */
    val engine get() = presenter.engine

    /** 帧计数器：驱动 Canvas 重绘，不驱动重组。 */
    var frame by mutableIntStateOf(0)
        private set

    var phase by mutableStateOf(Phase.READY)
        private set

    var score by mutableIntStateOf(0)
        private set

    var best by mutableIntStateOf(0)
        private set

    var lines by mutableIntStateOf(0)
        private set

    var level by mutableIntStateOf(1)
        private set

    var combo by mutableIntStateOf(-1)
        private set

    var soundOn by mutableStateOf(true)
        private set

    var hapticsOn by mutableStateOf(true)
        private set

    init {
        best = prefs.getInt(KEY_BEST, 0)
        soundOn = prefs.getBoolean(KEY_SOUND, true)
        hapticsOn = prefs.getBoolean(KEY_HAPTICS, true)
        mirror()
    }

    // ------------------------------------------------------------ 局面控制

    fun newGame() {
        presenter.newGame()
        mirror()
    }

    fun pause() {
        presenter.pause()
        mirror()
    }

    fun resume() {
        presenter.resume()
        mirror()
    }

    /** 切到后台时调用；只有正在游戏中才暂停。 */
    fun pauseIfRunning() {
        if (engine.phase == Phase.RUNNING) pause()
    }

    fun toggleSound() {
        soundOn = !soundOn
        prefs.edit().putBoolean(KEY_SOUND, soundOn).apply()
    }

    fun toggleHaptics() {
        hapticsOn = !hapticsOn
        prefs.edit().putBoolean(KEY_HAPTICS, hapticsOn).apply()
    }

    // ------------------------------------------------------------ 主循环

    /**
     * 推进一帧，返回本帧事件供 UI 播放音效与震动。
     *
     * 这里无条件镜像状态：引擎可能被 UI 直接调用（硬降 / 暂存）而在两次 tick 之间结束游戏，
     * 那时必须把 GAME_OVER 同步出来，否则遮罩不会出现、画面会冻结。
     */
    fun tick(dtMs: Long): List<GameEvent> {
        val events = presenter.tick(dtMs)
        mirror()

        if (presenter.justGameOver && presenter.score > best) {
            best = presenter.score
            prefs.edit().putInt(KEY_BEST, best).apply()
        }
        return events
    }

    /**
     * 只在值真的变化时写入 Compose 状态。
     * 不依赖 Compose 对相同赋值是否去重，保证 60fps 下文本区域不会被反复重组。
     */
    private fun mirror() {
        if (frame != presenter.frame) frame = presenter.frame
        if (score != presenter.score) score = presenter.score
        if (lines != presenter.lines) lines = presenter.lines
        if (level != presenter.level) level = presenter.level
        if (combo != presenter.combo) combo = presenter.combo
        if (phase != presenter.phase) phase = presenter.phase
    }

    private companion object {
        const val KEY_BEST = "best"
        const val KEY_SOUND = "sound"
        const val KEY_HAPTICS = "haptics"
    }
}
