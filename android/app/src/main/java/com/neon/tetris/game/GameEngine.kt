package com.neon.tetris.game

import kotlin.random.Random

/** 游戏阶段。UI 完全由它驱动，引擎自身不持有任何 Android / Compose 依赖。 */
enum class Phase { READY, RUNNING, PAUSED, GAME_OVER }

/**
 * 引擎在一个时间步里产生的一次性事件，供 UI 层播放音效与震动。
 * 水平移动也会发事件（包括 DAS 连发），由 UI 侧对移动音效做节流，避免听感糊掉。
 */
enum class GameEvent { MOVE, ROTATE, LOCK, HARD_DROP, HOLD, CLEAR, TETRIS, LEVEL_UP, GAME_OVER }

/** 正在下落的方块。可变类，避免每帧 copy 产生垃圾。 */
class Active(var piece: Piece, var rot: Int, var x: Int, var y: Int)

/** 上一步操作类型，T-spin 判定需要它。 */
private enum class Action { SPAWN, MOVE, ROTATE }

/**
 * 纯 Kotlin 实现的对战规则内核：SRS 旋转 + 踢墙、7-bag 随机、锁定延迟、
 * T-spin、Back-to-Back、连击与等级加速。
 *
 * 棋盘用扁平 `IntArray` 存储：`cells[y * COLS + x]`，0 表示空，
 * 否则为 `piece.ordinal + 1`。这样碰撞检测与消行搬移都无需分配对象。
 */
class GameEngine(private val random: Random = Random.Default) {

    companion object {
        const val COLS = 10
        const val ROWS = 20

        /** 顶部缓冲区行数：方块在此生成，不显示。保留它可避免生成瞬间越界。 */
        const val HIDDEN = 2
        const val TOTAL_ROWS = ROWS + HIDDEN

        const val LOCK_DELAY_MS = 500L
        const val MAX_LOCK_RESETS = 15
        const val CLEAR_MS = 170L

        /** 按住左右方向键的首次延迟与连发间隔 */
        const val DAS_MS = 150L
        const val ARR_MS = 38L
        const val SOFT_DROP_MS = 35L

        /** 每一级的自然下落间隔（毫秒），索引 = level - 1 */
        @JvmField
        val GRAVITY_MS = longArrayOf(
            1000, 850, 700, 570, 450, 350, 270, 200, 150, 110, 80, 60, 45, 35, 28
        )
    }

    // ---------------------------------------------------------------- 棋盘

    @JvmField
    val cells = IntArray(COLS * TOTAL_ROWS)

    // ---------------------------------------------------------------- 对外状态

    var phase: Phase = Phase.READY
        private set

    var current: Active? = null
        private set

    var holdPiece: Piece? = null
        private set

    var canHold: Boolean = true
        private set

    var score: Int = 0
        private set

    var lines: Int = 0
        private set

    var level: Int = 1
        private set

    /** -1 表示当前没有连击；0 表示连击一次，依次类推。 */
    var combo: Int = -1
        private set

    var backToBack: Boolean = false
        private set

    var piecesPlaced: Int = 0
        private set

    var tetrisCount: Int = 0
        private set

    var tspinCount: Int = 0
        private set

    /** 正在播消失动画的行号（棋盘绝对行号）。非空时游戏逻辑暂停推进。 */
    var clearingRows: IntArray = IntArray(0)
        private set

    var clearingElapsed: Long = 0L
        private set

    /** 接下来 5 个方块，供测试与需要完整队列的场景使用。 */
    val nextQueue: List<Piece> get() = queue.take(5)

    /** 按下标读取待出方块，避免绘制时分配列表。 */
    fun nextAt(index: Int): Piece? = queue.elementAtOrNull(index)

    // ---------------------------------------------------------------- 内部

    private val queue = ArrayDeque<Piece>()
    private val events = ArrayList<GameEvent>(8)

    private var dropAcc = 0L
    private var lockAcc = 0L
    private var lockResets = 0
    private var lastAction = Action.SPAWN

    private var heldDir = 0
    private var dasAcc = 0L
    private var arrAcc = 0L
    private var softDrop = false

    // ---------------------------------------------------------------- 生命周期

    /** 清空一切，回到 READY 状态。 */
    fun reset() {
        cells.fill(0)
        queue.clear()
        ensureQueue()
        current = null
        holdPiece = null
        canHold = true
        score = 0
        lines = 0
        level = 1
        combo = -1
        backToBack = false
        piecesPlaced = 0
        tetrisCount = 0
        tspinCount = 0
        clearingRows = IntArray(0)
        clearingElapsed = 0L
        dropAcc = 0L
        lockAcc = 0L
        lockResets = 0
        lastAction = Action.SPAWN
        heldDir = 0
        dasAcc = 0L
        arrAcc = 0L
        softDrop = false
        events.clear()
        phase = Phase.READY
    }

    /** 开新局。 */
    fun start() {
        reset()
        phase = Phase.RUNNING
        spawnNext()
    }

    fun pause() {
        if (phase == Phase.RUNNING) {
            phase = Phase.PAUSED
            heldDir = 0
            softDrop = false
        }
    }

    fun resume() {
        if (phase == Phase.PAUSED) phase = Phase.RUNNING
    }

    /** 取出本帧产生的事件并清空缓冲。无事件时不分配内存。 */
    fun drainEvents(): List<GameEvent> {
        if (events.isEmpty()) return emptyList()
        val out = ArrayList(events)
        events.clear()
        return out
    }

    // ---------------------------------------------------------------- 主循环

    fun update(dtMs: Long) {
        if (phase != Phase.RUNNING) return

        // 消行动画期间冻结逻辑
        if (clearingRows.isNotEmpty()) {
            clearingElapsed += dtMs
            if (clearingElapsed >= CLEAR_MS) finishClear()
            return
        }

        if (current == null) return

        // 按住左右时的自动连发
        if (heldDir != 0) {
            dasAcc += dtMs
            if (dasAcc >= DAS_MS) {
                arrAcc += dtMs
                var guard = 0
                while (arrAcc >= ARR_MS && guard++ < 12) {
                    arrAcc -= ARR_MS
                    if (!tryMove(heldDir, 0)) break
                }
            }
        }

        // 自然下落 / 软降
        val interval = if (softDrop) minOf(SOFT_DROP_MS, gravityMs()) else gravityMs()
        dropAcc += dtMs
        var guard = 0
        while (dropAcc >= interval && guard++ < 24) {
            val c = current ?: break
            if (collides(c.piece, c.rot, c.x, c.y + 1)) break
            c.y += 1
            dropAcc -= interval
            if (softDrop) score += 1
        }

        // 落地锁定：复位次数用尽后不再重置计时，仍要等满一个锁定延迟
        val c = current ?: return
        if (collides(c.piece, c.rot, c.x, c.y + 1)) {
            lockAcc += dtMs
            if (lockAcc >= LOCK_DELAY_MS) lockPiece()
        } else {
            lockAcc = 0L
        }
    }

    private fun gravityMs(): Long = GRAVITY_MS[minOf(level, GRAVITY_MS.size) - 1]

    // ---------------------------------------------------------------- 玩家操作

    /** 设置左右按住方向（-1 左 / 0 松开 / 1 右）。会立刻移动一格并开始 DAS 计时。 */
    fun setHeldDirection(dir: Int) {
        if (dir != -1 && dir != 0 && dir != 1) return
        if (heldDir == dir) return
        heldDir = dir
        dasAcc = 0L
        arrAcc = 0L
        if (dir != 0) tryMove(dir, 0)
    }

    fun setSoftDrop(on: Boolean) {
        if (softDrop == on) return
        softDrop = on
        if (!on) dropAcc = 0L
    }

    /** 水平移动一格。 */
    fun move(dx: Int): Boolean = tryMove(dx, 0)

    private fun tryMove(dx: Int, dy: Int): Boolean {
        if (phase != Phase.RUNNING || clearingRows.isNotEmpty()) return false
        val c = current ?: return false
        val nx = c.x + dx
        val ny = c.y + dy
        if (collides(c.piece, c.rot, nx, ny)) return false
        c.x = nx
        c.y = ny
        if (dx != 0) {
            lastAction = Action.MOVE
            events.add(GameEvent.MOVE)
        }
        resetLockIfGrounded()
        return true
    }

    /** 顺时针 [dir] > 0，逆时针 [dir] < 0。 */
    fun rotate(dir: Int): Boolean {
        if (phase != Phase.RUNNING || clearingRows.isNotEmpty()) return false
        val c = current ?: return false
        val from = c.rot
        val to = (from + if (dir > 0) 1 else 3) % 4
        val kicks = Pieces.kicks(c.piece, from, to)
        var i = 0
        while (i < kicks.size) {
            val nx = c.x + kicks[i]
            val ny = c.y + kicks[i + 1]
            if (!collides(c.piece, to, nx, ny)) {
                c.rot = to
                c.x = nx
                c.y = ny
                lastAction = Action.ROTATE
                resetLockIfGrounded()
                events.add(GameEvent.ROTATE)
                return true
            }
            i += 2
        }
        return false
    }

    fun hardDrop() {
        if (phase != Phase.RUNNING || clearingRows.isNotEmpty()) return
        val c = current ?: return
        val target = ghostY()
        val dist = target - c.y
        if (dist > 0) {
            c.y = target
            score += dist * 2
            events.add(GameEvent.HARD_DROP)
        }
        lockPiece()
    }

    /** 暂存当前方块，与已暂存的交换。每个方块只能暂存一次。 */
    fun hold() {
        if (phase != Phase.RUNNING || !canHold || clearingRows.isNotEmpty()) return
        val c = current ?: return
        val cur = c.piece
        val held = holdPiece
        if (held == null) {
            holdPiece = cur
            spawnNext()
        } else {
            holdPiece = cur
            spawnPiece(held)
        }
        canHold = false
        events.add(GameEvent.HOLD)
    }

    // ---------------------------------------------------------------- 几何

    /** 返回幽灵方块（落点预览）所在的 y。 */
    fun ghostY(): Int {
        val c = current ?: return 0
        var y = c.y
        while (!collides(c.piece, c.rot, c.x, y + 1)) y++
        return y
    }

    fun collides(piece: Piece, rot: Int, px: Int, py: Int): Boolean {
        val shape = Pieces.SHAPES[piece.ordinal][rot]
        var i = 0
        while (i < shape.size) {
            val x = px + shape[i]
            val y = py + shape[i + 1]
            if (x < 0 || x >= COLS || y >= TOTAL_ROWS) return true
            if (y >= 0 && cells[y * COLS + x] != 0) return true
            i += 2
        }
        return false
    }

    private fun resetLockIfGrounded() {
        if (lockResets >= MAX_LOCK_RESETS) return
        val c = current ?: return
        if (collides(c.piece, c.rot, c.x, c.y + 1)) {
            lockResets++
            lockAcc = 0L
        }
    }

    // ---------------------------------------------------------------- 生成

    private fun ensureQueue() {
        while (queue.size < 8) {
            val bag = Piece.entries.toMutableList()
            bag.shuffle(random)
            queue.addAll(bag)
        }
    }

    private fun spawnNext() {
        ensureQueue()
        spawnPiece(queue.removeFirst())
        canHold = true
    }

    /**
     * 在出生点放置指定方块。方块顶边对齐到第一可见行，保证玩家一生成就能看见。
     * 出生位置被占则判定游戏结束。
     */
    fun spawnPiece(piece: Piece) {
        val shape = Pieces.SHAPES[piece.ordinal][0]
        var minY = Int.MAX_VALUE
        var i = 1
        while (i < shape.size) {
            if (shape[i] < minY) minY = shape[i]
            i += 2
        }
        val x = (COLS - Pieces.BOX[piece.ordinal]) / 2
        val y = HIDDEN - minY

        current = Active(piece, 0, x, y)
        dropAcc = 0L
        lockAcc = 0L
        lockResets = 0
        lastAction = Action.SPAWN
        piecesPlaced++

        if (collides(piece, 0, x, y)) {
            current = null
            phase = Phase.GAME_OVER
            events.add(GameEvent.GAME_OVER)
        }
    }

    // ---------------------------------------------------------------- 锁定与消行

    /**
     * 三角规则判定 T-spin：T 块中心四个斜角中至少三个被占（墙体与地面也算占）。
     * 只有「上一步是旋转」才可能成立。
     */
    private fun detectTSpin(): Boolean {
        val c = current ?: return false
        if (c.piece != Piece.T || lastAction != Action.ROTATE) return false
        val cx = c.x + 1
        val cy = c.y + 1
        var filled = 0
        for (k in 0 until 4) {
            val x = cx + if (k % 2 == 0) -1 else 1
            val y = cy + if (k < 2) -1 else 1
            if (x < 0 || x >= COLS || y >= TOTAL_ROWS) {
                filled++
                continue
            }
            if (y < 0 || cells[y * COLS + x] != 0) filled++
        }
        return filled >= 3
    }

    private fun lockPiece() {
        val c = current ?: return
        val tspin = detectTSpin()

        val shape = Pieces.SHAPES[c.piece.ordinal][c.rot]
        var i = 0
        while (i < shape.size) {
            val x = c.x + shape[i]
            val y = c.y + shape[i + 1]
            if (y in 0 until TOTAL_ROWS) cells[y * COLS + x] = c.piece.ordinal + 1
            i += 2
        }

        val full = ArrayList<Int>(4)
        for (y in 0 until TOTAL_ROWS) {
            var complete = true
            for (x in 0 until COLS) {
                if (cells[y * COLS + x] == 0) {
                    complete = false
                    break
                }
            }
            if (complete) full.add(y)
        }

        val n = full.size
        var difficult = false

        if (n > 0) {
            if (tspin) {
                tspinCount++
            }
            val base = if (tspin) {
                intArrayOf(0, 800, 1200, 1600)[minOf(n, 3)]
            } else {
                intArrayOf(0, 100, 300, 500, 800)[minOf(n, 4)]
            }
            difficult = tspin || n == 4
            val multiplier = if (difficult && backToBack) 1.5 else 1.0
            score += (base * level * multiplier).toInt()

            lines += n
            val newLevel = minOf(GRAVITY_MS.size, lines / 10 + 1)
            if (newLevel != level) {
                level = newLevel
                events.add(GameEvent.LEVEL_UP)
            }
            combo++
        } else {
            combo = -1
        }

        backToBack = difficult
        current = null
        events.add(GameEvent.LOCK)

        if (n > 0) {
            clearingRows = IntArray(n) { full[it] }
            clearingElapsed = 0L
            if (n == 4) {
                tetrisCount++
                events.add(GameEvent.TETRIS)
            } else {
                events.add(GameEvent.CLEAR)
            }
        } else {
            spawnNext()
        }
    }

    /** 消行动画结束：把未满的行整体下移，顶部补空行。用 System.arraycopy 原地完成。 */
    private fun finishClear() {
        val doomed = HashSet<Int>(clearingRows.size * 2)
        for (r in clearingRows) doomed.add(r)

        var write = TOTAL_ROWS - 1
        for (read in TOTAL_ROWS - 1 downTo 0) {
            if (doomed.contains(read)) continue
            if (write != read) {
                System.arraycopy(cells, read * COLS, cells, write * COLS, COLS)
            }
            write--
        }
        var y = write
        while (y >= 0) {
            java.util.Arrays.fill(cells, y * COLS, y * COLS + COLS, 0)
            y--
        }

        clearingRows = IntArray(0)
        clearingElapsed = 0L
        spawnNext()
    }

    // ---------------------------------------------------------------- 测试辅助

    /** 直接写入棋盘格，仅用于单元测试搭场景。 */
    fun setCell(x: Int, y: Int, piece: Piece?) {
        cells[y * COLS + x] = if (piece == null) 0 else piece.ordinal + 1
    }

    /** 读取棋盘格。 */
    fun cellAt(x: Int, y: Int): Piece? {
        val v = cells[y * COLS + x]
        return if (v == 0) null else Piece.entries[v - 1]
    }

    /** 把当前方块挪到指定位置，仅用于测试。 */
    fun placeCurrent(rot: Int, x: Int, y: Int) {
        val c = current ?: return
        c.rot = rot
        c.x = x
        c.y = y
    }

    /** 标记上一步为旋转，仅用于测试 T-spin。 */
    fun markLastActionRotate() {
        lastAction = Action.ROTATE
    }
}
