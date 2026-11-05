package com.musicbox.nas.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavController
import com.musicbox.nas.MusicBoxApp
import com.musicbox.nas.SourceMode
import com.musicbox.nas.data.Track
import com.musicbox.nas.data.m3u.M3uParser
import com.musicbox.nas.dataStore
import com.musicbox.nas.player.PlayerController
import com.musicbox.nas.ui.components.CoverImage
import com.musicbox.nas.ui.components.TrackRow
import com.musicbox.nas.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

@Serializable
private data class StoredPlaylist(val name: String, val content: String)

/** 音源里自动识别到的歌单：WebDAV 已在扫描时解析好曲目，飞牛按需走网络接口。 */
private data class AutoPlaylist(
    val id: String,
    val name: String,
    val subtitle: String,
    val coverUrl: String?,
    val embeddedAudioUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val loadTracks: (suspend () -> List<Track>)? = null
)

/** 进程内保存歌单页的浏览状态，供「进入播放页再返回」时还原（进程重启自然清空）。 */
private object PlaylistsMemory {
    var stored: List<StoredPlaylist> = emptyList()
    var expandedStored: Int? = null
    var expandedAuto: String? = null
}

@Composable
fun PlaylistsScreen(navController: NavController) {
    val context = LocalContext.current
    val app = MusicBoxApp.instance
    val scope = rememberCoroutineScope()
    val mode by app.sourceMode.collectAsState()
    val davConn by app.webDavConn.collectAsState()
    val fnosConn by app.fnosConn.collectAsState()
    val davLib by app.webDavLibrary.collectAsState()
    val scanning by app.webDavScanning.collectAsState()

    // 进程内记住「已导入歌单 / 展开项」：进入播放页再返回时本页会被 NavHost 重建，
    // 若首帧列表为空，LazyColumn 的滚动位置会被钳到 0，看起来就是「又从头开始了」。
    var stored by remember { mutableStateOf(PlaylistsMemory.stored) }
    var auto by remember { mutableStateOf<List<AutoPlaylist>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var autoBusy by remember { mutableStateOf(false) }
    var expandedAuto by remember { mutableStateOf(PlaylistsMemory.expandedAuto) }
    var autoTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var autoTrackLoading by remember { mutableStateOf<String?>(null) }
    var expandedStored by remember { mutableStateOf(PlaylistsMemory.expandedStored) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(stored, expandedStored, expandedAuto) {
        PlaylistsMemory.stored = stored
        PlaylistsMemory.expandedStored = expandedStored
        PlaylistsMemory.expandedAuto = expandedAuto
    }

    val listSerializer =
        kotlinx.serialization.builtins.ListSerializer(StoredPlaylist.serializer())

    suspend fun load(): List<StoredPlaylist> {
        val raw = MusicBoxApp.instance.dataStore.data.first()[M3U_KEY]
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString(listSerializer, raw) }.getOrNull() ?: emptyList()
    }
    suspend fun save(list: List<StoredPlaylist>) {
        MusicBoxApp.instance.dataStore.edit { it[M3U_KEY] = Json.encodeToString(listSerializer, list) }
    }

    LaunchedEffect(Unit) { stored = load() }

    // 自动识别当前音源中的 M3U / M3U8 歌单：WebDAV 走全库扫描结果，飞牛走服务端歌单接口
    LaunchedEffect(mode, davConn, fnosConn, davLib) {
        when (mode) {
            SourceMode.WEBDAV -> {
                val c = davConn
                if (c == null) {
                    auto = emptyList()
                    note = "尚未连接 WebDAV，请到「设置 → 连接管理」填写连接信息"
                } else {
                    val lib = davLib
                    if (lib == null) {
                        auto = emptyList()
                        note = if (app.webDavScanning.value)
                            "正在递归扫描 WebDAV 全部目录中的 M3U / M3U8 歌单…"
                        else
                            "尚无扫描索引，请到「设置 → WebDAV 扫描」手动扫描"
                        if (!app.webDavScanning.value && !app.webDavAutoScanAttempted.value) {
                            // App 级作用域：离开本页也不会中断扫描
                            app.startWebDavScan(c, automatic = true)
                        }
                    } else {
                        auto = lib.playlists.map { pl ->
                            AutoPlaylist(
                                id = "dav:${pl.name}",
                                name = pl.name,
                                subtitle = "${pl.tracks.size} 首 · WebDAV 目录中的 M3U",
                                coverUrl = pl.tracks.firstOrNull()?.coverUrl
                                    ?: pl.tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
                                embeddedAudioUrl = pl.tracks.firstOrNull { !it.embeddedAudioUrl.isNullOrBlank() }?.embeddedAudioUrl,
                                tracks = pl.tracks
                            )
                        }
                        note = if (lib.playlists.isEmpty())
                            "扫描完成：该音源中未发现 .m3u / .m3u8 歌单"
                        else
                            "自动识别到 ${lib.playlists.size} 个 M3U 歌单（无需手动导入）"
                    }
                }
            }
            SourceMode.FNOS -> {
                val c = fnosConn
                if (c == null) {
                    auto = emptyList()
                    note = "尚未登录飞牛音乐，请到「设置 → 连接管理」登录"
                } else {
                    autoBusy = true
                    var err: String? = null
                    val result: List<AutoPlaylist> = withContext(Dispatchers.IO) {
                        runCatching {
                            val (list, _) = app.fnosClient.playlistList(1, 100)
                            list.map { pl ->
                                val guid = pl.guid
                                AutoPlaylist(
                                    id = "fnos:${pl.guid}",
                                    name = pl.name.ifBlank { "未命名歌单" },
                                    subtitle = "${pl.trackCount ?: "?"} 首 · 飞牛音乐服务端歌单",
                                    coverUrl = app.fnosClient.coverUrl(pl.coverId),
                                    loadTracks = {
                                        val (ts, _) = app.fnosClient.playlistTracks(guid, 1, 300)
                                        ts.map { app.fnosClient.toTrack(it) }
                                    }
                                )
                            }
                        }.getOrElse { e ->
                            CrashLogger.error("Playlists.fnos", e)
                            err = "读取失败：${e.message ?: e::class.java.simpleName}"
                            emptyList()
                        }
                    }
                    auto = result
                    note = err ?: if (result.isEmpty()) "飞牛音乐中暂无歌单" else "自动读取到 ${result.size} 个服务端歌单"
                    autoBusy = false
                }
            }
        }
    }

    val docLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
            }
            if (content.isBlank()) {
                message = "文件内容为空或无法读取"
            } else {
                val name = M3uParser.parse(content).name
                val list = stored + StoredPlaylist(name, content)
                stored = list
                save(list)
                message = null
                CrashLogger.info("M3U", "本地文件导入成功「$name」，${content.length} 字符")
            }
        }
    }

    if (showUrlDialog) {
        UrlImportDialog(
            onDismiss = { showUrlDialog = false },
            onImport = { url ->
                showUrlDialog = false
                scope.launch {
                    message = "正在导入…"
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val req = Request.Builder().url(url).build()
                            MusicBoxApp.instance.okHttpClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                                resp.body?.string().orEmpty()
                            }
                        }
                    }
                    result.fold(
                        onSuccess = { content ->
                            if (content.isBlank()) message = "歌单内容为空"
                            else {
                                val list = stored + StoredPlaylist(M3uParser.parse(content).name, content)
                                stored = list
                                save(list)
                                message = null
                                CrashLogger.info("M3U", "网络歌单导入成功：$url")
                            }
                        },
                        onFailure = { e -> message = CrashLogger.describe("导入失败", e) }
                    )
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
        Text("音源歌单 · 自动识别", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (autoBusy || scanning) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                docLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/mpegurl", "application/octet-stream", "*/*"))
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.padding(4.dp)); Text("从文件导入")
            }
            Button(onClick = { showUrlDialog = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Link, null); Spacer(Modifier.padding(4.dp)); Text("从网址导入")
            }
        }
        if (message != null) {
            Text(
                message!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(auto, key = { _, ap -> ap.id }) { _, ap ->
                AutoPlaylistCard(
                    ap = ap,
                    expanded = expandedAuto == ap.id,
                    tracks = if (expandedAuto == ap.id) autoTracks else emptyList(),
                    loading = autoTrackLoading == ap.id,
                    onToggle = {
                        if (expandedAuto == ap.id) {
                            expandedAuto = null
                        } else {
                            expandedAuto = ap.id
                            autoTracks = emptyList()
                            val loader = ap.loadTracks
                            when {
                                ap.tracks.isNotEmpty() -> autoTracks = ap.tracks
                                loader != null -> scope.launch {
                                    autoTrackLoading = ap.id
                                    autoTracks = withContext(Dispatchers.IO) {
                                        runCatching { loader() }.getOrElse {
                                            CrashLogger.error("Playlists.tracks", it)
                                            emptyList()
                                        }
                                    }
                                    autoTrackLoading = null
                                }
                            }
                        }
                    },
                    onPlayAll = { PlayerController.playTracks(autoTracks, 0) },
                    onPlayTrack = { idx -> PlayerController.playTracks(autoTracks, idx) }
                )
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "手动导入的 M3U 歌单",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            val disconnected = if (mode == SourceMode.WEBDAV) davConn == null else fnosConn == null
            if (auto.isEmpty() && disconnected) {
                item { TextButton(onClick = { navController.navigate("settings") }) { Text("前往设置连接音源") } }
            }

            itemsIndexed(stored, key = { i, _ -> "stored#$i" }) { i, pl ->
                val parsed = remember(pl.content) { M3uParser.parse(pl.content, pl.name) }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${parsed.tracks.size} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = {
                                val list = stored.toMutableList().also { it.removeAt(i) }
                                stored = list
                                scope.launch { save(list) }
                            }) { Icon(Icons.Default.Delete, null) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { expandedStored = if (expandedStored == i) null else i },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (expandedStored == i) "收起歌曲" else "查看歌曲（${parsed.tracks.size}）") }
                        if (expandedStored == i) {
                            Button(
                                onClick = { PlayerController.playTracks(parsed.tracks, 0) },
                                enabled = parsed.tracks.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("▶ 播放全部") }
                            parsed.tracks.forEachIndexed { idx, track ->
                                TrackRow(track, onClick = { PlayerController.playTracks(parsed.tracks, idx) })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            if (stored.isEmpty()) {
                item {
                    Text(
                        "还没有手动导入的歌单，可用上方按钮从本地 .m3u / .m3u8 文件或网址导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 音源自动识别的歌单卡片：展开后可播放全部或单曲，浏览不触碰当前播放。 */
@Composable
private fun AutoPlaylistCard(
    ap: AutoPlaylist,
    expanded: Boolean,
    tracks: List<Track>,
    loading: Boolean,
    onToggle: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayTrack: (Int) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(ap.coverUrl, Modifier.size(48.dp), embeddedAudioUrl = ap.embeddedAudioUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ap.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ap.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取歌单歌曲…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Button(
                        onClick = onPlayAll,
                        enabled = tracks.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("▶ 播放全部（${tracks.size}）") }
                    tracks.forEachIndexed { idx, t ->
                        TrackRow(t, onClick = { onPlayTrack(idx) })
                        HorizontalDivider()
                    }
                    if (tracks.isEmpty()) {
                        Text(
                            "该歌单没有返回歌曲",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (url.isNotBlank()) onImport(url.trim()) }) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("从网址导入 M3U") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("歌单 URL（.m3u / .m3u8）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

private val M3U_KEY = stringPreferencesKey("m3u_playlists")
