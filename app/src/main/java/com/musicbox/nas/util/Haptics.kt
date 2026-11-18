package com.musicbox.nas.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 极轻的触感反馈：给 iPod 转轮模拟「实体滚轮的一格」和「按键按下的段落感」。
 *
 * 不申请额外权限之外的东西：只用 VIBRATE，幅度压得很低（8~16ms、70~110/255），
 * 目的是「手上有一格一格的刻度」，而不是来电震动那种存在感。
 */
object Haptics {

    private var cached: Vibrator? = null

    private fun vibrator(ctx: Context): Vibrator? {
        cached?.let { if (it.hasVibrator()) return it }
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        cached = v
        return v?.takeIf { it.hasVibrator() }
    }

    /** 转轮转过一格：极短、轻。 */
    fun tick(ctx: Context) = buzz(ctx, 8L, 70)

    /** 按下实体按键：稍长一点，像机械按键到位。 */
    fun click(ctx: Context) = buzz(ctx, 16L, 120)

    private fun buzz(ctx: Context, ms: Long, amplitude: Int) {
        val v = vibrator(ctx) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }
}
