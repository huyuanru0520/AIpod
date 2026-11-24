package com.aipod.util

/**
 * 应用前后台标志，由 MainActivity 的 onStart / onStop 维护。
 *
 * 供纯 UI 的高频循环（环境光流光、波形时钟、音量条流光、进度轮询）在后台降频省电：
 * 切后台时这些画面不可见，继续 60fps 重绘 / 500ms 轮询是纯耗电。这些循环读取 [active]，
 * 后台时降到低频空转、不更新绘制值；回前台后的第一帧按墙钟时间直接算出正确相位，
 * 视觉连续、不会跳变。
 *
 * 播放解码 / MediaSession / 前台服务不读这个标志，因此后台播放功能完全不受影响。
 */
object AppForeground {
    @Volatile var active: Boolean = true
        private set

    fun set(foreground: Boolean) {
        active = foreground
    }
}
