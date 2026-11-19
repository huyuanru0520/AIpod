package com.musicbox.nas.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * 摇一摇检测器：监听加速度传感器，在检测到一次「摇动」手势时回调 [onShake]。
 *
 * 判定逻辑：对三轴加速度做高通近似（去掉重力常量分量），当合加速度的瞬时变化量
 * 连续多次超过阈值时视为一次摇动；两次摇动之间有冷却时间，避免一次物理摇动触发多次。
 * 加速度传感器不需要任何权限。
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    companion object {
        // 合加速度变化阈值（m/s²）：正常手持微动远小于此值，明显摇晃才会超过。
        private const val SHAKE_THRESHOLD = 12f
        // 采样节流：两次有效判定至少间隔 90ms，抑制抖动。
        private const val SAMPLE_INTERVAL_MS = 90L
        // 摇动冷却：触发一次后 900ms 内不再触发。
        private const val COOLDOWN_MS = 900L
    }

    private var lastSampleAt = 0L
    private var lastShakeAt = 0L
    // 上一帧三轴（已减去重力），用于计算变化量
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var initialized = false

    /** 设备是否具备加速度传感器。 */
    fun isAvailable(): Boolean = accelerometer != null

    fun start() {
        val sm = sensorManager ?: return
        val acc = accelerometer ?: return
        // SENSOR_DELAY_UI ≈ 16~60ms 一次，足够捕捉摇动且不至于过耗电。
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        lastSampleAt = now

        // 低通分离重力：gravity = 0.9*g + 0.1*linear
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        if (!initialized) {
            lastX = ax; lastY = ay; lastZ = az
            initialized = true
            return
        }

        // 相邻采样差值近似去除重力常量，得到运动加速度变化量
        val deltaX = ax - lastX
        val deltaY = ay - lastY
        val deltaZ = az - lastZ
        lastX = ax; lastY = ay; lastZ = az

        val movement = abs(deltaX) + abs(deltaY) + abs(deltaZ)
        if (movement > SHAKE_THRESHOLD && now - lastShakeAt > COOLDOWN_MS) {
            lastShakeAt = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
