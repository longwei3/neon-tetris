package com.neon.tetris.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neon.tetris.Bgm
import com.neon.tetris.Haptics
import com.neon.tetris.Sfx
import com.neon.tetris.game.GameEngine
import com.neon.tetris.game.GameEvent
import com.neon.tetris.game.Phase
import kotlin.math.abs

@Composable
fun GameScreen(vm: GameViewModel = viewModel()) {
    val context = LocalContext.current
    val sfx = remember { Sfx() }
    val bgm = remember { Bgm() }
    val haptics = remember { Haptics(context) }

    // 开关状态同步到底层
    LaunchedEffect(vm.soundOn) { sfx.enabled = vm.soundOn }
    LaunchedEffect(vm.hapticsOn) { haptics.enabled = vm.hapticsOn }

    // 背景音乐只在游戏进行中播放；暂停与结束时停掉，免得盖过结束音效
    LaunchedEffect(vm.phase, vm.soundOn) {
        if (vm.soundOn && vm.phase == Phase.RUNNING) bgm.start() else bgm.stop()
    }

    // 等级变化会同时改变速度与编曲层数
    LaunchedEffect(vm.level) { bgm.setLevel(vm.level) }

    // 离开界面时释放 AudioTrack
    DisposableEffect(Unit) {
        onDispose {
            sfx.release()
            bgm.release()
        }
    }

    // 切到后台自动暂停，回来时玩家自己点继续
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.pauseIfRunning()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 返回键：游戏中先暂停，否则退出
    val activity = context as? Activity
    BackHandler {
        if (vm.phase == Phase.RUNNING) vm.pause() else activity?.finish()
    }

    // 游戏主循环：逐帧推进引擎，读取并消费事件
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 16L else ((now - last) / 1_000_000L).coerceIn(1L, 64L)
                last = now
                val events = vm.tick(dt)
                if (events.isNotEmpty()) handleEvents(events, sfx, haptics)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 氛围背景单独一层：不读任何状态，只在布局与尺寸变化时绘制
        Canvas(Modifier.fillMaxSize()) { drawAmbientBackground() }

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            TopBar(vm)
            Spacer(Modifier.height(8.dp))
            StatStrip(vm)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // 棋盘背光：让深色棋盘从深色背景里浮起来
                Canvas(Modifier.fillMaxSize()) { drawBoardHalo() }
                BoardView(vm)
            }
            Spacer(Modifier.height(8.dp))
            ControlPad(vm)
        }

        if (vm.phase != Phase.RUNNING) {
            PhaseOverlay(vm)
        }
    }
}

private fun handleEvents(events: List<GameEvent>, sfx: Sfx, haptics: Haptics) {
    for (event in events) {
        when (event) {
            GameEvent.MOVE -> sfx.playMove()
            GameEvent.ROTATE -> {
                sfx.play(Sfx.ROTATE); haptics.tick(8)
            }

            GameEvent.LOCK -> {
                sfx.play(Sfx.LOCK); haptics.tick(12)
            }

            GameEvent.HARD_DROP -> {
                sfx.play(Sfx.HARD_DROP); haptics.tick(18)
            }

            GameEvent.HOLD -> {
                sfx.play(Sfx.HOLD); haptics.tick(10)
            }

            GameEvent.CLEAR -> {
                sfx.play(Sfx.CLEAR); haptics.tick(30)
            }

            GameEvent.TETRIS -> {
                sfx.play(Sfx.TETRIS); haptics.tick(50)
            }

            GameEvent.LEVEL_UP -> sfx.play(Sfx.LEVEL_UP)

            GameEvent.GAME_OVER -> {
                sfx.play(Sfx.GAME_OVER); haptics.tick(90)
            }
        }
    }
}

// ------------------------------------------------------------------ 顶栏

@Composable
private fun TopBar(vm: GameViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "霓虹方块",
            style = TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                brush = Neon.title,
                letterSpacing = 3.sp
            )
        )
        Spacer(Modifier.weight(1f))
        ChipLabel(if (vm.soundOn) "🔊" else "🔇") { vm.toggleSound() }
        Spacer(Modifier.width(6.dp))
        ChipLabel(if (vm.hapticsOn) "📳" else "📴") { vm.toggleHaptics() }
        Spacer(Modifier.width(6.dp))
        Chip(onClick = { if (vm.phase == Phase.RUNNING) vm.pause() }) { PauseIcon() }
    }
}

@Composable
private fun Chip(onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Neon.Blue.copy(alpha = 0.34f), Neon.Purple.copy(alpha = 0.16f))
                )
            )
            .border(1.dp, Neon.GridLine.copy(alpha = 0.38f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun ChipLabel(label: String, onClick: () -> Unit) {
    Chip(onClick) {
        Text(label, style = TextStyle(fontSize = 13.sp))
    }
}

/**
 * 暂停图标。
 * 系统会把 "⏸" 当作彩色 emoji 渲染成一整块橙色方块，看起来像被按下的按钮，
 * 与相邻两个按钮的风格也不统一，所以直接画两条圆角竖条。
 */
@Composable
private fun PauseIcon() {
    Canvas(Modifier.size(width = 13.dp, height = 15.dp)) {
        val barWidth = size.width * 0.34f
        val radius = CornerRadius(barWidth * 0.4f, barWidth * 0.4f)
        drawRoundRect(
            color = Neon.TextPrimary,
            topLeft = Offset(0f, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = radius
        )
        drawRoundRect(
            color = Neon.TextPrimary,
            topLeft = Offset(size.width - barWidth, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = radius
        )
    }
}

// ------------------------------------------------------------------ 数据条

@Composable
private fun StatStrip(vm: GameViewModel) {
    Row(Modifier.fillMaxWidth().height(66.dp)) {
        PanelBox(Modifier.width(56.dp).fillMaxHeight(), tint = Neon.Cyan) {
            PanelLabel("HOLD", Neon.Cyan)
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                @Suppress("UNUSED_EXPRESSION")
                vm.frame
                drawSinglePreview(vm.engine.holdPiece)
            }
        }

        Spacer(Modifier.width(8.dp))

        PanelBox(Modifier.weight(1f).fillMaxHeight(), tint = Neon.Purple) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatItem("分数", vm.score.toString(), Neon.Cyan)
                StatItem("等级", vm.level.toString(), Neon.Purple)
                StatItem("消行", vm.lines.toString(), Neon.Green)
                StatItem("连击", if (vm.combo > 0) "×${vm.combo + 1}" else "—", Neon.Amber)
            }
            Text(
                text = "最高 ${vm.best}",
                style = TextStyle(fontSize = 10.sp, color = Neon.TextMuted),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.width(8.dp))

        PanelBox(Modifier.width(104.dp).fillMaxHeight(), tint = Neon.Pink) {
            PanelLabel("NEXT", Neon.Pink)
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                @Suppress("UNUSED_EXPRESSION")
                vm.frame
                drawNextStrip(vm.engine, 3)
            }
        }
    }
}

@Composable
private fun PanelBox(
    modifier: Modifier,
    tint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.32f), Color.White.copy(alpha = 0.05f))
                )
            )
            .border(1.dp, tint.copy(alpha = 0.50f), shape)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        content = content
    )
}

@Composable
private fun PanelLabel(text: String, color: Color) {
    Text(
        text = text,
        style = TextStyle(fontSize = 9.sp, color = color.copy(alpha = 0.85f), letterSpacing = 2.sp)
    )
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = TextStyle(fontSize = 9.sp, color = Neon.TextMuted)
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        )
    }
}

// ------------------------------------------------------------------ 棋盘

@Composable
private fun BoardView(vm: GameViewModel) {
    val engine = vm.engine
    val phase = vm.phase
    var boardSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxHeight()
            .aspectRatio(GameEngine.COLS.toFloat() / GameEngine.ROWS)
            .clip(RoundedCornerShape(14.dp))
            .background(Neon.BoardBottom)
            .onSizeChanged { boardSize = it }
            .pointerInput(phase, boardSize) {
                if (phase != Phase.RUNNING) return@pointerInput
                val cell = if (boardSize.width > 0) {
                    boardSize.width / GameEngine.COLS.toFloat()
                } else {
                    24f
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var accX = 0f
                    var totalDy = 0f
                    var moved = false
                    val startedAt = System.currentTimeMillis()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        accX += change.position.x - lastX
                        totalDy = change.position.y - down.position.y
                        lastX = change.position.x

                        // 每拖过一格就横移一格
                        if (abs(accX) >= cell) {
                            val dir = if (accX > 0f) 1 else -1
                            engine.move(dir)
                            accX -= dir * cell
                            moved = true
                        }
                        change.consume()
                    }

                    val elapsed = System.currentTimeMillis() - startedAt
                    // 判定只看距离，不看速度：要求用户在一定毫秒内完成下滑是不合理的，
                    // 真机（以及 adb 注入）的下滑耗时波动很大，加时间窗会让手势时灵时不灵。
                    if (!moved && totalDy > cell * 3f) {
                        // 向下拖动超过 3 格 -> 硬降
                        engine.hardDrop()
                    } else if (!moved && elapsed < 250L && abs(totalDy) < cell) {
                        // 轻点 -> 顺时针旋转
                        engine.rotate(1)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            vm.frame // 订阅帧计数：只在绘制阶段失效，避免每帧重组
            drawBoard(engine)
        }
    }
}

// ------------------------------------------------------------------ 控制区

@Composable
private fun ControlPad(vm: GameViewModel) {
    val engine = vm.engine

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton("H", Modifier.weight(1f), caption = "暂存") { engine.hold() }
            PadButton("⟲", Modifier.weight(1f), caption = "左旋") { engine.rotate(-1) }
            PadButton("⟳", Modifier.weight(1f), caption = "右旋") { engine.rotate(1) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(
                label = "←",
                modifier = Modifier.weight(1f).height(62.dp),
                caption = "左移",
                onUp = { engine.setHeldDirection(0) }
            ) { engine.setHeldDirection(-1) }
            PadButton(
                label = "↓",
                modifier = Modifier.weight(1f).height(62.dp),
                caption = "软降",
                onUp = { engine.setSoftDrop(false) }
            ) { engine.setSoftDrop(true) }
            PadButton(
                label = "→",
                modifier = Modifier.weight(1f).height(62.dp),
                caption = "右移",
                onUp = { engine.setHeldDirection(0) }
            ) { engine.setHeldDirection(1) }
        }
    }
}

/**
 * 按住即触发 [onDown]，松开触发 [onUp]。
 * 左右与软降的自动连发由引擎的 DAS/ARR 负责，这里只负责「按下/松开」两个边沿，
 * 因此长时间按住不会产生协程或事件风暴。
 *
 * [onDown] 放在最后一个参数，这样调用处可以直接写尾部 lambda。
 * [caption] 是按钮下方的小字说明：图形本身表意不够明确。
 *
 * 硬降没有按钮，只保留棋盘上的「下滑」手势 —— 它是一次性动作，放在按键盘里
 * 既容易和「软降」混淆，也占掉一个位置。
 */
@Composable
private fun PadButton(
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onUp: () -> Unit = {},
    onDown: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Neon.Blue.copy(alpha = 0.26f), Neon.Purple.copy(alpha = 0.12f))
                )
            )
            .border(1.dp, Neon.GridLine.copy(alpha = 0.30f), shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onDown()
                        tryAwaitRelease()
                        onUp()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = TextStyle(fontSize = 20.sp, color = Neon.TextPrimary)
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = TextStyle(
                        fontSize = 9.sp,
                        color = Neon.TextMuted,
                        letterSpacing = 1.sp
                    )
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 遮罩层

@Composable
private fun PhaseOverlay(vm: GameViewModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD906070D)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            when (vm.phase) {
                Phase.READY -> {
                    Text(
                        text = "霓虹方块",
                        style = TextStyle(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            brush = Neon.title,
                            letterSpacing = 4.sp
                        )
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "SRS 旋转 · T-spin · Hold 暂存\n" +
                            "每消 10 行提升一级",
                        style = TextStyle(fontSize = 13.sp, color = Neon.TextMuted),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "棋盘上：轻点旋转 · 左右拖动横移\n下滑硬降（直接落底并锁定）",
                        style = TextStyle(fontSize = 12.sp, color = Neon.TextMuted),
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(22.dp))
                    PrimaryButton("开始游戏") { vm.newGame() }
                }

                Phase.PAUSED -> {
                    Text(
                        text = "已暂停",
                        style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Neon.TextPrimary
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "分数 ${vm.score} · 等级 ${vm.level} · 消行 ${vm.lines}",
                        style = TextStyle(fontSize = 13.sp, color = Neon.TextMuted)
                    )
                    Spacer(Modifier.height(22.dp))
                    PrimaryButton("继续游戏") { vm.resume() }
                }

                Phase.GAME_OVER -> {
                    Text(
                        text = "游戏结束",
                        style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Neon.TextPrimary
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = vm.score.toString(),
                        style = TextStyle(
                            fontSize = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Neon.Cyan
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (vm.score >= vm.best) "新纪录！" else "最高分 ${vm.best}",
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = if (vm.score >= vm.best) Neon.Amber else Neon.TextMuted
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "等级 ${vm.level} · 消行 ${vm.lines}",
                        style = TextStyle(fontSize = 13.sp, color = Neon.TextMuted)
                    )
                    Spacer(Modifier.height(22.dp))
                    PrimaryButton("再来一局") { vm.newGame() }
                }

                Phase.RUNNING -> Unit
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF67E8F9), Neon.Cyan, Neon.Purple))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 36.dp, vertical = 13.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF04121A),
                letterSpacing = 2.sp
            )
        )
    }
}
