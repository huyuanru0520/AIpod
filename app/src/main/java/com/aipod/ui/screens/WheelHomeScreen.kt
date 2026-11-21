package com.aipod.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aipod.LibraryTabs
import com.aipod.MusicBoxApp
import com.aipod.data.Track
import com.aipod.player.PlayerController
import com.aipod.ui.components.ClickWheel
import com.aipod.ui.components.CoverImage
import com.aipod.ui.components.fadeEdges
import com.aipod.ui.components.fadeInList
import com.aipod.ui.components.glassHighlight
import com.aipod.ui.components.rememberGlassBackdrop
import com.aipod.ui.components.LocalGlassBackdrop
import com.aipod.ui.components.AmbientMeshBackground
import com.aipod.ui.components.rememberCoverBitmap
import com.aipod.ui.theme.BrandRed
import com.aipod.util.CrashLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

/**
 * 滚轮首页的进程内缓存。
 * - 列表项缓存：进入过的层级（歌曲/收藏/专辑详情…）结果按 key 留存，返回或重复进入时
 *   立即显示，不再每次转圈等待网络分页（飞牛全量拉取尤其慢）。
 * - 选中位置缓存：每个层级记住上次停在哪一行，长按 MENU 回主界面再进来仍停在原处。
 * 进程重启自然清空，属于可重建的纯 UI 状态。
 */
private object WheelMemory {
    private val items = HashMap<String, List<WheelItem>>()
    private val selections = HashMap<String, Int>()

    /**
     * 缓存 key 带上 [data] 的实例身份：上游 data 由 remember(tracks/lib/conn) 构造，
     * 曲库未变时是同一实例 → key 稳定 → 命中缓存；重新扫描/切音源产生新实例 → key 变化
     * → 旧缓存自然失效并重新加载。这样"命中即返回"不会让曲库刷新后长期显示旧数据。
     */
    fun key(memoryKey: String, node: WheelNode, data: Any): String =
        "$memoryKey|${nodeKey(node)}|${System.identityHashCode(data)}"

    fun items(key: String): List<WheelItem>? = items[key]
    fun saveItems(key: String, list: List<WheelItem>) { items[key] = list }

    fun selection(key: String): Int = selections[key] ?: 0
    fun saveSelection(key: String, index: Int) { selections[key] = index }

    /** 列表内容变化（重新扫描/切歌收藏）后让相关缓存失效，避免长期显示旧数据。 */
    fun invalidateItems(memoryKey: String? = null) {
        if (memoryKey == null) items.clear()
        else items.keys.removeIf { it.startsWith("$memoryKey|") }
    }
}

/** 层级稳定 key：单例用类名，带参数的用参数值，保证同一层级跨进出 key 一致。 */
private fun nodeKey(node: WheelNode): String = when (node) {
    WheelNode.Root -> "root"
    WheelNode.Songs -> "songs"
    WheelNode.Albums -> "albums"
    WheelNode.Artists -> "artists"
    WheelNode.Playlists -> "playlists"
    WheelNode.Folders -> "folders"
    WheelNode.Genres -> "genres"
    WheelNode.Favorites -> "favorites"
    WheelNode.Search -> "search"
    is WheelNode.Folder -> "folder:${node.path}"
    is WheelNode.AlbumDetail -> "album:${node.entry.id}"
    is WheelNode.ArtistAlbums -> "artist:${node.entry.id}"
    is WheelNode.PlaylistDetail -> "playlist:${node.entry.id}"
    is WheelNode.GenreDetail -> "genre:${node.entry.id}"
}

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
    statusLine: String? = null,
    // 后台扫描状态：有缓存曲库时界面照常可操作，只在列表底部叠加一条细进度条。
    scanning: Boolean = false,
    scanProgress: String = ""
) {
    // 当前正在播放的曲目：主页背景取它的封面做高斯模糊；无播放时回退到苹果红模糊。
    val queue by PlayerController.queue.collectAsState()
    val currentIndex by PlayerController.currentIndex.collectAsState()
    val currentTrack = queue.getOrNull(currentIndex)

    // 浏览栈：每一层是一个 WheelNode，中心键 push，MENU pop。
    var stack by remember(data) { mutableStateOf<List<WheelNode>>(listOf(WheelNode.Root)) }
    // 当前层选中项：切层时从 WheelMemory 恢复上次位置（长按 MENU 回主界面再进入仍停原处）
    var selected by remember(data) { mutableStateOf(0) }

    // 页面数据由 Worker 异步加载；命中 WheelMemory 缓存时先用缓存立即显示，不再转圈。
    val scope = rememberCoroutineScope()
    var items by remember(data) { mutableStateOf<List<WheelItem>>(emptyList()) }
    var loading by remember(data) { mutableStateOf(true) }
    var loadError by remember(data) { mutableStateOf<String?>(null) }
    var searchQuery by remember(data) { mutableStateOf("") }
    // 搜索页的字母盘光标（纯滚轮输入，不用软键盘）
    var searchChar by remember(data) { mutableStateOf(0) }
    var searchPhase by remember(data) { mutableStateOf(0) } // 0=字母盘输入, 1=结果列表

    val node = stack.last()
    // 竖屏单一路径：Root 主菜单 → 转轮选择分类 → 进入对应层。
    // 横屏 CoverFlow 分支已移除（App 锁死竖屏）。

    // 层级导航方向：1=进入子层（新页从右滑入），-1=返回上层（新页从左滑入）。
    // 由 push / pop / home 更新，供 AnimatedContent 的 transitionSpec 读取。
    var navDirection by remember(data) { mutableStateOf(1) }

    // 每屏展示数据快照：AnimatedContent 切换时，退出中的旧页仍读到外层的 items/selected，
    // 会被新层加载的数据「闪改」。按 node 缓存一份，让旧页始终读自己进入时的那份数据。
    // items/selected 仍是加载逻辑的「当前工作副本」，这里只是额外镜像供转场内容读取。
    val screenSnapshots = remember(data) { mutableStateMapOf<String, ScreenSnapshot>() }

    val cacheKey = WheelMemory.key(memoryKey, node, data)
    val isSearch = node is WheelNode.Search && searchQuery.isNotBlank()

    LaunchedEffect(node, data, searchQuery) {
        loadError = null
        // 命中缓存：立即显示列表与上次选中位置，不转圈；随后在后台静默刷新数据。
        // 搜索结果按关键词实时查询，不进缓存。
        val cached = if (!isSearch) WheelMemory.items(cacheKey) else null
        if (cached != null) {
            items = cached
            selected = WheelMemory.selection(cacheKey).coerceIn(0, cached.lastIndex.coerceAtLeast(0))
            loading = false
            // 关键：命中缓存后必须立即返回，绝不能再往下走 loadNode 静默刷新。
            // 否则会：① 再拉一次全量 2185 首 / 22 页；② 返回后用【全新的 list 对象】二次整体
            // 替换 items；③ itemsIndexed 无稳定 key → 2185 行全部重新 diff + 组合；④ 每行
            // glassHighlight 动画 + CoverImage 重新解码封面；⑤ selected 已恢复到深层第 N 行，
            // 可见区正好落在深层，需即时解码该处封面。这五步叠加直接把主线程拖进 ANR，
            // 表现为「滑动多行后退出、再次进入歌曲页卡死」——这正是本 bug 的根因。
            // 缓存本身就是完整的 2185 首曲库（变化极少），直接使用即可，无需每次进入都刷新。
            return@LaunchedEffect
        } else {
            loading = true
            if (!isSearch) selected = WheelMemory.selection(cacheKey).coerceAtLeast(0)
        }
        val result = try {
            Result.success(
                if (isSearch) data.search(searchQuery).let { list -> list.mapIndexed { i, t -> t.toWheel(i, list) } }
                else loadNode(node, data)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 页面切走 / 重组导致协程取消（拉全量分页时更容易被取消）：正常流程，
            // 绝不能当成「加载失败」展示，也不要清空已有列表或改动状态。
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
        result.onSuccess { list ->
            if (!isSearch) WheelMemory.saveItems(cacheKey, list)
            items = list
            // 列表变化后收敛选中位置；命中缓存时已恢复过，这里只防止越界。
            selected = selected.coerceIn(0, list.lastIndex.coerceAtLeast(0))
            if (!isSearch) WheelMemory.saveSelection(cacheKey, selected)
        }.onFailure {
            // 有缓存时保留缓存继续可用，仅无缓存时展示错误。
            if (items.isEmpty()) {
                loadError = CrashLogger.describe("加载失败", it)
            }
        }
        loading = false
    }

    // 用户滚轮/点击改变选中行时，记住该层级位置（长按 MENU 回主界面再进入可恢复）。
    LaunchedEffect(selected, cacheKey) {
        if (!isSearch) WheelMemory.saveSelection(cacheKey, selected)
    }

    // 把当前层的展示数据镜像进快照，供 AnimatedContent 中转场中的旧页读取。
    // key 用 nodeKey（同一 AlbumDetail 实例稳定），值随 items/selected/状态同步刷新。
    LaunchedEffect(cacheKey, node, items, selected, loading, loadError, searchQuery, searchPhase) {
        screenSnapshots[cacheKey] = ScreenSnapshot(node, items, selected, loading, loadError, searchQuery, searchPhase)
    }

    fun push(next: WheelNode) {
        navDirection = 1
        stack = stack + next
    }

    fun pop() {
        if (stack.size > 1) {
            navDirection = -1
            stack = stack.dropLast(1)
        }
    }

    fun home() {
        if (stack.size > 1) {
            navDirection = -1
            stack = listOf<WheelNode>(WheelNode.Root)
        }
    }

    // 中心键 / 点击：歌曲直接播放；竖屏进播放页，横屏留在全屏 CoverFlow 只播放不跳页。
    fun confirm(item: WheelItem?, enterPlayer: Boolean = true) {
        if (item == null) return
        when (item) {
            is WheelItem.Op -> push(item.node)
            is WheelItem.Track -> {
                // 点击正在播放的曲目时 playFromList 保持进度不从头；其余正常起播。
                // 两种情况都进入播放页（横屏 CoverFlow 的 enterPlayer=false 除外）。
                PlayerController.playFromList(item.all, item.index)
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

    /**
     * 左右键移动一行（列表界面行为）：与中心转轮转动共用同一套「选中项」语义。
     * 搜索字母盘阶段左右键改字母光标，否则改列表选中行。
     * steps：左键 -1、右键 +1；短按调用一次，长按由 ClickWheel 循环调用实现连续移动。
     */
    fun moveSelection(steps: Int) {
        if (node is WheelNode.Search && searchPhase == 0) {
            val n = searchChars.length
            searchChar = ((searchChar + steps) % n + n) % n
        } else if (items.isNotEmpty()) {
            selected = (selected + steps).coerceIn(0, items.lastIndex)
        }
    }

    // 苹果音乐红：主界面底色统一用它，不再是黑底。
    val appleRed = com.aipod.ui.theme.BrandRed

    // 液态玻璃采样源：背景层录制进该 backdrop，滚轮玻璃从同一实例采样（兄弟配对）。
    val glassBackdrop = rememberGlassBackdrop()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassBackdrop provides glassBackdrop
    ) {
    // ============ 竖屏：动态背景（播放封面模糊 / 苹果红）+ 屏幕列表 + 滚轮 ============
    // 背景取色源：只有停在「专辑」层时才跟随当前选中的专辑封面；
    // 退出专辑层 / 回主菜单 / 进其它列表 / 正在播放时，统一跟随正在播放曲目，
    // 都没有就由 WheelBackdrop 用苹果红兜底。这样从播放页返回、退出专辑时颜色连续不跳。
    val focusItem = if (node is WheelNode.Albums) items.getOrNull(selected.coerceIn(0, items.lastIndex.coerceAtLeast(0))) else null
    val backdropCover = when (focusItem) {
        is WheelItem.Op -> focusItem.cover
        is WheelItem.Track -> focusItem.cover
        else -> currentTrack?.coverUrl
    }
    val backdropEmbedded = when (focusItem) {
        is WheelItem.Op -> focusItem.embedded
        is WheelItem.Track -> focusItem.embedded
        else -> currentTrack?.embeddedAudioUrl
    }
    Box(Modifier.fillMaxSize()) {
        WheelBackdrop(coverUrl = backdropCover, embedded = backdropEmbedded, red = appleRed)
        Column(Modifier.fillMaxSize()) {
            // 所有层级（含二级列表）都不显示标题与说明小字——滚轮上方就是纯列表屏幕，
            // 省下的高度让列表多显示一行。加载中/错误/空态由列表区的独立提示承担。
            Spacer(Modifier.statusBarsPadding())

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
                // 层级切换用 iPod 式整页推入转场：进入子层新页从右滑入、旧页左移淡出；
                // 返回则反向。targetState 用各层稳定的 cacheKey，node 本身由 stack.last() 派生。
                AnimatedContent(
                    targetState = cacheKey,
                    transitionSpec = {
                        val navSpec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(280)
                        val fadeSpec = androidx.compose.animation.core.tween<Float>(200)
                        if (navDirection > 0) {
                            (slideInHorizontally(navSpec) { it } + fadeIn(fadeSpec)) togetherWith
                                (slideOutHorizontally(navSpec) { -it / 4 } + fadeOut(fadeSpec))
                        } else {
                            (slideInHorizontally(navSpec) { -it } + fadeIn(fadeSpec)) togetherWith
                                (slideOutHorizontally(navSpec) { it / 4 } + fadeOut(fadeSpec))
                        }
                    },
                    label = "wheel-level-transition"
                ) { targetKey ->
                    // 转场中旧页读自己那一屏的快照，不被新层加载的 items 闪改；
                    // 首次进入还没镜像时回退到当前外层状态。
                    val snap = screenSnapshots[targetKey]
                    val shownItems = snap?.items ?: items
                    val shownSelected = snap?.selected ?: selected
                    val shownLoading = snap?.loading ?: loading
                    val shownError = snap?.loadError ?: loadError
                    val shownQuery = snap?.searchQuery ?: searchQuery
                    val shownPhase = snap?.searchPhase ?: searchPhase
                    // 用快照里冻结的 node 决定这一屏渲染哪种界面（不随外层 node 变）。
                    val screenNode = snap?.node ?: node

                    when {
                        // 搜索输入态：屏幕就是一块字母盘，转动滚轮选字母，中心键确认，MENU 退格
                        screenNode is WheelNode.Search && shownPhase == 0 -> SearchWheelPad(
                            query = shownQuery,
                            chars = searchChars,
                            charIndex = searchChar,
                            dark = false
                        )
                        shownLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                        shownError != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(shownError, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        }
                        shownItems.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(
                                if (scanning) "正在扫描音乐库，找到的歌曲会自动出现…" else screenNode.hint,
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        // 专辑层：3D CoverFlow 唱片架（转轮/左右键切换封面，中心键或点封面进专辑）。
                        // 这是用户真正看到的「专辑列表」——所有音源都走 WheelHome，这里才是生效点。
                        screenNode is WheelNode.Albums -> ClickCoverFlow(
                            items = shownItems,
                            selected = shownSelected.coerceIn(0, shownItems.lastIndex),
                            onSelected = { selected = it },
                            onClick = { confirm(it) },
                            followSelection = true,
                            hint = "转动滚轮切换封面 · 按中心键进专辑"
                        )
                        else -> {
                            WheelList(
                                items = shownItems,
                                selected = shownSelected,
                                immersive = false
                            )
                        }
                    }
                }

                // 后台扫描中：在列表底部叠加一条细进度条，仅提示进度、不遮挡滚轮操作。
                if (scanning) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (scanProgress.isNotBlank()) scanProgress else "后台扫描中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.22f)
                        )
                    }
                }
            }

            // 转轮：竖屏保留 iPod 转轮作为唯一交互。
            // 上移到「列表底部」与「屏幕底部」之间：底部留出约一项的空白，列表可视区相应少一项。
            val isPlaying by PlayerController.isPlaying.collectAsState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                ClickWheel(
                    diameter = 264.dp,
                    isPlaying = isPlaying,
                    centerActive = false,
                    dark = true,
                    onRotate = { steps -> moveSelection(steps) },
                    onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
                    // 非播放页长按播放键：直接跳到「正在播放」
                    onPlayLongPress = {
                        navController.navigate("player") {
                            popUpTo("library") { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    // 列表界面：左右键短按上一行 / 下一行，长按由 ClickWheel 循环调用连续移动行。
                    onPrevious = { moveSelection(-1) },
                    onNext = { moveSelection(1) },
                    onRewind = { moveSelection(-1) },
                    onForward = { moveSelection(1) },
                    onMenu = { onWheelMenu() },
                    onMenuHome = { home() },
                    onConfirm = { onWheelConfirm() },
                    hapticOnLongRepeat = true
                )
            }
        }
    }
    } // CompositionLocalProvider(LocalGlassBackdrop)
}

/**
 * 主页动态背景：封面取色 → 环境光流光渐变（详见 AmbientMeshBackground）。
 *
 * 不再把整张封面高斯模糊铺满——模糊封面既浑浊又有涂抹感，亮度也不可控。
 * 现在从封面提取主色/鲜艳色/柔和色，生成三团缓慢游走的流光，颜色彼此交融，
 * 配合前方液态玻璃滚轮，通透感和空间感都比一张糊图强得多。
 * 无播放时用苹果红作为种子色，保持主界面的红色气质。
 *
 * 封面通过 [rememberCoverBitmap] 走同一份 LRU 缓存，加载完成自动刷新；不订阅进度流，
 * 因此切歌之外不会因每秒进度更新而重组。
 */
@Composable
private fun WheelBackdrop(coverUrl: String?, embedded: String?, red: Color) {
    val bmp = rememberCoverBitmap(coverUrl, embedded)
    AmbientMeshBackground(
        bitmap = bmp,
        modifier = Modifier.fillMaxSize(),
        fallback = red,
        // 用封面 URL 做缓存 key：从播放页切回来时直接命中上次的取色，不再闪兜底红
        cacheKey = coverUrl ?: embedded
    )
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
    object Favorites : WheelNode("我的收藏", "还没有收藏歌曲 · 播放页长按播放键收藏")
    object Search : WheelNode("搜索", "输入关键词搜索歌曲")
    data class Folder(val path: String?, val label: String) : WheelNode(label, "此文件夹没有歌曲")
    data class AlbumDetail(val entry: SourceEntry) : WheelNode(entry.name, "没有歌曲")
    data class ArtistAlbums(val entry: SourceEntry) : WheelNode(entry.name, "没有专辑")
    data class PlaylistDetail(val entry: SourceEntry) : WheelNode(entry.name, "没有歌曲")
    data class GenreDetail(val entry: SourceEntry) : WheelNode(entry.name, "没有歌曲")
}

internal sealed class WheelItem {
    data class Op(val node: WheelNode, val title: String, val subtitle: String, val cover: String?, val embedded: String? = null) : WheelItem()
    data class Track(val title: String, val subtitle: String, val cover: String?, val embedded: String?, val index: Int, val all: List<com.aipod.data.Track>) : WheelItem()
    data class Action(val key: String, val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) : WheelItem()
}

/**
 * 单个层级的展示数据快照。层级切换用 AnimatedContent 做推入转场时，退出中的旧页组合仍存活，
 * 若直接读外层共享的 items/selected 会被新层加载结果「闪改」；按 node 冻结一份，转场期间各读各的。
 */
private data class ScreenSnapshot(
    val node: WheelNode,
    val items: List<WheelItem>,
    val selected: Int,
    val loading: Boolean,
    val loadError: String?,
    val searchQuery: String,
    val searchPhase: Int
)

/**
 * 拉取全部分页的歌曲。飞牛 /track/list 每页最多 100 首，只取第 1 页会漏掉其余曲目；
 * 这里按 total 循环到取满为止。WebDAV / 本地的 loader 第 1 页即返回全部、第 2 页为空，
 * 循环会自然终止，因此三种音源统一走这里没有副作用。设 100 页上限兜底防止异常死循环。
 */
private suspend fun loadAllSongs(data: SourceBrowserData): List<com.aipod.data.Track> {
    // 第 1 页必须串行：飞牛要用它拿 total 和单页条数，WebDAV/本地则第 1 页就返回全量。
    val (first, total) = try {
        data.loadSongs(1)
    } catch (e: CancellationException) {
        throw e // 页面切走/取消必须向上传播，不能吞掉（否则协程作用域泄露、日志误报）
    } catch (e: Exception) {
        com.aipod.util.CrashLogger.error("loadAllSongs.p1", e)
        return emptyList()
    }
    if (first.isEmpty()) return emptyList()
    // 第 1 页已取满（WebDAV/本地：total=0 或第 1 页即全部）→ 直接返回，不多发请求。
    if (total <= 0 || first.size >= total) return first

    // 按第 1 页实际条数推算总页数，上限 100 页兜底防异常循环。
    val pageSize = first.size
    val pageCount = ((total + pageSize - 1) / pageSize).coerceAtMost(100)
    if (pageCount <= 1) return first

    // 剩余页并发拉取（限 4 路，避免对 NAS 造成瞬时压力 / 被网盘判异常），
    // 结果按页码回填到数组，保证与串行版完全一致的歌曲顺序。
    val gate = Semaphore(4)
    val pages = coroutineScope {
        (2..pageCount).map { p ->
            async {
                gate.withPermit {
                    try {
                        data.loadSongs(p).first
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        com.aipod.util.CrashLogger.error("loadAllSongs.p$p", e)
                        emptyList()
                    }
                }
            }
        }.awaitAll()
    }
    val all = ArrayList<com.aipod.data.Track>(total.coerceAtMost(Int.MAX_VALUE - 8))
    all.addAll(first)
    pages.forEach { all.addAll(it) }
    com.aipod.util.CrashLogger.info(
        "loadAllSongs",
        "${data.sourceLabel} 并行拉取 $pageCount 页完成：${all.size}/$total 首"
    )
    return all
}

/** 根据节点异步加载条目（复用 SourceBrowserData 的 loader）。 */
private suspend fun loadNode(node: WheelNode, data: SourceBrowserData): List<WheelItem> = when (node) {
    WheelNode.Root -> buildRoot(data)
    WheelNode.Songs -> loadAllSongs(data).let { list -> list.mapIndexed { i, t -> t.toWheel(i, list) } }
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
    WheelNode.Favorites -> {
        // 从当前音源曲库中筛出已收藏曲目（收藏按 WebDAV/FNOS/本地 分表，取当前源那一表）
        val favIds = MusicBoxApp.instance.currentFavorites()
        val all = loadAllSongs(data)
        val favTracks = all.filter { it.id in favIds }
        favTracks.mapIndexed { i, t -> t.toWheel(i, favTracks) }
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
    out += WheelItem.Op(WheelNode.Favorites, "我的收藏", "已收藏的歌曲", null)
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
    tracks: List<com.aipod.data.Track>,
    data: SourceBrowserData,
    parent: String?
): List<WheelItem> {
    val out: MutableList<WheelItem> = folders.map { f ->
        WheelItem.Op(WheelNode.Folder(f, f.substringAfterLast('/')), f.substringAfterLast('/'), "文件夹", null)
    }.toMutableList()
    out += tracks.mapIndexed { i, t -> t.toWheel(i, tracks) }
    return out
}

private fun com.aipod.data.Track.toWheel(i: Int, all: List<com.aipod.data.Track>): WheelItem.Track =
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
    // 选中项变化时滚动，按跨度分两种走法：
    // - 大跨度（再次进入恢复上次位置、切换层级）：必须瞬时 scrollToItem。
    //   若此时仍用 animateScrollToItem，会从第 0 项一路动画滚到第 N 项，
    //   沿途组合数百个 item 并触发同等数量的网络封面加载，主线程测量 + 图片解码风暴
    //   直接表现为「进入歌曲页后卡死无响应」。这正是"滑动多行后退出再进入"必现的根因。
    // - 小跨度（转轮逐格 / 左右键）：animateScrollToItem 平滑跟随，保留 iPod 手感。
    LaunchedEffect(selected) {
        if (selected !in items.indices) return@LaunchedEffect
        val first = listState.firstVisibleItemIndex
        if (abs(selected - first) > 4) {
            runCatching { listState.scrollToItem(selected, -180) }
        } else {
            runCatching { listState.animateScrollToItem(selected, -180) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 环境光背景上统一用白字 / 白色高亮
        val fg = Color.White
        val rowShape = RoundedCornerShape(14.dp)
        // 不再套「整列一张玻璃卡」：列表直接浮在流光背景上，屏幕区域保持完整通透；
        // 选中行用和滚轮同款的真玻璃高亮（glassHighlight：离散挂载 + 轻 lens 折射），
        // 不做整列玻璃卡，所以不会「玻璃叠玻璃」发灰，也不会在滚动时留残影。
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .fadeInList()
                .fadeEdges(0.08f)
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 20.dp)
        ) {
            // 稳定 key：按内容标识而非下标，避免 items 被等价列表替换时 2185 行全部重新组合。
            itemsIndexed(items, key = { _, item ->
                when (item) {
                    is WheelItem.Op -> "op-${nodeKey(item.node)}"
                    is WheelItem.Track -> "track-${item.index}-${item.title}"
                    is WheelItem.Action -> "action-${item.key}"
                }
            }) { i, item ->
                val sel = i == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .glassHighlight(sel, rowShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                    }
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
 * 3D CoverFlow 唱片架（竖屏专辑层 / 触摸滑动）。
 * - 触摸左右滑动：LazyRow 自带手势，滑动停下后把居中项回报 [onSelected]；
 * - 转轮 / 左右键：[followSelection=true] 时 [selected] 变化会平滑滚动到对应封面；
 * - 点击：点任意封面即回调 [onClick]，由上层决定进专辑还是播放。
 *
 * 回环防护：程序因 selected 变化而滚动期间用 [suppressReport] 抑制「停止→回报」，
 * 避免「selected→滚动→回报→selected」互相触发造成抖动 / 自行滑回。
 */
@Composable
private fun ClickCoverFlow(
    items: List<WheelItem>,
    selected: Int,
    onSelected: (Int) -> Unit,
    onClick: (WheelItem) -> Unit,
    followSelection: Boolean = false,
    hint: String = "左右滑动切换封面 · 点击进专辑"
) {
    val rowState = rememberLazyListState()
    // 设备双轴倾斜（-1..1）+ 角速度：供倒影做明显视差与动态模糊。
    // 受「倒影随动」开关控制：关闭时注销传感器、回正为 0，倒影静止但仍显示、不耗电。
    // 用 MutableFloatState 对象（非 by 委托），倾斜只在 graphicsLayer lambda 内读快照，
    // 不触发 ClickCoverFlow 重组——避免打断 LazyRow 的逐帧 3D 滑动动画。
    val reflectionTiltOn by com.aipod.MusicBoxApp.instance.reflectionTilt.collectAsState()
    val tiltX = remember { mutableFloatStateOf(0f) }
    val tiltY = remember { mutableFloatStateOf(0f) }
    val tiltSpeed = remember { mutableFloatStateOf(0f) }
    // 动态模糊档位预建一次、按速度复用引用，避免每帧 new BlurEffect 卡顿。
    val blurDp = LocalDensity.current
    val blurStages = remember(blurDp) {
        val d = blurDp.density
        listOf(2.2f, 3.0f, 3.8f, 4.6f, 5.4f).map { r ->
            androidx.compose.ui.graphics.BlurEffect(r * d, r * d)
        }
    }
    val context = LocalContext.current
    val detector = remember { com.aipod.util.TiltDetector(context) }
    DisposableEffect(detector, reflectionTiltOn) {
        detector.onTilt = { x, y, s ->
            tiltX.floatValue = x
            tiltY.floatValue = y
            tiltSpeed.floatValue = s
        }
        if (reflectionTiltOn && detector.isAvailable()) detector.start()
        onDispose {
            detector.stop()
            detector.onTilt = null
        }
    }
    LaunchedEffect(reflectionTiltOn) {
        if (!reflectionTiltOn) {
            tiltX.floatValue = 0f
            tiltY.floatValue = 0f
            tiltSpeed.floatValue = 0f
        }
    }
    // 首次定位：瞬时跳到传入的选中项（无动画），避免进入页面时长距离滚动。
    var didInit by remember(items) { mutableStateOf(false) }
    // 程序滚动期间抑制回报，防止回环。
    var suppressReport by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (!didInit && items.isNotEmpty()) {
            didInit = true
            val target = selected.coerceIn(0, items.lastIndex)
            runCatching { rowState.scrollToItem(target, 0) }
        }
    }
    // 竖屏转轮 / 左右键切换：selected 变化时平滑滚动到对应封面。
    if (followSelection) {
        LaunchedEffect(selected, items) {
            if (!didInit || items.isEmpty()) return@LaunchedEffect
            val target = selected.coerceIn(0, items.lastIndex)
            // 只有当目标项确实偏离中心才滚动，避免触摸回报后的重复滚动。
            val info = rowState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val vi = info.visibleItemsInfo.firstOrNull { it.index == target }
            val off = if (vi != null) abs(vi.offset + vi.size / 2f - center) else Float.MAX_VALUE
            if (off > 4f) {
                suppressReport = true
                runCatching { rowState.animateScrollToItem(target) }
                suppressReport = false
            }
        }
    }

    // ============ 流畅度优化（消除滑动卡顿）============
    // 卡顿根因：旧实现用 derivedStateOf 依赖 firstVisibleItemScrollOffset 派生 centered，
    // 滑动每一帧 centered 都可能变 → LaunchedEffect(centered) 每帧 onSelected →
    // 顶层 selected 更新 → 整个 WheelHome（含 WheelBackdrop 的全屏 32/40dp 模糊、
    // 所有 CoverImage）跟着重组重绘，于是明显掉帧。
    //
    // 新实现：
    //  1) 3D 变换（rotation/scale/alpha）全部在 graphicsLayer{} 内直接读 rowState.layoutInfo
    //     实时计算——这是快照读取，只更新渲染图层，不触发组合重组；
    //  2) 只有当滚动「停止」（isScrollInProgress 由 true→false）时才计算一次居中项并
    //     上报 onSelected，顶层的 selected 在滑动过程中完全不动，背景模糊不再反复重算；
    //  3) 文字高亮用一个低频的 settledCentered（仅停下时更新），避免每帧重组文本。
    val scrolling by remember { derivedStateOf { rowState.isScrollInProgress } }
    var settledCentered by remember(items) { mutableStateOf(0) }
    LaunchedEffect(scrolling, items) {
        if (!scrolling && !suppressReport && items.isNotEmpty()) {
            val info = rowState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val c = info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2f - center) }?.index ?: 0
            settledCentered = c
            if (c != selected) onSelected(c)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 竖屏专辑层（followSelection）：屏幕区较窄且矮，卡片按高度为主、宽度兜底；
        // 触摸全屏流：沿用原横屏比例（宽屏按宽度 0.2）。
        val card = if (followSelection) {
            // iPod 唱片架：中间封面收小到约半屏，给左右两侧留足空间铺开邻封。
            minOf(maxHeight * 0.56f, maxWidth * 0.50f)
        } else {
            minOf(maxHeight * 0.55f, maxWidth * 0.20f)
        }
        // itemW 小于卡片宽 → 邻封紧凑向中心聚拢，一屏能露出左右各 2~3 张斜封；
        // 两端 contentPadding=(宽-itemW)/2 仍为正，首尾也对称留白。
        val itemW = if (followSelection) card * 0.86f else card * 1.18f
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = (maxWidth - itemW) / 2
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(items, key = { i, item ->
                when (item) {
                    is WheelItem.Op -> "op-${item.node}-$i"
                    is WheelItem.Track -> "track-${item.index}-$i"
                    is WheelItem.Action -> "action-${item.key}-$i"
                }
            }) { i, item ->
                // 文字高亮只在滚动停下后更新（settledCentered），滑动过程中不重组文本。
                val sel = i == settledCentered
                Column(
                    Modifier
                        .width(itemW)
                        .fillMaxHeight()
                        .graphicsLayer {
                            // 直接读滚动布局快照实时算 3D：只动图层，不重组。
                            val info = rowState.layoutInfo
                            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val vi = info.visibleItemsInfo.firstOrNull { it.index == i }
                            val norm = if (vi != null) {
                                val d = (vi.offset + vi.size / 2f - center) / (itemW.toPx() * 1.15f)
                                d.coerceIn(-3f, 3f)
                            } else 0f
                            val a = abs(norm)
                            // 中间正面，左右各斜放数张：明显倾角 + 后退缩小 + 渐隐，iPod 唱片架观感。
                            rotationY = -norm * 64f
                            val s = 1f - a * 0.26f
                            scaleX = s
                            scaleY = s
                            alpha = (1f - a * 0.28f).coerceIn(0.10f, 1f)
                            cameraDistance = 14f * density
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            // 点击时用实时布局判断被点的项是否居中，不依赖顶层 state
                            val info = rowState.layoutInfo
                            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val c = info.visibleItemsInfo
                                .minByOrNull { abs(it.offset + it.size / 2f - center) }?.index ?: i
                            if (i != c) {
                                settledCentered = i
                                onSelected(i)
                            }
                            onClick(item)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
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
                    val hasCover = !cover.isNullOrBlank() || !embedded.isNullOrBlank()
                    // 封面 + 倒影作为一个整体随卡片 3D 倾斜，倒影始终紧贴各自封面底边。
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(card)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                        ) {
                            if (hasCover) {
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
                        // 倒影直接紧贴封面底边，中间不加任何高光/分隔线。
                        // 玻璃台面式倒影：垂直翻转 + 压缩 + 随速度变化的动态模糊；
                        // 遮罩顶部提到 0.42 并指数衰减（上半保留轮廓）、下半快速融进背景。
                        // 纯平面视差：倾斜只驱动水平位移，倒影始终是一张平面镜像、不做 3D 透视、垂直方向不动。
                        if (hasCover) {
                            val reflectionH = card * 0.20f
                            Box(
                                Modifier
                                    .width(card)
                                    .height(reflectionH)
                                    // 只做水平位移、无旋转：圆角保持平面裁切即可。
                                    .graphicsLayer {
                                        compositingStrategy = CompositingStrategy.Offscreen
                                        translationX = tiltX.floatValue * card.toPx() * 0.13f
                                    }
                                    .clip(RoundedCornerShape(10.dp))
                                    .drawWithContent {
                                        drawContent()
                                        // 指数式衰减：0.42 起步、前段缓慢保留轮廓，后段快速归零融入背景。
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colorStops = arrayOf(
                                                    0f to Color.Black.copy(alpha = 0.42f),
                                                    0.22f to Color.Black.copy(alpha = 0.30f),
                                                    0.50f to Color.Black.copy(alpha = 0.12f),
                                                    0.72f to Color.Black.copy(alpha = 0.03f),
                                                    0.88f to Color.Transparent,
                                                    1f to Color.Transparent
                                                )
                                            ),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                            ) {
                                CoverImage(
                                    cover,
                                    Modifier
                                        .fillMaxSize()
                                        // 动态模糊：从预建 5 档按角速度复用引用，只跨档切换、不逐帧分配 native 对象。
                                        .graphicsLayer {
                                            val i = (tiltSpeed.floatValue * (blurStages.size - 1)).toInt()
                                                .coerceIn(0, blurStages.lastIndex)
                                            renderEffect = blurStages[i]
                                            // 翻转 + 竖直压短约 20%，台面透视更强。
                                            scaleY = -0.80f
                                        },
                                    embeddedAudioUrl = embedded,
                                    contentScale = ContentScale.Crop,
                                    // 内层不裁角（交外层统一裁），避免双层圆角叠加出现直角残边。
                                    shape = androidx.compose.ui.graphics.RectangleShape
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(if (hasCover) 8.dp else 14.dp))
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
        // 顶部提示：交互方式随场景定制（竖屏转轮 / 触摸滑动）
        Text(
            hint,
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
