package com.aipod.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipod.ui.components.CoverImage
import com.aipod.ui.theme.AccentPresets
import androidx.navigation.NavController
import com.aipod.BuildConfig
import com.aipod.MusicBoxApp
import com.aipod.ScanPace
import com.aipod.SourceMode
import com.aipod.ThemeMode
import com.aipod.AudioQuality
import com.aipod.LibraryTabs
import com.aipod.player.AacTranscoder
import com.aipod.data.fnos.FnosConnection
import com.aipod.data.normalizeHost
import com.aipod.data.sanitizeWebDavPath
import com.aipod.data.webdav.WebDavConnection
import com.aipod.player.PlayerHolder
import com.aipod.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("UNUSED_PARAMETER")
fun SettingsScreen(navController: NavController) {
    val app = MusicBoxApp.instance
    val context = LocalContext.current
    val themeMode by app.themeMode.collectAsState()
    val sourceMode by app.sourceMode.collectAsState()
    val webDav by app.webDavConn.collectAsState()
    val fnos by app.fnosConn.collectAsState()
    val savedWebDav by app.savedWebDav.collectAsState()
    val savedFnos by app.savedFnos.collectAsState()
    val scope = rememberCoroutineScope()

    // 连接信息编辑全部集中在本页，主页不再显示表单 / 账号 / 断开入口
    var editingWebDav by remember { mutableStateOf(false) }
    var editingFnos by remember { mutableStateOf(false) }
    val webDavScanning by app.webDavScanning.collectAsState()
    val webDavProgress by app.webDavScanProgress.collectAsState()
    val webDavLibrary by app.webDavLibrary.collectAsState()
    val webDavScanFound by app.webDavScanFound.collectAsState()
    val scanPace by app.scanPaceMs.collectAsState()
    val visibleTabs by app.visibleTabs.collectAsState()
    val audioQuality by app.audioQuality.collectAsState()
    val debugMode by app.debugMode.collectAsState()
    var transcodeSize by remember { mutableStateOf(AacTranscoder.cacheSize()) }
    var coverCacheSize by remember { mutableStateOf(com.aipod.ui.components.coverCacheSizeBytes()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    // 显示下载目录里日志文件的真实路径、大小与三条写入通道的结果
    var logInfo by remember { mutableStateOf(CrashLogger.logFileInfo()) }
    var showLog by remember { mutableStateOf(false) }
    var logPreview by remember { mutableStateOf("") }

    // 兜底：即使厂商文件管理器对 MediaStore 有限制，也能用系统文件选择器另存
    val saveLauncher = rememberLauncherForActivityResult(
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
            Toast.makeText(context, if (ok) "日志已另存" else "另存失败", Toast.LENGTH_SHORT).show()
            logInfo = withContext(Dispatchers.IO) { CrashLogger.logFileInfo() }
        }
    }

    // 导入设置：用系统文件选择器读取 JSON
    var importMsg by remember { mutableStateOf<String?>(null) }
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
                if (text == null) "读取失败：无法打开文件"
                else runCatching { app.importSettings(text) }
                    .fold(onSuccess = { "设置已导入并应用" }, onFailure = { "导入失败：${it.message}" })
            }
            importMsg = res
        }
    }

    // 导出设置：用系统文件选择器自选保存位置（任意可写目录，不再固定下载目录）
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
            importMsg = if (ok) "设置已导出到所选位置" else "导出失败：无法写入所选位置"
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("设置 · v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        // 主题模式 / 主题色设置已移除：外观固定为深色 + 品牌红，不再提供选项。

        Spacer(Modifier.height(20.dp))
        Text("音乐源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "主界面只显示当前选择的音乐源；切换后立即生效并记住选择。" +
                "两种音源使用同一套曲库界面（歌曲 / 歌手 / 歌单 / 专辑 / 搜索，可左右滑动）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row {
            SourceMode.values().forEach { mode ->
                FilterChip(
                    selected = sourceMode == mode,
                    onClick = { scope.launch { app.setSource(mode) } },
                    label = { Text(if (mode == SourceMode.FNOS) "飞牛音乐" else "WebDAV") },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        Text(
            "当前：" + (if (sourceMode == SourceMode.FNOS) "飞牛音乐" else "WebDAV") +
                "。切换音源不会清空任何已保存的账号与密码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        // 飞牛没有公开 API 文档，不同固件版本接口差异较大：提供一次自检，
        // 用户可在自己的 NAS 上确认风格 / 文件夹 / 漫游等分类接口是否真实存在。
        var probeResult by remember { mutableStateOf<List<String>?>(null) }
        TextButton(
            onClick = {
                scope.launch {
                    probeResult = listOf("正在检测…")
                    probeResult = runCatching { app.fnosClient.probeEndpoints() }
                        .getOrElse { listOf("检测失败：${it.message}") }
                }
            }
        ) { Text("检测飞牛新接口（风格 / 文件夹 / 漫游）") }
        probeResult?.let { lines ->
            AlertDialog(
                onDismissRequest = { probeResult = null },
                title = { Text("飞牛接口自检") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        lines.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { probeResult = null }) { Text("关闭") } }
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("主界面栏目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "勾选要显示的栏目，用右侧箭头调整 Tab 顺序（对 WebDAV 与飞牛同时生效）。" +
                "「风格」来自飞牛服务端的流派分类，WebDAV 会自动隐藏；至少保留一个。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        // 已勾选的按用户顺序排在前面，未勾选的按默认顺序跟在后面，方便随时重新勾上。
        val shown = visibleTabs
        val hidden = LibraryTabs.keys.filter { it !in shown }
        val ordered = shown + hidden
        val commit: (List<String>) -> Unit = { next -> scope.launch { app.setVisibleTabs(next) } }
        ordered.forEach { key ->
            val checked = key in shown
            val idx = shown.indexOf(key)
            val label = LibraryTabs.labelOf(key)
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        if (checked) commit(shown.filter { it != key })
                        else commit(shown + key)
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (checked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = checked && idx > 0,
                    onClick = {
                        val next = shown.toMutableList()
                        java.util.Collections.swap(next, idx, idx - 1)
                        commit(next)
                    }
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移「$label」")
                }
                IconButton(
                    enabled = checked && idx >= 0 && idx < shown.size - 1,
                    onClick = {
                        val next = shown.toMutableList()
                        java.util.Collections.swap(next, idx, idx + 1)
                        commit(next)
                    }
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移「$label」")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("播放音质", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row {
            AudioQuality.values().forEach { q ->
                FilterChip(
                    selected = audioQuality == q,
                    onClick = { scope.launch { app.setAudioQuality(q) } },
                    label = { Text(q.label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        Text(
            "「原采样率 / 原码率」直接播放原始文件，无损与 Hi-Res 保持原样（推荐）。\n" +
                "「AAC 256kbps」会在本地把后面的歌曲转成 256k AAC 并缓存：当前正在播的这首仍按原码率，" +
                "轮到下一首时自动用缓存版本；首次需要额外时间与存储空间，失败会自动回退原文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "转码缓存：${transcodeSize / 1024 / 1024} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = {
                AacTranscoder.clearCache()
                transcodeSize = AacTranscoder.cacheSize()
            }) { Text("清空缓存") }
        }

        Spacer(Modifier.height(20.dp))
        Text("设置备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "把当前全部设置（连接配置、外观、音源、栏目、音质、调试模式、扫描参数）导出为 JSON 文件，" +
                "或从一个 JSON 文件导入并应用。账号密码与 token 一并导出，请自行保管。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Button(
                onClick = { exportLauncher.launch("AIpod_settings.json") },
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) { Text("导出") }
            Button(
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.weight(1f)
            ) { Text("导入") }
        }
        importMsg?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(20.dp))
        Text("缓存管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "封面已落盘缓存，杀掉 App 再打开也能秒出封面、不再转圈；占用过大时可手动清理（不影响已保存的账号与曲库索引）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "封面缓存：${coverCacheSize / 1024 / 1024} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = {
                com.aipod.ui.components.clearCoverCache()
                coverCacheSize = com.aipod.ui.components.coverCacheSizeBytes()
                Toast.makeText(context, "已清理封面缓存", Toast.LENGTH_SHORT).show()
            }) { Text("清空封面缓存") }
        }

        Spacer(Modifier.height(20.dp))
        Text("连接管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "地址、账号、密码都在这里填写与修改，主页不再显示任何连接信息。" +
                "配置（含密码）会一直缓存，断开 / 退出只结束当前会话，不会清空。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("WebDAV", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (webDav != null) "已连接：${webDav!!.host}${if (webDav!!.port != 0) ":${webDav!!.port}" else ""}" else "未连接（配置已缓存）",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { editingWebDav = !editingWebDav }) {
                        Text(if (editingWebDav) "收起编辑" else "编辑连接")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { scope.launch { app.saveWebDav(null); editingWebDav = false } },
                        enabled = webDav != null
                    ) { Text("断开（保留配置）") }
                }
                if (editingWebDav) {
                    Spacer(Modifier.height(12.dp))
                    WebDavConnectionForm(
                        prefill = savedWebDav ?: webDav,
                        onSaved = { editingWebDav = false },
                        onCancel = { editingWebDav = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("飞牛音乐（FnOS）", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (fnos != null) "已登录：${fnos!!.username} @ ${fnos!!.host}:${fnos!!.port}" else "未登录（配置已缓存）",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { editingFnos = !editingFnos }) {
                        Text(if (editingFnos) "收起编辑" else "编辑登录")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { scope.launch { app.saveFnos(null, null); editingFnos = false } },
                        enabled = fnos != null
                    ) { Text("退出（保留配置）") }
                }
                if (editingFnos) {
                    Spacer(Modifier.height(12.dp))
                    FnOSConnectionForm(
                        prefill = savedFnos ?: fnos,
                        onSaved = { editingFnos = false },
                        onCancel = { editingFnos = false }
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("WebDAV 扫描", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "扫描会递归下钻根路径下的所有子目录（不只是根目录），逐层 PROPFIND Depth:1。" +
                "按所选档位并行（温和 3 路 / 均衡 6 路 / 快速 12 路 / 极速 32 路），命中 429 / 503 等限流会自动降速并退避重试。" +
                "每个目录的识别数量与 HTTP 状态都会写入日志，便于排查。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("扫描节奏（速度 · 并行度）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row {
            ScanPace.options.forEach { opt ->
                FilterChip(
                    selected = scanPace == opt.intervalMs,
                    onClick = { scope.launch { app.setScanPace(opt.intervalMs, opt.concurrency) } },
                    label = { Text(opt.label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "并行扫描：同时发起的请求路数随档位提升（温和 3 路 / 均衡 6 路 / 快速 12 路 / 极速 32 路）；" +
                "除「极速」外都配合固定最小请求间隔做匀速限速。「极速」档不再排队、32 路并发，" +
                "适合 STRM / 自建 openlist 等无网盘风控的场景；网盘源仍建议「均衡 / 快速」，" +
                "命中限流会自动把间隔翻倍（最高 2 秒）并退避重试。扫描索引会落盘缓存，冷启动不再重复全库扫描。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    when {
                        webDav == null -> "未连接 WebDAV，请先在上方「连接管理」保存连接"
                        webDavScanning -> webDavProgress.ifBlank { "正在扫描 WebDAV…" }
                        scanError != null -> "扫描失败：$scanError"
                        webDavLibrary != null -> webDavLibrary?.let { lib ->
                            "已索引 ${lib.tracks.size} 首歌曲 · ${lib.albums.size} 张专辑 · " +
                                "${lib.artists.size} 位歌手 · ${lib.playlists.size} 个 M3U 歌单" +
                                (if (lib.failedDirs > 0) "（${lib.failedDirs} 个目录无权限已跳过）" else "") +
                                (if (lib.limitedHits > 0) "（曾限流 ${lib.limitedHits} 次，已自动降速）" else "") +
                                (lib.note?.let { "\n$it" } ?: "")
                        } ?: "尚未扫描"
                        else -> webDavProgress.ifBlank { "尚未扫描" }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (scanError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 扫描中实时展示已找到的音乐（最多预览前 40 首），便于确认扫描在正常推进
                if (webDavScanFound.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "实时：已找到 ${webDavScanFound.size} 首（扫描未完成，数量持续增加）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        webDavScanFound.take(40).forEachIndexed { i, t ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverImage(t.coverUrl, Modifier.size(36.dp), embeddedAudioUrl = t.embeddedAudioUrl)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (webDavScanFound.size > 40) {
                            Text(
                                "…还有 ${webDavScanFound.size - 40} 首",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val c = webDav ?: return@Button
                        scanError = null
                        // App 级作用域：切到其他页面/退到后台也会继续扫描
                        app.startWebDavScan(c)
                    },
                    enabled = webDav != null && !webDavScanning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (webDavScanning) "扫描中…" else "立即扫描 WebDAV 音乐库") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val c = webDav ?: return@Button
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                runCatching { app.webDavClient.testConnection(c) }
                                    .getOrElse { "✗ 测试失败：${it.message ?: it::class.java.simpleName}" }
                            }
                            testing = false
                        }
                    },
                    enabled = webDav != null && !testing && !webDavScanning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (testing) "测试中…" else "测试连接 / 诊断 401") }
                testResult?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        r,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                if (webDavScanning) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("扫描中，请稍候…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("音频解码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "当前策略：${PlayerHolder.renderMode}\n" +
                if (PlayerHolder.ffmpegAvailable)
                    "FFmpeg 软解码已加载（${PlayerHolder.ffmpegVersion}）。MediaCodec 优先，" +
                        "系统不支持的格式（ALAC / m4a 等）自动回退 FFmpeg，避免全程软解开销。"
                else
                    "FFmpeg 未启用，当前仅使用系统 MediaCodec（FLAC / WAV / AAC / MP3 正常播放）。\n" +
                        "如果 FFmpeg 初始化失败会自动降级而不是让播放器整体不可用，详情见日志。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PlayerHolder.lastError?.let {
            Text("初始化诊断：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        Text("调试模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "关闭时完全停用运行日志（不生成日志文件、不写下载目录），并隐藏下方日志工具，" +
                "减少常驻 I/O；需要排查问题时打开它，复现后把下载目录的 AIpod_log.txt 发回即可。" +
                "崩溃日志无论开关都会保留，用于致命问题兜底。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth()
                .clickable { scope.launch { app.setDebugMode(!debugMode) } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用调试日志", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = debugMode, onCheckedChange = { scope.launch { app.setDebugMode(it) } })
        }

        // 调试模式关闭时整块隐藏：不显示日志路径、按钮与预览，设置页更干净。
        if (debugMode) {
            Spacer(Modifier.height(20.dp))
            Text("运行日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "固定文件：/storage/emulated/0/Download/AIpod_log.txt\n$logInfo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    scope.launch {
                        val info = withContext(Dispatchers.IO) {
                            CrashLogger.exportNow()
                            CrashLogger.logFileInfo()
                        }
                        logInfo = info
                        val text = withContext(Dispatchers.IO) {
                            CrashLogger.fullText().ifBlank { "（暂无日志记录）" }
                        }
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("AIpod 日志", text))
                        }
                        Toast.makeText(context, "日志已刷新并复制", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("刷新并复制全部日志") }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    scope.launch {
                        logPreview = withContext(Dispatchers.IO) { CrashLogger.read().orEmpty() }
                        showLog = !showLog
                    }
                }) { Text(if (showLog) "隐藏日志内容" else "查看日志内容") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { saveLauncher.launch("AIpod_log.txt") }) {
                    Text("另存为…")
                }
            }
            if (showLog) {
                Spacer(Modifier.height(8.dp))
                Text(
                    logPreview.takeLast(8000).ifBlank { "（暂无日志）" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                )
            }
            Text(
                "日志从应用启动起持续记录全部运行事件（版本、登录、扫描、播放、切歌、错误），" +
                    "不依赖是否发生异常，启动即在下载目录生成同名文件；同名覆盖。\n" +
                    "日志头部会写明应用版本，反馈问题时请一并附上，方便确认实际安装的版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "AIpod v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (debugMode) {
            Spacer(Modifier.height(8.dp))
            Text(
                "• 基于 Jetpack Compose + Material Design 3，Media3 ExoPlayer（MediaCodec 优先 + FFmpeg 软解兜底）\n" +
                    "• 音源：飞牛音乐 / WebDAV，连接信息统一在「设置 → 连接管理」编辑\n" +
                    "• 曲库八个栏目（歌曲 / 歌手 / 歌单 / 专辑 / 文件夹 / 风格 / 漫游 / 搜索）可勾选并排序\n" +
                    "• 主备线路自动切换，切换时改写索引地址，不重新扫描\n" +
                    "• 封面与歌词优先读取文件内嵌元数据，其次同目录同名文件\n" +
                    "• 运行日志固定写入 /storage/emulated/0/Download/AIpod_log.txt",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.DARK -> "深色"
    ThemeMode.LIGHT -> "浅色"
}

// ==================== 连接信息编辑表单（只在设置页出现） ====================

@Composable
internal fun WebDavConnectionForm(
    prefill: WebDavConnection?,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var host by rememberSaveable(prefill?.host) { mutableStateOf(prefill?.host ?: "") }
    var port by rememberSaveable(prefill?.port) {
        mutableStateOf((prefill?.port?.takeIf { it != 0 } ?: 5005).toString())
    }
    var https by rememberSaveable(prefill?.useHttps) { mutableStateOf(prefill?.useHttps ?: false) }
    var user by rememberSaveable(prefill?.username) { mutableStateOf(prefill?.username ?: "") }
    var pass by rememberSaveable(prefill?.password) { mutableStateOf(prefill?.password ?: "") }
    var path by rememberSaveable(prefill?.rootPath) { mutableStateOf(prefill?.rootPath ?: "/") }
    var bHost by rememberSaveable(prefill?.backupHost) { mutableStateOf(prefill?.backupHost ?: "") }
    var bPort by rememberSaveable(prefill?.backupPort) {
        mutableStateOf((prefill?.backupPort?.takeIf { it != 0 } ?: 0).toString().takeIf { it != "0" } ?: "")
    }
    var bHttps by rememberSaveable(prefill?.backupUseHttps) { mutableStateOf(prefill?.backupUseHttps ?: false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }

    // 备用线路字段较多，小屏放不下：根布局可上下滚动，保证「备用连接」完整可见可填。
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("编辑 WebDAV 连接", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row {
                LabeledTextField("服务器地址 / 域名", host, Modifier.weight(1f)) { host = it }
                Spacer(Modifier.width(8.dp))
                LabeledTextField("端口", port, Modifier.width(110.dp)) { port = it }
            }
            LabeledTextField("根路径（默认 /，AList 以 /dav 开头）", path) { path = it }
            LabeledTextField("用户名", user) { user = it }
            LabeledTextField("密码", pass, isPassword = true) { pass = it }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = https, onCheckedChange = { https = it })
            Text("使用 HTTPS（自签证书已在网络安全配置中放行）", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Text("备用线路（可选）", style = MaterialTheme.typography.titleSmall)
        Text(
            "主线路连接失败时自动切换到备用线路（域名与 IP、内网与外网互备最常用）；留空表示不启用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row {
            LabeledTextField("备用地址 / 域名", bHost, Modifier.weight(1f)) { bHost = it }
            Spacer(Modifier.width(8.dp))
            LabeledTextField("端口", bPort, Modifier.width(110.dp)) { bPort = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = bHttps, onCheckedChange = { bHttps = it })
            Text("备用线路使用 HTTPS", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                loading = true; error = null
                val p = port.toIntOrNull() ?: 0
                val c = WebDavConnection(
                    host = normalizeHost(host),
                    port = p,
                    useHttps = https,
                    username = user.trim(),
                    password = pass,
                    rootPath = sanitizeWebDavPath(path),
                    backupHost = bHost.trim().takeIf { it.isNotEmpty() }?.let { normalizeHost(it) },
                    backupPort = bPort.toIntOrNull() ?: 0,
                    backupUseHttps = bHttps
                )
                scope.launch {
                    try {
                        // 直接保存，由曲库页触发重新扫描来验证连接；失败会在主页明确提示
                        MusicBoxApp.instance.saveWebDav(c)
                        onSaved()
                    } catch (e: Exception) {
                        error = CrashLogger.describe("保存失败", e)
                    } finally { loading = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && host.isNotBlank()
        ) { Text(if (loading) "保存中…" else "保存并重新连接") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        Spacer(Modifier.height(4.dp))
        Text(
            "保存后会自动重新扫描音乐库；配置（含密码）一直缓存，断开不会清空。\n" +
                "地址栏只填 IP 或域名，不要带 http://。\n" +
                "AList / OpenList（端口 5244）：根路径必须以 /dav 开头；用户名和密码填 AList 后台登录账号（不是网盘账号），部分版本需在 AList 设置里单独配置 WebDAV 密码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun FnOSConnectionForm(
    prefill: FnosConnection?,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var host by rememberSaveable(prefill?.host) { mutableStateOf(prefill?.host ?: "") }
    var port by rememberSaveable(prefill?.port) {
        mutableStateOf((prefill?.port?.takeIf { it != 0 } ?: 5666).toString())
    }
    var https by rememberSaveable(prefill?.useHttps) { mutableStateOf(prefill?.useHttps ?: false) }
    var user by rememberSaveable(prefill?.username) { mutableStateOf(prefill?.username ?: "") }
    var pass by rememberSaveable(prefill?.password) { mutableStateOf(prefill?.password ?: "") }
    var bHost by rememberSaveable(prefill?.backupHost) { mutableStateOf(prefill?.backupHost ?: "") }
    var bPort by rememberSaveable(prefill?.backupPort) {
        mutableStateOf((prefill?.backupPort?.takeIf { it != 0 } ?: 0).toString().takeIf { it != "0" } ?: "")
    }
    var bHttps by rememberSaveable(prefill?.backupUseHttps) { mutableStateOf(prefill?.backupUseHttps ?: false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }

    // 备用线路字段较多，小屏放不下：根布局可上下滚动，保证「备用连接」完整可见可填。
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("编辑飞牛音乐登录", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LabeledTextField("NAS 地址 / 域名", host) { host = it }
        LabeledTextField("端口（默认 5666）", port) { port = it }
        LabeledTextField("用户名", user) { user = it }
        LabeledTextField("密码", pass, isPassword = true) { pass = it }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = https, onCheckedChange = { https = it })
            Text("使用 HTTPS", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Text("备用线路（可选）", style = MaterialTheme.typography.titleSmall)
        Text(
            "主线路连不上时自动切换（内网 IP 与外网域名互备最常用）；留空表示不启用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row {
            LabeledTextField("备用地址 / 域名", bHost, Modifier.weight(1f)) { bHost = it }
            Spacer(Modifier.width(8.dp))
            LabeledTextField("端口", bPort, Modifier.width(110.dp)) { bPort = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = bHttps, onCheckedChange = { bHttps = it })
            Text("备用线路使用 HTTPS", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                loading = true; error = null
                val p = port.toIntOrNull() ?: 5666
                val c = FnosConnection(
                    host = normalizeHost(host),
                    port = p,
                    useHttps = https,
                    username = user.trim(),
                    password = pass,
                    backupHost = bHost.trim().takeIf { it.isNotEmpty() }?.let { normalizeHost(it) },
                    backupPort = bPort.toIntOrNull() ?: 0,
                    backupUseHttps = bHttps
                )
                scope.launch {
                    try {
                        val token = MusicBoxApp.instance.fnosClient.login(c, pass)
                        MusicBoxApp.instance.saveFnos(c, token)
                        onSaved()
                    } catch (e: Exception) { error = CrashLogger.describe("登录失败", e) }
                    finally { loading = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && host.isNotBlank() && user.isNotBlank()
        ) { Text(if (loading) "登录中…" else "保存并重新登录") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        Spacer(Modifier.height(4.dp))
        Text(
            "已保存的地址、账号、密码会一直缓存，退出登录不会清空。\n使用飞牛音乐服务的独立网关端口（默认 5666），不是 NAS 网页后台端口。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default
    )
}
