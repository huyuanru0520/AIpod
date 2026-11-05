package com.musicbox.nas.data

import com.musicbox.nas.MusicBoxApp
import com.musicbox.nas.util.AudioTags
import com.musicbox.nas.util.CrashLogger
import com.musicbox.nas.util.MediaHead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.math.absoluteValue

/**
 * 歌词加载：**优先读文件内嵌的 metadata 歌词**，没有才回头去找同目录同名的 `.lrc`。
 *
 * 内嵌支持：
 * - FLAC：VorbisComment 里的 `LYRICS` / `UNSYNCEDLYRICS` / `LYRIC` 等键；
 * - MP3：ID3v2 的 `USLT`（甚至 v2.2 的 `ULT`）帧；
 * - M4A/MP4：`©lyr` atom。
 *
 * 解析结果按歌曲地址落盘缓存：同一首歌再次打开歌词页不会再下载文件头。
 * 飞牛音乐走它自己的歌词接口（服务端已从原始文件抽取）。
 */
object LyricsRepo {

    /** 最近用过的歌词（LRU），避免来回切歌反复读盘。 */
    private val mem = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 30
    }

    private val dir: File by lazy {
        File(MusicBoxApp.instance.cacheDir, "lyrics_cache").also { runCatching { it.mkdirs() } }
    }

    suspend fun load(track: Track): String? = withContext(Dispatchers.IO) {
        val key = cacheKey(track)
        mem[key]?.let { return@withContext it }
        diskGet(key)?.let {
            mem[key] = it
            return@withContext it
        }

        val (text, from) = if (track.source == SourceType.FNOS) {
            // 飞牛服务端已经能给出歌词（它内部就是读文件标签），先问接口；
            // 接口为空时再自己取文件头解析内嵌标签兜底。
            val api = fnosLyrics(track)
            if (!api.isNullOrBlank()) api to "飞牛接口" else embeddedOnly(track) to "内嵌标签"
        } else {
            embeddedThenLrc(track)
        }

        if (text.isNullOrBlank()) {
            CrashLogger.info("Lyrics", "「${track.title}」无歌词（内嵌与同名 .lrc 均未命中）")
            return@withContext null
        }
        mem[key] = text
        diskPut(key, text)
        CrashLogger.info(
            "Lyrics",
            "「${track.title}」来源=$from 行数=${text.lineSequence().count()}"
        )
        text
    }

    /** 只读文件内嵌歌词（飞牛接口为空时的兜底）。 */
    private fun embeddedOnly(track: Track): String? {
        val head = MediaHead.fetch(track.streamUrl, 1024 * 1024) ?: return null
        return AudioTags.lyricsFromHead(head)
    }

    /** 先内嵌标签，再同目录同名 .lrc。返回（歌词, 来源说明）。 */
    private fun embeddedThenLrc(track: Track): Pair<String?, String> {
        val head = MediaHead.fetch(track.streamUrl, 2 * 1024 * 1024)
        if (head != null) {
            val kind = AudioTags.kind(head)
            val embedded = AudioTags.lyricsFromHead(head)
            if (!embedded.isNullOrBlank()) return embedded to "内嵌标签[$kind]"
        }
        val lrcUrl = lrcUrlFor(track.streamUrl) ?: return null to "无"
        val resp = runCatching {
            MusicBoxApp.instance.okHttpClient
                .newCall(Request.Builder().url(lrcUrl).header("User-Agent", "NASMusic").build())
                .execute()
        }.getOrNull()
        resp?.use { r ->
            if (r.isSuccessful) {
                val body = r.body?.string()
                if (!body.isNullOrBlank()) return (body to "同名 .lrc")
            }
        }
        return null to "无"
    }

    private suspend fun fnosLyrics(track: Track): String? {
        val guid = track.id.removePrefix("fnos:")
        return runCatching { MusicBoxApp.instance.fnosClient.lyricText(guid) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** 把 ".../01.flac?token=x" 变成 ".../01.lrc?token=x"。 */
    private fun lrcUrlFor(streamUrl: String): String? {
        val q = streamUrl.indexOf('?')
        val base = if (q >= 0) streamUrl.substring(0, q) else streamUrl
        if (!base.contains('.')) return null
        val stripped = base.substringBeforeLast('.')
        if (stripped.isBlank()) return null
        return stripped + ".lrc" + if (q >= 0) streamUrl.substring(q) else ""
    }

    // ------------------------------------------------------------------ 缓存

    private fun cacheKey(track: Track): String {
        val raw = track.streamUrl.ifBlank { track.id }
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            md.update(raw.toByteArray())
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrElse { raw.hashCode().absoluteValue.toString(16) }
    }

    private fun diskGet(key: String): String? = runCatching {
        val f = File(dir, "$key.lrc")
        if (f.exists() && f.length() > 0) f.readText() else null
    }.getOrNull()

    private fun diskPut(key: String, text: String) {
        runCatching { File(dir, "$key.lrc").writeText(text) }
    }
}
