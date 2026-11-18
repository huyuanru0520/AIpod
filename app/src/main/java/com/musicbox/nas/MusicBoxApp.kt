package com.musicbox.nas

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import org.json.JSONObject
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.musicbox.nas.data.AuthRegistry
import com.musicbox.nas.data.createOkHttpClient
import com.musicbox.nas.data.createLenientOkHttpClient
import com.musicbox.nas.data.fnos.FnosClient
import com.musicbox.nas.data.fnos.FnosConnection
import com.musicbox.nas.data.Track
import com.musicbox.nas.data.rebasedTo
import com.musicbox.nas.data.webdav.DavLibrary
import com.musicbox.nas.data.webdav.WebDavClient
import com.musicbox.nas.data.webdav.WebDavConnection
import com.musicbox.nas.player.PlayerController
import com.musicbox.nas.util.CrashLogger
import com.musicbox.nas.util.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

val Application.dataStore: DataStore<Preferences> by preferencesDataStore(name = "musicbox_prefs")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** 主界面显示的音源：同一时刻只显示一种，在「设置」里切换并持久化。 */
enum class SourceMode { WEBDAV, FNOS, LOCAL }

/** 播放音质：原样直通，或转成 256kbps AAC（体积小、兼容老设备）。 */
enum class AudioQuality(val label: String) {
    ORIGINAL("原采样率 / 原码率"),
    AAC_256("AAC 256kbps")
}

/** 主界面可勾选显示的栏目（文件夹仅 WebDAV 有）。 */
object LibraryTabs {
    const val SONGS = "songs"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val ALBUMS = "albums"
    const val FOLDERS = "folders"
    /** 风格 / 流派：飞牛原生 /genre/list，WebDAV 无此元数据。 */
    const val GENRES = "genres"
    /** 漫游：全曲库随机流，两种音源都支持。 */
    const val ROAM = "roam"
    const val SEARCH = "search"

    val all: List<Pair<String, String>> = listOf(
        SONGS to "歌曲",
        ARTISTS to "歌手",
        PLAYLISTS to "歌单",
        ALBUMS to "专辑",
        FOLDERS to "文件夹",
        GENRES to "风格",
        ROAM to "漫游",
        SEARCH to "搜索"
    )

    val keys: List<String> get() = all.map { it.first }

    /** 默认顺序 = all 的声明顺序。用户可在设置里上下移动调整。 */
    val default: List<String> get() = keys

    fun labelOf(key: String): String = all.firstOrNull { it.first == key }?.second ?: key

    /** 保留传入顺序、去重、丢弃不认识的 key。 */
    fun normalize(ordered: Iterable<String>): List<String> {
        val valid = keys.toSet()
        val seen = LinkedHashSet<String>()
        ordered.forEach { k -> if (k in valid) seen.add(k) }
        return seen.toList()
    }
}

/** WebDAV 扫描节奏：相邻请求最小间隔 + 并行数，两者共同决定速度与风控强度。 */
object ScanPace {
    data class Option(val label: String, val intervalMs: Long, val concurrency: Int)

    const val GENTLE_INTERVAL = 250L
    const val GENTLE_CONCURRENCY = 3
    const val BALANCED_INTERVAL = 120L
    const val BALANCED_CONCURRENCY = 6
    const val FAST_INTERVAL = 50L
    const val FAST_CONCURRENCY = 12
    // 极速：STRM / 自建 openlist 等无网盘风控的场景。请求间隔 0（不再匀速排队）+ 32 路并发。
    const val TURBO_INTERVAL = 0L
    const val TURBO_CONCURRENCY = 32

    val options: List<Option> = listOf(
        Option("温和", GENTLE_INTERVAL, GENTLE_CONCURRENCY),
        Option("均衡", BALANCED_INTERVAL, BALANCED_CONCURRENCY),
        Option("快速", FAST_INTERVAL, FAST_CONCURRENCY),
        Option("极速", TURBO_INTERVAL, TURBO_CONCURRENCY)
    )
    /** 默认档位（均衡）：约 8 次/秒、6 路并行，兼顾速度与风控。 */
    val default: Option get() = options[1]
}

class MusicBoxApp : Application() {

    lateinit var okHttpClient: OkHttpClient
    lateinit var authRegistry: AuthRegistry
    lateinit var fnosClient: FnosClient
    lateinit var webDavClient: WebDavClient

    val webDavConn = MutableStateFlow<WebDavConnection?>(null)
    val fnosConn = MutableStateFlow<FnosConnection?>(null)
    val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    /** 主题主色（ARGB Int）。0 = 用默认品牌红。 */
    val accentColor = MutableStateFlow(0xFA2D48)
    val sourceMode = MutableStateFlow(SourceMode.WEBDAV)

    /** 已保存的连接配置（含密码）：断开会话后仍保留，供表单预填与编辑，不会被清空。 */
    val savedWebDav = MutableStateFlow<WebDavConnection?>(null)
    val savedFnos = MutableStateFlow<FnosConnection?>(null)
    val fnosLoggedIn = MutableStateFlow(false)

    /** WebDAV 全库扫描结果：落盘缓存，冷启动直接恢复，避免每次开 App 都全库扫描。 */
    val webDavLibrary = MutableStateFlow<DavLibrary?>(null)

    /**
     * 收藏表：飞牛 / 本地 / WebDAV 三张独立表，元素是 Track.id。
     * 按音源分表，互不串味；用 Set 便于 O(1) 判定与去重，持久化时序列化为 JSON 数组。
     */
    val favFnos = MutableStateFlow<Set<String>>(emptySet())
    val favLocal = MutableStateFlow<Set<String>>(emptySet())
    val favWebDav = MutableStateFlow<Set<String>>(emptySet())

    /** 本地音乐曲库：扫描本机文件夹得到的曲目，冷启动从 DataStore 恢复。 */
    val localLibrary = MutableStateFlow<List<Track>>(emptyList())

    val webDavScanProgress = MutableStateFlow("")
    val webDavScanning = MutableStateFlow(false)
    /** 一次进程生命周期内最多自动全库扫描一次；失败也不反复重试冲击网盘。 */
    val webDavAutoScanAttempted = MutableStateFlow(false)
    /** 扫描过程中实时回传的「已找到歌曲」列表，每次扫描开始清空，供曲库页实时展示。 */
    val webDavScanFound = MutableStateFlow<List<Track>>(emptyList())
    /**
     * 落盘曲库索引是按哪条线路扫出来的（如 http://192.168.1.10:5005）。
     * 主备线路切换后据此把索引里的绝对地址原地改写，避免重新全库扫描。
     */
    private var davCacheBase: String? = null
    /** 相邻请求最小间隔（ms），设置页可调，默认均衡档。 */
    val scanPaceMs = MutableStateFlow(ScanPace.BALANCED_INTERVAL)
    /** 并行 PROPFIND 数：真正的并发，默认均衡档 6 路，越大扫描越快但越易触发限流。 */
    val scanConcurrency = MutableStateFlow(ScanPace.BALANCED_CONCURRENCY)
    /** 主界面显示哪些栏目（歌曲/歌手/歌单/专辑/文件夹/搜索），设置里可勾选。 */
    val visibleTabs = MutableStateFlow<List<String>>(LibraryTabs.default)
    /** 播放音质：默认原采样率直通。 */
    val audioQuality = MutableStateFlow(AudioQuality.ORIGINAL)
    /** 转轮音量：0f~1f，设置后立即写入 ExoPlayer 并落盘。 */
    val volume = MutableStateFlow(1f)
    /**
     * 调试模式（默认关闭）。开启后全量运行日志才会写盘 / 输出到下载目录；
     * 关闭时调试日志完全停用（仅 Logcat 静默），也可在设置里手动切换。
     */
    val debugMode = MutableStateFlow(false)
    /** 设置页是否显示各条目的说明文字（副标题），默认显示；可在设置里一键隐藏。 */
    val showDescriptions = MutableStateFlow(true)
    /**
     * 转轮震动强度：0=关闭，1=弱，2=中，3=强。默认中档（2）。
     * 设置页可调，Haptics 每次触发时读取此值换算实际幅度。
     */
    val hapticStrength = MutableStateFlow(2)
    /**
     * 横屏 Cover Flow / 由其打开的专辑列表是否为「全屏沉浸」态。
     * 为 true 时 MainActivity 隐藏底部导航与 MiniPlayer，并切到沉浸式系统栏。
     */
    val fullScreenMode = MutableStateFlow(false)
    @Volatile private var lastScanStartMs = 0L

    /**
     * App 级作用域：扫描不随页面销毁而取消。
     * 之前扫描由曲库页/Settings 的 rememberCoroutineScope 启动，切 Tab、退出页面就中断，
     * 表现为「扫描到一半就停了」。现在统一挂在这里，退到后台也继续跑完。
     */
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 从任意页面发起扫描（不阻塞调用方，页面退出也不取消）。 */
    fun startWebDavScan(conn: WebDavConnection, automatic: Boolean = false) {
        if (webDavScanning.value) return
        if (automatic && webDavAutoScanAttempted.value) return
        appScope.launch {
            runCatching { refreshWebDavLibrary(conn, automatic) }
                .onFailure { CrashLogger.info("WebDAV", "扫描任务结束：${it.message}") }
        }
    }

    /**
     * 递归扫描 WebDAV 并生成「所有歌曲 / 专辑 / 歌手 / 歌单」分类索引。
     * automatic=true 时表示页面自动触发，整个进程只允许一次；手动扫描不受限制。
     */
    suspend fun refreshWebDavLibrary(conn: WebDavConnection, automatic: Boolean = false) {
        if (webDavScanning.value) return
        if (automatic && webDavAutoScanAttempted.value) return
        // 手动扫描也做 1.5 秒防连点：日志里曾出现 1 秒内连发 5 次全库扫描，
        // 在 401 的情况下这只会被服务器判为异常流量并升级为 429。
        val now = android.os.SystemClock.elapsedRealtime()
        if (!automatic && now - lastScanStartMs < 1500L) return
        lastScanStartMs = now
        if (automatic) webDavAutoScanAttempted.value = true
        webDavScanning.value = true
        webDavScanFound.value = emptyList() // 每次扫描开始清空实时列表
        // 前台服务保活：退到后台/锁屏时扫描继续，通知栏显示进度
        com.musicbox.nas.scan.ScanService.start(this)
        try {
            webDavScanProgress.value = "正在并行递归扫描 WebDAV…"
            val result = webDavClient.scanLibrary(
                conn, scanPaceMs.value, scanConcurrency.value,
                onProgress = { msg -> webDavScanProgress.value = msg },
                onBatch = { found -> webDavScanFound.value = found }
            )
            webDavLibrary.value = result
            webDavScanFound.value = emptyList()
            // 记录这份索引是按哪条线路扫出来的，主备切换时据此改写地址而不必重扫
            saveDavLibraryCache(result, webDavClient.lastActiveBaseUrl ?: conn.baseUrl)
            webDavScanProgress.value =
                "扫描完成：${result.tracks.size} 首歌曲 / ${result.albums.size} 张专辑 / " +
                    "${result.artists.size} 位歌手 / ${result.playlists.size} 个歌单" +
                    (if (result.failedDirs > 0) "（${result.failedDirs} 个目录无权限已跳过）" else "") +
                    (result.note?.let { "；$it" } ?: "")
            CrashLogger.info("WebDAV", webDavScanProgress.value)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) {
                // 页面退出导致的正常取消（LeftCompositionCancellationException），不算错误
                webDavScanProgress.value = "扫描已取消"
                throw e
            }
            CrashLogger.error("WebDAV.scan", e)
            webDavScanProgress.value = "扫描失败：${e.message ?: e::class.java.simpleName}"
            throw e
        } finally {
            webDavScanning.value = false
            com.musicbox.nas.scan.ScanService.stop(this)
        }
    }

    // ---- WebDAV 索引落盘缓存：冷启动不再重复全库扫描（这是最容易被网盘判定为异常的行为） ----

    private fun davCacheFile() = java.io.File(filesDir, "dav_library.cache")

    private suspend fun restoreDavLibraryCache() {
        val f = davCacheFile()
        if (!f.exists()) return
        val cached = runCatching {
            java.io.ObjectInputStream(f.inputStream()).use { it.readObject() as? DavLibrary }
        }.getOrElse {
            CrashLogger.error("WebDAV.cache.read", it)
            runCatching { f.delete() }
            null
        }
        if (cached != null) {
            davCacheBase = dataStore.data.first()[W_CACHE_BASE]
            webDavLibrary.value = cached
            webDavAutoScanAttempted.value = true
            webDavScanProgress.value =
                "已加载上次扫描的索引：${cached.tracks.size} 首歌曲 · ${cached.albums.size} 张专辑 · " +
                    "${cached.artists.size} 位歌手 · ${cached.playlists.size} 个歌单（如需更新请手动扫描）"
            CrashLogger.info(
                "WebDAV",
                "从缓存恢复索引，跳过本次自动全库扫描：${cached.tracks.size} 首歌曲；" +
                    "索引线路=${davCacheBase ?: "未记录（旧版缓存）"}"
            )
        }
    }

    private suspend fun saveDavLibraryCache(lib: DavLibrary, base: String? = null) {
        runCatching {
            java.io.ObjectOutputStream(davCacheFile().outputStream()).use { it.writeObject(lib) }
        }.onFailure { CrashLogger.error("WebDAV.cache.write", it) }
        davCacheBase = base
        runCatching {
            dataStore.edit { p ->
                if (base.isNullOrBlank()) p.remove(W_CACHE_BASE) else p[W_CACHE_BASE] = base
            }
        }.onFailure { CrashLogger.error("WebDAV.cache.base", it) }
    }

    private suspend fun clearDavLibraryCache() {
        davCacheBase = null
        runCatching { davCacheFile().delete() }
        runCatching { dataStore.edit { it.remove(W_CACHE_BASE) } }
    }

    /**
     * 这份缓存是按哪条线路扫出来的。旧版本缓存没有记录该字段，返回 null。
     */
    suspend fun cachedLibraryBase(): String? {
        if (davCacheBase == null) {
            davCacheBase = runCatching { dataStore.data.first()[W_CACHE_BASE] }.getOrNull()
        }
        return davCacheBase
    }

    /**
     * 主备线路切换时，把曲库里所有绝对地址整体换到新线路 —— **不重新扫描**。
     *
     * 扫描结果是几千条绝对 URL，重新全库 PROPFIND 既慢又最容易触发网盘风控；
     * 而主备线路指向的是同一台 NAS、同一份目录，只是入口地址不同，
     * 因此把 `scheme://host:port` 前缀换掉就能继续用。
     *
     * @return 是否真的发生了改写
     */
    suspend fun rebaseWebDavLibrary(newBase: String): Boolean {
        val lib = webDavLibrary.value ?: return false
        val oldBase = cachedLibraryBase() ?: return false
        if (oldBase == newBase) return false

        val rebased = lib.rebasedTo(oldBase, newBase)
        webDavLibrary.value = rebased
        PlayerController.rebaseQueue(oldBase, newBase)
        saveDavLibraryCache(rebased, newBase)
        CrashLogger.info("WebDAV", "线路切换：曲库地址 $oldBase → $newBase（复用索引，未重新扫描）")
        return true
    }

    /**
     * 进入曲库前对齐线路：探测一次当前可用线路，若与缓存索引的线路不同则原地改写地址。
     *
     * 只在「缓存线路是当前配置里的主/备之一」时才改写；否则说明用户换了地址或根路径，
     * 交给正常扫描流程处理，避免把地址改错。
     */
    suspend fun ensureWebDavLine(conn: WebDavConnection): Boolean {
        val lib = webDavLibrary.value ?: return false
        if (lib.tracks.isEmpty() || conn.backupBaseUrl == null) return false
        val known = setOfNotNull(conn.baseUrl, conn.backupBaseUrl)
        val oldBase = cachedLibraryBase() ?: return false
        if (oldBase !in known) return false

        val active = webDavClient.pickActiveConn(conn)
        val newBase = active.baseUrl
        if (newBase == oldBase) return false
        return rebaseWebDavLibrary(newBase)
    }

    /** 设置页调整扫描节奏（间隔 + 并行数），持久化后下一次扫描生效。 */
    suspend fun setScanPace(intervalMs: Long, concurrency: Int) {
        scanPaceMs.value = intervalMs.coerceIn(0L, 2000L)
        scanConcurrency.value = concurrency.coerceIn(1, 48)
        dataStore.edit {
            it[S_PACE] = scanPaceMs.value
            it[S_CONCURRENCY] = scanConcurrency.value.toLong()
        }
        CrashLogger.info("Settings", "WebDAV 扫描节奏=${scanPaceMs.value}ms/请求 · 并行=${scanConcurrency.value}")
    }

    /** 勾选主界面要显示的栏目；至少保留一个，避免整页空白。 */
    /** 保存栏目顺序（逗号串本身就是有序的），WebDAV 与飞牛通用。 */
    suspend fun setVisibleTabs(tabs: List<String>) {
        val safe = LibraryTabs.normalize(tabs)
        visibleTabs.value = if (safe.isEmpty()) listOf(LibraryTabs.SONGS) else safe
        CrashLogger.info("Settings", "主界面栏目顺序=${visibleTabs.value.joinToString(" > ")}")
        dataStore.edit {
            it[T_TABS] = visibleTabs.value.joinToString(",")
            it[T_TABS_ORDERED] = true
        }
    }

    suspend fun setAudioQuality(q: AudioQuality) {
        audioQuality.value = q
        CrashLogger.info("Settings", "播放音质=${q.label}")
        dataStore.edit { it[Q_QUALITY] = q.name }
    }

    suspend fun setVolume(v: Float) {
        val safe = v.coerceIn(0f, 1f)
        volume.value = safe
        PlayerController.setVolume(safe)
        dataStore.edit { it[P_VOLUME] = safe }
    }

    companion object {
        lateinit var instance: MusicBoxApp
            private set
    }

    /** 比 onCreate 更早：ContentProvider 初始化之前就装好崩溃处理器。 */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashLogger.install(base)
        CrashLogger.mark("attachBaseContext")
    }

    override fun onCreate() {
        CrashLogger.mark("Application.onCreate enter")
        super.onCreate()
        instance = this
        // 转轮震动 + 音效：先加载资源（强度用默认中档，会话恢复后会同步持久化值）
        runCatching { Haptics.init(this, hapticStrength.value) }
            .onFailure { CrashLogger.error("haptics-init", it) }
        CrashLogger.mark("clients init start")
        runCatching {
            authRegistry = AuthRegistry()
            okHttpClient = createOkHttpClient(authRegistry)
            // 飞牛走宽松 HTTPS 客户端：信任自签证书 + 兼容旧 TLS，避免自托管/反代握手失败
            fnosClient = FnosClient(createLenientOkHttpClient(authRegistry), authRegistry)
            webDavClient = WebDavClient(okHttpClient, authRegistry)
        }.onFailure { CrashLogger.error("init-clients", it) }
        CrashLogger.mark("clients init done")

        // 把版本号写进日志：以后拿到日志即可确认用户实际安装的是哪一版，避免装错旧包
        val version = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        CrashLogger.info("App", "应用版本=$version / package=$packageName")

        // 会话恢复改为后台异步：不在主线程做磁盘 I/O，也不让持久化异常拖垮冷启动
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { restoreSessions() }
                .onFailure { CrashLogger.error("restore-sessions", it) }
        }
        CrashLogger.mark("Application.onCreate done")
    }

    // 保留这些方法供其他模块调用，内部统一委托给 CrashLogger
    fun recordError(tag: String, t: Throwable) = CrashLogger.error(tag, t)
    fun readCrashLog(): String? = CrashLogger.read()
    fun clearCrashLog() = CrashLogger.clear()

    private suspend fun restoreSessions() {
        if (!::webDavClient.isInitialized || !::fnosClient.isInitialized) return
        val p = dataStore.data.first()
        val wHost = p[W_HOST]
        if (!wHost.isNullOrBlank()) {
            val conn = WebDavConnection(
                host = wHost,
                port = p[W_PORT] ?: 0,
                useHttps = p[W_HTTPS] ?: false,
                username = p[W_USER] ?: "",
                password = p[W_PASS] ?: "",
                rootPath = p[W_PATH] ?: "/",
                backupHost = p[W_BHOST],
                backupPort = p[W_BPORT] ?: 0,
                backupUseHttps = p[W_BHTTPS] ?: false
            )
            savedWebDav.value = conn
            webDavClient.registerAuth(conn)
            webDavConn.value = conn
        }
        val fHost = p[F_HOST]
        val fToken = p[F_TOKEN]
        if (!fHost.isNullOrBlank()) {
            val conn = FnosConnection(
                host = fHost,
                port = p[F_PORT] ?: 5666,
                useHttps = p[F_HTTPS] ?: false,
                username = p[F_USER] ?: "",
                password = p[F_PASS] ?: "",
                backupHost = p[F_BHOST],
                backupPort = p[F_BPORT] ?: 0,
                backupUseHttps = p[F_BHTTPS] ?: false
            )
            savedFnos.value = conn
            if (!fToken.isNullOrBlank()) {
                fnosClient.setSession(conn, fToken)
                fnosConn.value = conn
                fnosLoggedIn.value = true
            }
        }
        p[T_THEME]?.let { runCatching { themeMode.value = ThemeMode.valueOf(it) } }
        p[T_ACCENT]?.let { runCatching { accentColor.value = it } }
        p[S_PACE]?.let { scanPaceMs.value = it.coerceIn(0L, 2000L) }
        p[S_CONCURRENCY]?.let { scanConcurrency.value = it.toInt().coerceIn(1, 48) }
        p[T_TABS]?.let { raw ->
            val parts = raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
            val ordered = if (p[T_TABS_ORDERED] == true) {
                // 新版：逗号串本身就是用户排好的顺序
                LibraryTabs.normalize(parts)
            } else {
                // 老版存的是 Set（无顺序语义）：按默认顺序还原勾选状态，
                // 并把新增的「风格 / 漫游」追加到末尾直接显示。
                val saved = parts.toSet()
                val base = LibraryTabs.keys.filter { it in saved }
                (base + listOf(LibraryTabs.GENRES, LibraryTabs.ROAM).filter { it !in base }).distinct()
            }
            if (ordered.isNotEmpty()) visibleTabs.value = ordered
        }
        p[Q_QUALITY]?.let { runCatching { audioQuality.value = AudioQuality.valueOf(it) } }
        p[P_VOLUME]?.let { runCatching { volume.value = it.coerceIn(0f, 1f) } }
        // 调试模式：从持久化读取并同步给 CrashLogger（默认关闭，避免常驻日志 I/O）
        val dbg = p[D_DEBUG] ?: false
        debugMode.value = dbg
        CrashLogger.setEnabled(dbg)
        // 说明文字显隐（默认显示）
        showDescriptions.value = p[T_SHOW_DESC] ?: true
        // 震动强度（0-3，默认中档 2），同步给 Haptics
        hapticStrength.value = (p[T_HAPTIC] ?: 2).coerceIn(0, 3)
        Haptics.setStrength(hapticStrength.value)
        val savedSource = p[S_SOURCE]
        if (savedSource != null) {
            runCatching { sourceMode.value = SourceMode.valueOf(savedSource) }
        } else {
            // 首次启动：优先展示已经登录、能立刻出内容的音源
            sourceMode.value = if (fnosLoggedIn.value && webDavConn.value == null)
                SourceMode.FNOS else SourceMode.WEBDAV
        }
        // 收藏三表：JSON 数组字符串 → Set
        favWebDav.value = p[FAV_WEBDAV]?.let { decodeIds(it) } ?: emptySet()
        favFnos.value = p[FAV_FNOS]?.let { decodeIds(it) } ?: emptySet()
        favLocal.value = p[FAV_LOCAL]?.let { decodeIds(it) } ?: emptySet()
        // 本地音乐曲库：从 JSON 恢复扫描结果
        p[LOCAL_LIBRARY]?.let { runCatching { localLibrary.value = decodeTracks(it) } }
        // 有缓存就直接恢复索引，冷启动不再发起整库 PROPFIND
        restoreDavLibraryCache()
        CrashLogger.mark(
            "restoreSessions done (webdav=${webDavConn.value != null}, " +
                "fnos=${fnosLoggedIn.value}, theme=${themeMode.value}, source=${sourceMode.value}, " +
                "davCache=${webDavLibrary.value != null})"
        )
    }

    // ---- WebDAV persistence ----

    /** 两份配置是否共享至少一个端点：说明只是主备互换/调整，仍是同一台 NAS。 */
    private fun sharesEndpoint(a: WebDavConnection, b: WebDavConnection): Boolean {
        val aSet = setOfNotNull(a.baseUrl, a.backupBaseUrl)
        val bSet = setOfNotNull(b.baseUrl, b.backupBaseUrl)
        return aSet.any { it in bSet }
    }

    /** 保存（或更新）WebDAV 配置并连接。传 null 等价于「断开但保留配置」。 */
    suspend fun saveWebDav(conn: WebDavConnection?) {
        if (conn == null) {
            disconnectWebDav()
            return
        }
        CrashLogger.info("Settings", "保存 WebDAV ${conn.baseUrl}")
        val previous = savedWebDav.value
        savedWebDav.value = conn
        webDavConn.value = conn
        webDavClient.registerAuth(conn)

        // 只是主线路 / 备用线路互换或调整（账号、密码、根路径都没变）时，
        // 索引内容依然有效 —— 把里面的绝对地址整体换到新线路即可，不必重新全库扫描。
        val sameAccount = previous != null &&
            previous.username == conn.username &&
            previous.password == conn.password &&
            previous.rootPath == conn.rootPath &&
            sharesEndpoint(previous, conn)

        if (sameAccount) {
            val cacheBase = cachedLibraryBase()
            if (cacheBase != null) {
                rebaseWebDavLibrary(conn.baseUrl)
            } else if (previous != null && previous.baseUrl == conn.baseUrl) {
                CrashLogger.info("Settings", "仅备用线路变化，曲库索引继续复用")
            } else {
                // 旧版缓存没记录线路，改写不安全，只能重扫这一次
                CrashLogger.info("Settings", "旧缓存缺少线路记录，安排一次重新扫描")
                webDavLibrary.value = null
                clearDavLibraryCache()
                webDavAutoScanAttempted.value = false
            }
        } else {
            // 账号 / 根路径 / 全新地址：旧索引不可信，删缓存并允许自动扫描一次
            webDavLibrary.value = null
            clearDavLibraryCache()
            webDavAutoScanAttempted.value = false
        }
        dataStore.edit { p ->
            p[W_HOST] = conn.host
            p[W_PORT] = conn.port
            p[W_HTTPS] = conn.useHttps
            p[W_USER] = conn.username
            p[W_PASS] = conn.password
            p[W_PATH] = conn.rootPath
            if (conn.backupHost.isNullOrBlank()) {
                p.remove(W_BHOST); p.remove(W_BPORT); p.remove(W_BHTTPS)
            } else {
                p[W_BHOST] = conn.backupHost
                p[W_BPORT] = conn.backupPort
                p[W_BHTTPS] = conn.backupUseHttps
            }
        }
    }

    /** 断开当前会话，但保留已保存的地址 / 账号 / 密码 / 路径，下次可直接编辑重连。 */
    suspend fun disconnectWebDav() {
        CrashLogger.info("Settings", "断开 WebDAV（保留已保存配置）")
        val previous = webDavConn.value
        webDavConn.value = null
        previous?.let { authRegistry.remove(it.baseUrl) }
        webDavLibrary.value = null
        webDavScanProgress.value = ""
    }

    // ---- FnOS persistence ----

    suspend fun saveFnos(conn: FnosConnection?, token: String?) {
        if (conn == null || token.isNullOrBlank()) {
            logoutFnos()
            return
        }
        CrashLogger.info("Settings", "保存飞牛音乐 ${conn.baseUrl}")
        savedFnos.value = conn
        fnosConn.value = conn
        fnosLoggedIn.value = true
        fnosClient.setSession(conn, token)
        dataStore.edit {
            it[F_HOST] = conn.host
            it[F_PORT] = conn.port
            it[F_HTTPS] = conn.useHttps
            it[F_USER] = conn.username
            it[F_PASS] = conn.password
            it[F_TOKEN] = token
            if (conn.backupHost.isNullOrBlank()) {
                it.remove(F_BHOST); it.remove(F_BPORT); it.remove(F_BHTTPS)
            } else {
                it[F_BHOST] = conn.backupHost
                it[F_BPORT] = conn.backupPort
                it[F_BHTTPS] = conn.backupUseHttps
            }
        }
    }

    /** 退出登录只清除 token，保留地址 / 账号 / 密码，打开表单即可看到并修改。 */
    suspend fun logoutFnos() {
        CrashLogger.info("Settings", "退出飞牛音乐（保留已保存配置）")
        fnosClient.logout()
        fnosConn.value = null
        fnosLoggedIn.value = false
        dataStore.edit { it.remove(F_TOKEN) }
    }

    suspend fun setTheme(mode: ThemeMode) {
        themeMode.value = mode
        dataStore.edit { it[T_THEME] = mode.name }
    }

    /** 切换主题主色，全应用立即换色并持久化。 */
    suspend fun setAccentColor(argb: Int) {
        accentColor.value = argb and 0xFFFFFF
        dataStore.edit { it[T_ACCENT] = accentColor.value }
        CrashLogger.info("Settings", "主题色=#%06X".format(accentColor.value))
    }

    /** 切换主界面音源，立即生效并持久化。 */
    suspend fun setSource(mode: SourceMode) {
        sourceMode.value = mode
        dataStore.edit { it[S_SOURCE] = mode.name }
        CrashLogger.info("Settings", "切换音乐源=$mode（主界面只显示该音源）")
    }

    /** 切换调试模式：持久化 + 同步给 CrashLogger（开/关日志写盘）。 */
    suspend fun setDebugMode(on: Boolean) {
        debugMode.value = on
        dataStore.edit { it[D_DEBUG] = on }
        CrashLogger.setEnabled(on)
    }

    /** 设置页说明文字（副标题）显隐：持久化。 */
    suspend fun setShowDescriptions(on: Boolean) {
        showDescriptions.value = on
        dataStore.edit { it[T_SHOW_DESC] = on }
    }

    /** 设置转轮震动强度（0=关 / 1=弱 / 2=中 / 3=强）：持久化。 */
    suspend fun setHapticStrength(level: Int) {
        val v = level.coerceIn(0, 3)
        hapticStrength.value = v
        Haptics.setStrength(v)
        dataStore.edit { it[T_HAPTIC] = v }
        CrashLogger.info("Settings", "震动强度=$v")
    }

    // ---- 收藏三表（按音源分表）----

    /** 该曲目所属音源的收藏表。M3U 归到 WebDAV。 */
    private fun favoritesOf(source: com.musicbox.nas.data.SourceType): MutableStateFlow<Set<String>> =
        when (source) {
            com.musicbox.nas.data.SourceType.FNOS -> favFnos
            com.musicbox.nas.data.SourceType.LOCAL -> favLocal
            else -> favWebDav
        }

    fun isFavorite(track: Track): Boolean = favoritesOf(track.source).value.contains(track.id)

    /** 切换收藏状态，返回切换后是否为收藏。 */
    suspend fun toggleFavorite(track: Track): Boolean {
        val table = favoritesOf(track.source)
        val next = if (table.value.contains(track.id)) table.value - track.id else table.value + track.id
        table.value = next
        val key = when (track.source) {
            com.musicbox.nas.data.SourceType.FNOS -> FAV_FNOS
            com.musicbox.nas.data.SourceType.LOCAL -> FAV_LOCAL
            else -> FAV_WEBDAV
        }
        dataStore.edit { it[key] = encodeIds(next) }
        return next.contains(track.id)
    }

    /** 当前音源的收藏 id 集合（供「我的收藏」列表读取）。 */
    fun currentFavorites(): Set<String> = when (sourceMode.value) {
        SourceMode.FNOS -> favFnos.value
        SourceMode.LOCAL -> favLocal.value
        SourceMode.WEBDAV -> favWebDav.value
    }

    /** 保存本地扫描结果并持久化。 */
    suspend fun setLocalLibrary(tracks: List<Track>) {
        localLibrary.value = tracks
        dataStore.edit { it[LOCAL_LIBRARY] = encodeTracks(tracks) }
    }

    // ---- 收藏 / 本地库 JSON 编解码 ----

    private fun encodeIds(ids: Set<String>): String =
        org.json.JSONArray(ids.toList()).toString()

    private fun decodeIds(raw: String): Set<String> = runCatching {
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun encodeTracks(tracks: List<Track>): String {
        val arr = org.json.JSONArray()
        tracks.forEach { t ->
            arr.put(org.json.JSONObject().apply {
                put("id", t.id); put("title", t.title); put("artist", t.artist)
                put("album", t.album); put("durationMs", t.durationMs)
                put("coverUrl", t.coverUrl); put("streamUrl", t.streamUrl)
                put("source", t.source.name); put("embeddedAudioUrl", t.embeddedAudioUrl)
                put("folder", t.folder); put("sampleRateHz", t.sampleRateHz)
                put("bitDepth", t.bitDepth); put("codec", t.codec)
            })
        }
        return arr.toString()
    }

    private fun decodeTracks(raw: String): List<Track> {
        val arr = org.json.JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Track(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    durationMs = o.optLong("durationMs"),
                    coverUrl = o.optString("coverUrl").ifBlank { null },
                    streamUrl = o.getString("streamUrl"),
                    source = runCatching {
                        com.musicbox.nas.data.SourceType.valueOf(o.optString("source"))
                    }.getOrDefault(com.musicbox.nas.data.SourceType.LOCAL),
                    embeddedAudioUrl = o.optString("embeddedAudioUrl").ifBlank { null },
                    folder = o.optString("folder"),
                    sampleRateHz = o.optInt("sampleRateHz"),
                    bitDepth = o.optInt("bitDepth"),
                    codec = o.optString("codec")
                )
            }.getOrNull()
        }
    }

    // ---- 设置 JSON 导出 / 导入 ----

    /**
     * 导出全部设置（含连接配置、外观、音源、栏目、音质、调试模式、扫描参数）为格式化 JSON 文本。
     * 密码 / token 一并导出（本地文件，用户自行保管）。
     */
    suspend fun exportSettings(): String {
        val p = dataStore.data.first()
        val w = savedWebDav.value
        val f = savedFnos.value
        val fToken = p[F_TOKEN].orEmpty()
        return JSONObject().apply {
            put("version", 1)
            put("theme_mode", themeMode.value.name)
            put("accent_color", "#%06X".format(accentColor.value))
            put("source_mode", sourceMode.value.name)
            put("visible_tabs", visibleTabs.value.joinToString(","))
            put("audio_quality", audioQuality.value.name)
            put("debug_mode", debugMode.value)
            put("show_descriptions", showDescriptions.value)
            put("haptic_strength", hapticStrength.value)
            put("favorites_webdav", encodeIds(favWebDav.value))
            put("favorites_fnos", encodeIds(favFnos.value))
            put("favorites_local", encodeIds(favLocal.value))
            put("local_library", encodeTracks(localLibrary.value))
            put("scan_pace_ms", scanPaceMs.value)
            put("scan_concurrency", scanConcurrency.value)
            put("webdav", JSONObject().apply {
                put("host", w?.host.orEmpty())
                put("port", w?.port ?: 0)
                put("https", w?.useHttps ?: false)
                put("user", w?.username.orEmpty())
                put("pass", w?.password.orEmpty())
                put("path", w?.rootPath ?: "/")
                put("backup_host", w?.backupHost.orEmpty())
                put("backup_port", w?.backupPort ?: 0)
                put("backup_https", w?.backupUseHttps ?: false)
            })
            put("fnos", JSONObject().apply {
                put("host", f?.host.orEmpty())
                put("port", f?.port ?: 5666)
                put("https", f?.useHttps ?: false)
                put("user", f?.username.orEmpty())
                put("pass", f?.password.orEmpty())
                put("backup_host", f?.backupHost.orEmpty())
                put("backup_port", f?.backupPort ?: 0)
                put("backup_https", f?.backupUseHttps ?: false)
                put("token", fToken)
            })
        }.toString(2)
    }

    /**
     * 从 JSON 文本导入设置并逐项正确应用 / 持久化。
     * 缺字段不报错（用当前值兜底）；解析失败抛出带说明的异常。
     */
    suspend fun importSettings(json: String) {
        val o = runCatching { JSONObject(json) }
            .getOrElse { e -> throw IllegalArgumentException("JSON 解析失败：${e.message}") }
        o.optString("theme_mode").takeIf { it.isNotBlank() }
            ?.let { runCatching { setTheme(ThemeMode.valueOf(it)) } }
        o.optString("accent_color").takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching {
                setAccentColor(raw.removePrefix("#").toInt(16) or 0xFF000000.toInt())
            } }
        o.optString("source_mode").takeIf { it.isNotBlank() }
            ?.let { runCatching { setSource(SourceMode.valueOf(it)) } }
        o.optString("visible_tabs").takeIf { it.isNotBlank() }?.let { raw ->
            val ordered = LibraryTabs.normalize(raw.split(',').map { it.trim() })
            if (ordered.isNotEmpty()) setVisibleTabs(ordered)
        }
        o.optString("audio_quality").takeIf { it.isNotBlank() }
            ?.let { runCatching { setAudioQuality(AudioQuality.valueOf(it)) } }
        if (o.has("debug_mode")) setDebugMode(o.optBoolean("debug_mode"))
        if (o.has("show_descriptions")) setShowDescriptions(o.optBoolean("show_descriptions"))
        if (o.has("haptic_strength")) setHapticStrength(o.optInt("haptic_strength"))
        if (o.has("favorites_webdav")) { favWebDav.value = decodeIds(o.optString("favorites_webdav")); dataStore.edit { it[FAV_WEBDAV] = encodeIds(favWebDav.value) } }
        if (o.has("favorites_fnos")) { favFnos.value = decodeIds(o.optString("favorites_fnos")); dataStore.edit { it[FAV_FNOS] = encodeIds(favFnos.value) } }
        if (o.has("favorites_local")) { favLocal.value = decodeIds(o.optString("favorites_local")); dataStore.edit { it[FAV_LOCAL] = encodeIds(favLocal.value) } }
        if (o.has("local_library")) runCatching { setLocalLibrary(decodeTracks(o.optString("local_library"))) }
        if (o.has("scan_pace_ms")) scanPaceMs.value = o.optLong("scan_pace_ms").coerceIn(0L, 2000L)
        if (o.has("scan_concurrency")) scanConcurrency.value = o.optInt("scan_concurrency").coerceIn(1, 48)
        // WebDAV
        o.optJSONObject("webdav")?.let { w ->
            val host = w.optString("host", "").trim()
            if (host.isNotBlank()) {
                saveWebDav(
                    WebDavConnection(
                        host = host,
                        port = w.optInt("port", 0),
                        useHttps = w.optBoolean("https", false),
                        username = w.optString("user", ""),
                        password = w.optString("pass", ""),
                        rootPath = w.optString("path", "/").ifBlank { "/" },
                        backupHost = w.optString("backup_host", "").takeIf { it.isNotBlank() },
                        backupPort = w.optInt("backup_port", 0),
                        backupUseHttps = w.optBoolean("backup_https", false)
                    )
                )
            } else disconnectWebDav()
        }
        // 飞牛音乐
        o.optJSONObject("fnos")?.let { f ->
            val host = f.optString("host", "").trim()
            val token = f.optString("token", "").trim()
            if (host.isNotBlank()) {
                saveFnos(
                    FnosConnection(
                        host = host,
                        port = f.optInt("port", 5666),
                        useHttps = f.optBoolean("https", false),
                        username = f.optString("user", ""),
                        password = f.optString("pass", ""),
                        backupHost = f.optString("backup_host", "").takeIf { it.isNotBlank() },
                        backupPort = f.optInt("backup_port", 0),
                        backupUseHttps = f.optBoolean("backup_https", false)
                    ),
                    token.ifBlank { null }
                )
            } else logoutFnos()
        }
    }
}

// 会话持久化键：顶层私有属性（Kotlin 每个类只允许一个 companion object）
private val W_HOST = stringPreferencesKey("webdav_host")
private val W_PORT = intPreferencesKey("webdav_port")
private val W_HTTPS = booleanPreferencesKey("webdav_https")
private val W_USER = stringPreferencesKey("webdav_user")
private val W_PASS = stringPreferencesKey("webdav_pass")
private val W_PATH = stringPreferencesKey("webdav_path")
private val W_BHOST = stringPreferencesKey("webdav_backup_host")
private val W_BPORT = intPreferencesKey("webdav_backup_port")
private val W_BHTTPS = booleanPreferencesKey("webdav_backup_https")
/** 索引缓存对应的线路地址；用于主备切换时判断是否需要改写 URL。 */
private val W_CACHE_BASE = stringPreferencesKey("webdav_cache_base")
private val F_HOST = stringPreferencesKey("fnos_host")
private val F_PORT = intPreferencesKey("fnos_port")
private val F_HTTPS = booleanPreferencesKey("fnos_https")
private val F_BHOST = stringPreferencesKey("fnos_backup_host")
private val F_BPORT = intPreferencesKey("fnos_backup_port")
private val F_BHTTPS = booleanPreferencesKey("fnos_backup_https")
private val F_USER = stringPreferencesKey("fnos_user")
private val F_PASS = stringPreferencesKey("fnos_pass")
private val F_TOKEN = stringPreferencesKey("fnos_token")
private val T_THEME = stringPreferencesKey("theme_mode")
/** 主题主色，存 ARGB 十进制串。 */
private val T_ACCENT = intPreferencesKey("accent_color")
private val S_PACE = longPreferencesKey("scan_pace_ms")
private val S_CONCURRENCY = longPreferencesKey("scan_concurrency")
private val S_SOURCE = stringPreferencesKey("source_mode")
private val T_TABS = stringPreferencesKey("visible_tabs")
/** 标记 visible_tabs 是否已按「有序列表」格式保存（老版本是无序 Set）。 */
private val T_TABS_ORDERED = booleanPreferencesKey("visible_tabs_ordered_v2")
private val Q_QUALITY = stringPreferencesKey("audio_quality")
private val P_VOLUME = floatPreferencesKey("wheel_volume")
private val D_DEBUG = booleanPreferencesKey("debug_mode")
private val T_SHOW_DESC = booleanPreferencesKey("show_descriptions")
private val T_HAPTIC = intPreferencesKey("haptic_strength")
/** 收藏三表：按音源分表，存 Track.id 的 JSON 数组字符串。 */
private val FAV_WEBDAV = stringPreferencesKey("favorites_webdav")
private val FAV_FNOS = stringPreferencesKey("favorites_fnos")
private val FAV_LOCAL = stringPreferencesKey("favorites_local")
/** 本地音乐曲库：扫描结果 JSON。 */
private val LOCAL_LIBRARY = stringPreferencesKey("local_library")
