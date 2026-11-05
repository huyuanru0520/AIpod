package com.musicbox.nas.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

enum class SourceType { WEBDAV, FNOS, M3U }

/** Unified track model used across all sources (WebDAV / FnOS / M3U). */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val streamUrl: String,
    val source: SourceType,
    /** WebDAV 音频文件地址：当目录无 cover 图时，用于在线提取文件内嵌的专辑封面。 */
    val embeddedAudioUrl: String? = null,
    /** 所在文件夹路径（WebDAV 用于「按文件夹」浏览；其余音源为空）。 */
    val folder: String = "",
    /** 采样率（Hz，0 = 未知）。 */
    val sampleRateHz: Int = 0,
    /** 位深（bit，0 = 未知）。 */
    val bitDepth: Int = 0,
    /** 编码 / 容器标识，如 FLAC、ALAC、DSD、APE。 */
    val codec: String = ""
) : java.io.Serializable {

    /**
     * Hi-Res 判定：位深 > 16bit、采样率 > 48kHz，或 DSD / APE 这类本身就是高解析的格式。
     * 与常见定义一致（CD 为 16bit/44.1kHz）。
     *
     * 注意：这里刻意用 getter 而不是 `by lazy` —— Track / DavLibrary 走 Java 序列化缓存，
     * lazy 的委托字段（Lazy 对象）不可序列化，会让扫描缓存写盘直接失败。
     */
    val isHiRes: Boolean
        get() = bitDepth > 16 || sampleRateHz > 48_000 || codec.uppercase() in HI_RES_CODECS

    /** 例如 "24bit / 96kHz"；信息不全时只显示已知部分，全未知返回空串。 */
    val qualityLabel: String
        get() {
            val parts = mutableListOf<String>()
            if (bitDepth > 0) parts.add("${bitDepth}bit")
            if (sampleRateHz > 0) parts.add(formatSampleRate(sampleRateHz))
            if (parts.isEmpty() && codec.isNotBlank()) parts.add(codec.uppercase())
            return parts.joinToString(" / ")
        }

    fun toMediaItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title.ifBlank { "未知标题" })
            .setArtist(artist.ifBlank { null })
            .setAlbumTitle(album.ifBlank { null })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (!coverUrl.isNullOrBlank()) meta.setArtworkUri(Uri.parse(coverUrl))
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(id)
            .setMediaMetadata(meta.build())
            .build()
    }

    private companion object {
        val HI_RES_CODECS = setOf("DSD", "DSF", "DFF", "APE", "WAVPACK", "WV")

        fun formatSampleRate(hz: Int): String {
            val khz = hz / 1000.0
            return if (hz % 1000 == 0) "${hz / 1000}kHz"
            else String.format(java.util.Locale.US, "%.1fkHz", khz)
        }
    }
}
