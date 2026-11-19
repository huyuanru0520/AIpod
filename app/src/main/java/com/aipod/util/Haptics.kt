package com.aipod.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.aipod.R

/**
 * iPod 转轮的触感 + 音效：用震动 + 短促「咔哒」声模拟实体滚轮的「一格」。
 *
 * 震动走两条通道叠加（部分 ROM 上低幅度短脉冲完全无感）：
 *   1) View.performHapticFeedback —— 系统级触摸反馈，厂商 ROM 支持最好；
 *   2) Vibrator + VibrationAttributes(USAGE_MEDIA) —— 明确用途，避开后台震动限制。
 * 音效走 SoundPool 播放打包好的短 WAV（iPod 风格的 tick / click）。
 *
 * 强度由设置页控制：0=关闭（震动与音效都不触发），1/2/3 逐级放大。
 * 首次触发写一行日志，方便从 AIpod_log.txt 判断设备到底走通了哪条。
 */
object Haptics {

    private var cached: Vibrator? = null
    private var logged = false

    /** 0=关 / 1=弱 / 2=中 / 3=强；由 App 启动与设置变更时同步。默认中档。 */
    @Volatile
    private var strength = 2

    /** 音效播放器：懒加载，注入 context 后首次 tick/click 时初始化。 */
    private var soundPool: SoundPool? = null
    private var tickSoundId = 0
    private var clickSoundId = 0

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

    /** App 启动时注入：同步震动强度并加载音效资源。 */
    fun init(context: Context, initialStrength: Int) {
        strength = initialStrength.coerceIn(0, 3)
        ensureSoundPool(context.applicationContext)
    }

    /** 设置页变更后同步强度。 */
    fun setStrength(level: Int) { strength = level.coerceIn(0, 3) }

    private fun ensureSoundPool(context: Context) {
        if (soundPool != null) return
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            tickSoundId = soundPool!!.load(context, R.raw.wheel_tick, 1)
            clickSoundId = soundPool!!.load(context, R.raw.wheel_click, 1)
        }.onFailure { CrashLogger.error("Wheel.sound.init", it) }
    }

    /** 转轮滚过一格：短、脆，像金属齿轮的一下。 */
    fun tick(context: Context, view: View?) {
        if (strength <= 0) return
        playSound(context, tickSoundId, volumeFor(0.55f))
        fire(context, view, tickFeedback, 12L, 95, "tick")
    }

    /** 按下 MENU / 切歌 / 播放键：略长，像机械按键到位。 */
    fun click(context: Context, view: View?) {
        if (strength <= 0) return
        playSound(context, clickSoundId, volumeFor(0.9f))
        fire(context, view, clickFeedback, 32L, 180, "click")
    }

    /** 强度档位 → 音效音量（0 档已在调用处拦截）。 */
    private fun volumeFor(base: Float): Float = when (strength) {
        1 -> base * 0.4f
        2 -> base * 0.7f
        else -> base
    }

    private fun playSound(context: Context, soundId: Int, volume: Float) {
        if (soundId == 0) return
        ensureSoundPool(context)
        runCatching { soundPool?.play(soundId, volume, volume, 1, 0, 1f) }
            .onFailure { CrashLogger.error("Wheel.sound.play", it) }
    }

    /** 强度档位 → 震动幅度（1..255）；强度越高幅度越大。 */
    private fun ampFor(base: Int): Int {
        val scaled = when (strength) {
            1 -> (base * 0.45f).toInt()
            2 -> (base * 0.75f).toInt()
            else -> base
        }
        return scaled.coerceIn(1, 255)
    }

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
                val effect = VibrationEffect.createOneShot(ms, ampFor(amp))
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
                "SDK=${Build.VERSION.SDK_INT} device=${Build.DEVICE} strength=$strength " +
                    "viewFeedback=$viewOk hasVibrator=$hasVibrator " +
                    "($tag ${ms}ms/${ampFor(amp)}/255)"
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
