package com.aipod.util

import java.security.MessageDigest
import kotlin.random.Random

/** SHA-256 hex, used by 飞牛音乐 login (password is sha256(plain)). */
fun sha256Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/** 32-char hex device id, mirrors the FnOS client. */
fun randomDeviceId(): String =
    (1..16).joinToString("") { Random.nextInt(256).toString(16).padStart(2, '0') }

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 视为可播放音频的后缀。覆盖 NAS 上常见的无损、有损与容器格式。
 * 播放能力由 MediaCodec + FFmpeg 决定；这里只负责「扫描时是否把它收进曲库」。
 */
val AUDIO_EXTENSIONS: Set<String> = setOf(
    // 无损 / 高解析
    "flac", "wav", "alac", "ape", "wv", "tta", "dsf", "dff",
    "aiff", "aif", "aifc", "caf", "tak", "mpc", "au", "snd", "w64", "rf64",
    // 多声道 / 环绕音频
    "dts", "dtshd", "ac3", "eac3", "thd", "mlp",
    // 常见有损与容器
    "mp3", "m4a", "m4b", "m4r", "aac", "adts", "ogg", "oga", "opus", "spx",
    "wma", "mp2", "mpga", "amr", "awb", "3gp", "mid", "midi", "ra", "rm",
    "asf", "webm", "mka", "mp4"
)

/** 从文件名 / URL / WebDAV href 里取出规范化的小写扩展名，取不到返回 null。 */
fun extensionOf(name: String): String? {
    val clean = name
        .substringBefore('?')
        .substringBefore('#')
        .trim()
        .trimEnd('/')
    val idx = clean.lastIndexOf('.')
    if (idx < 0 || idx == clean.lastIndex) return null
    return clean.substring(idx + 1).lowercase().trim().ifBlank { null }
}

fun isAudioFile(name: String): Boolean = AUDIO_EXTENSIONS.contains(extensionOf(name))

fun isM3uFile(name: String): Boolean {
    val ext = extensionOf(name)
    return ext == "m3u" || ext == "m3u8"
}
