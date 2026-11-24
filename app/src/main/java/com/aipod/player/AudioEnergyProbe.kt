package com.aipod.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.aipod.ui.components.WaveAmplitude
import com.aipod.util.CrashLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 从 ExoPlayer 解码后的 PCM 里实时提取能量，喂给波形律动数据源。
 *
 * 用 Media3 官方 [TeeAudioProcessor]：它把音频 buffer 以只读副本交给 sink，
 * 随后把**原始字节原样**拷回输出流，所以：
 * - 不改任何采样字节 → bit-perfect / 音质零影响；
 * - 不需要 RECORD_AUDIO 权限 → 不弹授权；
 * - 拿的是解码后的裸 PCM → 真·随当前播放的音乐起伏。
 */
object AudioEnergyProbe {

    /** 交给 DefaultAudioSink.Builder.setAudioProcessors 的 processor。 */
    fun newProcessor(): TeeAudioProcessor {
        CrashLogger.debug("WaveProbe") { "newProcessor 创建" }
        return TeeAudioProcessor(Sink)
    }

    private object Sink : TeeAudioProcessor.AudioBufferSink {
        private var encoding = C.ENCODING_PCM_16BIT
        // 自适应峰值：缓慢跟随最近的 RMS，把不同音量的曲子归一化到 0..1
        private var peak = 0.06f
        // 阻尼后的能量
        private var smooth = 0f
        // 诊断节流
        private var flushCount = 0
        private var bufferCount = 0

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            // 切歌 / seek / 格式变化时调用：记住新格式，清掉阻尼避免从旧曲突跳
            this.encoding = encoding
            smooth = 0f
            if (flushCount < 5) {
                val n = flushCount
                CrashLogger.debug("WaveProbe") { "flush#$n sr=$sampleRateHz ch=$channelCount enc=$encoding" }
                flushCount++
            }
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            // Tee 给的是只读副本，可随意移动 position；PCM 一律 little-endian
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val ch: Float = when (encoding) {
                C.ENCODING_PCM_FLOAT -> rmsFloat(b)
                C.ENCODING_PCM_16BIT -> rms16(b)
                C.ENCODING_PCM_24BIT -> rms24(b)
                C.ENCODING_PCM_32BIT -> rms32(b)
                C.ENCODING_PCM_8BIT -> rms8(b)
                else -> return
            }

            // 自适应峰值：快涨慢落，让归一化对不同音量的曲子都稳定
            peak = if (ch > peak) ch * 0.75f + peak * 0.25f
                   else peak * 0.9995f + ch * 0.0005f
            val norm = (ch / maxOf(peak, 0.008f)).coerceIn(0f, 1f)
            // 轻度抬升低能量段，让起伏更明显
            val shaped = sqrt(norm)
            // 阻尼平滑后再喂给波形层（绘制层还有一层按 dt 的平滑）
            smooth += (shaped - smooth) * 0.35f

            if (bufferCount % 200 == 0) {
                val n = bufferCount
                val enc = encoding
                val rms = ch
                val remain = buffer.remaining()
                CrashLogger.debug("WaveProbe") {
                    "buffer#$n enc=$enc bytes=$remain rms=$rms norm=$norm smooth=$smooth"
                }
            }
            bufferCount++

            WaveAmplitude.feedAmplitude(smooth)
        }

        private fun rmsFloat(b: ByteBuffer): Float {
            val f = b.asFloatBuffer()
            var sum = 0f
            var n = 0
            while (f.hasRemaining()) {
                val v = f.get()
                sum += v * v
                n++
            }
            return if (n == 0) 0f else sqrt(sum / n)
        }

        private fun rms16(b: ByteBuffer): Float {
            val s = b.asShortBuffer()
            var sum = 0f
            var n = 0
            while (s.hasRemaining()) {
                val v = s.get().toInt() / 32768f
                sum += v * v
                n++
            }
            return if (n == 0) 0f else sqrt(sum / n)
        }

        private fun rms24(b: ByteBuffer): Float {
            var sum = 0f
            var n = 0
            while (b.remaining() >= 3) {
                val raw = (b.get().toInt() and 0xFF) or
                    ((b.get().toInt() and 0xFF) shl 8) or
                    ((b.get().toInt() and 0xFF) shl 16)
                val signed = if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
                val v = signed / 8388608f
                sum += v * v
                n++
            }
            return if (n == 0) 0f else sqrt(sum / n)
        }

        private fun rms32(b: ByteBuffer): Float {
            val i = b.asIntBuffer()
            var sum = 0f
            var n = 0
            while (i.hasRemaining()) {
                val v = i.get() / 2147483648f
                sum += v * v
                n++
            }
            return if (n == 0) 0f else sqrt(sum / n)
        }

        private fun rms8(b: ByteBuffer): Float {
            var sum = 0f
            var n = 0
            while (b.hasRemaining()) {
                val v = ((b.get().toInt() and 0xFF) - 128) / 128f
                sum += v * v
                n++
            }
            return if (n == 0) 0f else sqrt(sum / n)
        }
    }
}
