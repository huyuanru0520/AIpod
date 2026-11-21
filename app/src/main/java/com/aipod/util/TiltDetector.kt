package com.aipod.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * 设备倾斜检测器（双轴 + 角速度）：把手机的左右倾斜与前后俯仰转换成连续值，
 * 供封面倒影做有空间感的视差与动态模糊。
 *
 * 坐标系（锁竖屏）：加速度计静止读数约 (gX, ±9.8, gZ)，重力主分量在设备 Y 轴。
 * - 左右 tiltX = gX / 9.8 ≈ sin(左右倾角)
 * - 前后 tiltY = gZ / 9.8 ≈ sin(前后倾角)
 * 用重力分量直接归一化（而非 atan2/固定角度），好处：完全线性、直到 ±90° 才饱和，
 * 倾斜多少就连续输出多少，不会出现「只有中间/两端几个离散位置」的跳变。
 *
 * 输出：
 * - [tiltX] 左右倾斜 -1..1（右正左负）
 * - [tiltY] 前后俯仰 -1..1
 * - [speed] 归一化角速度 0..1，用于倾斜越快倒影越糊的动态模糊；停下平滑回 0。
 *
 * 连续性关键：低通只负责滤抖动、不设「变化阈值」——每个传感器事件都上报，
 * 靠 mainHandler 合并同一帧内的多次回调。阈值过滤会让慢速倾斜的小增量被全部丢弃，
 * 表现就是倒影只在少数几个位置跳、且顿挫。加速度传感器无需任何权限。
 */
class TiltDetector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val g = 9.81f // 标准重力，用作归一化分母

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var initialized = false

    @Volatile
    var tiltX: Float = 0f
        private set

    @Volatile
    var tiltY: Float = 0f
        private set

    @Volatile
    var speed: Float = 0f
        private set

    /** 兼容旧消费方：左右倾斜即 tiltX。 */
    val tilt: Float get() = tiltX

    /** 三参数回调 (tiltX, tiltY, speed)，主线程调用。 */
    var onTilt: ((Float, Float, Float) -> Unit)? = null

    fun isAvailable(): Boolean = accelerometer != null

    fun start() {
        val sm = sensorManager ?: return
        val acc = accelerometer ?: return
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        initialized = false
        mainHandler.removeCallbacks(publish)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        if (!initialized) {
            gravityX = ax
            gravityY = ay
            gravityZ = az
            initialized = true
            return
        }
        // 低通分离重力、滤掉手持线性抖动；0.78/0.22 兼顾平滑与跟手。
        gravityX = gravityX * 0.78f + ax * 0.22f
        gravityY = gravityY * 0.78f + ay * 0.22f
        gravityZ = gravityZ * 0.78f + az * 0.22f
        // 连续映射：直接用重力分量 / g，±90° 才饱和，全程线性无跳变。
        val nx = (gravityX / g).coerceIn(-1f, 1f)
        val ny = (gravityZ / g).coerceIn(-1f, 1f)
        // 角速度：单帧变化的低通，快速甩动接近 1，静止平滑回 0（驱动动态模糊档位）。
        val raw = (abs(nx - tiltX) + abs(ny - tiltY)) * 4f
        val ns = speed * 0.55f + raw.coerceIn(0f, 1f) * 0.45f
        tiltX = nx
        tiltY = ny
        speed = ns
        // 每帧上报（合并同帧回调）：Compose state 写相同值不会重绘，静止时无额外开销。
        mainHandler.removeCallbacks(publish)
        mainHandler.post(publish)
    }

    private val publish = Runnable {
        onTilt?.invoke(tiltX, tiltY, speed)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
