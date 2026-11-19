package com.aipod.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.aipod.MusicBoxApp
import com.aipod.data.Track
import com.aipod.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.util.Collections

/**
 * AAC 256kbps 转码缓存。
 *
 * WebDAV / 飞牛都是直接流式播放原文件，服务端不做转码，所以「AAC 256kbps」档只能在本地转：
 * 先把源文件完整下载到缓存目录，用 MediaCodec 解码 → AAC-LC 256kbps 编码 → 写成 ADTS(.aac)，
 * 之后每次播放命中缓存即可秒开（也降低解码压力、方便老设备播放高解析文件）。
 *
 * 全程兜底：任何一步失败都返回 null，调用方继续用原文件播放，不影响出音。
 */
object AacTranscoder {

    private const val TAG = "Transcode"
    private const val BITRATE = 256_000
    private const val MAX_SOURCE_BYTES = 600L * 1024 * 1024
    /** 一次只转一首，避免多首歌同时解码把 CPU 打满导致正在播放的歌卡顿。 */
    private val gate = Semaphore(1)
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    private fun dir(): File = File(MusicBoxApp.instance.cacheDir, "aac256").apply { mkdirs() }

    private fun outFile(track: Track) =
        File(dir(), "t_${track.id.hashCode().toUInt().toString(16)}.aac")

    /** 已转码好的缓存文件；没有则返回 null。 */
    fun cached(track: Track): File? = outFile(track).takeIf { it.isFile && it.length() > 0 }

    /** 转码（命中缓存直接返回）。失败返回 null，调用方回退原文件播放。 */
    suspend fun ensure(track: Track): File? = withContext(Dispatchers.IO) {
        cached(track)?.let { return@withContext it }
        if (!inFlight.add(track.id)) return@withContext null
        try {
            gate.withPermit {
                cached(track)?.let { return@withPermit it }
                val src = File(dir(), "src_${System.nanoTime()}.tmp")
                val tmpOut = File(dir(), "tmp_${System.nanoTime()}.aac")
                try {
                    if (!download(track.streamUrl, src)) return@withPermit null
                    if (!transcode(src, tmpOut)) {
                        CrashLogger.info(TAG, "转码失败，回退原文件：${track.title}")
                        return@withPermit null
                    }
                    val dst = outFile(track)
                    if (dst.exists()) dst.delete()
                    if (tmpOut.renameTo(dst)) {
                        CrashLogger.info(TAG, "转码完成 ${track.title} → ${dst.length() / 1024}KB")
                        dst
                    } else null
                } finally {
                    runCatching { src.delete() }
                    runCatching { tmpOut.delete() }
                }
            }
        } catch (e: Exception) {
            CrashLogger.info(TAG, "转码异常：${e.message}")
            null
        } finally {
            inFlight.remove(track.id)
        }
    }

    /** 清理缓存（设置里「清空转码缓存」调用）。 */
    fun clearCache(): Long {
        var freed = 0L
        runCatching {
            dir().listFiles()?.forEach { freed += it.length(); it.delete() }
        }
        return freed
    }

    fun cacheSize(): Long = runCatching { dir().listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    private fun download(url: String, dst: File): Boolean = runCatching {
        val resp = MusicBoxApp.instance.okHttpClient
            .newCall(Request.Builder().url(url).header("User-Agent", "AIpod").build())
            .execute()
        if (!resp.isSuccessful) return@runCatching false
        val body = resp.body ?: return@runCatching false
        if (body.contentLength() > MAX_SOURCE_BYTES) {
            CrashLogger.info(TAG, "源文件过大（${body.contentLength()}），跳过转码")
            return@runCatching false
        }
        var total = 0L
        body.byteStream().use { input ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_SOURCE_BYTES) return@runCatching false
                    out.write(buf, 0, n)
                }
            }
        }
        dst.length() > 0
    }.getOrDefault(false)

    private fun transcode(src: File, dst: File): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        return try {
            extractor.setDataSource(src.absolutePath)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIdx = i; format = f; break }
            }
            if (trackIdx < 0 || format == null) {
                CrashLogger.info(TAG, "源文件没有音频轨道")
                return false
            }
            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            val srcCh = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            // AAC-LC 在移动端最稳的是 44.1/48kHz；超过 48kHz 的按 48k 输出
            val outRate = if (srcRate > 48_000) 48_000 else srcRate
            val outCh = srcCh.coerceIn(1, 2)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val encFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, outRate, outCh
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            dst.outputStream().buffered(128 * 1024).use { out ->
                pump(extractor, decoder!!, encoder!!, out, outRate, outCh)
            }
            dst.length() > 0
        } catch (e: Exception) {
            CrashLogger.info(TAG, "转码流程异常：${e.message}")
            false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun pump(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        out: OutputStream,
        sampleRate: Int,
        channels: Int
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderEos = false
        var encoderDone = false
        val started = System.currentTimeMillis()

        while (!encoderDone) {
            // 超时保护：单曲转码最长 10 分钟
            if (System.currentTimeMillis() - started > 10 * 60_000) {
                CrashLogger.info(TAG, "转码超时，放弃")
                return
            }
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            if (!decoderDone) {
                when (val outIdx = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outIdx >= 0) {
                        val buf = decoder.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            var offset = 0
                            while (offset < info.size) {
                                val encIn = encoder.dequeueInputBuffer(10_000)
                                if (encIn < 0) continue
                                val eb = encoder.getInputBuffer(encIn)!!
                                eb.clear()
                                val n = minOf(eb.remaining(), info.size - offset)
                                buf.position(info.offset + offset)
                                buf.limit(info.offset + offset + n)
                                eb.put(buf)
                                encoder.queueInputBuffer(encIn, 0, n, info.presentationTimeUs, 0)
                                offset += n
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                    }
                }
            }
            if (decoderDone && !encoderEos) {
                val encIn = encoder.dequeueInputBuffer(10_000)
                if (encIn >= 0) {
                    encoder.queueInputBuffer(encIn, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    encoderEos = true
                }
            }
            // 排空编码器
            while (true) {
                when (val encOut = encoder.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> {
                        if (encOut < 0) break
                        val eb = encoder.getOutputBuffer(encOut)!!
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val data = ByteArray(info.size)
                            eb.position(info.offset)
                            eb.get(data)
                            writeAdts(out, data, sampleRate, channels)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(encOut, false)
                        if (eos) { encoderDone = true; break }
                    }
                }
            }
        }
        runCatching { out.flush() }
    }

    /** 给每帧 AAC 加 ADTS 头，生成可直接播放的 .aac 文件。 */
    private fun writeAdts(out: OutputStream, data: ByteArray, sampleRate: Int, channels: Int) {
        val freqIdx = when (sampleRate) {
            96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4
            32_000 -> 5; 24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9
            11_025 -> 10; 8_000 -> 11; 7_350 -> 12; else -> 4
        }
        val ch = channels.coerceIn(1, 2)
        val len = data.size + 7
        val h = ByteArray(7)
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte()
        h[2] = (((2 - 1) shl 6) or (freqIdx shl 2) or ((ch shr 2) and 1)).toByte()
        h[3] = (((ch and 3) shl 6) or ((len shr 11) and 0x03)).toByte()
        h[4] = ((len shr 3) and 0xFF).toByte()
        h[5] = (((len and 7) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
        out.write(h)
        out.write(data)
    }

    private fun MediaFormat.intOr(key: String, def: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def
}
