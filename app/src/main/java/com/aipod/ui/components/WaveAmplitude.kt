package com.aipod.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin

/**
 * 波形律动的振幅「目标值」数据源（归一化 0..1）。
 *
 * 振幅由两部分叠加，观赏性优先、且绝不恒定一个频率：
 * - 观赏性包络（AMBIENT_*）：三个**不成整数比**的正弦叠加，产生不规则、起伏不一的呼吸，
 *   不是单一频率的匀速起伏——这是「起伏不一」的关键；
 * - 真实音乐能量（LIVE_GAIN）：由 [com.aipod.player.AudioEnergyProbe] 从解码 PCM 提取的 RMS，
 *   通过 [feedAmplitude] 推送，主导振幅大小，让波形跟着鼓点/音量忽大忽小。
 *
 * 暂停 / 切歌 / 缓冲超过 [STALE_MS] 没有音频数据时，live 归零，只剩不规则自发包络；
 * 播放态的「缓慢变直线」由绘制层的播放门控（playGate）负责，本对象不处理。
 *
 * 平滑在能量采集层（AudioEnergyProbe）做；自发包络本身是连续多频正弦，无需阻尼。
 */
object WaveAmplitude {
    enum class Source { LIVE, SIMULATED }

    // ===== 观赏性增益旋钮（纯视觉，不依赖音乐；想更夸张/收敛，改这几个数即可）=====
    private const val BASE_AMP = 0.12f      // 基础振幅：保证有可见起伏
    private const val AMBIENT_GAIN = 0.32f  // 不规则自发包络的权重
    private const val LIVE_GAIN = 0.62f     // 真实音乐能量的权重（主导，起伏不一）

    private const val STALE_MS = 500L

    private val _source = MutableStateFlow(Source.SIMULATED)
    val source: StateFlow<Source> = _source

    @Volatile private var liveValue = 0f
    @Volatile private var lastFeedMs = 0L

    /** 振幅变化小于此阈值时不写 StateFlow（解码每 buffer 都会推，静止段值几乎不变）。 */
    private const val FEED_EPS = 0.004f

    /** 外部（PCM 能量分析器）推送 0..1；调用即视为 LIVE。 */
    fun feedAmplitude(value: Float) {
        val v = value.coerceIn(0f, 1f)
        // stale 判定的时间戳每次都更新（证明音频还在来），与值是否变化无关。
        lastFeedMs = android.os.SystemClock.elapsedRealtime()
        // 值几乎没变则跳过：避免每 buffer 一次 StateFlow.value 写入唤醒订阅者。
        if (_source.value == Source.LIVE && kotlin.math.abs(v - liveValue) < FEED_EPS) return
        liveValue = v
        _source.value = Source.LIVE
    }

    /** 强制回到模拟（设置里可关掉真律动时用；当前默认保持 LIVE）。 */
    fun useSimulated() {
        _source.value = Source.SIMULATED
    }

    /**
     * 返回 tSeconds 时刻的目标振幅 0..1（纯函数，供绘制帧实时调用）。
     *
     * 不规则自发包络（三个非整数比频率叠加，起伏不一）+ 真实音乐能量主导。
     * 不用单一频率正弦——那正是「一直是一个频率」的根源。
     */
    fun targetAt(tSeconds: Float): Float {
        val w = 2f * Math.PI.toFloat()
        val e1 = sin(tSeconds * w * 0.73f) * 0.5f + 0.5f
        val e2 = sin(tSeconds * w * 1.31f + 1.7f) * 0.5f + 0.5f
        val e3 = sin(tSeconds * w * 2.17f + 3.1f) * 0.5f + 0.5f
        // 权重不均等 → 叠加后没有固定周期，忽高忽低、起伏不一
        val ambient = (e1 * 0.50f + e2 * 0.32f + e3 * 0.18f)

        val fresh = android.os.SystemClock.elapsedRealtime() - lastFeedMs < STALE_MS
        val live = if (fresh && _source.value == Source.LIVE) liveValue else 0f

        return (BASE_AMP + ambient * AMBIENT_GAIN + live * LIVE_GAIN).coerceIn(0.10f, 1f)
    }
}
