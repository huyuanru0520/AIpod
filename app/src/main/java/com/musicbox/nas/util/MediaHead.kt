package com.musicbox.nas.util

import com.musicbox.nas.MusicBoxApp
import okhttp3.Request
import java.io.ByteArrayOutputStream

/**
 * 只下载音频文件头部若干字节（HTTP Range），用于解析内嵌封面 / 内嵌歌词。
 * 走 App 内带鉴权的 OkHttp（WebDAV Basic / 飞牛 Cookie），与播放用的是同一套身份。
 */
object MediaHead {

    /**
     * 默认只取 1MB：FLAC 的 PICTURE / VorbisComment、ID3 标签绝大多数都在这之内。
     * 以前默认 4MB，列表一滚动就是「每首 4MB」，既慢又浪费；
     * 不够时由调用方按 [AudioTags.CoverResult.NeedMore] 续读。
     */
    const val DEFAULT_CAP = 1024 * 1024

    /** 封面续读上限：极个别文件在 PICTURE 之前塞了超大 PADDING，超过这个就不再追。 */
    const val MAX_CAP = 16 * 1024 * 1024

    fun fetch(url: String, capBytes: Int = DEFAULT_CAP): ByteArray? =
        request(url, "bytes=0-${capBytes - 1}", capBytes)

    /** 取文件尾部若干字节：MP4/M4A 的 moov（含 covr 封面）常常放在文件末尾。 */
    fun fetchTail(url: String, capBytes: Int = 1536 * 1024): ByteArray? =
        request(url, "bytes=-$capBytes", capBytes)

    private fun request(url: String, range: String, capBytes: Int): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Range", range)
            .header("User-Agent", "NASMusic")
            .build()
        val resp = MusicBoxApp.instance.okHttpClient.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful && r.code != 206) {
                CrashLogger.info("MediaHead", "取头部失败 HTTP ${r.code} → $url")
                return@runCatching null
            }
            val input = r.body?.byteStream() ?: return@runCatching null
            val out = ByteArrayOutputStream(256 * 1024)
            val buf = ByteArray(64 * 1024)
            input.use {
                while (out.size() < capBytes) {
                    val n = it.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, minOf(n, capBytes - out.size()))
                }
            }
            if (out.size() == 0) null else out.toByteArray()
        }
    }.onFailure { CrashLogger.info("MediaHead", "取头部异常 ${it.message} → $url") }.getOrNull()
}
