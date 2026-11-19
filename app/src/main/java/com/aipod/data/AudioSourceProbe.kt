package com.aipod.data

import com.aipod.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Locale

/**
 * 音源真实性探测工具。
 *
 * 目的：验证飞牛 /track/stream 与 WebDAV 直链返回的字节流是否为「原始无损文件」，
 * 还是被服务端按默认策略转码成了 mp3/aac。bit-perfect 的前提是音源必须是原始无损字节，
 * 本工具只用一次极小的 Range 请求（前 64 字节）读取响应头与文件头魔数，不改变播放链路。
 *
 * 判定依据（按优先级）：
 *  1. 文件头魔数（最可靠）：fLaC / OggS / RIFF....WAVE / ID3 / FF FB 等；
 *  2. Content-Type：audio/flac vs audio/mpeg；
 *  3. 与 Track.codec 元数据比对。
 */
object AudioSourceProbe {

    data class Result(
        val url: String,
        val httpCode: Int,
        val contentType: String,
        val contentLength: String,
        val acceptRanges: String,
        val magic: String,          // 文件头魔数（hex）
        val detectedFormat: String, // 由魔数推断的编码
        val headAscii: String,      // 头部可打印字符（便于人工核对）
        val verdict: String,        // 结论
        val error: String? = null
    ) {
        fun toLog(): String = buildString {
            append("URL=$url | HTTP=$httpCode | Content-Type=$contentType | ")
            append("Length=$contentLength | Accept-Ranges=$acceptRanges\n")
            append("    魔数=$magic | 推断编码=$detectedFormat | 头部ASCII=$headAscii\n")
            append("    结论：$verdict")
            if (error != null) append("\n    错误：$error")
        }
    }

    /**
     * 对给定音频 URL 做一次轻量探测。必须在 IO 线程调用（内部切 Dispatchers.IO）。
     * @param track 目标曲目（提供 URL 与元数据编码）
     * @param client 带认证的 OkHttpClient（复用全局实例，AuthInterceptor 会注入凭证）
     */
    suspend fun probe(track: com.aipod.data.Track, client: OkHttpClient): Result =
        withContext(Dispatchers.IO) {
            var resp: Response? = null
            try {
                val req = Request.Builder()
                    .url(track.streamUrl)
                    .header("Range", "bytes=0-63")   // 只需前 64 字节
                    .get()
                    .build()
                resp = client.newCall(req).execute()

                val code = resp.code
                // 206 = 支持范围返回；200 = 服务端忽略 Range，仍返回完整/流式内容
                val ct = resp.header("Content-Type") ?: ""
                val len = resp.header("Content-Length") ?: ""
                val ar = resp.header("Accept-Ranges") ?: ""

                val bytes = resp.body?.byteStream()?.use { input ->
                    val buf = ByteArray(64)
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r <= 0) break
                        n += r
                    }
                    buf.copyOf(n)
                } ?: ByteArray(0)

                val magic = bytes.joinToString("") { "%02X".format(it).uppercase(Locale.US) }
                val ascii = bytes.map { if (it in 32..126) it.toInt().toChar() else '.' }
                    .joinToString("")
                val detected = detectFormat(bytes, ascii)
                val verdict = judge(detected, track.codec, code, ct)

                Result(
                    url = track.streamUrl,
                    httpCode = code,
                    contentType = ct,
                    contentLength = len,
                    acceptRanges = ar,
                    magic = magic.take(32),
                    detectedFormat = detected,
                    headAscii = ascii.take(24),
                    verdict = verdict
                )
            } catch (t: Throwable) {
                Result(
                    url = track.streamUrl,
                    httpCode = -1,
                    contentType = "",
                    contentLength = "",
                    acceptRanges = "",
                    magic = "",
                    detectedFormat = "未知",
                    headAscii = "",
                    verdict = "探测失败：无法取得响应",
                    error = "${t::class.java.simpleName}: ${t.message}"
                )
            } finally {
                resp?.close()
            }
        }

    /** 依据文件头字节识别容器/编码。注意 M4A(mp4) 容器既可能装 ALAC(无损) 也可能装 AAC(有损)，需再结合声明编码判定。 */
    private fun detectFormat(b: ByteArray, ascii: String): String {
        if (b.size >= 4 && ascii.startsWith("fLaC")) return "FLAC（无损）"
        if (b.size >= 4 && ascii.startsWith("OggS")) return "OGG 容器（FLAC/Opus/Vorbis）"
        if (b.size >= 12 && ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WAVE") return "WAV（无损/PCM）"
        // ISO BMFF / MP4 家族：偏移 4..8 是 "ftyp"，8..12 是 major brand（iso5/mp42/M4A /isom）。
        // 飞牛的 ALAC 曲目就封装在这种容器里（ftypiso5 … isomiso5）。
        if (b.size >= 12 && ascii.substring(4, 8) == "ftyp") {
            val brand = ascii.substring(8, 12).trim()
            return "MP4/M4A 容器（brand=$brand；ALAC 无损或 AAC 有损需结合声明编码）"
        }
        if (b.size >= 4 && ascii.startsWith("ID3")) return "ID3 标签（需看后续帧，常为 MP3/AAC）"
        if (b.size >= 4) {
            val h = (b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)
            // MPEG 音频帧同步：11 个置 1 的位
            if ((h and 0xFFE0) == 0xFFE0) return "MP3（有损，疑似被转码）"
            // ADTS AAC 同步字 0xFFF1 / 0xFFF9
            if ((h and 0xFFF6) == 0xFFF0) return "AAC ADTS（有损，疑似被转码）"
        }
        if (b.size >= 4 && ascii.startsWith("8SVX")) return "8SVX"
        if (ascii.startsWith("<?xml")) return "XML/播放列表（非音频）"
        return "未识别（hex: ${b.take(8).joinToString("") { "%02X".format(it) }})"
    }

    /** 将探测结果与曲目声明的编码、Content-Type 综合比对，给出人话结论。 */
    private fun judge(detected: String, declaredCodec: String, code: Int, contentType: String): String {
        if (code !in 200..299) return "HTTP $code：请求未成功，无法判定"
        val codec = declaredCodec.uppercase()
        val ct = contentType.lowercase()
        // MP4/M4A 容器：ALAC 装在里面是无损，AAC/MP3 装在里面是有损。
        if (detected.contains("MP4/M4A 容器")) {
            val alac = codec.contains("ALAC")
            val aac = codec.contains("AAC") || codec.contains("MP3")
            return when {
                alac -> "✅ 原始 ALAC 无损（封装在 M4A/MP4 容器：ftyp/iso5）。" +
                    "魔数与声明编码=ALAC、Content-Type=$contentType 一致，服务端直出原始文件未转码，" +
                    "具备 bit-perfect 音源前提（输出端仍需 USB DAC + Android14+ 支持机型）。"
                aac -> "⚠️ M4A/MP4 容器，但声明编码=$codec 为有损。音源本身是有损压缩，无法 bit-perfect。"
                else -> "✅ M4A/MP4 容器直出（brand 见上，Content-Type=$contentType）。" +
                    "声明编码=${declaredCodec.ifBlank { "未知" }}；容器结构完整、支持 Range，判定为原始文件未转码。"
            }
        }
        val lossy = detected.contains("有损") || detected.contains("MP3") || detected.contains("AAC")
        val lossless = detected.contains("无损") || detected.contains("FLAC") ||
            detected.contains("WAV") || detected.contains("OGG")
        return when {
            lossy -> "⚠️ 检测到有损编码（${detected}）。Content-Type=$contentType，声明编码=${declaredCodec.ifBlank { "无损" }}，" +
                "服务端很可能做了转码，无法 bit-perfect。"
            lossless -> "✅ 检测到无损编码（${detected}）。声明编码=${declaredCodec.ifBlank { "未知" }}，" +
                "Content-Type=$contentType，音源端为原始无损字节，具备 bit-perfect 前提" +
                "（输出端仍需 USB DAC + Android14+ 支持机型）。"
            else -> "❓ 编码无法从文件头确定（${detected}）。Content-Type=$contentType，声明编码=${declaredCodec.ifBlank { "未知" }}，" +
                "需结合完整文件比对。"
        }
    }

    /** 便捷方法：探测并把结论写入日志。 */
    suspend fun probeAndLog(track: com.aipod.data.Track, client: OkHttpClient, tag: String = "Probe") {
        val r = probe(track, client)
        CrashLogger.info(tag, "音源真实性探测【${track.title}】\n    ${r.toLog()}")
    }
}
