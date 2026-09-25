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
 * ## 音乐风格
 *
 * 舒缓的合成器氛围乐：Am7 - Fmaj7 - Cmaj7 - Em7 四小节循环，**完全没有鼓组**。
 * 三层织体：
 *  - 铺底和弦：正弦波，慢起音慢收尾，跨整小节持续，相邻和弦自然交叠；
 *  - 低音：正弦波，整小节长音；
 *  - 旋律：三角波加长衰减，像柔和的钟琴，每小节只有两三个音，留白很足。
 *
 * ## 为什么需要「跨步」的声音模型
 *
 * 早先的版本是「每个音符都在一步内衰减完」的打击乐写法，做不出舒缓的效果——
 * 舒缓的核心就是长音。所以这里改成 [Voice]：每个声音记录自己的起始采样与总时长，
 * 渲染某一步时只画它与这一步重叠的部分，因此一个音可以持续任意长。
 */
class BgmSynth(seed: Long = System.nanoTime()) {

    companion object {
        const val SAMPLE_RATE = 44100

        /** 每小节 8 步 —— 一步是一个八分音符。 */
        const val STEPS_PER_BAR = 8
        const val BARS = 4
        const val TOTAL_STEPS = STEPS_PER_BAR * BARS

        /** 一步最多多少采样：70 BPM 的八分音符约 18900 点，留些余量。 */
        const val MAX_STEP_SAMPLES = 24576

        /** 总音量。压得比音效低，避免盖过操作反馈。 */
        private const val MASTER = 0.60

        private const val SINE = 0
        private const val TRIANGLE = 1

        /** 速度映射：1 级 70 BPM，每级 +3，封顶 110。舒缓的曲子不该加速太猛。 */
        fun bpmForLevel(level: Int): Double =
            (70.0 + (level - 1).coerceAtLeast(0) * 3.0).coerceAtMost(110.0)

        /** 4 级起加入一句高八度的对位旋律。 */
        fun hasCounterMelody(level: Int): Boolean = level >= 4

        /** 8 级起加一层很轻的高频泛音，让织体更通透。 */
        fun hasShimmer(level: Int): Boolean = level >= 8

        /** 某个等级的八分音符对应多少采样。 */
        fun stepSamplesFor(level: Int): Int =
            (SAMPLE_RATE * (60.0 / bpmForLevel(level) / 2.0)).toInt()
                .coerceIn(64, MAX_STEP_SAMPLES)
    }

    /** 一个持续发声的音。可以跨越任意多个步。 */
    private class Voice(
        val start: Int,      // 起始采样（相对循环起点）
        val length: Int,     // 总时长（采样）
        val freq: Double,
        val wave: Int,
        val amp: Double,
        val attack: Double,  // 起音占时长的比例
        val release: Double, // 收尾占时长的比例
        val decay: Double    // 持续段的指数衰减，0 = 不衰减
    )

    private val rnd = Random(seed)
    private val mix = FloatArray(MAX_STEP_SAMPLES)

    private var voices: List<Voice> = emptyList()
    private var builtForStepSamples = -1
    private var builtForLevel = -1

    // 四小节的和弦，用七和弦让色彩柔和一些（MIDI 音高）
    private val chords = arrayOf(
        intArrayOf(57, 60, 64, 67), // Am7  : A3 C4 E4 G4
        intArrayOf(57, 60, 65, 69), // Fmaj7: A3 C4 F4 A4
        intArrayOf(55, 60, 64, 67), // Cmaj7: G3 C4 E4 G4
        intArrayOf(55, 59, 62, 67)  // Em7  : G3 B3 D4 G4
    )

    /** 每小节的低音根音。 */
    private val bassRoot = intArrayOf(45, 41, 48, 40)

    /**
     * 旋律：(起始步, 持续步数, MIDI 音高)。
     * 每小节只有两三个音，留白是刻意的 —— 音符排满就不叫舒缓了。
     */
    private val melody = arrayOf(
        intArrayOf(0, 3, 76), intArrayOf(5, 3, 72),   // Am7  : E5 C5
        intArrayOf(8, 3, 69), intArrayOf(13, 4, 72),  // Fmaj7: A4 C5
        intArrayOf(16, 3, 67), intArrayOf(21, 4, 76), // Cmaj7: G4 E5
        intArrayOf(24, 4, 74), intArrayOf(29, 2, 71)  // Em7  : D5 B4
    )

    /** 高八度对位旋律，4 级后进入。 */
    private val counterMelody = arrayOf(
        intArrayOf(2, 4, 84), intArrayOf(10, 5, 81),
        intArrayOf(18, 4, 79), intArrayOf(26, 5, 83)
    )

    // ------------------------------------------------------------ 渲染

    /** 渲染第 [step] 步到内部混音缓冲，共 [n] 个采样。 */
    fun renderStep(step: Int, n: Int, level: Int) {
        // 速度或等级变化时重建声音表：位置是按采样算的，换了步长就得重算
        if (n != builtForStepSamples || level != builtForLevel) {
            voices = buildVoices(n, level)
            builtForStepSamples = n
            builtForLevel = level
        }

        java.util.Arrays.fill(mix, 0, n, 0f)
        val base = step * n
        val end = base + n

        for (v in voices) {
            val from = maxOf(v.start, base)
            val to = minOf(v.start + v.length, end)
            if (from >= to) continue

            val inc = v.freq / SAMPLE_RATE
            val attackLen = v.attack * v.length
            val releaseStart = (1.0 - v.release) * v.length

            for (p in from until to) {
                val age = (p - v.start).toDouble()
                val t = age / v.length

                // 包络：起音斜坡 × 收尾斜坡 × 持续段衰减
                var env = 1.0
                if (age < attackLen) env *= age / attackLen
                if (age > releaseStart) env *= (v.length - age) / (v.length - releaseStart)
                if (v.decay > 0.0) env *= exp(-v.decay * t)

                val phase = (age * inc) % 1.0
                val raw = if (v.wave == TRIANGLE) 4.0 * abs(phase - 0.5) - 1.0
                else sin(2.0 * PI * phase)
                mix[p - base] += (raw * env * v.amp).toFloat()
            }
        }
    }

    /** 把内部混音缓冲的前 [n] 个采样转成 16 位 PCM。 */
    fun toPcm(n: Int, out: ShortArray) {
        for (i in 0 until n) {
            val v = (mix[i] * MASTER).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** 按指定等级把完整一圈渲染成一个 PCM 数组，用于试听导出与测试。 */
    fun renderLoop(level: Int): ShortArray {        val n = stepSamplesFor(level)
        voices = buildVoices(n, level)
        builtForStepSamples = n
        builtForLevel = level

        val out = ShortArray(n * TOTAL_STEPS)
        val tmp = ShortArray(n)
        for (step in 0 until TOTAL_STEPS) {
            renderStep(step, n, level)
            toPcm(n, tmp)
            System.arraycopy(tmp, 0, out, step * n, n)
        }
        return out
    }

    // ------------------------------------------------------------ 编曲

    private fun buildVoices(stepSamples: Int, level: Int): List<Voice> {
        val out = ArrayList<Voice>(64)

        fun add(
            startStep: Double,
            steps: Double,
            freq: Double,
            wave: Int,
            amp: Double,
            attack: Double,
            release: Double,
            decay: Double
        ) {
            val start = (startStep * stepSamples).toInt()
            val length = (steps * stepSamples).toInt().coerceAtLeast(256)
            out.add(Voice(start, length, freq, wave, amp, attack, release, decay))
        }

        for (bar in 0 until BARS) {
            val barStart = (bar * STEPS_PER_BAR).toDouble()

            // 铺底和弦：持续 1.4 小节，与下一小节的和弦交叠。
            // 如果只铺满整小节，收尾会落在下个和弦的起音之前，交界处就出现一个凹陷，
            // 听感变成一顿一顿的；交叠之后和弦切换是渐变而不是断点。
            for (note in chords[bar]) {
                // 两路略微失谐的正弦叠出温暖感；失谐量与音量带一点随机，
                // 每局的织体因此略有不同（同种子仍可复现）。
                val detune = 1.0 + (rnd.nextDouble() - 0.5) * 0.006
                val vary = 0.9 + rnd.nextDouble() * 0.2
                add(
                    barStart, STEPS_PER_BAR * 1.4, midiToFreq(note), SINE,
                    0.070 * vary, 0.12, 0.45, 0.28
                )
                add(
                    barStart, STEPS_PER_BAR * 1.4, midiToFreq(note) * detune, SINE,
                    0.040 * vary, 0.15, 0.45, 0.28
                )
            }

            // 低音：略长于一小节，同样与下一小节交叠
            add(
                barStart, STEPS_PER_BAR * 1.25, midiToFreq(bassRoot[bar]), SINE,
                0.175, 0.14, 0.42, 0.20
            )
        }

        // 旋律：三角波 + 缓慢衰减，像柔和的钟琴。
        // 注意 release 不能给太大 —— 试过 0.70，结果是音符在前四分之一就衰减殆尽，
        // 单看「有没有这个音」的代码是对的，实际却几乎听不见。
        for (m in melody) {
            add(
                m[0].toDouble(), m[1].toDouble(), midiToFreq(m[2]), TRIANGLE,
                0.115, 0.06, 0.55, 1.1
            )
        }

        if (hasCounterMelody(level)) {
            for (m in counterMelody) {
                add(
                    m[0].toDouble(), m[1].toDouble(), midiToFreq(m[2]), SINE,
                    0.145, 0.10, 0.55, 1.2
                )
            }
        }

        if (hasShimmer(level)) {
            // 很轻的高频泛音，只点缀在每小节开头
            for (bar in 0 until BARS) {
                add(
                    (bar * STEPS_PER_BAR).toDouble(), 3.0, midiToFreq(88), SINE,
                    0.080, 0.08, 0.70, 1.8
                )
            }
        }

        // 循环接缝：末尾那几小节的长音会拖过循环终点，如果不处理，
        // 每次循环回头时这段尾巴就被切掉了。把它们按循环长度平移一份到开头。
        val loopSamples = TOTAL_STEPS * stepSamples
        val wrapped = ArrayList<Voice>(out.size + 8)
        for (v in out) {
            wrapped.add(v)
            if (v.start + v.length > loopSamples) {
                wrapped.add(
                    Voice(
                        v.start - loopSamples, v.length, v.freq, v.wave,
                        v.amp, v.attack, v.release, v.decay
                    )
                )
            }
        }
        return wrapped
    }

    private fun midiToFreq(note: Int): Double = 440.0 * Math.pow(2.0, (note - 69) / 12.0)

    /**
     * 某个等级下会发出多少个声音。
     * 用于测试「编曲随等级变厚」这一约定 —— 直接比较渲染后的 RMS 会被
     * 速度变化干扰（等级高时循环更短），用声音数量更准确。
     */
    internal fun voiceCountFor(level: Int): Int =
        buildVoices(stepSamplesFor(level), level).size
}
