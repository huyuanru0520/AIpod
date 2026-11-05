package com.musicbox.nas.data

import com.musicbox.nas.data.webdav.DavAlbum
import com.musicbox.nas.data.webdav.DavArtist
import com.musicbox.nas.data.webdav.DavLibrary
import com.musicbox.nas.data.webdav.DavPlaylist

/**
 * 主备线路切换时，把已缓存曲库里的绝对地址从旧线路换到新线路。
 *
 * 背景：扫描出来的 [Track] 里存的是绝对 URL（`http://host:port/dav/...`），
 * 主线路挂掉切到备用线路后，这些 URL 全部指向一台不可达的机器；而重新全库扫描
 * 恰恰是网盘风控最敏感、最费时的行为。这里只做一次内存内的前缀替换（几千首歌毫秒级），
 * 不产生任何网络请求。
 *
 * 只替换「以 oldBase 开头」的地址，已经是新线路或第三方地址（如外链封面）保持原样。
 */
fun rebaseUrl(url: String?, oldBase: String, newBase: String): String? {
    if (url.isNullOrBlank()) return url
    if (oldBase.isBlank() || newBase.isBlank() || oldBase == newBase) return url
    if (!url.startsWith(oldBase)) return url
    return newBase + url.substring(oldBase.length)
}

/** 单曲换线路。id 里带着完整 URL（`webdav:$audioUrl`），所以一并改写。 */
fun Track.rebasedTo(oldBase: String, newBase: String): Track {
    if (oldBase.isBlank() || newBase.isBlank() || oldBase == newBase) return this
    val newStream = rebaseUrl(streamUrl, oldBase, newBase) ?: streamUrl
    val newId = if (id.startsWith("webdav:$oldBase")) "webdav:$newStream" else id
    return copy(
        id = newId,
        streamUrl = newStream,
        coverUrl = rebaseUrl(coverUrl, oldBase, newBase),
        embeddedAudioUrl = rebaseUrl(embeddedAudioUrl, oldBase, newBase)
    )
}

/**
 * 整个曲库换线路。
 *
 * tracks / albums / artists / playlists 里的 [Track] 是同一批歌曲的多份引用，
 * 用 id 做记忆化，避免上万首歌被复制四遍造成内存峰值。
 */
fun DavLibrary.rebasedTo(oldBase: String, newBase: String): DavLibrary {
    if (oldBase.isBlank() || newBase.isBlank() || oldBase == newBase) return this

    val memo = HashMap<String, Track>(tracks.size.coerceAtLeast(64))
    fun convert(t: Track): Track = memo.getOrPut(t.id) { t.rebasedTo(oldBase, newBase) }
    fun convertAll(list: List<Track>): List<Track> = list.map { convert(it) }

    return copy(
        tracks = convertAll(tracks),
        albums = albums.map { DavAlbum(it.name, it.artist, convertAll(it.tracks)) },
        artists = artists.map {
            DavArtist(it.name, it.albums.map { a -> DavAlbum(a.name, a.artist, convertAll(a.tracks)) }, convertAll(it.tracks))
        },
        playlists = playlists.map { DavPlaylist(it.name, convertAll(it.tracks)) }
    )
}
