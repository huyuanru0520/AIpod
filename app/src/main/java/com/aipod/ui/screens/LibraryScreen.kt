package com.aipod.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aipod.MusicBoxApp
import com.aipod.LibraryTabs
import com.aipod.SourceMode
import com.aipod.data.Track
import com.aipod.data.fnos.FnosCatalog
import com.aipod.data.webdav.DavAlbum
import com.aipod.data.webdav.DavLibrary
import com.aipod.player.PlayerController
import com.aipod.ui.components.ClickWheel
import com.aipod.ui.components.CoverImage
import com.aipod.ui.components.TrackRow
import com.aipod.util.CrashLogger
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 曲库顶层：主界面只显示「设置 → 音乐源」里选择的那一种音源。
 *
 * 连接信息的填写 / 编辑 / 断开全部集中在「设置 → 连接管理」，
 * 本页只负责浏览曲库，不再显示任何账号、密码、编辑或断开入口。
 * 两种音源通过同一套适配层喂给同一个浏览器界面。
 */
@Composable
fun LibraryScreen(navController: NavController) {
    val app = MusicBoxApp.instance
    val mode by app.sourceMode.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (mode) {
            SourceMode.WEBDAV -> WebDavSection(navController)
            SourceMode.FNOS -> FnOSSection(navController)
            SourceMode.LOCAL -> LocalSection(navController)
        }
    }
}

// ==================== 统一数据适配层 ====================

/** 专辑 / 歌手 / 歌单列表项；tracks、albums 用于 WebDAV 这类已全量索引的音源。 */
internal data class SourceEntry(
    val id: String,
    val name: String,
    val subtitle: String,
    val coverUrl: String?,
    val embeddedAudioUrl: String? = null,
    val key: String = "",
    val tracks: List<Track> = emptyList(),
    val albums: List<SourceEntry> = emptyList()
)

/**
 * 两种音源共用的数据接口。飞牛按需走网络分页，WebDAV 直接返回内存索引，
 * 但界面层只看到同样的 suspend 回调，因此布局与交互完全一致。
 */
internal class SourceBrowserData(
    val sourceLabel: String,
    val loadSongs: suspend (page: Int) -> Pair<List<Track>, Int>,
    val loadAlbums: suspend () -> List<SourceEntry>,
    val loadArtists: suspend () -> List<SourceEntry>,
    val loadPlaylists: suspend () -> List<SourceEntry>,
    val loadAlbumTracks: suspend (SourceEntry) -> List<Track>,
    val loadArtistAlbums: suspend (SourceEntry) -> List<SourceEntry>,
    val loadPlaylistTracks: suspend (SourceEntry) -> List<Track>,
    val loadFolders: suspend (parentPath: String?) -> Pair<List<String>, List<Track>> = { _ -> emptyList<String>() to emptyList() },
    val supportsFolders: Boolean = true,
    /** 风格 / 流派：飞牛走 /genre/list，WebDAV 无此元数据。 */
    val loadGenres: suspend () -> List<SourceEntry> = { emptyList() },
    val loadGenreTracks: suspend (SourceEntry) -> List<Track> = { emptyList() },
    val supportsGenres: Boolean = false,
    /** 漫游：全库随机流，两种音源都支持。 */
    val loadRoam: suspend () -> List<Track> = { emptyList() },
    val supportsRoam: Boolean = true,
    val search: suspend (String) -> List<Track>,
    val status: String = "",
    val playlistHint: String = ""
)

private fun coverOf(tracks: List<Track>): String? =
    tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl

/** 歌单封面：优先第一首歌的封面，缺省时退回到任意有封面的歌。 */
private fun firstCoverOf(tracks: List<Track>): String? =
    tracks.firstOrNull()?.coverUrl ?: coverOf(tracks)

/**
 * 取列表中靠前几首带内嵌音频地址的歌，用 `\u0001` 串成候选链（最多 3 首）。
 * 专辑场景下第一首经常恰好没内嵌封面（单曲 EP、伴奏轨等），只绑一首会让整张专辑变占位图；
 * 候选链让封面加载按顺序往下试，直到某一首命中。
 */
private fun firstEmbedded(tracks: List<Track>): String? {
    val urls = tracks.asSequence()
        .mapNotNull { it.embeddedAudioUrl?.takeIf { u -> u.isNotBlank() } }
        .distinct()
        .take(3)
        .toList()
    return if (urls.isEmpty()) null else urls.joinToString("\u0001")
}

/** 所有歌曲文件夹路径的最长公共前缀，作为「按文件夹」浏览的树根。 */
private fun folderRootPrefix(tracks: List<Track>): String {
    val folders = tracks.mapNotNull { it.folder.ifBlank { null } }.distinct()
    if (folders.isEmpty()) return ""
    val segs = folders.map { it.split('/') }
    val minLen = segs.minOf { it.size }
    val out = mutableListOf<String>()
    for (i in 0 until minLen) {
        val s = segs.first()[i]
        if (segs.all { it[i] == s }) out.add(s) else break
    }
    return out.joinToString("/")
}

/**
 * 给定父路径（null=树根），返回其直接子文件夹列表与该文件夹下的歌曲。
 * 用于「按文件夹」逐级深入浏览。
 */
private fun foldersOf(
    tracks: List<Track>,
    parentPath: String?,
    rootPrefix: String
): Pair<List<String>, List<Track>> {
    val all = tracks.mapNotNull { it.folder.ifBlank { null } }.toSet()
    val parent = parentPath ?: rootPrefix
    val childFolders = all.filter { f ->
        val p = f.substringBeforeLast('/', "")
        p == parent && f != parent
    }.sorted()
    val direct = tracks.filter { it.folder == parent }
    return childFolders to direct
}

internal fun buildDavData(lib: DavLibrary): SourceBrowserData {
    fun albumEntry(a: DavAlbum, prefix: String) = SourceEntry(
        id = "$prefix-${a.name}-${a.artist}",
        name = a.name.ifBlank { "未知专辑" },
        subtitle = "${a.artist} · ${a.tracks.size} 首",
        coverUrl = coverOf(a.tracks),
        embeddedAudioUrl = firstEmbedded(a.tracks),
        tracks = a.tracks
    )
    // 关键：专辑 / 歌手 / 歌单的条目**只构建一次**。
    // EntriesTab 每次被组合（切 Tab、从详情返回）都会重新调用 loader，
    // 以前每次都把上千条重新 map 一遍（还带字符串拼接和封面扫描），主线程会明显卡一拍。
    // 这里用 lazy 把结果挂在 buildDavData 的闭包里（data 由 remember(lib) 持有）——
    // 返回的是**同一个 List 实例**，LazyColumn 连项都不需要重建。
    val albumEntries by lazy { lib.albums.map { albumEntry(it, "dav-album") } }
    val artistEntries by lazy {
        lib.artists.map { ar ->
            SourceEntry(
                id = "dav-artist-${ar.name}",
                name = ar.name.ifBlank { "未知歌手" },
                subtitle = "${ar.albums.size} 张专辑 · ${ar.tracks.size} 首",
                coverUrl = coverOf(ar.tracks),
                embeddedAudioUrl = firstEmbedded(ar.tracks),
                albums = ar.albums.map { albumEntry(it, "dav-artist-album") }
            )
        }
    }
    val playlistEntries by lazy {
        lib.playlists.map { pl ->
            SourceEntry(
                id = "dav-playlist-${pl.name}",
                name = pl.name,
                subtitle = "${pl.tracks.size} 首 · M3U 自动识别",
                coverUrl = firstCoverOf(pl.tracks),
                embeddedAudioUrl = firstEmbedded(pl.tracks),
                tracks = pl.tracks
            )
        }
    }
    // 文件夹层级同理：按父路径缓存「子目录 + 直属歌曲」，反复进出不再全量 filter
    val folderCache = HashMap<String?, Pair<List<String>, List<Track>>>()
    val rootPrefix = folderRootPrefix(lib.tracks)
    return SourceBrowserData(
        sourceLabel = "WebDAV",
        loadSongs = { page ->
            if (page <= 1) lib.tracks to lib.tracks.size else emptyList<Track>() to lib.tracks.size
        },
        loadAlbums = { albumEntries },
        loadArtists = { artistEntries },
        // 歌单 Tab 直接来自全库扫描时自动识别的 .m3u / .m3u8，无需手动导入
        loadPlaylists = { playlistEntries },
        loadAlbumTracks = { e -> e.tracks },
        loadArtistAlbums = { e -> e.albums },
        loadPlaylistTracks = { e -> e.tracks },
        loadFolders = { parent ->
            folderCache.getOrPut(parent) { foldersOf(lib.tracks, parent, rootPrefix) }
        },
        supportsFolders = true,
        supportsGenres = false,
        loadRoam = { lib.tracks.shuffled().take(60) },
        search = { q ->
            if (q.isBlank()) emptyList()
            else lib.tracks.filter {
                it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
            }
        },
        // 只有异常才在顶部提示（认证失败 / 截断 / 限流）；正常完成不常驻展示统计数字
        status = lib.note ?: "",
        playlistHint = "已自动识别 WebDAV 目录中的 .m3u / .m3u8 歌单（共 ${lib.playlists.size} 个），点击即可查看歌曲"
    )
}

/** 本地音乐曲库：扫描本机文件夹得到，按专辑 / 歌手 / 文件夹分组，纯本地无需网络。 */
internal fun buildLocalData(tracks: List<Track>): SourceBrowserData {
    fun albumEntry(name: String, list: List<Track>) = SourceEntry(
        id = "local-album-$name",
        name = name.ifBlank { "未知专辑" },
        subtitle = "${list.firstOrNull()?.artist.orEmpty().ifBlank { "未知歌手" }} · ${list.size} 首",
        coverUrl = list.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
        embeddedAudioUrl = firstEmbedded(list),
        tracks = list
    )
    val albums by lazy {
        tracks.groupBy { it.album.ifBlank { "未知专辑" } }
            .map { (name, list) -> albumEntry(name, list) }
    }
    val artists by lazy {
        tracks.groupBy { it.artist.ifBlank { "未知歌手" } }.map { (name, list) ->
            SourceEntry(
                id = "local-artist-$name",
                name = name,
                subtitle = "${list.map { it.album }.distinct().size} 张专辑 · ${list.size} 首",
                coverUrl = list.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
                embeddedAudioUrl = firstEmbedded(list),
                albums = list.groupBy { it.album.ifBlank { "未知专辑" } }
                    .map { (an, al) -> albumEntry(an, al) }
            )
        }
    }
    val folderCache = HashMap<String?, Pair<List<String>, List<Track>>>()
    val rootPrefix = folderRootPrefix(tracks)
    return SourceBrowserData(
        sourceLabel = "本地音乐",
        loadSongs = { page -> if (page <= 1) tracks to tracks.size else emptyList<Track>() to tracks.size },
        loadAlbums = { albums },
        loadArtists = { artists },
        loadPlaylists = { emptyList() },
        loadAlbumTracks = { e -> e.tracks },
        loadArtistAlbums = { e -> e.albums },
        loadPlaylistTracks = { e -> e.tracks },
        loadFolders = { parent -> folderCache.getOrPut(parent) { foldersOf(tracks, parent, rootPrefix) } },
        supportsFolders = true,
        supportsGenres = false,
        loadRoam = { tracks.shuffled().take(60) },
        supportsRoam = true,
        search = { q ->
            if (q.isBlank()) emptyList()
            else tracks.filter {
                it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
            }
        },
        status = tracks.size.let { if (it > 0) "本地音乐 · $it 首" else "" },
        playlistHint = ""
    )
}

internal fun buildFnosData(): SourceBrowserData {
    val client = MusicBoxApp.instance.fnosClient
    // 文件夹树由本地全量索引构建：按父路径缓存，避免反复 filter 上万首歌
    val fnosFolderCache = HashMap<String?, Pair<List<String>, List<Track>>>()
    fun fnosFolderRoot(all: List<Track>) = folderRootPrefix(all)
    fun cover(id: String?) = client.coverUrl(id)
    return SourceBrowserData(
        sourceLabel = "飞牛音乐",
        loadSongs = { page ->
            val (list, total) = client.trackList(page, 100)
            list.map { client.toTrack(it) } to total
        },
        loadAlbums = {
            // 全量分页拉取：只取第一页会把曲库砍到 100 张（「专辑数量不对」的根因）。
            client.allAlbums().map { al ->
                SourceEntry(
                    id = "fnos-album-${al.guid}",
                    name = al.name.ifBlank { "未知专辑" },
                    subtitle = "${al.trackCount ?: "?"} 首 · 点击查看歌曲",
                    coverUrl = cover(al.coverId),
                    key = al.guid
                )
            }
        },
        loadArtists = {
            client.allArtists().map { ar ->
                SourceEntry(
                    id = "fnos-artist-${ar.guid}",
                    name = ar.name.ifBlank { "未知歌手" },
                    subtitle = "${ar.albumCount ?: ar.trackCount ?: "?"} 项 · 点击查看专辑",
                    coverUrl = cover(ar.coverId),
                    key = ar.guid
                )
            }
        },
        loadPlaylists = {
            client.allPlaylists().map { pl ->
                SourceEntry(
                    id = "fnos-playlist-${pl.guid}",
                    name = pl.name.ifBlank { "未命名歌单" },
                    subtitle = "${pl.trackCount ?: "?"} 首 · 服务端歌单",
                    coverUrl = cover(pl.coverId),
                    key = pl.guid
                )
            }
        },
        loadAlbumTracks = { e ->
            if (e.tracks.isNotEmpty()) e.tracks
            else {
                client.allAlbumTracks(e.key).map { client.toTrack(it) }
            }
        },
        loadArtistAlbums = { e ->
            if (e.albums.isNotEmpty()) e.albums
            else {
                val list = client.allArtistTracks(e.key)
                val grouped = LinkedHashMap<String, MutableList<Track>>()
                list.forEach { ft ->
                    val tr = client.toTrack(ft)
                    grouped.getOrPut(tr.album.ifBlank { "未知专辑" }) { mutableListOf() }.add(tr)
                }
                grouped.map { (name, ts) ->
                    SourceEntry(
                        id = "fnos-artist-album-${e.key}-$name",
                        name = name,
                        subtitle = "${ts.size} 首",
                        coverUrl = coverOf(ts),
                        tracks = ts
                    )
                }
            }
        },
        loadPlaylistTracks = { e ->
            if (e.tracks.isNotEmpty()) e.tracks
            else {
                client.allPlaylistTracks(e.key).map { client.toTrack(it) }
            }
        },
        search = { q ->
            val (list, _) = client.searchTrack(q, 1, 100)
            list.map { client.toTrack(it) }
        },
        supportsFolders = true,
        loadFolders = { parent ->
            val all = FnosCatalog.tracks()
            fnosFolderCache.getOrPut(parent) { foldersOf(all, parent, fnosFolderRoot(all)) }
        },
        supportsGenres = true,
        loadGenres = {
            val (list, _) = client.genreList(1, 300)
            list.map { g ->
                SourceEntry(
                    id = "fnos-genre-${g.guid}",
                    name = g.name.ifBlank { "未知风格" },
                    subtitle = "${g.trackCount ?: "?"} 首 · 服务端风格分类",
                    coverUrl = null,
                    key = g.guid
                )
            }
        },
        loadGenreTracks = { e ->
            val (list, _) = client.genreTracks(e.key, e.name, 1, 300)
            list.map { client.toTrack(it) }
        },
        loadRoam = { FnosCatalog.roam(60) },
        playlistHint = "自动读取飞牛音乐服务端的歌单，点击即可查看歌曲"
    )
}

// ==================== 统一浏览器界面（飞牛样式） ====================

private sealed class BrowserDetail {
    data class Album(val entry: SourceEntry) : BrowserDetail()
    data class Artist(val entry: SourceEntry) : BrowserDetail()
    data class ArtistAlbum(val entry: SourceEntry) : BrowserDetail()
    data class Playlist(val entry: SourceEntry) : BrowserDetail()
    data class Genre(val entry: SourceEntry) : BrowserDetail()
}

/**
 * 进程内记住各音源的「浏览位置」。
 *
 * 从详情里的歌曲进入播放页时，NavHost 会销毁 LibraryScreen 的 composition；
 * `remember` 的详情栈随之丢失，返回后就被扔回根列表、需要重新滑动——
 * 这是用户反馈「点击进入详情再返回列表应当停留原位置」的根因。
 * 详情栈里的 SourceEntry 持有 Track 列表，没法用 rememberSaveable 序列化，
 * 所以放在进程级对象里：返回时原样恢复，进程重启后自然清空。
 */
private object BrowserMemory {
    private val detailStacks = HashMap<String, List<BrowserDetail>>()
    private val pagerPages = HashMap<String, Int>()

    fun stack(key: String): List<BrowserDetail> = detailStacks[key] ?: emptyList()
    fun saveStack(key: String, stack: List<BrowserDetail>) { detailStacks[key] = stack }

    fun page(key: String): Int = pagerPages[key] ?: 0
    fun savePage(key: String, page: Int) { pagerPages[key] = page }
}

/**
 * 五个分类用 TabRow + HorizontalPager 承载：既可点 Tab，也可左右滑动切换。
 * 顶部只显示曲库统计 / 扫描进度与刷新，不再显示账号、编辑、断开等连接信息。
 */
@Composable
private fun SourceBrowser(
    data: SourceBrowserData,
    navController: NavController,
    /** 浏览位置的内存键（"webdav" / "fnos"）：进入播放页再返回时用来还原详情栈与 Tab 页。 */
    memoryKey: String,
    statusLine: String? = null,
    scanning: Boolean = false
) {
    // 栏目顺序完全由「设置 → 主界面栏目」决定（有序列表），
    // 再按当前音源的能力过滤掉用不上的栏目。
    val app = MusicBoxApp.instance
    val visibleTabs by app.visibleTabs.collectAsState()
    val tabs = buildList {
        visibleTabs.forEach { key ->
            if (key == LibraryTabs.FOLDERS && !data.supportsFolders) return@forEach
            if (key == LibraryTabs.GENRES && !data.supportsGenres) return@forEach
            if (key == LibraryTabs.ROAM && !data.supportsRoam) return@forEach
            add(key to LibraryTabs.labelOf(key))
        }
    }.ifEmpty { listOf(LibraryTabs.SONGS to "歌曲") }

    // 栏目集合变化时重建 PagerState：否则分页数量与当前页会不同步（可能越界崩溃）
    val tabsKey = tabs.joinToString(",") { it.first }
    val pagerState = key(tabsKey) {
        rememberPagerState(initialPage = BrowserMemory.page(memoryKey).coerceIn(0, tabs.lastIndex)) { tabs.size }
    }
    LaunchedEffect(pagerState.currentPage) { BrowserMemory.savePage(memoryKey, pagerState.currentPage) }
    val scope = rememberCoroutineScope()
    // 初值从进程内缓存恢复：进入播放页（NavHost 会销毁本页）再返回时，仍停在原来的详情层。
    var detailStack by remember(data) { mutableStateOf(BrowserMemory.stack(memoryKey)) }
    LaunchedEffect(detailStack) { BrowserMemory.saveStack(memoryKey, detailStack) }

    // 列表滚动位置：进入详情时列表会被移出 composition（remember 状态随之丢失），
    // 用 SaveableStateHolder 按「根列表 / 每个 Tab / 每层详情」分别保存，
    // 返回时精确恢复到原来的滚动位置，而不是从第一项重新开始。
    val stateHolder = rememberSaveableStateHolder()

    // App 锁死竖屏：SourceBrowser 只保留普通列表 + 详情层，横屏 CoverFlow 相关逻辑已移除。
    BackHandler(enabled = detailStack.isNotEmpty()) {
        detailStack = detailStack.dropLast(1)
    }

    Column(Modifier.fillMaxSize()) {
        // 仅在非全屏态、且扫描中或存在异常（认证失败 / 限流 / 目录上限）时显示文字；
        // 扫描正常完成后不再常驻展示「已扫描 N 个目录、找到 M 首歌」这类统计。
        // 顶部不再提供刷新按钮：重新扫描统一走「设置 → WebDAV 扫描」。
        val warn = statusLine != null && listOf("⚠", "失败", "认证", "上限", "限流", "未连接")
            .any { statusLine.contains(it) }
        val showText = scanning || warn
        if (showText && statusLine != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (scanning) "$statusLine" else "${data.sourceLabel} · $statusLine",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (detailStack.isNotEmpty()) {
            // 每层详情用独立 key：返回上一层时恢复它自己的滚动位置；
            // 同层再次进入（key 变化）则从头开始，符合预期。
            val detailKey = remember(detailStack) {
                "d${detailStack.size}_" + detailStack.joinToString(",") { it.hashCode().toString() }
            }
            // 进入一层详情：内容从右侧轻滑入 + 淡入，避免整屏"啪"地替换。
            // 只做进入动画——返回时内容立即消失，手感更跟手。
            val enter = remember { Animatable(0f) }
            LaunchedEffect(detailKey) {
                enter.snapTo(0f)
                enter.animateTo(1f, tween(durationMillis = 220))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = enter.value
                        translationX = (1f - enter.value) * 36.dp.toPx()
                    }
            ) {
                stateHolder.SaveableStateProvider(detailKey) {
                    DetailView(
                        data = data,
                        stack = detailStack,
                        onPush = { detailStack = detailStack + it },
                        onPop = { detailStack = detailStack.dropLast(1) }
                    )
                }
            }
        } else {
            // 根列表整体套一个 SaveableStateProvider：进入详情时这块会被移出 composition，
            // 没有它的话所有 Tab 的 LazyListState 都会丢，返回后从第一项重新开始。
            Box(Modifier.weight(1f).fillMaxWidth()) {
                stateHolder.SaveableStateProvider("root") {
                    Column(Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = pagerState.currentPage) {
                            tabs.forEachIndexed { index, (_, label) ->
                                Tab(
                                    selected = pagerState.currentPage == index,
                                    onClick = {
                                        detailStack = emptyList()
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    }
                                ) { Text(label, modifier = Modifier.padding(8.dp)) }
                            }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            beyondViewportPageCount = 1
                        ) { page ->
                            // 每个 Tab 单独保存：左右滑动切换 Tab 时旧页会被回收，
                            // 单独 provider 才能让「切过去再切回来」仍停在原位。
                            stateHolder.SaveableStateProvider("tab_" + tabs[page].first) {
                                when (tabs[page].first) {
                                    "songs" -> SongsTab(data)
                                    "artists" -> EntriesTab(data.loadArtists, "没有歌手") {
                                        detailStack = listOf(BrowserDetail.Artist(it))
                                    }
                                    "playlists" -> EntriesTab(
                                        data.loadPlaylists,
                                        "音源中未发现 M3U / M3U8 歌单",
                                        hint = data.playlistHint
                                    ) {
                                        detailStack = listOf(BrowserDetail.Playlist(it))
                                    }
                                    "albums" -> AlbumCoverFlowTab(data.loadAlbums, "没有专辑") {
                                        detailStack = listOf(BrowserDetail.Album(it))
                                    }
                                    // 只有「当前停留页」才允许文件夹层级接管返回手势，
                                    // 避免 Pager 预组合的相邻页抢走返回键。
                                    "folders" -> FoldersTab(data, active = pagerState.currentPage == page)
                                    "genres" -> EntriesTab(
                                        data.loadGenres,
                                        "服务端没有风格分类",
                                        hint = "读取飞牛音乐的风格 / 流派分类，点击某个风格查看曲目"
                                    ) {
                                        detailStack = listOf(BrowserDetail.Genre(it))
                                    }
                                    "roam" -> RoamTab(data)
                                    else -> SearchTab(data)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * 竖屏「专辑」Tab 的 Cover Flow：与横屏全屏版共用同一套 3D 唱片架视觉
 *（居中正对、两侧绕 Y 轴旋转 / 缩小 / 淡出 / 向中心聚拢），但作为普通 Tab 内容嵌在 Pager 里，
 * 去掉全屏沉浸 / 正在播放卡等横屏专属元素。左右滑动切专辑，点击居中封面翻面进详情；
 * 封面下方显示当前专辑名与副标题。
 */
@Composable
private fun AlbumCoverFlowTab(
    loader: suspend () -> List<SourceEntry>,
    emptyText: String,
    onOpen: (SourceEntry) -> Unit
) {
    var albums by remember(loader) { mutableStateOf<List<SourceEntry>>(emptyList()) }
    var loading by remember(loader) { mutableStateOf(true) }
    var error by remember(loader) { mutableStateOf<String?>(null) }

    LaunchedEffect(loader) {
        loading = true
        error = null
        runCatching { loader() }
            .onSuccess { albums = it }
            .onFailure { error = CrashLogger.describe("加载失败", it) }
        loading = false
    }

    when {
        loading -> LoadingRow()
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InlineError(error!!) }
        albums.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyHint(emptyText) }
        else -> {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            // 当前居中项：决定「点击是否进入」与底部信息栏，避免误点侧面封面就进详情。
            val currentIndex by remember(albums) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index ?: 0
                }
            }
            val currentAlbum = albums.getOrNull(currentIndex)

            // 翻转动画：点击居中封面后绕 Y 轴翻 180°，结束再打开专辑详情。
            var flippingIndex by remember { mutableStateOf<Int?>(null) }
            val flip by animateFloatAsState(
                targetValue = if (flippingIndex != null) 1f else 0f,
                animationSpec = tween(durationMillis = 480),
                finishedListener = { _ ->
                    val idx = flippingIndex
                    if (idx != null) {
                        flippingIndex = null
                        onOpen(albums[idx])
                    }
                }
            )

            val bg = Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    MaterialTheme.colorScheme.surface
                )
            )

            BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
                val availW = maxWidth
                val availH = maxHeight
                // 竖屏宽度较窄，以宽度为限制；留出底部信息栏高度。
                val card = minOf(availW * 0.66f, availH * 0.5f)
                val itemW = card * 0.84f

                Column(Modifier.fillMaxSize()) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = (availW - itemW) / 2),
                        verticalAlignment = Alignment.CenterVertically,
                        // 翻转过程中锁定滑动，保证翻面动画完整播放。
                        userScrollEnabled = flippingIndex == null
                    ) {
                        itemsIndexed(albums, key = { i, e -> "${e.id}#$i" }) { i, e ->
                            Box(
                                Modifier.width(itemW).height(card * 1.18f),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    Modifier
                                        .size(card)
                                        .graphicsLayer {
                                            if (i == flippingIndex) {
                                                // 翻转中：动画进度驱动 Y 轴旋转，呈现翻面效果。
                                                rotationY = flip * 180f
                                                scaleX = 1f
                                                scaleY = 1f
                                                alpha = 1f
                                                cameraDistance = 14f * density
                                            } else {
                                                val info = listState.layoutInfo
                                                val vi = info.visibleItemsInfo.firstOrNull { it.index == i }
                                                if (vi != null) {
                                                    val centerPx =
                                                        (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                                    val d = (vi.offset + vi.size / 2f) - centerPx
                                                    val norm = (d / (card.toPx() * 1.15f)).coerceIn(-1f, 1f)
                                                    rotationY = -norm * 46f
                                                    val s = 1f - abs(norm) * 0.26f
                                                    scaleX = s
                                                    scaleY = s
                                                    alpha = (1f - abs(norm) * 0.55f).coerceIn(0.12f, 1f)
                                                    translationX = -d * 0.16f
                                                    cameraDistance = 14f * density
                                                }
                                            }
                                        }
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (currentIndex == i) {
                                                // 居中封面：触发翻面动画，结束后进专辑详情。
                                                if (flippingIndex == null) flippingIndex = i
                                            } else scope.launch { listState.animateScrollToItem(i) }
                                        }
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                                RoundedCornerShape(10.dp)
                                            )
                                    ) {
                                        CoverImage(
                                            e.coverUrl,
                                            Modifier.fillMaxSize(),
                                            embeddedAudioUrl = e.embeddedAudioUrl,
                                            contentScale = ContentScale.Fit,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 底部：当前专辑信息 + 操作提示。
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            currentAlbum?.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            currentAlbum?.subtitle.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "左右滑动切换 · 点击封面进专辑   ${currentIndex + 1}/${albums.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}


/** 秒 → m:ss */
private fun fmtTime(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

/** 随机漫游：全库随机曲目，支持换一批与一键播放。 */
@Composable
private fun RoamTab(data: SourceBrowserData) {
    var tracks by remember(data) { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember(data) { mutableStateOf(true) }
    var error by remember(data) { mutableStateOf<String?>(null) }
    var refresh by remember(data) { mutableStateOf(0) }

    LaunchedEffect(data, refresh) {
        loading = true; error = null
        runCatching { data.loadRoam() }
            .onSuccess { tracks = it }
            .onFailure { error = CrashLogger.describe("漫游加载失败", it) }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "随机漫游" + if (tracks.isNotEmpty()) " · ${tracks.size} 首" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { refresh++ }) { Text("换一批") }
                if (tracks.isNotEmpty()) {
                    Button(onClick = { PlayerController.playTracks(tracks, 0) }) { Text("播放全部") }
                }
            }
        }
        if (loading) item { LoadingRow() }
        if (error != null) item { InlineError(error!!) }
        itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
            TrackRow(t, onClick = { PlayerController.playTracks(tracks, i) })
            HorizontalDivider()
        }
        if (tracks.isEmpty() && !loading && error == null) item { EmptyHint("曲库为空，无法漫游") }
    }
}

/** 歌曲 Tab：左侧封面歌曲行，飞牛的分页由「加载更多」承接。 */
@Composable
private fun SongsTab(data: SourceBrowserData) {
    var tracks by remember(data) { mutableStateOf<List<Track>>(emptyList()) }
    var page by remember(data) { mutableStateOf(1) }
    var total by remember(data) { mutableStateOf(0) }
    var loading by remember(data) { mutableStateOf(true) }
    var error by remember(data) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(data) {
        loading = true; error = null
        runCatching { data.loadSongs(1) }
            .onSuccess { (l, t) -> tracks = l; total = t; page = 1 }
            .onFailure { error = CrashLogger.describe("加载失败", it) }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (loading) item { LoadingRow() }
        if (error != null) item { InlineError(error!!) }
        itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
            TrackRow(t, onClick = { PlayerController.playTracks(tracks, i) })
            HorizontalDivider()
        }
        if (tracks.isEmpty() && !loading && error == null) item { EmptyHint("曲库为空") }
        if (tracks.size < total && !loading) {
            item {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { data.loadSongs(page + 1) }
                                .onSuccess { (l, t) -> tracks = tracks + l; page += 1; total = t }
                                .onFailure { error = CrashLogger.describe("加载更多失败", it) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) { Text("加载更多") }
            }
        }
    }
}

/** 专辑 / 歌手 / 歌单列表：左侧封面，点击进入详情，浏览不改变播放。 */
@Composable
private fun EntriesTab(
    loader: suspend () -> List<SourceEntry>,
    emptyText: String,
    hint: String? = null,
    onOpen: (SourceEntry) -> Unit
) {
    var entries by remember(loader) { mutableStateOf<List<SourceEntry>>(emptyList()) }
    var loading by remember(loader) { mutableStateOf(true) }
    var error by remember(loader) { mutableStateOf<String?>(null) }

    LaunchedEffect(loader) {
        loading = true; error = null
        runCatching { loader() }
            .onSuccess { entries = it }
            .onFailure { error = CrashLogger.describe("加载失败", it) }
        loading = false
    }

    when {
        loading -> LoadingRow()
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InlineError(error!!) }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (hint != null) {
                item {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
            if (entries.isEmpty()) item { EmptyHint(emptyText) }
            itemsIndexed(entries, key = { i, e -> "${e.id}#$i" }) { _, e ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverImage(e.coverUrl, Modifier.size(48.dp), embeddedAudioUrl = e.embeddedAudioUrl)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SearchTab(data: SourceBrowserData) {
    var query by rememberSaveable { mutableStateOf("") }
    var tracks by remember(data) { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            shape = RoundedCornerShape(12.dp),
            value = query, onValueChange = { query = it },
            label = { Text("搜索歌曲") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        Button(
            onClick = {
                val q = query
                if (q.isBlank()) return@Button
                loading = true; error = null; searched = true
                scope.launch {
                    // 全库检索是 O(n) 的字符串匹配（上万首时几十毫秒），放到后台线程，
                    // 否则点「搜索」的瞬间主线程会卡住、按钮涟漪都会掉帧。
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching { data.search(q) }
                    }
                    result
                        .onSuccess { tracks = it }
                        .onFailure { error = CrashLogger.describe("搜索失败", it) }
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        ) { Text("搜索") }
        if (loading) LoadingRow()
        else if (error != null) InlineError(error!!)
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
                TrackRow(t, onClick = { PlayerController.playTracks(tracks, i) })
                HorizontalDivider()
            }
            if (tracks.isEmpty() && searched) item { EmptyHint("没有找到匹配的歌曲") }
        }
    }
}

@Composable
private fun FoldersTab(data: SourceBrowserData, active: Boolean = true) {
    // 用「路径栈」而不是裸字符串记当前目录：
    // 1) 手势返回只要 dropLast 一层，不必靠字符串截取去猜根前缀在哪，避免退到根前缀时
    //    误判成「还在子目录里」，导致返回箭头一直亮着、再返回一次才回根；
    // 2) 栈本身可保存，进播放页再回来仍停在原目录。
    var pathHistory by rememberSaveable(stateSaver = pathStackSaver) {
        mutableStateOf<List<String>>(emptyList())
    }
    val currentPath = pathHistory.lastOrNull()
    var childFolders by remember(currentPath) { mutableStateOf<List<String>>(emptyList()) }
    var tracks by remember(currentPath) { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember(currentPath) { mutableStateOf(true) }
    var error by remember(currentPath) { mutableStateOf<String?>(null) }

    // 关键：文件夹是 Tab 内部的层级浏览，不走 detailStack。
    // 没有这个 BackHandler 时，返回手势会一路落到 MainActivity 的兜底逻辑 ——
    // 路由仍是 "library"，于是被判成「首页按返回」，直接 moveTaskToBack 退回桌面。
    // active 用于排除「Pager 预组合的相邻页」：左右预加载的文件夹页若也注册，会在别的
    // Tab 上抢走返回手势（Compose 的 BackHandler 是后注册优先）。
    BackHandler(enabled = active && pathHistory.isNotEmpty()) {
        pathHistory = pathHistory.dropLast(1)
    }

    LaunchedEffect(currentPath) {
        loading = true; error = null
        runCatching { data.loadFolders(currentPath) }
            .onSuccess { (f, t) -> childFolders = f; tracks = t }
            .onFailure { error = CrashLogger.describe("加载文件夹失败", it) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentPath != null) {
                IconButton(onClick = { pathHistory = pathHistory.dropLast(1) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回上级")
                }
            }
            Text(
                if (currentPath.isNullOrBlank()) "全部文件夹" else currentPath!!.substringAfterLast('/'),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
        }
        // 每一级目录各自保存滚动位置：返回上级时停在离开时的位置，而不是回到顶部。
        val stateHolder = rememberSaveableStateHolder()
        stateHolder.SaveableStateProvider(currentPath ?: "__folder_root__") {
            when {
                loading -> LoadingRow()
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InlineError(error!!) }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(childFolders) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { pathHistory = pathHistory + f }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(f.substringAfterLast('/').ifBlank { f }, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                    itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
                        TrackRow(t, onClick = { PlayerController.playTracks(tracks, i) })
                        HorizontalDivider()
                    }
                    if (childFolders.isEmpty() && tracks.isEmpty()) item { EmptyHint("该文件夹下没有音乐") }
                }
            }
        }
    }
}

/** 目录路径栈的保存器：List<String> 直接交给 Bundle 存字符串数组。 */
private val pathStackSaver = androidx.compose.runtime.saveable.listSaver<List<String>, String>(
    save = { it },
    restore = { it }
)

@Composable
private fun DetailView(
    data: SourceBrowserData,
    stack: List<BrowserDetail>,
    onPush: (BrowserDetail) -> Unit,
    onPop: () -> Unit
) {
    when (val detail = stack.last()) {
        is BrowserDetail.Artist -> ArtistAlbumsView(
            data = data,
            entry = detail.entry,
            onOpenAlbum = { onPush(BrowserDetail.ArtistAlbum(it)) },
            onBack = onPop
        )
        // 专辑 / 歌手专辑：参考 iOS 5，进入后只有歌曲列表，不再重复展示大图或缩略图
        is BrowserDetail.Album ->
            TracksDetailView(data.loadAlbumTracks, detail.entry, onPop, showCover = false)
        is BrowserDetail.ArtistAlbum ->
            TracksDetailView(data.loadAlbumTracks, detail.entry, onPop, showCover = false)
        is BrowserDetail.Playlist ->
            TracksDetailView(data.loadPlaylistTracks, detail.entry, onPop, showCover = true)
        is BrowserDetail.Genre ->
            TracksDetailView(data.loadGenreTracks, detail.entry, onPop, showCover = false)
    }
}

/** 歌手 → 专辑列表 → 歌曲列表，全程只浏览，不改变当前播放。 */
@Composable
private fun ArtistAlbumsView(
    data: SourceBrowserData,
    entry: SourceEntry,
    onOpenAlbum: (SourceEntry) -> Unit,
    onBack: () -> Unit
) {
    var albums by remember(entry) { mutableStateOf<List<SourceEntry>>(emptyList()) }
    var loading by remember(entry) { mutableStateOf(true) }
    var error by remember(entry) { mutableStateOf<String?>(null) }

    LaunchedEffect(entry) {
        loading = true; error = null
        runCatching { data.loadArtistAlbums(entry) }
            .onSuccess { albums = it }
            .onFailure { error = CrashLogger.describe("读取歌手专辑失败", it) }
        loading = false
        CrashLogger.info("Library", "歌手「${entry.name}」按专辑分组得到 ${albums.size} 张")
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${albums.size} 张专辑 · 点击查看歌曲", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (loading) LoadingRow()
        error?.let { InlineError(it) }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(albums, key = { i, a -> "${a.id}#$i" }) { _, a ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenAlbum(a) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverImage(a.coverUrl, Modifier.size(48.dp), embeddedAudioUrl = a.embeddedAudioUrl)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${a.tracks.size} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
            if (albums.isEmpty() && !loading && error == null) item { EmptyHint("该歌手暂无专辑数据") }
        }
    }
}

@Composable
private fun TracksDetailView(
    loader: suspend (SourceEntry) -> List<Track>,
    entry: SourceEntry,
    onBack: () -> Unit,
    showCover: Boolean = true
) {
    var tracks by remember(entry) { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember(entry) { mutableStateOf(true) }
    var error by remember(entry) { mutableStateOf<String?>(null) }

    LaunchedEffect(entry) {
        loading = true; error = null
        runCatching { loader(entry) }
            .onSuccess { tracks = it }
            .onFailure { error = CrashLogger.describe("读取歌曲失败", it) }
        loading = false
    }

    UnifiedTrackList(
        title = entry.name,
        subtitle = if (tracks.isNotEmpty()) "${tracks.size} 首 · ${entry.subtitle}" else entry.subtitle,
        tracks = tracks,
        loading = loading,
        error = error,
        onBack = onBack,
        showCover = showCover
    )
}

/** 详情歌曲列表：返回 + 标题 + 播放全部 + 左侧封面歌曲行。 */
@Composable
private fun UnifiedTrackList(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    showCover: Boolean = true
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (loading) LoadingRow()
        error?.let { InlineError(it) }
        Button(
            onClick = { PlayerController.playTracks(tracks, 0) },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            enabled = tracks.isNotEmpty()
        ) { Text("▶ 播放全部（${tracks.size}）") }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
                TrackRow(
                    t,
                    showCover = showCover,
                    trackNumber = if (showCover) null else i + 1,
                    onClick = { PlayerController.playTracks(tracks, i) }
                )
                HorizontalDivider()
            }
            if (tracks.isEmpty() && !loading && error == null) item { EmptyHint("该列表没有返回歌曲") }
        }
    }
}

// ==================== 音源入口（只浏览，不编辑连接） ====================

@Composable
private fun LocalSection(navController: NavController) {
    val app = MusicBoxApp.instance
    val tracks by app.localLibrary.collectAsState()
    if (tracks.isEmpty()) {
        LocalEmptyHint(navController)
        return
    }
    val data = remember(tracks) { buildLocalData(tracks) }
    WheelHome(
        data = data,
        navController = navController,
        memoryKey = "local",
        statusLine = data.status
    )
}

/**
 * 本地曲库为空时的引导页。
 *
 * library 路由是纯 iPod 转轮交互：没有 Dock、没有触摸手势，系统返回键在本页只会把 App 退到后台。
 * 因此这里必须保留转轮作为唯一交互——否则用户把音源切到「本地音乐」却一首都没收录时，
 * 既进不了设置重新扫描，也切不回其它音源，会彻底卡死。
 * 中心键 / MENU / 长按 MENU 一律前往设置，保证任何转轮操作都能脱困。
 */
@Composable
private fun LocalEmptyHint(navController: NavController) {
    val isPlaying by PlayerController.isPlaying.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    "尚未收录本地音乐",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "到「设置 → 扫描本地音乐」选择一个音乐文件夹",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "转动滚轮 · 按中心键或 MENU 前往设置",
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp), Alignment.Center) {
            ClickWheel(
                diameter = 240.dp,
                isPlaying = isPlaying,
                onRotate = {},
                onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
                // 非播放页长按播放键：直接跳到「正在播放」
                onPlayLongPress = {
                    navController.navigate("player") {
                        popUpTo("library") { saveState = true }
                        launchSingleTop = true
                    }
                },
                onPrevious = { PlayerController.previous() },
                onNext = { PlayerController.next() },
                onRewind = {},
                onForward = {},
                onMenu = { navController.navigate("settings") },
                onMenuHome = { navController.navigate("settings") },
                onConfirm = { navController.navigate("settings") }
            )
        }
    }
}

@Composable
private fun WebDavSection(navController: NavController) {
    val app = MusicBoxApp.instance
    val conn by app.webDavConn.collectAsState()
    val library by app.webDavLibrary.collectAsState()
    val progress by app.webDavScanProgress.collectAsState()
    val scanning by app.webDavScanning.collectAsState()
    val lib = library

    val c = conn
    if (c == null) {
        NotConnectedHint("WebDAV", navController)
        return
    }

    LaunchedEffect(c) {
        // 先对齐线路：主线路不可达时会切到备用，并把已缓存索引里的绝对地址原地改写，
        // 整个过程不重新扫描（重扫既慢又最容易触发网盘限流）。
        runCatching { app.ensureWebDavLine(c) }
            .onFailure { com.aipod.util.CrashLogger.error("WebDAV.line", it) }
        if (app.webDavLibrary.value == null && !app.webDavScanning.value &&
            !app.webDavAutoScanAttempted.value
        ) {
            // 交给 App 级作用域：离开本页/切 Tab 不会中断扫描
            app.startWebDavScan(c, automatic = true)
        }
    }
    // 始终展示可操作的滚轮主界面，扫描只在列表底部叠加一条细进度条，绝不整屏切换遮挡操作。
    // 首次连接尚无缓存（lib==null）时，用空曲库构造 data，列表显示「扫描中」提示；扫描完成
    // lib 出现后自动切到真实曲库。error 也只走列表内提示，不再有独立全屏扫描视图。
    val effectiveLib = lib ?: com.aipod.data.webdav.DavLibrary(
        tracks = emptyList(),
        albums = emptyList(),
        artists = emptyList(),
        playlists = emptyList(),
        scannedDirs = 0,
        failedDirs = 0
    )
    val data = remember(c, effectiveLib) { buildDavData(effectiveLib) }
    WheelHome(
        data = data,
        navController = navController,
        memoryKey = "webdav",
        statusLine = if (lib == null && scanning) "首次连接 · 后台扫描中" else data.status,
        scanning = scanning,
        scanProgress = progress
    )
}

@Composable
private fun FnOSSection(navController: NavController) {
    val app = MusicBoxApp.instance
    val conn by app.fnosConn.collectAsState()
    val loggedIn by app.fnosLoggedIn.collectAsState()
    val c = conn
    if (!loggedIn || c == null) {
        NotConnectedHint("飞牛音乐", navController)
        return
    }
    val data = remember(c) { buildFnosData() }
    WheelHome(data = data, navController = navController, memoryKey = "fnos")
}

/** 未连接时主页只做引导，不显示任何连接表单或账号信息。 */
@Composable
private fun NotConnectedHint(sourceName: String, navController: NavController) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("尚未连接$sourceName", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "连接信息统一在「设置 → 连接管理」中填写与修改",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { navController.navigate("settings") }) { Text("前往设置连接") }
        }
    }
}

// ==================== 共用小组件 ====================

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoadingRow() {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InlineError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
}
