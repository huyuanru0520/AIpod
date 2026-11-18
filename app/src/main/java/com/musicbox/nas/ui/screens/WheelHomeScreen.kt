package com.musicbox.nas.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.musicbox.nas.ui.theme.BrandRed
import com.musicbox.nas.util.CrashLogger
import com.musicbox.nas.util.formatDuration
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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

    // 当前正在播放的曲目：主页背景取它的封面做高斯模糊；无播放时回退到苹果红模糊。
    val queue by PlayerController.queue.collectAsState()
    val currentIndex by PlayerController.currentIndex.collectAsState()
    val currentTrack = queue.getOrNull(currentIndex)

    // 浏览栈：每一层是一个 WheelNode，中心键 push，MENU pop。
    var stack by remember(data) { mutableStateOf<List<WheelNode>>(listOf(WheelNode.Root)) }
    // 当前层选中项（切层时重置）
    var selected by remember(data) { mutableStateOf(0) }
    // 横屏：CoverFlow 点开的专辑详情节点；null=封面流。横竖屏各用一套浏览状态，互不干扰。
    var landscapeDetail by remember(data) { mutableStateOf<WheelNode?>(null) }

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
    // 横屏：无滚轮，点封面进专辑列表。landscapeDetail 为 null 时展示专辑封面流；
    // 非 null 时展示该专辑的曲目列表（仍全屏），左上角返回键回到封面流。
    // 竖屏：Root 主菜单直接映射为「专辑」层由滚轮驱动（保持既有行为）。
    val effectiveNode: WheelNode = if (isLandscape) (landscapeDetail ?: WheelNode.Albums) else node

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

    // 中心键 / 点击：歌曲直接播放；竖屏进播放页，横屏留在全屏 CoverFlow 只播放不跳页。
    fun confirm(item: WheelItem?, enterPlayer: Boolean = true) {
        if (item == null) return
        when (item) {
            is WheelItem.Op -> push(item.node)
            is WheelItem.Track -> {
                PlayerController.playTracks(item.all, item.index)
                if (enterPlayer) navController.navigate("player")
            }
            is WheelItem.Action -> {
                when (item.key) {
                    "now_playing" -> navController.navigate("player")
                    "settings" -> navController.navigate("settings")
                    "roam" -> scope.launch {
                        val list = data.loadRoam()
                        if (list.isNotEmpty()) {
                            PlayerController.playTracks(list, 0)
                            if (enterPlayer) navController.navigate("player")
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

    // 苹果音乐红：主界面底色统一用它，横竖屏都不再是黑底。
    val appleRed = com.musicbox.nas.ui.theme.BrandRed

    if (isLandscape) {
        // ============ 横屏：全屏 CoverFlow，无滚轮，点击进专辑列表，左上角返回 ============
        Box(Modifier.fillMaxSize()) {
            WheelBackdrop(current = currentTrack, red = appleRed)
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                loadError != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(loadError ?: "加载失败", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(effectiveNode.hint, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                }
                landscapeDetail != null -> {
                    // 专辑曲目列表（仍全屏）：可点击播放，左上角返回封面流
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { landscapeDetail = null }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "返回封面流", tint = Color.White)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(effectiveNode.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("共 ${items.size} 首 · 点击播放", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        LazyColumn(
                            Modifier.fillMaxSize().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            itemsIndexed(items) { i, item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { confirm(item, enterPlayer = false) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
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
                                        CoverImage(cover, Modifier.size(46.dp), embeddedAudioUrl = embedded)
                                    } else {
                                        Box(Modifier.size(46.dp), Alignment.Center) {
                                            Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.7f))
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title(), color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.subtitle(), color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // 专辑封面流：左右滑动切换（CoverFlow 自身保留滑动），点击封面进专辑
                    ClickCoverFlow(
                        items = items,
                        selected = selected.coerceIn(0, items.lastIndex),
                        onSelected = { selected = it },
                        onClick = { item ->
                            when (item) {
                                is WheelItem.Op -> landscapeDetail = item.node
                                is WheelItem.Track -> confirm(item, enterPlayer = false)
                                is WheelItem.Action -> confirm(item, enterPlayer = false)
                            }
                        }
                    )
                }
            }
        }
        return
    }

    // ============ 竖屏：动态背景（播放封面模糊 / 苹果红）+ 屏幕列表 + 滚轮 ============
    Box(Modifier.fillMaxSize()) {
        WheelBackdrop(current = currentTrack, red = appleRed)
        Column(Modifier.fillMaxSize()) {
            // 顶栏只是「屏幕」上的一块静态标题，没有任何按钮——首页唯一交互是转轮。
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        effectiveNode.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
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
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!statusLine.isNullOrBlank()) {
                Text(
                    statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 「屏幕」：滚轮上方那块显示区。不设任何底色 / 边框，列表直接浮在
            // 底下的模糊封面 / 红色光晕之上，配合透明系统栏形成统一的玻璃质感。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                when {
                    // 搜索输入态：屏幕就是一块字母盘，转动滚轮选字母，中心键确认，MENU 退格
                    node is WheelNode.Search && searchPhase == 0 -> SearchWheelPad(
                        query = searchQuery,
                        chars = searchChars,
                        charIndex = searchChar,
                        dark = false
                    )
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    loadError != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(loadError ?: "加载失败", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                    }
                    items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(effectiveNode.hint, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        WheelList(
                            items = items,
                            selected = selected,
                            immersive = false
                        )
                    }
                }
            }

            // 转轮：竖屏保留 iPod 转轮作为唯一交互
            val isPlaying by PlayerController.isPlaying.collectAsState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                ClickWheel(
                    diameter = 264.dp,
                    isPlaying = isPlaying,
                    centerActive = false,
                    dark = true,
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

/**
 * 主页动态背景：
 * - 有正在播放曲目 → 取该曲目封面做真高斯模糊铺满，再叠一层半透明暗色保证白字可读；
 * - 无正在播放 → 苹果音乐红的径向渐变（中心亮、边缘暗），模拟「红色模糊光晕」。
 *
 * 封面通过 [rememberCoverBitmap] 走同一份 LRU 缓存，加载完成自动刷新；不订阅进度流，
 * 因此切歌之外不会因每秒进度更新而重组。
 */
@Composable
private fun WheelBackdrop(current: com.musicbox.nas.data.Track?, red: Color) {
    val bmp = rememberCoverBitmap(current?.coverUrl, current?.embeddedAudioUrl)
    Box(Modifier.fillMaxSize()) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                    .blur(32.dp)
            )
            // 暗化蒙层：让白色文字与选中高亮在任意封面颜色上都清晰
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        } else {
            // 无播放：苹果红径向渐变做真高斯模糊，得到柔和光晕而非硬边色块（需求：背景模糊处理）。
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.1f; scaleY = 1.1f }
                    .blur(48.dp)
            ) {
                val w = size.width
                val h = size.height
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            red.copy(alpha = 0.96f),
                            red,
                            Color(0xFF8E1024),
                            Color(0xFF4E0814)
                        ),
                        center = Offset(w * 0.42f, h * 0.30f),
                        radius = maxOf(w, h) * 0.95f
                    )
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
    // 置顶「正在播放」：任何音源、任何栏目配置下都固定出现在第一项。
    val q = PlayerController.queue.value
    val cur = q.getOrNull(PlayerController.currentIndex.value)
    val nowSub = if (cur != null) "${cur.title} · ${cur.artist}" else "打开播放器"
    out += WheelItem.Action("now_playing", "正在播放", nowSub, Icons.Default.PlayArrow)
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
        // 红底上统一用白字 / 白色高亮
        val fg = Color.White
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 选中指示：左侧一条短竖线，取代原来的灰色圆角背景框
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 30.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (sel) BrandRed else Color.Transparent)
                    )
                    Spacer(Modifier.width(10.dp))
                    when (item) {
                        is WheelItem.Action -> {
                            Box(Modifier.size(48.dp), Alignment.Center) {
                                Icon(item.icon, contentDescription = null, tint = fg)
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
                        Text("↵", color = Color.White, fontSize = 20.sp)
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
    Box(Modifier.size(48.dp), Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg)
    }
}

// ==================== 横屏：全屏 CoverFlow 唱片架（左右滑动 + 点击选择） ====================

/**
 * 横屏全屏 CoverFlow：铺满整屏、没有滚轮、没有底栏。
 * - 左右滑动：用 LazyRow 自带手势（这是被允许的例外——CoverFlow 保留左右滑动切封面）；
 *   滑动停下后把居中项回报给 [onSelected]，3D 倾斜随之更新。
 * - 点击：点任意封面即回调 [onClick]，由上层决定进专辑列表还是播放。
 */
@Composable
private fun ClickCoverFlow(
    items: List<WheelItem>,
    selected: Int,
    onSelected: (Int) -> Unit,
    onClick: (WheelItem) -> Unit
) {
    val rowState = rememberLazyListState()
    // 只在首次有数据时定位一次到传入的选中项；之后绝不再因 selected 变化反向滚动。
    // 之前的实现同时有「selected 变化 → animateScrollToItem」和「滑动停止 → onSelected」，
    // 两者互相触发形成回环，表现为向后滑动静置后又自行滑回第一张。
    var didInit by remember(items) { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (!didInit && items.isNotEmpty()) {
            didInit = true
            val target = selected.coerceIn(0, items.lastIndex)
            runCatching { rowState.scrollToItem(target, 0) }
        }
    }
    // 居中项完全由当前滚动位置派生（含滚动偏移），实时驱动 3D 效果；
    // 滑动停止时只把结果回报给上层选中态，不做任何反向 scroll —— 这是消除回弹的关键。
    val centered by remember {
        derivedStateOf {
            if (items.isEmpty()) 0 else {
                val first = rowState.firstVisibleItemIndex
                val off = rowState.firstVisibleItemScrollOffset
                val approx = (off / 220f).roundToInt().coerceIn(0, 1)
                (first + approx).coerceIn(0, items.lastIndex)
            }
        }
    }
    LaunchedEffect(centered) {
        if (centered != selected) onSelected(centered)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val card = minOf(maxHeight * 0.55f, maxWidth * 0.20f)
        val itemW = card * 1.18f
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = (maxWidth - itemW) / 2
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(items) { i, item ->
                val d = (i - centered).toFloat()
                val norm = (d / 1.6f).coerceIn(-1f, 1f)
                val sel = i == centered
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
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (i != centered) onSelected(i)
                            onClick(item)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(card)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.10f))
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
                                    tint = Color.White.copy(alpha = 0.5f),
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
        // 顶部提示：左右滑动切换 · 点击进专辑
        Text(
            "左右滑动切换封面 · 点击进专辑",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        )
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
