package com.musicbox.nas.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.musicbox.nas.LibraryTabs
import com.musicbox.nas.MusicBoxApp
import com.musicbox.nas.data.Track
import com.musicbox.nas.player.PlayerController
import com.musicbox.nas.ui.components.ClickWheel
import com.musicbox.nas.ui.components.CoverImage
import com.musicbox.nas.ui.components.rememberCoverBitmap
import com.musicbox.nas.util.CrashLogger
import com.musicbox.nas.util.formatDuration
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 滚轮首页：iPod classic 式「转轮选歌」主界面，替换原来的 TabRow + Pager 首页。
 *
 * - 竖屏：层级列表（主菜单 → 分类 → 子项 → 歌曲），转轮上下选择，中心键进入 / 播放，
 *   MENU 返回上一层（长按回主菜单），右上角设置按钮。
 * - 横屏：列表区直接呈现专辑封面 CoverFlow（3D 唱片架），转轮左右切换，中心键进入。
 *
 * 所有数据加载复用 [SourceBrowserData] 的 loader，不改动任何音源逻辑。
 */
@Composable
internal fun WheelHome(
    data: SourceBrowserData,
    navController: NavController,
    memoryKey: String,
    statusLine: String? = null
) {
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 浏览栈：每一层是一个 WheelNode，中心键 push，MENU pop。
    var stack by remember(data) { mutableStateOf<List<WheelNode>>(listOf(WheelNode.Root)) }
    // 当前层选中项（切层时重置）
    var selected by remember(data) { mutableStateOf(0) }

    // 页面数据由 Worker 异步加载，按「层类型 + 参数」缓存，避免重组重复请求网络。
    val scope = rememberCoroutineScope()
    var items by remember(data) { mutableStateOf<List<WheelItem>>(emptyList()) }
    var loading by remember(data) { mutableStateOf(true) }
    var loadError by remember(data) { mutableStateOf<String?>(null) }
    var searchQuery by remember(data) { mutableStateOf("") }
    // 搜索页的字母盘光标（纯滚轮输入，不用软键盘）
    var searchChar by remember(data) { mutableStateOf(0) }
    var searchPhase by remember(data) { mutableStateOf(0) } // 0=字母盘输入, 1=结果列表

    val node = stack.last()
    // 横屏：Root 主菜单直接映射为「专辑」层，进入第一屏即为封面流（对齐原 CoverFlow 行为）
    val effectiveNode: WheelNode = if (isLandscape && node is WheelNode.Root) WheelNode.Albums else node

    LaunchedEffect(effectiveNode, data, searchQuery) {
        loading = true
        loadError = null
        selected = 0
        val result = runCatching {
            if (effectiveNode is WheelNode.Search && searchQuery.isNotBlank()) {
                data.search(searchQuery).let { list -> list.mapIndexed { i, t -> t.toWheel(i, list) } }
            } else loadNode(effectiveNode, data)
        }
        result.onSuccess { items = it }.onFailure {
            items = emptyList()
            loadError = CrashLogger.describe("加载失败", it)
        }
        loading = false
    }

    fun push(next: WheelNode) {
        stack = stack + next
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun home() {
        if (stack.size > 1) stack = listOf<WheelNode>(WheelNode.Root)
    }

    // 中心键：歌曲直接播放并进入播放页；其它层 push 下一层。
    fun confirm(item: WheelItem?) {
        if (item == null) return
        when (item) {
            is WheelItem.Op -> push(item.node)
            is WheelItem.Track -> {
                PlayerController.playTracks(item.all, item.index)
                navController.navigate("player")
            }
            is WheelItem.Action -> {
                when (item.key) {
                    "settings" -> navController.navigate("settings")
                    "roam" -> scope.launch {
                        val list = data.loadRoam()
                        if (list.isNotEmpty()) {
                            PlayerController.playTracks(list, 0)
                            navController.navigate("player")
                        }
                    }
                    "search" -> push(WheelNode.Search)
                }
            }
        }
    }

    // 搜索字母盘：iPod 式滚轮输入，不用软键盘。末尾 ⌫=退格，✓=完成并看结果。
    val searchChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ ⌫✓"

    /** 转轮中心键的统一分派：搜索输入态走字母盘，其余走普通层级。 */
    fun onWheelConfirm() {
        if (node is WheelNode.Search && searchPhase == 0) {
            when (val ch = searchChars[searchChar.coerceIn(searchChars.indices)]) {
                '⌫' -> searchQuery = searchQuery.dropLast(1)
                '✓' -> if (searchQuery.isNotBlank()) searchPhase = 1
                ' ' -> searchQuery += " "
                else -> searchQuery += ch
            }
            return
        }
        confirm(items.getOrNull(selected))
    }

    /** 转轮 MENU 键：搜索输入态退格 / 结果态回字母盘；其余返回上一层。 */
    fun onWheelMenu() {
        when {
            node is WheelNode.Search && searchPhase == 0 ->
                searchQuery = searchQuery.dropLast(1)
            node is WheelNode.Search && searchPhase == 1 -> searchPhase = 0
            else -> pop()
        }
    }

    // 沉浸：横屏 CoverFlow 全屏；竖屏保留系统栏（状态栏常驻，避免历史黑带问题）。
    val app = MusicBoxApp.instance
    val immersive = isLandscape
    DisposableEffect(immersive) {
        app.fullScreenMode.value = immersive
        onDispose { app.fullScreenMode.value = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (immersive) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏只是「屏幕」上的一块静态标题，没有任何按钮——首页唯一交互是转轮。
            // 标题 / 副标题直接画在屏幕上，不去响应点击。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        effectiveNode.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (immersive) Color.White else MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = when {
                        loading -> "加载中…"
                        loadError != null -> loadError ?: ""
                        items.isEmpty() -> effectiveNode.hint
                        else -> "共 ${items.size} 项 · 转动滚轮选择"
                    }
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (immersive) Color.White.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!statusLine.isNullOrBlank() && !immersive) {
                Text(
                    statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 「屏幕」：滚轮上方那块显示区，做成 iPod 式内嵌面板——圆角 + 内嵌底色 + 细描边。
            // 只影响观感，里面所有内容仍由转轮驱动，无任何点击。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (immersive) Color(0xFF0C0C0E)
                        else {
                            val bg = MaterialTheme.colorScheme.background
                            // 浅色用略深于背景的灰、深色用略亮于背景的灰，形成「屏幕」凹陷感
                            if (bg.luminance() < 0.5f) Color(0xFF202024) else Color(0xFFEDEDF0)
                        }
                    )
                    .border(1.dp, if (immersive) Color.White.copy(alpha = 0.08f)
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            ) {
                when {
                    // 搜索输入态：屏幕就是一块字母盘，转动滚轮选字母，中心键确认，MENU 退格
                    node is WheelNode.Search && searchPhase == 0 -> SearchWheelPad(
                        query = searchQuery,
                        chars = searchChars,
                        charIndex = searchChar,
                        dark = !immersive
                    )
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = if (immersive) Color.White else MaterialTheme.colorScheme.primary)
                    }
                    loadError != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            loadError ?: "加载失败",
                            color = if (immersive) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            effectiveNode.hint,
                            color = if (immersive) Color.White.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    isLandscape -> {
                        // 横屏：列表区直接展示 CoverFlow，滚轮切换
                        CoverFlowWheel(
                            items = items,
                            selected = selected.coerceIn(0, items.lastIndex)
                        )
                    }
                    else -> {
                        WheelList(
                            items = items,
                            selected = selected,
                            immersive = immersive
                        )
                    }
                }
            }

            // 转轮：竖屏大半屏可见；横屏缩小放底部
            val isPlaying by PlayerController.isPlaying.collectAsState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                ClickWheel(
                    diameter = if (isLandscape) 168.dp else 264.dp,
                    isPlaying = isPlaying,
                    centerActive = false,
                    dark = !immersive,
                    onRotate = { steps ->
                        if (node is WheelNode.Search && searchPhase == 0) {
                            val n = searchChars.length
                            searchChar = ((searchChar + steps) % n + n) % n
                        } else if (items.isNotEmpty()) {
                            selected = (selected + steps).coerceIn(0, items.lastIndex)
                        }
                    },
                    onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
                    onPrevious = { PlayerController.previous() },
                    onNext = { PlayerController.next() },
                    onRewind = { PlayerController.seekTo((PlayerController.currentPositionMs() - 5_000L).coerceAtLeast(0)) },
                    onForward = { PlayerController.seekTo(PlayerController.currentPositionMs() + 5_000L) },
                    onMenu = { onWheelMenu() },
                    onMenuHome = { home() },
                    onConfirm = { onWheelConfirm() }
                )
            }
        }
    }
}

// ==================== 层级节点与条目 ====================

internal sealed class WheelNode(val title: String, val hint: String) {
    object Root : WheelNode("音乐库", "转动滚轮选择分类")
    object Songs : WheelNode("歌曲", "没有歌曲")
    object Albums : WheelNode("专辑", "没有专辑")
    object Artists : WheelNode("歌手", "没有歌手")
    object Playlists : WheelNode("歌单", "没有歌单")
    object Folders : WheelNode("文件夹", "没有文件夹")
    object Genres : WheelNode("风格", "没有风格分类")
    object Search : WheelNode("搜索", "输入关键词搜索歌曲")
    data class Folder(val path: String?, val label: String) : WheelNode(label, "此文件夹没有歌曲")
    data class AlbumDetail(val entry: SourceEntry) : WheelNode(entry.name, "没有歌曲")
    data class ArtistAlbums(val entry: SourceEntry) : WheelNode(entry.name, "没有专辑")
    data class PlaylistDetail(val entry: SourceEntry) : WheelNode(entry.name, "没有歌曲")
    data class GenreDetail(val entry: SourceEntry) : WheelNode(entry.name, "没有歌曲")
}

internal sealed class WheelItem {
    data class Op(val node: WheelNode, val title: String, val subtitle: String, val cover: String?, val embedded: String? = null) : WheelItem()
    data class Track(val title: String, val subtitle: String, val cover: String?, val embedded: String?, val index: Int, val all: List<com.musicbox.nas.data.Track>) : WheelItem()
    data class Action(val key: String, val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) : WheelItem()
}

/** 根据节点异步加载条目（复用 SourceBrowserData 的 loader）。 */
private suspend fun loadNode(node: WheelNode, data: SourceBrowserData): List<WheelItem> = when (node) {
    WheelNode.Root -> buildRoot(data)
    WheelNode.Songs -> data.loadSongs(1).first.let { list -> list.mapIndexed { i, t -> t.toWheel(i, list) } }
    WheelNode.Albums -> data.loadAlbums().map {
        WheelItem.Op(WheelNode.AlbumDetail(it), it.name, it.subtitle, it.coverUrl, it.embeddedAudioUrl)
    }
    WheelNode.Artists -> data.loadArtists().map {
        WheelItem.Op(WheelNode.ArtistAlbums(it), it.name, it.subtitle, it.coverUrl, it.embeddedAudioUrl)
    }
    WheelNode.Playlists -> data.loadPlaylists().map {
        WheelItem.Op(WheelNode.PlaylistDetail(it), it.name, it.subtitle, it.coverUrl, it.embeddedAudioUrl)
    }
    WheelNode.Folders -> {
        val (folders, tracks) = data.loadFolders(null)
        buildFolderItems(folders, tracks, data, null)
    }
    WheelNode.Genres -> data.loadGenres().map {
        WheelItem.Op(WheelNode.GenreDetail(it), it.name, it.subtitle, it.coverUrl, it.embeddedAudioUrl)
    }
    is WheelNode.Folder -> {
        val (folders, tracks) = data.loadFolders(node.path)
        buildFolderItems(folders, tracks, data, node.path)
    }
    is WheelNode.AlbumDetail -> data.loadAlbumTracks(node.entry).let { list ->
        list.mapIndexed { i, t -> t.toWheel(i, list) }
    }
    is WheelNode.ArtistAlbums -> data.loadArtistAlbums(node.entry).map {
        WheelItem.Op(WheelNode.AlbumDetail(it), it.name, it.subtitle, it.coverUrl, it.embeddedAudioUrl)
    }
    is WheelNode.PlaylistDetail -> data.loadPlaylistTracks(node.entry).let { list ->
        list.mapIndexed { i, t -> t.toWheel(i, list) }
    }
    is WheelNode.GenreDetail -> data.loadGenreTracks(node.entry).let { list ->
        list.mapIndexed { i, t -> t.toWheel(i, list) }
    }
    WheelNode.Search -> emptyList()
}

private fun buildRoot(data: SourceBrowserData): List<WheelItem> {
    val out = mutableListOf<WheelItem>()
    val visible = MusicBoxApp.instance.visibleTabs.value
    fun has(key: String) = key in visible
    if (has(LibraryTabs.SONGS)) out += WheelItem.Op(WheelNode.Songs, "歌曲", "全部曲目", null)
    if (has(LibraryTabs.ALBUMS)) out += WheelItem.Op(WheelNode.Albums, "专辑", "按专辑浏览", null)
    if (has(LibraryTabs.ARTISTS)) out += WheelItem.Op(WheelNode.Artists, "歌手", "按歌手浏览", null)
    if (has(LibraryTabs.PLAYLISTS)) out += WheelItem.Op(WheelNode.Playlists, "歌单", "歌单列表", null)
    if (has(LibraryTabs.FOLDERS) && data.supportsFolders) out += WheelItem.Op(WheelNode.Folders, "文件夹", "按目录浏览", null)
    if (has(LibraryTabs.GENRES) && data.supportsGenres) out += WheelItem.Op(WheelNode.Genres, "风格", "风格 / 流派", null)
    if (has(LibraryTabs.ROAM) && data.supportsRoam) out += WheelItem.Action("roam", "漫游", "随机播放全库", Icons.Default.Shuffle)
    if (has(LibraryTabs.SEARCH)) out += WheelItem.Action("search", "搜索", "查找歌曲", Icons.Default.Search)
    // 设置也作为普通条目放进滚轮列表——首页顶栏不放任何可点按钮（纯滚轮交互）
    out += WheelItem.Action("settings", "设置", "主题 / 音源 / 音质", Icons.Default.Settings)
    return out
}

private fun buildFolderItems(
    folders: List<String>,
    tracks: List<com.musicbox.nas.data.Track>,
    data: SourceBrowserData,
    parent: String?
): List<WheelItem> {
    val out: MutableList<WheelItem> = folders.map { f ->
        WheelItem.Op(WheelNode.Folder(f, f.substringAfterLast('/')), f.substringAfterLast('/'), "文件夹", null)
    }.toMutableList()
    out += tracks.mapIndexed { i, t -> t.toWheel(i, tracks) }
    return out
}

private fun com.musicbox.nas.data.Track.toWheel(i: Int, all: List<com.musicbox.nas.data.Track>): WheelItem.Track =
    WheelItem.Track(title, artist.ifBlank { album.ifBlank { "未知" } }, coverUrl, embeddedAudioUrl, i, all)

/** 条目的通用显示文案。 */
private fun WheelItem.title(): String = when (this) {
    is WheelItem.Op -> title
    is WheelItem.Track -> title
    is WheelItem.Action -> title
}
private fun WheelItem.subtitle(): String = when (this) {
    is WheelItem.Op -> subtitle
    is WheelItem.Track -> subtitle
    is WheelItem.Action -> subtitle
}

// ==================== 竖屏：滚轮列表 ====================

@Composable
private fun WheelList(
    items: List<WheelItem>,
    selected: Int,
    immersive: Boolean
) {
    val listState = rememberLazyListState()
    // 选中项变化时自动滚到可视区中央
    LaunchedEffect(selected) {
        if (selected in items.indices) {
            runCatching { listState.animateScrollToItem(selected, -180) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fg = if (immersive) Color.White else MaterialTheme.colorScheme.onBackground
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp)
        ) {
            itemsIndexed(items) { i, item ->
                val sel = i == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(66.dp)
                        .background(
                            if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (item) {
                        is WheelItem.Action -> {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                                Alignment.Center
                            ) {
                                Icon(item.icon, contentDescription = null, tint = if (sel) MaterialTheme.colorScheme.primary else fg)
                            }
                        }
                        is WheelItem.Op -> {
                            if (item.cover != null || item.embedded != null) {
                                CoverImage(item.cover, Modifier.size(48.dp), embeddedAudioUrl = item.embedded)
                            } else {
                                CategoryIcon(item.node, sel, fg)
                            }
                        }
                        is WheelItem.Track -> {
                            CoverImage(item.cover, Modifier.size(48.dp), embeddedAudioUrl = item.embedded)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title(),
                            style = if (sel) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            item.subtitle(),
                            style = MaterialTheme.typography.labelSmall,
                            color = fg.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (sel) {
                        Text("↵", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryIcon(node: WheelNode, selected: Boolean, fg: Color) {
    val icon = when (node) {
        WheelNode.Songs -> Icons.Default.MusicNote
        WheelNode.Albums -> Icons.Default.Album
        WheelNode.Artists -> Icons.Default.Person
        WheelNode.Playlists -> Icons.Default.PlaylistPlay
        WheelNode.Folders, is WheelNode.Folder -> Icons.Default.Folder
        WheelNode.Genres -> Icons.Default.Album
        WheelNode.Search -> Icons.Default.Search
        else -> Icons.Default.MusicNote
    }
    Box(
        Modifier
            .size(48.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(10.dp)
            ),
        Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else fg)
    }
}

// ==================== 横屏：CoverFlow 唱片架（滚轮驱动） ====================

@Composable
private fun CoverFlowWheel(
    items: List<WheelItem>,
    selected: Int
) {
    val rowState = rememberLazyListState()
    LaunchedEffect(selected) {
        runCatching { rowState.animateScrollToItem(selected, -160) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val card = minOf(maxHeight * 0.5f, maxWidth * 0.22f)
        val itemW = card * 1.15f
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = (maxWidth - itemW) / 2
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(items) { i, item ->
                val d = (i - selected).toFloat()
                val norm = (d / 1.6f).coerceIn(-1f, 1f)
                val sel = i == selected
                Column(
                    Modifier
                        .width(itemW)
                        .fillMaxHeight()
                        .graphicsLayer {
                            rotationY = -norm * 42f
                            val s = 1f - abs(norm) * 0.28f
                            scaleX = s
                            scaleY = s
                            alpha = (1f - abs(norm) * 0.5f).coerceIn(0.15f, 1f)
                            cameraDistance = 14f * density
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(card)
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    ) {
                        val cover = when (item) {
                            is WheelItem.Op -> item.cover
                            is WheelItem.Track -> item.cover
                            is WheelItem.Action -> null
                        }
                        val embedded = when (item) {
                            is WheelItem.Op -> item.embedded
                            is WheelItem.Track -> item.embedded
                            is WheelItem.Action -> null
                        }
                        if (cover != null || embedded != null) {
                            CoverImage(cover, Modifier.fillMaxSize(), embeddedAudioUrl = embedded, contentScale = ContentScale.Crop)
                        } else {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                Icon(
                                    Icons.Default.Album,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.3f),
                                    modifier = Modifier.size(card * 0.4f)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        item.title(),
                        color = Color.White.copy(alpha = if (sel) 1f else 0.6f),
                        style = if (sel) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(card)
                    )
                }
            }
        }
    }
}

// ==================== 搜索：滚轮字母盘（纯转轮输入，无软键盘、无点击） ====================

/**
 * iPod 式搜索字母盘：把当前输入 query、正在选中的字符、以及一条字母轨道画在「屏幕」上。
 * 转动滚轮移动光标、中心键确认、MENU 退格——整块区域没有任何可点击控件。
 */
@Composable
private fun SearchWheelPad(
    query: String,
    chars: String,
    charIndex: Int,
    dark: Boolean
) {
    val fg = if (dark) MaterialTheme.colorScheme.onBackground else Color.White
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("搜索", style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.6f))
        Spacer(Modifier.height(10.dp))
        // 已输入的关键词（带光标下划线），没有输入时给占位提示
        Text(
            if (query.isEmpty()) "转动滚轮输入关键词" else query,
            style = MaterialTheme.typography.headlineSmall,
            color = if (query.isEmpty()) fg.copy(alpha = 0.4f) else fg,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(120.dp)
                .height(2.dp)
                .background(accent)
        )
        Spacer(Modifier.height(28.dp))
        // 当前选中的字符——大字显示，一眼知道中心键会输入什么
        val cur = chars[charIndex.coerceIn(chars.indices)]
        val label = when (cur) {
            '⌫' -> "退格"
            '✓' -> "完成"
            ' ' -> "空格"
            else -> cur.toString()
        }
        Text(
            label,
            style = MaterialTheme.typography.displayMedium,
            color = accent,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        // 字母轨道：高亮当前位置，左右各显示一段上下文
        val ctx = 6
        val start = (charIndex - ctx).coerceAtLeast(0)
        val end = (charIndex + ctx + 1).coerceAtMost(chars.length)
        val window = chars.substring(start, end)
        val offsetInWindow = charIndex - start
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            window.forEachIndexed { i, c ->
                val isCur = i == offsetInWindow
                Text(
                    when (c) {
                        '⌫' -> "←"
                        '✓' -> "✓"
                        ' ' -> "_"
                        else -> c.toString()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCur) accent else fg.copy(alpha = 0.35f),
                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "滚轮选字符 · 中心键确认 · MENU 退格 / 看好结果后按 ✓",
            style = MaterialTheme.typography.labelSmall,
            color = fg.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}
