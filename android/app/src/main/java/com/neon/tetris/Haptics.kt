package com.neon.tetris

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 触感反馈。方块落定、消行、游戏结束时给不同长度的短震，
 * 让操作有「物理感」而不用看屏幕。设备不支持时静默降级。
 */
class Haptics(context: Context) {

    @Volatile
    var enabled: Boolean = true

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    private val available: Boolean = vibrator?.hasVibrator() == true

    fun tick(durationMs: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        if (!enabled || !available) return
        val v = vibrator ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } catch (_: Throwable) {
            // 忽略
        }
    }
}
