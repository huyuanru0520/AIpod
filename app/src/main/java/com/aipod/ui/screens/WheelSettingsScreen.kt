package com.aipod.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aipod.AudioQuality
import com.aipod.BuildConfig
import com.aipod.LibraryTabs
import com.aipod.MusicBoxApp
import com.aipod.ScanPace
import com.aipod.SourceMode
import com.aipod.player.AacTranscoder
import com.aipod.player.PlayerController
import com.aipod.ui.components.ClickWheel
import com.aipod.ui.components.clearCoverCache
import com.aipod.ui.components.coverCacheSizeBytes
import com.aipod.ui.components.fadeEdges
import com.aipod.ui.components.fadeInList
import com.aipod.ui.components.glassHighlight
import com.aipod.ui.components.rememberCoverBitmap
import com.aipod.ui.theme.BrandRed
import com.aipod.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 滚轮版设置页：整个设置页是一台 iPod——底部只有转轮，上方是「屏幕」列表，
 * 所有设置项通过转轮选择、中心键确认 / 切换，MENU 返回。没有任何可点击控件。
 *
 * 文本输入（WebDAV / 飞牛账号）与文件选择（备份导入导出）无法用转轮完成，
 * 这两类仍复用原有表单 / 系统选择器，但都由转轮选中后进入，保持主交互一致。
 */
@Composable
fun WheelSettingsScreen(navController: NavController) {
    val app = MusicBoxApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 所有设置状态
    val sourceMode by app.sourceMode.collectAsState()
    val audioQuality by app.audioQuality.collectAsState()
    val scanPace by app.scanPaceMs.collectAsState()
    val visibleTabs by app.visibleTabs.collectAsState()
    val debugMode by app.debugMode.collectAsState()
    val showDescriptions by app.showDescriptions.collectAsState()
    val hapticStrength by app.hapticStrength.collectAsState()
    val webDav by app.webDavConn.collectAsState()
    val fnos by app.fnosConn.collectAsState()
    val savedWebDav by app.savedWebDav.collectAsState()
    val savedFnos by app.savedFnos.collectAsState()
    val webDavScanning by app.webDavScanning.collectAsState()
    val webDavLibrary by app.webDavLibrary.collectAsState()
    val queue by PlayerController.queue.collectAsState()
    val currentIndex by PlayerController.currentIndex.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()

    // 浏览栈
    var stack by remember { mutableStateOf<List<SettingsNode>>(listOf(SettingsNode.Root)) }
    var selected by remember { mutableStateOf(0) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var transcodeSize by remember { mutableStateOf(AacTranscoder.cacheSize()) }
    var coverCacheSize by remember { mutableStateOf(coverCacheSizeBytes()) }
    var showLogs by remember { mutableStateOf(false) }
    // 本地扫描状态提到 App 级：选文件夹返回时页面重建也不会中断扫描、进度不丢。
    val localScanning by app.localScanning.collectAsState()
    val localScanProgress by app.localScanProgress.collectAsState()
    val localTracks by app.localLibrary.collectAsState()
    val localCount = localTracks.size
    val localFolderUri by app.localFolderUri.collectAsState()
    // 日志导出：下载目录里日志文件的真实路径、大小与写入通道结果
    var logInfo by remember { mutableStateOf(runCatching { CrashLogger.logFileInfo() }.getOrDefault("")) }
    var logPreview by remember { mutableStateOf("") }

    val node = stack.last()

    // 备份导入 / 导出
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = app.exportSettings()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
            }
            statusLine = if (ok) "设置已导出" else "导出失败：无法写入所选位置"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                        ?: throw IllegalStateException("无法读取所选文件")
                }.getOrNull()
                if (text == null) "导入失败：无法打开文件"
                else runCatching { app.importSettings(text) }
                    .fold(onSuccess = { "设置已导入并应用" }, onFailure = { "导入失败：${it.message}" })
            }
            statusLine = res
        }
    }
    // 本地音乐：SAF 选文件夹 → 递归扫描音频 → 保存到本地曲库
    val localFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        // 取得可持久读取授权，冷启动仍可访问这棵子树
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        // 交给 App 级作用域执行：SAF 选完文件夹返回时页面可能重建，
        // 用页面 scope 会让扫描协程被取消、结果根本写不进曲库（表现为「添加文件夹后一首都没扫到」）。
        statusLine = "正在扫描本地音乐…"
        app.startLocalScan(context, uri) { count ->
            if (count >= 0) {
                statusLine = if (count > 0) "本地扫描完成：$count 首，已切到本地音乐" else "未找到音频文件，请确认文件夹内含 mp3/flac/m4a 等"
                // 扫到歌就自动切到本地音源，省得用户还要手动切换才能看到。
                if (count > 0) scope.launch { app.setSource(SourceMode.LOCAL) }
            } else {
                statusLine = "本地扫描失败，请查看日志"
            }
        }
    }
    // 日志另存：用系统文件选择器自选位置写出完整日志（滚轮页无触摸，由转轮选中后触发）
    val logSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                CrashLogger.flushNow()
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(CrashLogger.fullText().toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
            }
            statusLine = if (ok) "日志已另存" else "日志另存失败"
            logInfo = withContext(Dispatchers.IO) { runCatching { CrashLogger.logFileInfo() }.getOrDefault("") }
        }
    }

    // 构建当前页条目
    val items: List<SettingItem> = when (node) {
        SettingsNode.Root -> buildRootSettings(
            sourceMode, audioQuality, scanPace, hapticStrength, debugMode, showDescriptions,
            webDav, fnos, webDavScanning, coverCacheSize, transcodeSize, localCount, localScanning, localScanProgress,
            !localFolderUri.isNullOrBlank()
        )
        SettingsNode.Tabs -> buildTabsSettings(visibleTabs)
        SettingsNode.Logs -> buildLogsSettings(debugMode, logInfo, showLogs, logPreview)
        SettingsNode.About -> buildAboutSettings()
        SettingsNode.WebDavForm, SettingsNode.FnosForm -> emptyList()
    }

    // 换页时选中项归零
    LaunchedEffect(node) { selected = 0 }

    fun push(next: SettingsNode) { stack = stack + next }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    // 中心键：按条目类型分派
    fun confirm(item: SettingItem?) {
        if (item == null) return
        when (item) {
            is SettingItem.Nav -> when (item.key) {
                "tabs" -> push(SettingsNode.Tabs)
                "webdav" -> push(SettingsNode.WebDavForm)
                "fnos" -> push(SettingsNode.FnosForm)
                "logs" -> push(SettingsNode.Logs)
                "about" -> push(SettingsNode.About)
            }
            is SettingItem.Choice -> when (item.key) {
                "haptic" -> scope.launch { app.setHapticStrength((hapticStrength + 1) % 4) }
                "source" -> scope.launch {
                    val next = when (sourceMode) {
                        SourceMode.WEBDAV -> SourceMode.FNOS
                        SourceMode.FNOS -> SourceMode.LOCAL
                        SourceMode.LOCAL -> SourceMode.WEBDAV
                    }
                    app.setSource(next)
                }
                "quality" -> scope.launch {
                    app.setAudioQuality(if (audioQuality == AudioQuality.ORIGINAL) AudioQuality.AAC_256 else AudioQuality.ORIGINAL)
                }
                "scanpace" -> scope.launch {
                    val idx = ScanPace.options.indexOfFirst { it.intervalMs == scanPace }
                    val next = ScanPace.options[(idx + 1).let { if (it < 0) 0 else it % ScanPace.options.size }]
                    app.setScanPace(next.intervalMs, next.concurrency)
                }
            }
            is SettingItem.Toggle -> when (item.key) {
                "debug" -> scope.launch { app.setDebugMode(!debugMode) }
                "descriptions" -> scope.launch { app.setShowDescriptions(!showDescriptions) }
                "tabcheck" -> {
                    val key = item.data
                    val next = if (key in visibleTabs) visibleTabs.filter { it != key } else visibleTabs + key
                    if (next.isNotEmpty()) scope.launch { app.setVisibleTabs(next) }
                    else statusLine = "至少保留一个栏目"
                }
            }
            is SettingItem.Action -> when (item.key) {
                "scan" -> {
                    val c = webDav
                    if (c == null) statusLine = "请先保存 WebDAV 连接"
                    else { statusLine = "开始扫描 WebDAV…"; app.startWebDavScan(c) }
                }
                "scanlocal" -> localFolderLauncher.launch(null)
                "rescanlocal" -> {
                    // 用已保存的文件夹重新扫描；没有保存过则退回到选文件夹
                    val started = app.rescanLocal(context) { count ->
                        if (count >= 0) {
                            statusLine = if (count > 0) "重新扫描完成：$count 首" else "仍未找到音频文件，可导出日志查看逐文件判定"
                            if (count > 0) scope.launch { app.setSource(SourceMode.LOCAL) }
                        } else statusLine = "重新扫描失败，请查看日志"
                    }
                    if (!started) {
                        statusLine = "尚无已保存的文件夹，请先选文件夹"
                        localFolderLauncher.launch(null)
                    }
                }
                "test" -> {
                    val c = webDav
                    if (c == null) statusLine = "请先保存 WebDAV 连接"
                    else {
                        statusLine = "测试连接中…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { app.webDavClient.testConnection(c) }
                                    .getOrElse { "测试失败：${it.message ?: it::class.java.simpleName}" }
                            }
                            statusLine = r.take(80)
                        }
                    }
                }
                "probe" -> {
                    statusLine = "正在检测飞牛新接口…"
                    scope.launch {
                        val lines = runCatching { app.fnosClient.probeEndpoints() }
                            .getOrElse { listOf("检测失败：${it.message}") }
                        statusLine = lines.joinToString(" · ").take(80)
                    }
                }
                "usbaudio" -> {
                    statusLine = "正在检测 USB DAC / bit-perfect 能力…"
                    scope.launch {
                        val report = com.aipod.audio.UsbAudioDiagnostics.diagnoseAndLog(context)
                        statusLine = report.summary.take(90)
                    }
                }
                "export" -> exportLauncher.launch("AIpod_settings.json")
                "import" -> importLauncher.launch(arrayOf("application/json", "*/*"))
                "clearcover" -> {
                    clearCoverCache(); coverCacheSize = coverCacheSizeBytes()
                    statusLine = "封面缓存已清理"; Toast.makeText(context, "已清理封面缓存", Toast.LENGTH_SHORT).show()
                }
                "cleartranscode" -> {
                    AacTranscoder.clearCache(); transcodeSize = AacTranscoder.cacheSize()
                    statusLine = "转码缓存已清理"; Toast.makeText(context, "已清理转码缓存", Toast.LENGTH_SHORT).show()
                }
                "copylog" -> {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            CrashLogger.flushNow()
                            CrashLogger.fullText().ifBlank { "（暂无日志记录）" }
                        }
                        runCatching {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("AIpod 日志", text))
                        }
                        statusLine = "日志已刷新并复制"
                    }
                }
                "savelog" -> scope.launch {
                    // 直接写到下载目录固定路径，不弹系统文件选择器（纯转轮页弹窗难操作）。
                    val path = withContext(Dispatchers.IO) {
                        CrashLogger.saveTextToDownloads("AIpod_log.txt", "text/plain", CrashLogger.fullText())
                    }
                    statusLine = if (path != null) "日志已保存到 $path" else "保存失败：请检查存储权限"
                    logInfo = withContext(Dispatchers.IO) { runCatching { CrashLogger.logFileInfo() }.getOrDefault("") }
                }
                "clearlog" -> scope.launch {
                    withContext(Dispatchers.IO) { CrashLogger.clear() }
                    logInfo = withContext(Dispatchers.IO) { runCatching { CrashLogger.logFileInfo() }.getOrDefault("") }
                    statusLine = "日志已清空"
                }
                "logdetails" -> {
                    showLogs = !showLogs
                    if (showLogs) scope.launch {
                        logPreview = withContext(Dispatchers.IO) {
                            runCatching { CrashLogger.fullText() }.getOrDefault("")
                        }
                    }
                }
                "back" -> navController.popBackStack()
            }
            is SettingItem.Info -> Unit
        }
    }

    // 中心键统一入口
    fun onWheelConfirm() {
        // 表单页中心键交给表单自身（触摸），这里只处理列表页
        confirm(items.getOrNull(selected))
    }

    // MENU：短按返回上一层；长按回曲库
    fun onWheelMenu() { pop() }
    fun onWheelHome() { navController.navigate("library") { popUpTo("settings") { inclusive = true } } }

    // 栏目页：转轮左 / 右区用于把当前选中栏目上移 / 下移（其它页仍是上一首 / 下一首）
    fun moveTab(delta: Int) {
        if (node != SettingsNode.Tabs) return
        val item = items.getOrNull(selected)
        if (item !is SettingItem.Toggle) return
        val key = item.data
        val idx = visibleTabs.indexOf(key)
        if (idx < 0) {
            statusLine = "隐藏栏目不参与排序，先用中心键显示"
            return
        }
        val target = idx + delta
        if (target < 0 || target >= visibleTabs.size) return
        val next = visibleTabs.toMutableList()
        java.util.Collections.swap(next, idx, target)
        scope.launch { app.setVisibleTabs(next) }
    }

    // 当前曲目封面（设置页背景也跟随正在播放，观感与主页一致）
    val currentTrack = queue.getOrNull(currentIndex)

    Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1D))) {
        // 背景：有播放用封面模糊，无播放用中性磨砂玻璃
        SettingsBackdrop(currentTrack, BrandRed)
        Column(Modifier.fillMaxSize()) {
            // 顶栏标题（静态，不可点）
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(node.title, style = MaterialTheme.typography.titleMedium, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        statusLine != null -> statusLine ?: ""
                        !showDescriptions -> ""
                        node == SettingsNode.Tabs -> "中心键显示/隐藏 · 左右键调顺序"
                        items.isEmpty() -> "转动滚轮操作"
                        else -> "共 ${items.size} 项 · 中心键确认"
                    }
                    if (sub.isNotBlank()) {
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // 屏幕区：不加底色 / 边框，列表直接浮在模糊封面 / 红色光晕上，玻璃质感
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                when (node) {
                    SettingsNode.WebDavForm -> WebDavConnectionForm(
                        prefill = savedWebDav ?: webDav,
                        onSaved = { pop() },
                        onCancel = { pop() }
                    )
                    SettingsNode.FnosForm -> FnOSConnectionForm(
                        prefill = savedFnos ?: fnos,
                        onSaved = { pop() },
                        onCancel = { pop() }
                    )
                    // 日志页也用统一列表渲染，保证「复制 / 另存 / 展开 / 清空」选项在屏幕上可见，
                    // 转轮选中才有反馈。之前这里单独渲染 LogsScreenBody，把选项列表整个挡住，
                    // 用户转轮时在动却看不到任何选项，误以为「日志导出界面没有具体选项」。
                    SettingsNode.Logs -> SettingsList(items = items, selected = selected, showDescriptions = true)
                    else -> SettingsList(items = items, selected = selected, showDescriptions = showDescriptions)
                }
            }

            // 转轮：与首页同步——下调到底部 28dp，列表多显示一行
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp), contentAlignment = Alignment.Center) {
                ClickWheel(
                    diameter = 264.dp,
                    isPlaying = isPlaying,
                    centerActive = false,
                    dark = true,
                    onRotate = { steps ->
                        if (items.isNotEmpty()) selected = (selected + steps).coerceIn(0, items.lastIndex)
                    },
                    onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
                    onPrevious = { if (node == SettingsNode.Tabs) moveTab(-1) else PlayerController.previous() },
                    onNext = { if (node == SettingsNode.Tabs) moveTab(1) else PlayerController.next() },
                    onRewind = { PlayerController.seekTo((PlayerController.currentPositionMs() - 5_000L).coerceAtLeast(0)) },
                    onForward = { PlayerController.seekTo(PlayerController.currentPositionMs() + 5_000L) },
                    onMenu = { onWheelMenu() },
                    onMenuHome = { onWheelHome() },
                    onConfirm = { onWheelConfirm() }
                )
            }
        }
    }
}

// ==================== 节点与条目 ====================

internal sealed class SettingsNode(val title: String) {
    object Root : SettingsNode("设置")
    object Tabs : SettingsNode("主界面栏目")
    object WebDavForm : SettingsNode("WebDAV 连接")
    object FnosForm : SettingsNode("飞牛音乐登录")
    object Logs : SettingsNode("运行日志")
    object About : SettingsNode("关于")
}

internal sealed class SettingItem {
    data class Nav(val key: String, val title: String, val subtitle: String, val icon: ImageVector) : SettingItem()
    data class Choice(val key: String, val title: String, val subtitle: String, val icon: ImageVector) : SettingItem()
    data class Toggle(val key: String, val title: String, val subtitle: String, val icon: ImageVector, val data: String = "") : SettingItem()
    data class Action(val key: String, val title: String, val subtitle: String, val icon: ImageVector) : SettingItem()
    data class Info(val title: String, val subtitle: String) : SettingItem()
}

private fun SettingItem.title(): String = when (this) {
    is SettingItem.Nav -> title
    is SettingItem.Choice -> title
    is SettingItem.Toggle -> title
    is SettingItem.Action -> title
    is SettingItem.Info -> title
}
private fun SettingItem.subtitle(): String = when (this) {
    is SettingItem.Nav -> subtitle
    is SettingItem.Choice -> subtitle
    is SettingItem.Toggle -> subtitle
    is SettingItem.Action -> subtitle
    is SettingItem.Info -> subtitle
}

private fun buildRootSettings(
    source: SourceMode, quality: AudioQuality,
    scanPace: Long, haptic: Int, debug: Boolean, showDescriptions: Boolean, webDav: Any?, fnos: Any?, scanning: Boolean,
    coverSize: Long, transcodeSize: Long, localCount: Int, localScanning: Boolean, localScanProgress: String,
    hasLocalFolder: Boolean
): List<SettingItem> {
    val sourceName = when (source) {
        SourceMode.WEBDAV -> "WebDAV"
        SourceMode.FNOS -> "飞牛音乐"
        SourceMode.LOCAL -> "本地音乐"
    }
    val qualityName = quality.label
    val paceName = ScanPace.options.firstOrNull { it.intervalMs == scanPace }?.label ?: "均衡"
    val hapticName = when (haptic) { 0 -> "关闭"; 1 -> "弱"; 2 -> "中"; else -> "强" }
    val out = mutableListOf<SettingItem>()
    out += SettingItem.Choice("source", "音乐源", sourceName, Icons.Default.MusicNote)
    out += SettingItem.Nav("tabs", "主界面栏目", "勾选并调整顺序", Icons.Default.List)
    out += SettingItem.Choice("quality", "播放音质", qualityName, Icons.Default.Audiotrack)
    out += SettingItem.Choice("scanpace", "扫描节奏", paceName, Icons.Default.Speed)
    out += SettingItem.Choice("haptic", "震动强度", hapticName, Icons.Default.Vibration)
    out += SettingItem.Nav("webdav", "WebDAV 连接", if (webDav != null) "已配置" else "未配置", Icons.Default.Link)
    out += SettingItem.Nav("fnos", "飞牛音乐登录", if (fnos != null) "已登录" else "未登录", Icons.Default.Storage)
    out += SettingItem.Action(
        "scanlocal", "扫描本地音乐（选新文件夹）",
        if (localScanning) localScanProgress.ifBlank { "扫描中…" } else "已收录 $localCount 首",
        Icons.Default.Folder
    )
    out += SettingItem.Action(
        "rescanlocal", "重新扫描本地音乐",
        if (!hasLocalFolder) "尚未选过文件夹" else "用已保存的文件夹重扫 · 已收录 $localCount 首",
        Icons.Default.Refresh
    )
    out += SettingItem.Action("scan", "立即扫描 WebDAV", if (scanning) "扫描中…" else "扫描音乐库", Icons.Default.Storage)
    out += SettingItem.Action("test", "测试连接 / 诊断", "WebDAV 连接自检", Icons.Default.Link)
    out += SettingItem.Action("probe", "检测飞牛新接口", "风格 / 文件夹 / 漫游", Icons.Default.Storage)
    out += SettingItem.Action("usbaudio", "USB DAC / 无损输出检测", "检测 DAC 与 bit-perfect 支持", Icons.Default.Audiotrack)
    out += SettingItem.Toggle("debug", "调试模式", if (debug) "已开启" else "已关闭", Icons.Default.BugReport)
    out += SettingItem.Toggle("descriptions", "说明文字", if (showDescriptions) "显示" else "已隐藏", Icons.Default.Description)
    out += SettingItem.Nav("logs", "运行日志", "查看 / 复制 / 另存", Icons.Default.Description)
    out += SettingItem.Action("export", "导出设置", "备份为 JSON", Icons.Default.ImportExport)
    out += SettingItem.Action("import", "导入设置", "从 JSON 恢复", Icons.Default.ImportExport)
    out += SettingItem.Action("clearcover", "清理封面缓存", "${coverSize / 1024 / 1024} MB", Icons.Default.Delete)
    out += SettingItem.Action("cleartranscode", "清理转码缓存", "${transcodeSize / 1024 / 1024} MB", Icons.Default.Delete)
    out += SettingItem.Nav("about", "关于", "版本 $vName", Icons.Default.Info)
    out += SettingItem.Action("back", "返回曲库", "MENU 键也可返回", Icons.Default.PlayArrow)
    return out
}

private val vName: String get() = BuildConfig.VERSION_NAME

private fun buildTabsSettings(visible: List<String>): List<SettingItem> {
    val shown = visible.filter { it in LibraryTabs.keys }
    val hidden = LibraryTabs.keys.filter { it !in shown }
    val ordered = shown + hidden
    return ordered.map { key ->
        SettingItem.Toggle(
            key = "tabcheck",
            title = LibraryTabs.labelOf(key),
            subtitle = if (key in shown) "已显示（第 ${shown.indexOf(key) + 1} 位）· 中心键隐藏" else "已隐藏 · 中心键显示",
            icon = Icons.Default.Check,
            data = key
        )
    }
}

private fun buildLogsSettings(debug: Boolean, logInfo: String, showDetails: Boolean, logPreview: String): List<SettingItem> {
    val out = mutableListOf<SettingItem>()
    out += SettingItem.Action("copylog", "复制日志到剪贴板", "可直接粘贴发给他人", Icons.Default.ContentCopy)
    out += SettingItem.Action("savelog", "另存日志到下载目录", "生成 AIpod_log.txt", Icons.Default.SaveAs)
    out += SettingItem.Action("logdetails", if (showDetails) "收起日志内容" else "展开日志内容", "在下方查看最近日志", Icons.Default.Description)
    out += SettingItem.Action("clearlog", "清空日志", "清除全部记录", Icons.Default.Delete)
    out += SettingItem.Info("日志位置", logInfo)
    // 展开时把最近日志作为一个可见条目塞进列表，转轮可上下浏览（不再藏进单独界面）。
    if (showDetails) {
        out += SettingItem.Info("—— 最近日志（最新在末尾）——", logPreview.takeLast(2000).ifBlank { "（暂无日志）" })
    }
    out += SettingItem.Info(
        "说明",
        if (debug) "运行中日志持续写入内部文件，并同步到下方下载目录路径。"
        else "运行日志持续记录，仅在「另存日志」时写出到下载目录。"
    )
    return out
}

private fun buildAboutSettings(): List<SettingItem> = listOf(
    SettingItem.Info("AIpod", "v${BuildConfig.VERSION_NAME}"),
    SettingItem.Info("技术栈", "Jetpack Compose + Material 3 · Media3 ExoPlayer"),
    SettingItem.Info("音源", "飞牛音乐 / WebDAV，主备线路自动切换"),
    SettingItem.Info("交互", "iPod 点击轮：转动选择 · 中心键确认 · MENU 返回"),
    SettingItem.Info("日志", "/storage/emulated/0/Download/AIpod_log.txt")
)

// ==================== 屏幕列表 ====================

@Composable
private fun SettingsList(items: List<SettingItem>, selected: Int, showDescriptions: Boolean = true) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        if (selected in items.indices) runCatching { listState.animateScrollToItem(selected, -180) }
    }
    val fg = Color.White
    val rowShape = RoundedCornerShape(14.dp)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().fadeInList().fadeEdges(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 24.dp)
    ) {
        itemsIndexed(items) { i, item ->
            val sel = i == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .glassHighlight(sel, rowShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (item) {
                    is SettingItem.Nav -> item.icon
                    is SettingItem.Choice -> item.icon
                    is SettingItem.Toggle -> item.icon
                    is SettingItem.Action -> item.icon
                    is SettingItem.Info -> Icons.Default.Info
                }
                Box(Modifier.size(30.dp), Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = fg)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title(), style = if (sel) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, color = fg,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Choice / Toggle 的副标题是「当前值」（如音乐源=WebDAV、震动强度=中），
                    // 属于选项本身，必须始终显示；只有 Nav / Action 的副标题才是可选说明文字。
                    // 否则关闭「说明文字」会把音乐源、音质等当前值一起藏掉，看起来像选项消失了。
                    val isCurrentValue = item is SettingItem.Choice || item is SettingItem.Toggle
                    if (showDescriptions || isCurrentValue) {
                        Text(item.subtitle(), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsScreenBody(showDetails: Boolean) {
    val log by remember { mutableStateOf(runCatching { CrashLogger.fullText() }.getOrDefault("")) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("运行日志", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        if (showDetails) {
            Text(
                log.takeLast(6000).ifBlank { "（暂无日志）" },
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f)
            )
        } else {
            Text("中心键选择「展开 / 收起日志内容」查看最近日志。", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

/** 设置页背景：与主界面一致（有播放用封面模糊，无播放用中性磨砂玻璃）。 */
@Composable
private fun SettingsBackdrop(current: com.aipod.data.Track?, red: Color) {
    val bmp: ImageBitmap? = rememberCoverBitmap(current?.coverUrl, current?.embeddedAudioUrl)
    Box(Modifier.fillMaxSize()) {
        if (bmp != null) {
            Image(bmp, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }.blur(32.dp))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
        } else {
            // 无播放：中性磨砂玻璃（深灰雾面 + 柔光斑 + 高斯模糊），不掺任何彩色
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f }
                    .blur(40.dp)
            ) {
                val w = size.width; val h = size.height
                drawRect(Brush.verticalGradient(
                    listOf(Color(0xFF242428), Color(0xFF151517), Color(0xFF2A2A2F))
                ))
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)),
                    radius = maxOf(w, h) * 0.75f,
                    center = Offset(w * 0.28f, h * 0.18f)
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)),
                    radius = maxOf(w, h) * 0.6f,
                    center = Offset(w * 0.85f, h * 0.88f)
                )
            }
        }
    }
}
