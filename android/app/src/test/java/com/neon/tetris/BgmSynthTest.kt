package com.neon.tetris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 背景音乐的合成测试。
 *
 * [BgmSynth] 刻意做成纯 Kotlin，所以这些断言能在 JVM 上以毫秒级跑完，不用装到手机上听。
 * 除了验证正确性，还会导出一份 WAV 到 `app/build/bgm-preview.wav` 用于试听 —— 编曲改动
 * 不必每次都重新打包安装。
 */
class BgmSynthTest {

    private fun rms(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / samples.size)
    }

    private fun peak(samples: ShortArray): Int =
        samples.maxOf { abs(it.toInt()) }

    /** 直流偏置：应当接近 0，否则说明包络或波形有系统性偏移。 */
    private fun dcOffset(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) sum += s
        return sum / samples.size / 32768.0
    }

    // ------------------------------------------------------------ 速度映射

    @Test
    fun `速度随等级提升并封顶`() {
        assertEquals(70.0, BgmSynth.bpmForLevel(1), 0.001)
        assertEquals(79.0, BgmSynth.bpmForLevel(4), 0.001)
        assertEquals(103.0, BgmSynth.bpmForLevel(12), 0.001)
        // 15 级算出 112，被上限压到 110
        assertEquals("超过上限后应被压到 110", 110.0, BgmSynth.bpmForLevel(15), 0.001)
        assertEquals("等级 0 或负数按 1 级处理", 70.0, BgmSynth.bpmForLevel(0), 0.001)
    }

    @Test
    fun `等级越高每步采样数越少`() {
        val slow = BgmSynth.stepSamplesFor(1)
        val fast = BgmSynth.stepSamplesFor(9)
        assertTrue("等级高时八分音符应更短", fast < slow)
        // 70 BPM 的八分音符 = 0.4286s
        assertEquals(18900, slow)
        assertTrue("不得超过缓冲上限", slow <= BgmSynth.MAX_STEP_SAMPLES)
    }

    // ------------------------------------------------------------ 分层解锁

    @Test
    fun `对位旋律与泛音层按等级解锁`() {
        assertFalse(BgmSynth.hasCounterMelody(1))
        assertFalse(BgmSynth.hasCounterMelody(3))
        assertTrue(BgmSynth.hasCounterMelody(4))

        assertFalse(BgmSynth.hasShimmer(7))
        assertTrue(BgmSynth.hasShimmer(8))
    }

    @Test
    fun `编曲随等级变厚`() {
        // 直接比较渲染后的 RMS 会被速度变化干扰（等级高时循环更短、包络更紧凑），
        // 所以先用声音数量判断分层是否真的加上了。
        val l1 = BgmSynth(0L).voiceCountFor(1)
        val l4 = BgmSynth(0L).voiceCountFor(4)
        val l8 = BgmSynth(0L).voiceCountFor(8)
        assertTrue("4 级应加入对位旋律（1 级 $l1 -> 4 级 $l4）", l4 > l1)
        assertTrue("8 级应再加入泛音层（4 级 $l4 -> 8 级 $l8）", l8 > l4)

        // 光有层还不够 —— 曾经出现过「层加上了但振幅太小、实际听不见」的情况，
        // 所以再断言渲染出来的能量确实随之上升。
        val low = rms(BgmSynth(seed = 1L).renderLoop(1))
        val mid = rms(BgmSynth(seed = 1L).renderLoop(4))
        val high = rms(BgmSynth(seed = 1L).renderLoop(8))
        assertTrue("4 级能量应高于 1 级（$low vs $mid）", mid > low)
        assertTrue("8 级能量应高于 4 级（$mid vs $high）", high > mid)
    }

    @Test
    fun `各等级电平都在合理区间`() {
        for (lv in intArrayOf(1, 4, 8, 15)) {
            val loop = BgmSynth(seed = 7L).renderLoop(lv)
            val level = rms(loop)
            assertTrue("$lv 级不应是静音，RMS=$level", level > 0.02)
            assertTrue("$lv 级电平不应过高，RMS=$level", level < 0.45)
        }
    }

    // ------------------------------------------------------------ 输出质量

    @Test
    fun `渲染长度与步数一致`() {
        val synth = BgmSynth(seed = 1L)
        val loop = synth.renderLoop(1)
        assertEquals(BgmSynth.TOTAL_STEPS * BgmSynth.stepSamplesFor(1), loop.size)
    }

    @Test
    fun `不是静音且电平合理`() {
        val loop = BgmSynth(seed = 7L).renderLoop(1)
        val level = rms(loop)
        assertTrue("整体不应是静音，RMS=$level", level > 0.02)
        assertTrue("电平不应过高，RMS=$level", level < 0.45)
        assertTrue("不应有直流偏置，DC=${dcOffset(loop)}", abs(dcOffset(loop)) < 0.01)
    }

    @Test
    fun `几乎不削波`() {
        val loop = BgmSynth(seed = 99L).renderLoop(1)
        var clipped = 0
        for (s in loop) if (abs(s.toInt()) >= 32767) clipped++
        val ratio = clipped.toDouble() / loop.size
        // MASTER 压到 0.34，正常编曲下不应有可感知的削波
        assertTrue("削波比例过高：${"%.4f".format(ratio)}", ratio < 0.001)
        assertTrue("峰值不应持续贴顶", peak(loop) <= 32767)
    }

    @Test
    fun `每一步都被填满而不是只渲染开头`() {
        // 底鼓只覆盖一步的前 0.14s，若实现有误会剩下一段静音；
        // 这里检查整圈里靠后的采样同样有能量。
        val loop = BgmSynth(seed = 5L).renderLoop(1)
        val tail = loop.copyOfRange(loop.size * 9 / 10, loop.size)
        assertTrue("循环尾部不应是静音，RMS=${rms(tail)}", rms(tail) > 0.02)
    }

    @Test
    fun `相同种子渲染结果可复现`() {
        val a = BgmSynth(seed = 4242L).renderLoop(1)
        val b = BgmSynth(seed = 4242L).renderLoop(1)
        assertTrue("同种子应逐采样一致", a.contentEquals(b))
    }

    // ------------------------------------------------------------ 试听导出

    /**
     * 导出一段试听文件：同一首曲子在 1 / 4 / 9 级各放一整圈，
     * 可以直观听出「速度提升 + 编曲变厚」这两个设计。
     */
    @Test
    fun `导出试听 WAV`() {
        val sections = intArrayOf(1, 4, 9)
        val perSection = sections.map { BgmSynth(seed = 20260925L).renderLoop(it) }
        // 段与段之间插 0.35s 静音，便于分辨
        val gap = ShortArray((BgmSynth.SAMPLE_RATE * 0.35).toInt())

        val total = perSection.sumOf { it.size } + gap.size * (sections.size - 1)
        val joined = ShortArray(total)
        var offset = 0
        perSection.forEachIndexed { index, part ->
            System.arraycopy(part, 0, joined, offset, part.size)
            offset += part.size
            if (index < sections.size - 1) {
                offset += gap.size
            }
        }

        val out = File("build/bgm-preview.wav")
        out.parentFile?.mkdirs()
        writeWav(out, joined, BgmSynth.SAMPLE_RATE)

        assertTrue("试听文件应已写出", out.exists() && out.length() > 100_000)
        println("试听文件已导出：${out.absolutePath}（${joined.size / BgmSynth.SAMPLE_RATE} 秒）")
    }

    /** 手写 16 位单声道 WAV，避免为了导出一个文件引入音频库。 */
    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            fun le16(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }

            fun le32(v: Int) {
                le16(v and 0xFFFF)
                le16((v ushr 16) and 0xFFFF)
            }

            out.writeBytes("RIFF")
            le32(36 + dataBytes)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            le32(16)
            le16(1)            // PCM
            le16(1)            // 单声道
            le32(sampleRate)
            le32(sampleRate * 2)
            le16(2)            // 每帧字节数
            le16(16)           // 位深
            out.writeBytes("data")
            le32(dataBytes)
            for (s in samples) le16(s.toInt() and 0xFFFF)
        }
    }
}
