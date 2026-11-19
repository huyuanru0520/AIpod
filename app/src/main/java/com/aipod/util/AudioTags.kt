package com.aipod.util

/**
 * 直接解析音频容器头部的标签数据：内嵌封面（FLAC PICTURE / ID3 APIC / MP4 covr）
 * 与内嵌歌词（FLAC VorbisComment LYRICS / ID3 USLT / MP4 ©lyr）。
 *
 * 为什么不用 `MediaMetadataRetriever.embeddedPicture`：
 * 我们只下载文件头部若干 MB（整首可能几百 MB），而 MMR 对「截断文件」的解析并不稳定——
 * FLAC / ALAC 常常直接返回 null 或抛异常，于是「许嵩的很多歌没有封面」。
 * 这里按格式规范自己走一遍元数据块，只要标签在头部就能稳定拿到，且不需要完整文件。
 */
object AudioTags {

    /** 封面查找结果：找到了 / 确实没有 / 头部数据不够需要继续下载。 */
    sealed class CoverResult {
        class Found(val data: ByteArray) : CoverResult()
        class NeedMore(val minBytes: Int) : CoverResult()
        object NotFound : CoverResult()
    }

    /** VorbisComment 里可能存放歌词的键（大小写不敏感）。 */
    private val LYRIC_KEYS = setOf(
        "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "UNSYNCED_LYRICS",
        "LYRIC", "LRC", "SYNCEDLYRICS"
    )

    /**
     * 查找内嵌封面。与 [coverFromHead] 的区别：数据不够时返回 [CoverResult.NeedMore]，
     * 上层可以按需续读，而不是像以前那样「头部不够就直接判定没有封面」。
     */
    fun findCover(b: ByteArray): CoverResult = runCatching {
        when {
            isFlac(b) -> flacCover(b)
            isId3(b) -> id3Picture(b)?.let { CoverResult.Found(it) } ?: CoverResult.NotFound
            isMp4(b) -> mp4AtomData(b, "covr")?.let { CoverResult.Found(it) } ?: CoverResult.NotFound
            else -> CoverResult.NotFound
        }
    }.getOrElse { CoverResult.NotFound }

    /** MP4 的 moov（含 covr）常在文件尾部，头部窗口找不到时用尾部窗口再试一次。 */
    fun coverFromTail(b: ByteArray): ByteArray? = runCatching {
        mp4AtomData(b, "covr")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 供日志使用的格式标识。 */
    fun kind(b: ByteArray): String = when {
        isFlac(b) -> "flac"
        isId3(b) -> "id3v${b[3].toInt() and 0xFF}"
        isMp4(b) -> "mp4"
        else -> "unknown"
    }

    fun coverFromHead(b: ByteArray): ByteArray? =
        (findCover(b) as? CoverResult.Found)?.data

    fun lyricsFromHead(b: ByteArray): String? = runCatching {
        when {
            isFlac(b) -> flacLyrics(b)
            isId3(b) -> id3Lyrics(b)
            isMp4(b) -> mp4Lyrics(b)
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // ---------------------------------------------------------------- FLAC

    private fun isFlac(b: ByteArray) =
        b.size > 8 && b[0].toInt() == 0x66 && b[1].toInt() == 0x4C &&
            b[2].toInt() == 0x61 && b[3].toInt() == 0x43 // "fLaC"

    /** 依次走元数据块，type 6 = PICTURE，type 4 = VORBIS_COMMENT。 */
    private inline fun forEachFlacBlock(b: ByteArray, onBlock: (type: Int, start: Int, end: Int) -> Boolean) {
        var p = 4
        while (p + 4 <= b.size) {
            val h = b[p].toInt() and 0xFF
            val last = h and 0x80 != 0
            val type = h and 0x7F
            val len = be24(b, p + 1)
            val start = p + 4
            val end = start + len
            if (len < 0 || end > b.size) return
            if (onBlock(type, start, end)) return
            if (last) return
            p = end
        }
    }

    /**
     * 遍历 FLAC 元数据块找封面：
     * - type 6 = PICTURE（标准做法，绝大多数文件）；
     * - type 4 = VORBIS_COMMENT 里的 `METADATA_BLOCK_PICTURE`（base64，Ogg 风格，不少转换工具这么写）、
     *   `COVERART`（裸图片 base64）。
     *
     * 遇到块长度超出已下载区间时返回 NeedMore：以前这里直接 return，
     * 只要 STREAMINFO 后面跟了个超出头部窗口的 PADDING，后面的 PICTURE 就永远读不到，
     * 表现正是「同一个库里有的有图、有的没图」。
     */
    private fun flacCover(b: ByteArray): CoverResult {
        if (b.size <= 4) return CoverResult.NotFound
        var p = 4
        while (p + 4 <= b.size) {
            val h = b[p].toInt() and 0xFF
            val last = h and 0x80 != 0
            val type = h and 0x7F
            val len = be24(b, p + 1)
            if (len < 0) return CoverResult.NotFound
            val start = p + 4
            val end = start + len
            if (end > b.size) return CoverResult.NeedMore(end + 4)
            when (type) {
                6 -> parseFlacPicture(b, start, end)?.let { return CoverResult.Found(it) }
                4 -> vorbisCommentCover(b, start, end)?.let { return CoverResult.Found(it) }
            }
            if (last) return CoverResult.NotFound
            p = end
        }
        // 头部窗口刚好在某个块头处截断：再要一点数据就能继续
        return CoverResult.NeedMore(b.size + 16 * 1024)
    }

    /** VorbisComment 里的 base64 封面。 */
    private fun vorbisCommentCover(b: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        if (p + 4 > end) return null
        val vendorLen = le32(b, p); p += 4
        if (vendorLen < 0 || p + vendorLen > end) return null
        p += vendorLen
        if (p + 4 > end) return null
        var count = le32(b, p); p += 4
        var plainArt: String? = null
        while (count-- > 0) {
            if (p + 4 > end) break
            val len = le32(b, p); p += 4
            if (len <= 0 || p + len > end) break
            val kv = String(b, p, len, Charsets.UTF_8)
            p += len
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val key = kv.substring(0, eq).trim().uppercase()
            val value = kv.substring(eq + 1).trim()
            if (value.isEmpty()) continue
            when (key) {
                "METADATA_BLOCK_PICTURE" -> {
                    val raw = decodeBase64(value) ?: continue
                    parseFlacPicture(raw, 0, raw.size)?.let { return it }
                        ?: raw.takeIf { isImage(it) }
                }
                "COVERART" -> plainArt = value
            }
        }
        return plainArt?.let { decodeBase64(it) }?.takeIf { isImage(it) }
    }

    private fun decodeBase64(s: String): ByteArray? {
        val clean = s.filterNot { it == '\n' || it == '\r' || it == ' ' }
        for (flag in intArrayOf(android.util.Base64.DEFAULT, android.util.Base64.URL_SAFE, android.util.Base64.NO_WRAP)) {
            val out = runCatching { android.util.Base64.decode(clean, flag) }.getOrNull()
            if (out != null && out.size > 32) return out
        }
        return null
    }

    /** 粗略校验是不是图片数据（JPEG / PNG / GIF / BMP / WEBP）。 */
    private fun isImage(b: ByteArray): Boolean {
        if (b.size < 8) return false
        val jpeg = b[0].toInt() == 0xFF.toByte().toInt() && b[1].toInt() == 0xD8.toByte().toInt()
        val png = b[0].toInt() == 0x89.toByte().toInt() && b[1].toInt() == 0x50.toByte().toInt() &&
            b[2].toInt() == 0x4E.toByte().toInt() && b[3].toInt() == 0x47.toByte().toInt()
        val gif = b[0].toInt() == 0x47.toByte().toInt() && b[1].toInt() == 0x49.toByte().toInt()
        val bmp = b[0].toInt() == 0x42.toByte().toInt() && b[1].toInt() == 0x4D.toByte().toInt()
        val webp = b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        return jpeg || png || gif || bmp || webp
    }

    private fun parseFlacPicture(b: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        p += 4                              // picture type
        val mimeLen = be32(b, p); p += 4
        if (p + mimeLen > end) return null
        p += mimeLen                        // mime
        val descLen = be32(b, p); p += 4
        if (p + descLen > end) return null
        p += descLen                        // description
        p += 16                             // width / height / depth / colors
        val dataLen = be32(b, p); p += 4
        if (dataLen <= 0 || p + dataLen > end) return null
        return b.copyOfRange(p, p + dataLen)
    }

    private fun flacLyrics(b: ByteArray): String? {
        var out: String? = null
        forEachFlacBlock(b) { type, start, end ->
            if (type != 4) return@forEachFlacBlock false
            out = parseVorbisComment(b, start, end)
            true
        }
        return out
    }

    private fun parseVorbisComment(b: ByteArray, start: Int, end: Int): String? {
        var p = start
        if (p + 4 > end) return null
        val vendorLen = le32(b, p); p += 4
        if (vendorLen < 0 || p + vendorLen > end) return null
        p += vendorLen
        if (p + 4 > end) return null
        val count = le32(b, p); p += 4
        if (count <= 0) return null
        repeat(count) {
            if (p + 4 > end) return null
            val len = le32(b, p); p += 4
            if (len <= 0 || p + len > end) return null
            val kv = String(b, p, len, Charsets.UTF_8)
            p += len
            val eq = kv.indexOf('=')
            if (eq > 0) {
                val key = kv.substring(0, eq).trim().uppercase()
                if (key in LYRIC_KEYS) {
                    val v = kv.substring(eq + 1).trim()
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return null
    }

    // ----------------------------------------------------------------- ID3

    private fun isId3(b: ByteArray) =
        b.size > 10 && b[0].toInt() == 0x49 && b[1].toInt() == 0x44 && b[2].toInt() == 0x33
    // "ID3"

    /** 找到第一个匹配 id 的帧，返回帧 id 与 payload 区间。 */
    private fun findId3Frame(b: ByteArray, ids: Set<String>): Pair<String, IntRange>? {
        val major = b[3].toInt() and 0xFF
        val bodySize = syncsafe(b, 6)
        val end = minOf(b.size, 10 + bodySize)
        var p = 10
        val headerLen = if (major == 2) 6 else 10
        while (p + headerLen <= end) {
            val idLen = if (major == 2) 3 else 4
            if (p + idLen > end) return null
            val id = String(b, p, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') return null
            val size = when {
                major == 2 -> be24(b, p + 3)
                major == 4 -> syncsafe(b, p + 4)
                else -> be32(b, p + 4)
            }
            val payloadStart = p + headerLen
            if (size <= 0 || payloadStart + size > end) return null
            if (id in ids) return id to payloadStart.until(payloadStart + size)
            p = payloadStart + size
        }
        return null
    }

    private fun id3Picture(b: ByteArray): ByteArray? {
        val (id, range) = findId3Frame(b, setOf("APIC", "PIC")) ?: return null
        var p = range.first
        val end = range.last + 1
        if (p >= end) return null
        val enc = b[p].toInt() and 0xFF; p++
        p = if (id == "PIC") p + 3 else skipTerminator(b, p, end, enc)
        p++  // picture type
        p = skipTerminator(b, p, end, enc) // description
        if (p >= end) return null
        return b.copyOfRange(p, end)
    }

    private fun id3Lyrics(b: ByteArray): String? {
        val (_, range) = findId3Frame(b, setOf("USLT", "ULT")) ?: return null
        var p = range.first
        val end = range.last + 1
        if (p >= end) return null
        val enc = b[p].toInt() and 0xFF; p++
        p += 3                                   // language
        p = skipTerminator(b, p, end, enc)       // content descriptor
        if (p >= end) return null
        return decodeText(b, p, end - p, enc)
    }

    /** 跳过以 0 结尾的字符串（UTF-16 编码时是 00 00 双字节结尾）。 */
    private fun skipTerminator(b: ByteArray, from: Int, end: Int, enc: Int): Int {
        var p = from
        if (enc == 1 || enc == 2) {
            while (p + 1 < end) {
                if (b[p].toInt() == 0 && b[p + 1].toInt() == 0) return p + 2
                p += 2
            }
            return end
        }
        while (p < end) {
            if (b[p].toInt() == 0) return p + 1
            p++
        }
        return end
    }

    private fun decodeText(b: ByteArray, start: Int, len: Int, enc: Int): String? {
        if (len <= 0) return null
        val cs = when (enc) {
            1 -> Charsets.UTF_16      // 带 BOM
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val s = runCatching { String(b, start, len, cs) }.getOrNull() ?: return null
        return s.trim('\u0000', ' ', '\r', '\n', '\uFEFF').trim()
    }

    // ----------------------------------------------------------------- MP4

    private fun isMp4(b: ByteArray) =
        b.size > 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    private fun mp4AtomData(b: ByteArray, leaf: String): ByteArray? {
        val moov = findAtom(b, 0, b.size, "moov") ?: return null
        val udta = findAtom(b, moov.first, moov.second, "udta") ?: return null
        // meta 是 FullBox：payload 前 4 字节是 version/flags
        val meta = findAtom(b, udta.first, udta.second, "meta") ?: return null
        val ilst = findAtom(b, meta.first + 4, meta.second, "ilst") ?: return null
        val atom = findAtom(b, ilst.first, ilst.second, leaf) ?: return null
        val data = findAtom(b, atom.first, atom.second, "data") ?: return null
        val p = data.first + 8          // version/flags + locale
        if (p >= data.second) return null
        return b.copyOfRange(p, data.second)
    }

    private fun mp4Lyrics(b: ByteArray): String? {
        val bytes = mp4AtomData(b, "\u00A9lyr") ?: mp4AtomData(b, "lyr") ?: return null
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?.trim('\u0000', ' ', '\r', '\n')
            ?.takeIf { it.isNotEmpty() }
    }

    /** 在同级 atom 序列里线性查找指定 type，返回 payload 区间。 */
    private fun findAtom(b: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var p = start
        val limit = minOf(end, b.size)
        while (p + 8 <= limit) {
            var size = be32(b, p)
            var header = 8
            if (size == 1 && p + 16 <= limit) {
                val hi = be32(b, p + 8).toLong() and 0xFFFFFFFFL
                val lo = be32(b, p + 12).toLong() and 0xFFFFFFFFL
                val big = (hi shl 32) or lo
                if (big > Int.MAX_VALUE) return null
                size = big.toInt()
                header = 16
            }
            if (size == 0) size = limit - p
            if (size < header) return null
            val payloadStart = p + header
            val payloadEnd = minOf(p + size, limit)
            if (randomAccessEquals(b, p + 4, type)) return payloadStart to payloadEnd
            p += size
        }
        return null
    }

    // ------------------------------------------------------------- 基础读取

    private fun randomAccessEquals(b: ByteArray, at: Int, s: String): Boolean {
        if (at + s.length > b.size) return false
        for (i in s.indices) if (b[at + i].toInt() != s[i].code) return false
        return true
    }

    private fun be24(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 16) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() and 0xFF)

    private fun be32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    /** ID3 的 syncsafe 整数：每字节只用低 7 位。 */
    private fun syncsafe(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0x7F) shl 21) or ((b[i + 1].toInt() and 0x7F) shl 14) or
            ((b[i + 2].toInt() and 0x7F) shl 7) or (b[i + 3].toInt() and 0x7F)
}
