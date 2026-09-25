package com.neon.tetris

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * 零资源音效：启动时用波形合成 PCM，写进静态 [AudioTrack]。
 *
 * 好处是 APK 里一个 wav/ogg 都不需要，音色也天然统一为 chiptune 风格。
 * 每种音效独占一条 track，因此可以叠加播放（比如同时锁定与消行）。
 * 任何一步失败都静默降级为「没有声音」，绝不因为音频问题影响游戏。
 */
class Sfx {

    companion object {
        const val MOVE = 0
        const val ROTATE = 1
        const val LOCK = 2
        const val HARD_DROP = 3
        const val HOLD = 4
        const val CLEAR = 5
        const val TETRIS = 6
        const val LEVEL_UP = 7
        const val GAME_OVER = 8

        private const val COUNT = 9
        private const val SAMPLE_RATE = 44100
        private const val MIN_STATIC_FRAMES = 2048
        private const val WAVE_SQUARE = 0
        private const val WAVE_TRIANGLE = 1
        private const val WAVE_SINE = 2
        private const val WAVE_SAW = 3
    }

    /** 由 UI 的音效开关控制。 */
    @Volatile
    var enabled: Boolean = true

    private val tracks = arrayOfNulls<AudioTrack>(COUNT)
    private var lastMoveAt = 0L

    init {
        build()
    }

    private fun build() {
        put(MOVE, tone(220.0, 30, WAVE_SQUARE, 0.16))
        put(ROTATE, tone(430.0, 45, WAVE_TRIANGLE, 0.22))
        put(LOCK, tone(150.0, 70, WAVE_SINE, 0.32))
        put(HARD_DROP, tone(95.0, 90, WAVE_SAW, 0.30))
        put(HOLD, tone(660.0, 60, WAVE_TRIANGLE, 0.24))
        put(CLEAR, arpeggio(intArrayOf(520, 700, 880), 90, WAVE_SQUARE, 0.26))
        put(TETRIS, arpeggio(intArrayOf(600, 800, 1000, 1250), 105, WAVE_SQUARE, 0.28))
        put(LEVEL_UP, arpeggio(intArrayOf(500, 660, 830), 80, WAVE_TRIANGLE, 0.24))
        put(GAME_OVER, arpeggio(intArrayOf(340, 270, 200, 140), 190, WAVE_SAW, 0.28))
    }

    private fun put(id: Int, samples: ShortArray) {
        tracks[id] = createTrack(padToMinimum(samples))
    }

    /**
     * 部分设备对静态 AudioTrack 的缓冲区有下限要求，太短会构造失败（表现为该音效静默消失）。
     * 统一补零到 2048 帧（约 46ms），多出的静音在听感上没有影响。
     */
    private fun padToMinimum(samples: ShortArray): ShortArray {
        if (samples.size >= MIN_STATIC_FRAMES) return samples
        return ShortArray(MIN_STATIC_FRAMES).also { samples.copyInto(it) }
    }

    private fun createTrack(samples: ShortArray): AudioTrack? = try {
        // 注意：这里不能再写 val 声明，否则 try 块的值会变成 Unit
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(samples, 0, samples.size) }
    } catch (_: Throwable) {
        null
    }

    fun play(id: Int) {
        if (!enabled) return
        val track = tracks.getOrNull(id) ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: Throwable) {
            // 忽略：音频不可用不应影响游戏
        }
    }

    /** 水平移动会高频触发，做一次节流，避免连发时听感糊成一片。 */
    fun playMove() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveAt < 45L) return
        lastMoveAt = now
        play(MOVE)
    }

    fun release() {
        for (i in 0 until COUNT) {
            val track = tracks[i] ?: continue
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (_: Throwable) {
                // 忽略
            }
            tracks[i] = null
        }
    }

    // ------------------------------------------------------------ 波形合成

    /** 带指数衰减包络与短促淡入的单音，淡入用于消除起播爆音。 */
    private fun tone(
        freq: Double,
        durationMs: Int,
        wave: Int,
        volume: Double,
        decay: Double = 3.5
    ): ShortArray {
        val n = (SAMPLE_RATE * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        val attack = (SAMPLE_RATE * 0.002).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val phase = (i.toDouble() / SAMPLE_RATE * freq) % 1.0
            val raw = when (wave) {
                WAVE_SQUARE -> if (phase < 0.5) 1.0 else -1.0
                WAVE_TRIANGLE -> 4.0 * abs(phase - 0.5) - 1.0
                WAVE_SAW -> 2.0 * phase - 1.0
                else -> sin(2.0 * PI * phase)
            }
            val envelope = exp(-decay * i / n) * if (i < attack) i.toDouble() / attack else 1.0
            out[i] = (raw * envelope * volume * Short.MAX_VALUE).toInt()
                .coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 把若干音高顺序拼成一段琶音。 */
    private fun arpeggio(
        freqs: IntArray,
        stepMs: Int,
        wave: Int,
        volume: Double
    ): ShortArray {
        val parts = freqs.map { tone(it.toDouble(), stepMs, wave, volume) }
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }
}
