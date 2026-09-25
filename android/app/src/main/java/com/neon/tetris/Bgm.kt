package com.neon.tetris

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 背景音乐的播放部分：把 [BgmSynth] 逐拍渲染出的 PCM 推给流式 [AudioTrack]。
 *
 * 用流式而不是静态缓冲，是为了支持动态变速 —— 每一步都按当前等级重新计算 16 分音符时长。
 * 作曲与合成逻辑全在 [BgmSynth] 里，这个类只负责音频输出，因此没有什么可测的分支。
 *
 * 和 [Sfx] 一样不依赖任何音频资源文件，APK 里没有 wav/ogg。
 * 任何一步失败都静默降级为「没有音乐」，绝不影响游戏。
 */
class Bgm {

    /** 由 UI 的音效开关控制（本项目没有单独的音乐开关）。 */
    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var level: Int = 1

    @Volatile
    private var running: Boolean = false

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    private val synth = BgmSynth()
    private val pcm = ShortArray(BgmSynth.MAX_STEP_SAMPLES)

    fun setLevel(value: Int) {
        level = value.coerceIn(1, 20)
    }

    /** 开始播放。已在播放时是空操作。 */
    fun start() {
        if (running || !enabled) return
        running = true
        thread = Thread({ playLoop() }, "neon-bgm").apply {
            isDaemon = true
            start()
        }
    }

    /** 停止并回收音频轨。可重复调用。 */
    fun stop() {
        if (!running && thread == null) return
        running = false
        // 让可能阻塞在 write() 上的合成线程立刻返回
        try {
            track?.pause()
            track?.flush()
        } catch (_: Throwable) {
            // 忽略
        }
        thread?.let {
            try {
                it.join(400)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }

    fun release() = stop()

    // ------------------------------------------------------------ 播放线程

    private fun playLoop() {
        val t = createTrack()
        if (t == null) {
            running = false
            return
        }
        track = t
        var step = 0
        try {
            t.play()
            while (running) {
                val n = BgmSynth.stepSamplesFor(level)
                synth.renderStep(step, n, level)
                synth.toPcm(n, pcm)

                var written = 0
                while (written < n && running) {
                    val w = t.write(pcm, written, n - written)
                    if (w <= 0) break
                    written += w
                }
                if (running) step = (step + 1) % BgmSynth.TOTAL_STEPS
            }
        } catch (_: Throwable) {
            // 音频不可用就安静退出
        } finally {
            running = false
            try {
                t.stop()
                t.release()
            } catch (_: Throwable) {
                // 忽略
            }
            track = null
        }
    }

    private fun createTrack(): AudioTrack? = try {
        val minBuf = AudioTrack.getMinBufferSize(
            BgmSynth.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 约 200ms，够吸收调度抖动；太短会在低端机上断续
        val bytes = maxOf(minBuf, BgmSynth.SAMPLE_RATE / 5 * 2)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(BgmSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (_: Throwable) {
        null
    }
}
