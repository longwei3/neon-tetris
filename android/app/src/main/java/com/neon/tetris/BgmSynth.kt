package com.neon.tetris

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 背景音乐的「作曲 + 合成」部分。
 *
 * 刻意做成**纯 Kotlin、不依赖任何 Android API**，原因有两个：
 *  1. 可以在 JVM 上直接渲染出 PCM 做单元测试，确认不是静音、没有削波；
 *  2. 可以导出 WAV 试听，改曲子时不必每次都装到手机上听。
 *
 * 音乐本身是原创的 synthwave / 芯片风格：Am - F - C - G 四小节循环，
 * 由底鼓、军鼓、踩镲、锯齿贝斯、方波琶音和三角波主旋律六层组成。
 */
class BgmSynth(seed: Long = System.nanoTime()) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val STEPS_PER_BAR = 16
        const val BARS = 4
        const val TOTAL_STEPS = STEPS_PER_BAR * BARS

        /** 一步最多多少采样：96 BPM 的 16 分音符约 156ms ≈ 6890 点，留些余量。 */
        const val MAX_STEP_SAMPLES = 8192

        /** 总音量。压得比音效低，避免盖过操作反馈。 */
        private const val MASTER = 0.45

        private const val SQUARE = 0
        private const val SAW = 1
        private const val TRIANGLE = 2

        /** 速度映射：1 级 96 BPM，每级 +7，封顶 176。 */
        fun bpmForLevel(level: Int): Double =
            (96.0 + (level - 1).coerceAtLeast(0) * 7.0).coerceAtMost(176.0)

        /** 4 级起加入主旋律层。 */
        fun hasLead(level: Int): Boolean = level >= 4

        /** 8 级起琶音加倍，织体变厚。 */
        fun hasDoubleArp(level: Int): Boolean = level >= 8

        /** 某个等级的 16 分音符对应多少采样。 */
        fun stepSamplesFor(level: Int): Int =
            (SAMPLE_RATE * (60.0 / bpmForLevel(level) / 4.0)).toInt()
                .coerceIn(64, MAX_STEP_SAMPLES)
    }

    private val rnd = Random(seed)
    private val mix = FloatArray(MAX_STEP_SAMPLES)

    // 每小节的根音（MIDI 音高）：Am / F / C / G
    private val bassRoot = intArrayOf(45, 41, 48, 43)

    // 每小节的琶音音型，按 16 分音符循环
    private val arpNotes = arrayOf(
        intArrayOf(69, 72, 76, 72),
        intArrayOf(65, 69, 72, 69),
        intArrayOf(72, 76, 79, 76),
        intArrayOf(67, 71, 74, 71)
    )

    // 主旋律：每小节 8 个八分音符
    private val leadNotes = arrayOf(
        intArrayOf(76, 69, 72, 71, 69, 72, 76, 74),
        intArrayOf(72, 69, 65, 69, 72, 74, 72, 69),
        intArrayOf(76, 79, 76, 72, 67, 72, 76, 79),
        intArrayOf(74, 71, 67, 71, 74, 76, 74, 71)
    )

    /** 渲染第 [step] 步到内部混音缓冲，共 [n] 个采样。 */
    fun renderStep(step: Int, n: Int, level: Int) {
        java.util.Arrays.fill(mix, 0, n, 0f)

        val bar = (step / STEPS_PER_BAR) % BARS
        val s = step % STEPS_PER_BAR

        // 底鼓：每拍一次
        if (s % 4 == 0) addKick(n, 0.55)

        // 军鼓：二、四拍
        if (s == 4 || s == 12) addNoise(n, 0.20, decay = 26.0, tone = 0.35)

        // 踩镲：反拍
        if (s % 2 == 1) addNoise(n, 0.055, decay = 60.0)

        // 第四小节末尾的鼓花，让循环接缝不那么规整
        if (bar == BARS - 1 && s >= 12) addNoise(n, 0.16, decay = 40.0, tone = 0.25)

        // 贝斯：八分音符；每小节最后一拍走高八度做推进
        if (s % 2 == 0) {
            val root = bassRoot[bar]
            addTone(n, midiToFreq(if (s >= 14) root + 12 else root), SAW, 0.30, decay = 1.5)
        }

        // 琶音：每步一个音，每四步换一次八度
        val arpNote = arpNotes[bar][s % 4] + if ((s / 4) % 2 == 1) 12 else 0
        addTone(n, midiToFreq(arpNote), SQUARE, 0.115, decay = 3.0)
        if (hasDoubleArp(level)) {
            addTone(n, midiToFreq(arpNote + 12), SQUARE, 0.06, decay = 4.0)
        }

        // 主旋律：八分音符，等级够了才进来
        if (hasLead(level) && s % 2 == 0) {
            addTone(n, midiToFreq(leadNotes[bar][s / 2]), TRIANGLE, 0.19, decay = 2.2)
        }
    }

    /** 把内部混音缓冲的前 [n] 个采样转成 16 位 PCM。 */
    fun toPcm(n: Int, out: ShortArray) {
        for (i in 0 until n) {
            val v = (mix[i] * MASTER).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** 按指定等级把完整一圈（四小节）渲染成一个 PCM 数组，用于试听导出。 */
    fun renderLoop(level: Int): ShortArray {
        val n = stepSamplesFor(level)
        val out = ShortArray(n * TOTAL_STEPS)
        val tmp = ShortArray(n)
        for (step in 0 until TOTAL_STEPS) {
            renderStep(step, n, level)
            toPcm(n, tmp)
            System.arraycopy(tmp, 0, out, step * n, n)
        }
        return out
    }

    // ------------------------------------------------------------ 波形

    private fun midiToFreq(note: Int): Double = 440.0 * Math.pow(2.0, (note - 69) / 12.0)

    private fun addTone(n: Int, freq: Double, wave: Int, amp: Double, decay: Double) {
        val inc = freq / SAMPLE_RATE
        val attack = 48
        var phase = 0.0
        for (i in 0 until n) {
            val raw = when (wave) {
                SQUARE -> if (phase < 0.5) 1.0 else -1.0
                SAW -> 2.0 * phase - 1.0
                TRIANGLE -> 4.0 * abs(phase - 0.5) - 1.0
                else -> sin(2.0 * PI * phase)
            }
            val env = exp(-decay * i / n) * if (i < attack) i.toDouble() / attack else 1.0
            bufAdd(i, raw * env * amp)
            phase += inc
            if (phase >= 1.0) phase -= 1.0
        }
    }

    /** 底鼓：正弦做 145Hz → 45Hz 的快速下滑。 */
    private fun addKick(n: Int, amp: Double) {
        val len = minOf(n, (SAMPLE_RATE * 0.14).toInt())
        var phase = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-9.0 * t)
            bufAdd(i, sin(2.0 * PI * phase) * env * amp)
            phase += (45.0 + 100.0 * exp(-30.0 * t)) / SAMPLE_RATE
        }
    }

    /** 噪声用于军鼓与踩镲；[tone] 给军鼓叠一点音高成分。 */
    private fun addNoise(n: Int, amp: Double, decay: Double, tone: Double = 0.0) {
        for (i in 0 until n) {
            val env = exp(-decay * i / n)
            val noise = rnd.nextDouble() * 2.0 - 1.0
            val body =
                if (tone > 0.0) noise + tone * sin(2.0 * PI * 190.0 * i / SAMPLE_RATE) else noise
            bufAdd(i, body * env * amp)
        }
    }

    private fun bufAdd(i: Int, v: Double) {
        if (i < mix.size) mix[i] += v.toFloat()
    }
}
