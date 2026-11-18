package com.musicbox.nas.util

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * iPod 转轮的触感：用震动模拟实体滚轮的「一格」。
 *
 * 荣耀 / ColorOS / MIUI 等 ROM 上，Vibrator.vibrate() 的低幅度短脉冲经常完全无感，
 * 甚至 hasVibrator() 会返回 false。这里走两条通道叠加：
 *   1) View.performHapticFeedback —— 系统级触摸反馈，厂商 ROM 支持最好；
 *   2) Vibrator + VibrationAttributes(USAGE_MEDIA) —— 明确指定用途，避开后台震动限制。
 * 首次触发会写一行日志，方便从 NASMusic_log.txt 判断设备到底走通了哪条。
 */
object Haptics {

    private var cached: Vibrator? = null
    private var logged = false

    private val tickFeedback: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            HapticFeedbackConstants.CLOCK_TICK
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }

    private val clickFeedback: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            HapticFeedbackConstants.VIRTUAL_KEY
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }

    /** 转轮滚过一格：短、脆，像金属齿轮的一下。 */
    fun tick(context: Context, view: View?) =
        fire(context, view, tickFeedback, 28L, 200, "tick")

    /** 按下 MENU / 切歌 / 播放键：略长，像机械按键到位。 */
    fun click(context: Context, view: View?) =
        fire(context, view, clickFeedback, 46L, 235, "click")

    private fun fire(context: Context, view: View?, feedback: Int, ms: Long, amp: Int, tag: String) {
        var viewOk = false
        if (view != null) {
            runCatching {
                @Suppress("DEPRECATION")
                view.performHapticFeedback(
                    feedback,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                viewOk = true
            }.onFailure { CrashLogger.error("Wheel.haptic.view[$tag]", it) }
        }

        val v = vibrator(context)
        val hasVibrator = v?.hasVibrator() == true
        v?.takeIf { hasVibrator }?.let { vib ->
            runCatching {
                val effect = VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_MEDIA)
                        .build()
                    vib.vibrate(effect, attrs)
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(effect)
                }
            }.onFailure { CrashLogger.error("Wheel.haptic.vibrator[$tag]", it) }
        }

        if (!logged) {
            logged = true
            CrashLogger.info(
                "Wheel.haptic",
                "SDK=${Build.VERSION.SDK_INT} device=${Build.DEVICE} " +
                    "viewFeedback=$viewOk hasVibrator=$hasVibrator " +
                    "($tag ${ms}ms/${amp}/255)"
            )
        }
    }

    private fun vibrator(ctx: Context): Vibrator? {
        cached?.let { return it }
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        cached = v
        return v
    }
}
