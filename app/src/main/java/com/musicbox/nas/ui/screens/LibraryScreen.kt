package com.musicbox.nas.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musicbox.nas.MusicBoxApp
import com.musicbox.nas.LibraryTabs
import com.musicbox.nas.SourceMode
import com.musicbox.nas.data.Track
import com.musicbox.nas.data.fnos.FnosCatalog
import com.musicbox.nas.data.webdav.DavAlbum
import com.musicbox.nas.data.webdav.DavLibrary
import com.musicbox.nas.player.PlayerController
import com.musicbox.nas.ui.components.CoverImage
import com.musicbox.nas.ui.components.TrackRow
import com.musicbox.nas.util.CrashLogger
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
            val (list, _) = client.albumList(1, 100)
            list.map { al ->
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
            val (list, _) = client.artistList(1, 100)
            list.map { ar ->
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
            val (list, _) = client.playlistList(1, 100)
            list.map { pl ->
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
                val (list, _) = client.albumTracks(e.key, 1, 300)
                list.map { client.toTrack(it) }
            }
        },
        loadArtistAlbums = { e ->
            if (e.albums.isNotEmpty()) e.albums
            else {
                val (list, _) = client.artistTracks(e.key, 1, 500)
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
                val (list, _) = client.playlistTracks(e.key, 1, 300)
                list.map { client.toTrack(it) }
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
    private val coverFlowFlags = HashMap<String, Boolean>()
    private val pagerPages = HashMap<String, Int>()

    fun stack(key: String): List<BrowserDetail> = detailStacks[key] ?: emptyList()
    fun saveStack(key: String, stack: List<BrowserDetail>) { detailStacks[key] = stack }

    fun fromCoverFlow(key: String): Boolean = coverFlowFlags[key] == true
    fun saveFromCoverFlow(key: String, on: Boolean) { coverFlowFlags[key] = on }

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
    // 标记当前详情是否由「Cover Flow 点击」进入：若是，则专辑列表也走全屏沉浸。
    var detailFromCoverFlow by remember(data) { mutableStateOf(BrowserMemory.fromCoverFlow(memoryKey)) }
    LaunchedEffect(detailStack, detailFromCoverFlow) {
        BrowserMemory.saveStack(memoryKey, detailStack)
        BrowserMemory.saveFromCoverFlow(memoryKey, detailFromCoverFlow)
    }

    // 列表滚动位置：进入详情时列表会被移出 composition（remember 状态随之丢失），
    // 用 SaveableStateHolder 按「根列表 / 每个 Tab / 每层详情」分别保存，
    // 返回时精确恢复到原来的滚动位置，而不是从第一项重新开始。
    val stateHolder = rememberSaveableStateHolder()

    // 把 Cover Flow 的滚动状态提升到 SourceBrowser：进入专辑再返回时，
    // 因为 SourceBrowser 本身不卸载，滚动位置会被保留，停在之前选中的专辑而非从头开始。
    val cfListState = rememberLazyListState()

    // 「正在播放」卡片开关：横屏封面流内点按触发，从底部弹出；返回手势缩回原位置。
    var showNowPlaying by remember { mutableStateOf(false) }

    // 横屏：整页切换为 iOS 风格的专辑 Cover Flow（可左右滑动、3D 透视），
    // 点击居中封面进入专辑详情；右上角可切回普通列表。
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var forceList by rememberSaveable(data) { mutableStateOf(false) }
    // 回到竖屏时清掉「强制列表」，下次横屏仍进入 Cover Flow
    LaunchedEffect(isLandscape) { if (!isLandscape) forceList = false }

    // 详情栈清空即退出「由 Cover Flow 进入」的全屏上下文
    LaunchedEffect(detailStack.isEmpty()) {
        if (detailStack.isEmpty()) detailFromCoverFlow = false
    }

    // 进入 Cover Flow / 由其打开的专辑列表 → 全屏沉浸（隐藏底部导航、MiniPlayer 与系统栏）。
    val cfActive = isLandscape && !forceList && detailStack.isEmpty()
    val fullScreen = cfActive || (detailFromCoverFlow && detailStack.isNotEmpty())
    DisposableEffect(fullScreen) {
        app.fullScreenMode.value = fullScreen
        onDispose { app.fullScreenMode.value = false }
    }

    // 返回手势优先级（高→低）：①关闭「正在播放」卡 ②退出详情栈 ③退出 Cover Flow。
    // 关键：Cover Flow 自身也作为一层「可返回」，否则在封面流按返回会直接退到桌面，
    // 而不是回到普通列表视图（用户期望的「退出 coverflow」）。
    BackHandler(enabled = showNowPlaying || detailStack.isNotEmpty() || cfActive) {
        when {
            showNowPlaying -> showNowPlaying = false
            detailStack.isNotEmpty() -> {
                detailStack = detailStack.dropLast(1)
                if (detailStack.isEmpty()) detailFromCoverFlow = false
            }
            cfActive -> forceList = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 仅在非全屏态、且扫描中或存在异常（认证失败 / 限流 / 目录上限）时显示文字；
        // 扫描正常完成后不再常驻展示「已扫描 N 个目录、找到 M 首歌」这类统计。
        // 顶部不再提供刷新按钮：重新扫描统一走「设置 → WebDAV 扫描」。
        val warn = statusLine != null && listOf("⚠", "失败", "认证", "上限", "限流", "未连接")
            .any { statusLine.contains(it) }
        val showText = scanning || warn
        if (!fullScreen && showText && statusLine != null) {
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
                        onPop = {
                            detailStack = detailStack.dropLast(1)
                            if (detailStack.isEmpty()) detailFromCoverFlow = false
                        }
                    )
                }
            }
        } else if (cfActive) {
            CoverFlow(
                data = data,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                listState = cfListState,
                showNowPlaying = showNowPlaying,
                onOpenNowPlaying = { showNowPlaying = true },
                onCloseNowPlaying = { showNowPlaying = false },
                onOpenPlayer = { navController.navigate("player") },
                onOpen = {
                    detailFromCoverFlow = true
                    detailStack = listOf(BrowserDetail.Album(it))
                },
                onShowList = { forceList = true }
            )
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
                                        detailFromCoverFlow = false
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
                                    "albums" -> EntriesTab(data.loadAlbums, "没有专辑") {
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
 * iOS 风格的 Cover Flow（横屏专用）：
 * 专辑封面在中间 1:1 正对用户，左右两侧按距离做 Y 轴旋转 / 缩小 / 淡出 / 向中心聚拢，
 * 形成经典的「唱片架」透视；滑动带吸附，点击居中封面进入专辑详情。
 */
@Composable
private fun CoverFlow(
    data: SourceBrowserData,
    modifier: Modifier = Modifier,
    listState: LazyListState,
    showNowPlaying: Boolean,
    onOpenNowPlaying: () -> Unit,
    onCloseNowPlaying: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpen: (SourceEntry) -> Unit,
    onShowList: () -> Unit
) {
    var albums by remember(data) { mutableStateOf<List<SourceEntry>>(emptyList()) }
    var loading by remember(data) { mutableStateOf(true) }
    var error by remember(data) { mutableStateOf<String?>(null) }

    LaunchedEffect(data) {
        loading = true; error = null
        runCatching { data.loadAlbums() }
            .onSuccess { albums = it }
            .onFailure { error = CrashLogger.describe("加载失败", it) }
        loading = false
    }

    if (loading) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (error != null) {
        Box(modifier, contentAlignment = Alignment.Center) { InlineError(error!!) }
        return
    }
    if (albums.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { EmptyHint("没有专辑") }
        return
    }

    // 正在播放状态（用于底部「当前播放」入口与弹出卡片）。
    // 刻意不在这一层订阅 position：进度每 500ms 变一次，在这里读会让整个 Cover Flow
    //（含所有可见封面项的 graphicsLayer）跟着重组，横屏滑动会掉帧。进度下沉到 SheetProgress。
    val queue by PlayerController.queue.collectAsState()
    val curIdx by PlayerController.currentIndex.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val currentTrack = if (curIdx in queue.indices) queue[curIdx] else null
    val npProgress by animateFloatAsState(
        targetValue = if (showNowPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 320)
    )

    val scope = rememberCoroutineScope()

    // 翻转动画：点击居中封面后，该封面绕 Y 轴翻转 180°，动画结束后打开专辑列表。
    var flippingIndex by remember { mutableStateOf<Int?>(null) }
    val flip by animateFloatAsState(
        targetValue = if (flippingIndex != null) 1f else 0f,
        animationSpec = tween(durationMillis = 520),
        finishedListener = { _ ->
            val idx = flippingIndex
            if (idx != null) {
                flippingIndex = null
                onOpen(albums[idx])
            }
        }
    )

    // 当前居中项：用于「点击是否进入」
    val currentIndex by remember(albums) {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index ?: 0
        }
    }
    val bg = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surface
        )
    )

    BoxWithConstraints(modifier.background(bg)) {
        // 全屏沉浸：封面铺满可用区域，不再叠加底部专辑信息栏
        val availW = maxWidth
        val availH = maxHeight
        val card = minOf(availH * 0.62f, availW * 0.42f)
        val itemW = card * 0.82f
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = (availW - itemW) / 2),
            verticalAlignment = Alignment.CenterVertically,
            // 翻转过程中锁定滑动，保证翻面动画完整播放
            userScrollEnabled = flippingIndex == null
        ) {
            itemsIndexed(albums, key = { i, e -> "${e.id}#$i" }) { i, e ->
                Box(
                    Modifier.width(itemW).height(card * 1.3f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(card)
                            .graphicsLayer {
                                if (i == flippingIndex) {
                                    // 翻转中：用动画进度驱动 Y 轴旋转，呈现「翻面」效果
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
                                        // 以「一个卡片宽」为归一化单位，超出后收敛到 ±1
                                        val norm = (d / (card.toPx() * 1.15f)).coerceIn(-1f, 1f)
                                        rotationY = -norm * 46f
                                        val s = 1f - abs(norm) * 0.26f
                                        scaleX = s
                                        scaleY = s
                                        alpha = (1f - abs(norm) * 0.55f).coerceIn(0.12f, 1f)
                                        // 向中心轻推，营造封面互相叠靠的效果
                                        translationX = -d * 0.16f
                                        cameraDistance = 14f * density
                                    }
                                }
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (currentIndex == i) {
                                    // 点击居中封面：触发翻转动画，结束后打开专辑列表
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
                                // 完整展示整张封面，不裁切
                                contentScale = ContentScale.Fit,
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // 右上角：切回普通列表视图（竖屏时自动回到列表）
        IconButton(
            onClick = onShowList,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
        ) {
            Icon(Icons.Default.ViewList, contentDescription = "切换列表视图")
        }

        // 底部「当前播放」入口：点按从底部弹出卡片
        NowPlayingPill(
            track = currentTrack,
            isPlaying = isPlaying,
            onClick = onOpenNowPlaying,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
        )

        // 从底部滑入的「正在播放」卡片（返回手势缩回原位置）
        NowPlayingSheet(
            visible = showNowPlaying,
            progress = npProgress,
            track = currentTrack,
            queue = queue,
            currentIndex = curIdx,
            isPlaying = isPlaying,
            onClose = onCloseNowPlaying,
            onOpenPlayer = onOpenPlayer,
            onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
            onNext = { PlayerController.next() },
            onPrev = { PlayerController.previous() },
            onSeek = { PlayerController.seekTo(it) },
            onSelect = { PlayerController.playTracks(queue, it) }
        )
    }
}

/**
 * 横屏 Cover Flow 底部的「当前播放」入口：胶囊按钮，点按从底部弹出正在播放卡片。
 * 没有正在播放的歌曲时也显示，便于从封面流直接进入播放控制。
 */
/**
 * 卡片内的进度区：自己订阅播放进度，500ms 的刷新只影响这一小块，
 * 不会牵动整张「正在播放」卡片（含播放队列列表）重组。
 */
@Composable
private fun SheetProgress(onSeek: (Long) -> Unit) {
    val position by PlayerController.position.collectAsState()
    val duration by PlayerController.duration.collectAsState()
    val posSec = (position / 1000).toInt().coerceAtLeast(0)
    val durSec = (duration / 1000).toInt().coerceAtLeast(1)
    Slider(
        value = posSec.toFloat().coerceIn(0f, durSec.toFloat()),
        onValueChange = { onSeek((it * 1000).toLong()) },
        valueRange = 0f..durSec.toFloat(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(fmtTime(posSec), style = MaterialTheme.typography.labelSmall)
        Text(fmtTime(durSec), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NowPlayingPill(
    track: Track?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = track?.let { "${it.title} · ${it.artist}" } ?: "当前播放"
    Box(
        modifier
            .clickable(onClick = onClick)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

/**
 * 从底部滑入的「正在播放」卡片：封面 + 标题 + 进度 + 播放控制 + 打开播放页 + 播放队列。
 * progress∈[0,1] 驱动滑入/缩回动画；visible=false 且 progress≈0 时不参与组合。
 */
@Composable
private fun NowPlayingSheet(
    visible: Boolean,
    progress: Float,
    track: Track?,
    queue: List<Track>,
    currentIndex: Int,
    isPlaying: Boolean,
    onClose: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelect: (Int) -> Unit
) {
    if (!visible && progress <= 0.001f) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardH = maxHeight * 0.82f
        // 遮罩：随 progress 淡入，点按关闭卡片
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress * 0.5f }
                .background(Color.Black)
                .clickable(onClick = onClose)
        )
        // 卡片本体：底部对齐，按 progress 从下方滑入；缩回时回到原位置（屏幕下方）
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(cardH)
                .graphicsLayer {
                    translationY = (1f - progress) * cardH.toPx()
                    alpha = progress
                    clip = true
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                }
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
                .padding(16.dp)
        ) {
            // 顶部手柄条
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                )
            }
            Spacer(Modifier.height(12.dp))
            if (track == null) {
                Text(
                    "尚未播放任何歌曲",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(
                        track.coverUrl,
                        Modifier.size(64.dp),
                        embeddedAudioUrl = track.embeddedAudioUrl,
                        contentScale = ContentScale.Crop,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${track.artist} · ${track.album}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 进度条 + 时间：单独订阅播放进度，避免整张卡片每 500ms 重组
                SheetProgress(onSeek = onSeek)
                Spacer(Modifier.height(8.dp))
                // 播放控制
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "上一首") }
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "播放/暂停",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "下一首") }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenPlayer, modifier = Modifier.fillMaxWidth()) { Text("打开播放页") }
                Spacer(Modifier.height(10.dp))
                Text(
                    "播放队列",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // 队列（可滚动），点击任一首跳转到该首
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                    itemsIndexed(queue, key = { i, t -> "${t.id}#$i" }) { i, t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(i) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (i == currentIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (i == currentIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    t.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (i == currentIndex) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        HorizontalDivider()
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
private fun WebDavSection(navController: NavController) {
    val app = MusicBoxApp.instance
    val conn by app.webDavConn.collectAsState()
    val library by app.webDavLibrary.collectAsState()
    val progress by app.webDavScanProgress.collectAsState()
    val scanning by app.webDavScanning.collectAsState()
    val found by app.webDavScanFound.collectAsState()
    var scanError by remember { mutableStateOf<String?>(null) }

    val c = conn
    if (c == null) {
        NotConnectedHint("WebDAV", navController)
        return
    }

    LaunchedEffect(c) {
        // 先对齐线路：主线路不可达时会切到备用，并把已缓存索引里的绝对地址原地改写，
        // 整个过程不重新扫描（重扫既慢又最容易触发网盘限流）。
        runCatching { app.ensureWebDavLine(c) }
            .onFailure { com.musicbox.nas.util.CrashLogger.error("WebDAV.line", it) }
        if (app.webDavLibrary.value == null && !app.webDavScanning.value &&
            !app.webDavAutoScanAttempted.value
        ) {
            // 交给 App 级作用域：离开本页/切 Tab 不会中断扫描
            app.startWebDavScan(c, automatic = true)
        }
    }
    val refresh: () -> Unit = {
        scanError = null
        app.startWebDavScan(c)
    }
    val lib = library
    // 扫描中（含重新扫描、初始自动扫描）始终展示实时找到的音乐；
    // 未连接数据且空闲时，也由 ScanLiveView 统一给出空态 / 错误 / 重新扫描入口。
    if (lib == null || scanning) {
        ScanLiveView(
            progress = progress,
            scanning = scanning,
            found = found,
            error = scanError,
            onRefresh = refresh,
            onGoSettings = { navController.navigate("settings") }
        )
    } else {
        val data = remember(c, lib) { buildDavData(lib) }
        WheelHome(
            data = data,
            navController = navController,
            memoryKey = "webdav",
            statusLine = data.status
        )
    }
}

/**
 * 扫描态 / 空态统一视图：实时展示扫描过程中「已经找到的音乐」。
 * - 扫描中：顶部进度 + 实时滚动的歌曲列表（边扫边显示，点击任意一首可立即播放）
 * - 扫描结束但没数据：给出空态与「前往设置连接」引导
 * - 出错：展示错误并允许「重新扫描」
 */
@Composable
private fun ScanLiveView(
    progress: String,
    scanning: Boolean,
    found: List<Track>,
    error: String?,
    onRefresh: () -> Unit,
    onGoSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scanning) CircularProgressIndicator(Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (scanning) "正在并行扫描 WebDAV 音乐库…" else "扫描未找到音乐",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (progress.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        progress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        }
        if (error != null) {
            InlineError(error)
        }
        if (found.isNotEmpty()) {
            Text(
                "已找到 ${found.size} 首音乐（实时更新 · 点击任意一首可立即播放）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                itemsIndexed(found, key = { i, t -> "live-${t.id}#$i" }) { i, t ->
                    TrackRow(t, onClick = { PlayerController.playTracks(found, i) })
                    HorizontalDivider()
                }
            }
        } else if (!scanning && error == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("尚未扫描到音乐文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "连接信息在「设置 → 连接管理」中填写与修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onGoSettings) { Text("前往设置连接") }
                }
            }
        } else {
            // 扫描已开始，但还没找到任何文件
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "正在递归遍历目录、识别音频文件…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!scanning) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) { Text("重新扫描") }
        }
    }
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
