package com.aipod.data.m3u

import com.aipod.data.SourceType
import com.aipod.data.Track

object M3uParser {

    data class ParsedPlaylist(val name: String, val tracks: List<Track>)

    /** Parse M3U / M3U8 text into a list of [Track]. */
    fun parse(content: String, listName: String = "导入歌单"): ParsedPlaylist {
        val tracks = mutableListOf<Track>()
        var pendingTitle: String? = null
        var pendingArtist: String? = null
        var pendingDuration: Long = 0L

        for (raw in content.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> continue
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.removePrefix("#EXTINF:").trim()
                    val commaIdx = body.indexOf(',')
                    val durPart = if (commaIdx >= 0) body.substring(0, commaIdx) else body
                    val titlePart = if (commaIdx >= 0) body.substring(commaIdx + 1) else ""
                    pendingDuration = parseDuration(durPart)
                    pendingTitle = null
                    pendingArtist = null
                    val clean = titlePart.trim()
                    if (clean.contains(" - ")) {
                        val parts = clean.split(" - ", limit = 2)
                        pendingArtist = parts[0].trim().ifBlank { null }
                        pendingTitle = parts[1].trim().ifBlank { null }
                    } else {
                        pendingTitle = clean.ifBlank { null }
                    }
                }
                line.startsWith("#", ignoreCase = true) -> {
                    if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                        // optional playlist name directive ignored here
                    }
                    continue
                }
                else -> {
                    val url = line
                    val title = pendingTitle ?: nameFromUrl(url)
                    tracks.add(
                        Track(
                            id = "m3u:$url",
                            title = title,
                            artist = pendingArtist ?: "",
                            album = "",
                            durationMs = pendingDuration * 1000L,
                            coverUrl = null,
                            streamUrl = url,
                            source = SourceType.M3U
                        )
                    )
                    pendingTitle = null
                    pendingArtist = null
                    pendingDuration = 0L
                }
            }
        }
        return ParsedPlaylist(listName, tracks)
    }

    private fun parseDuration(s: String): Long {
        // EXTINF duration may be "-1" or seconds (possibly fractional).
        return s.toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
    }

    private fun nameFromUrl(url: String): String {
        val trimmed = url.substringBefore('?')
        val last = trimmed.substringAfterLast('/').ifBlank { url }
        return last.substringBeforeLast('.').ifBlank { last }
    }
}
