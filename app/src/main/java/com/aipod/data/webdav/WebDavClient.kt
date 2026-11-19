package com.aipod.data.webdav

import com.aipod.MusicBoxApp
import com.aipod.data.AuthRegistry
import com.aipod.data.AuthType
import com.aipod.data.HostAuth
import com.aipod.data.SourceType
import com.aipod.data.Track
import com.aipod.data.m3u.M3uParser
import com.aipod.data.normalizeHost
import com.aipod.util.AUDIO_EXTENSIONS
import com.aipod.util.CrashLogger
import com.aipod.util.extensionOf
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import kotlin.math.absoluteValue
import java.net.URLEncoder

data class DavEntry(
    val name: String,
    val href: String,
    val isDirectory: Boolean,
    val size: Long,
    val contentType: String?,
    val lastModified: String?
) : java.io.Serializable

data class WebDavConnection(
    val host: String,
    val port: Int,
    val useHttps: Boolean,
    val username: String,
    val password: String,
    val rootPath: String,
    /** 备用线路：主线路连接失败时自动切换（同一账号，仅地址/端口/协议不同）。 */
    val backupHost: String? = null,
    val backupPort: Int = 0,
    val backupUseHttps: Boolean = false
) {
    val baseUrl: String
        get() = buildBase(host, port, useHttps)

    val backupBaseUrl: String?
        get() = if (backupHost.isNullOrBlank()) null else buildBase(backupHost, backupPort, backupUseHttps)

    private fun buildBase(h: String, p: Int, https: Boolean): String {
        val scheme = if (https) "https" else "http"
        val portStr = if (p in 1..65535) ":$p" else ""
        return "$scheme://${normalizeHost(h)}$portStr"
    }
}

/** 专辑：同名专辑的歌曲聚合。 */
data class DavAlbum(val name: String, val artist: String, val tracks: List<Track>) : java.io.Serializable

/** 歌手：按专辑分组展示。 */
data class DavArtist(val name: String, val albums: List<DavAlbum>, val tracks: List<Track>) : java.io.Serializable

/** WebDAV 目录里的 m3u / m3u8 歌单。 */
data class DavPlaylist(val name: String, val tracks: List<Track>) : java.io.Serializable

/** 一次全库扫描的结果。 */
data class DavLibrary(
    val tracks: List<Track>,
    val albums: List<DavAlbum>,
    val artists: List<DavArtist>,
    val playlists: List<DavPlaylist>,
    val scannedDirs: Int,
    val failedDirs: Int,
    /** 扫描被上限截断 / 触发限流时的说明，供设置页提示。 */
    val note: String? = null,
    /** 扫描期间命中服务器限流的次数。 */
    val limitedHits: Int = 0
) : java.io.Serializable {
    val isEmpty: Boolean get() = tracks.isEmpty() && playlists.isEmpty()
}

/** 一次 PROPFIND 的结果分类：成功 / 触发限流 / 其它失败。 */
private sealed interface DavReply {
    data class Ok(val entries: List<DavEntry>) : DavReply
    data class Limited(val code: Int, val retryAfterMs: Long?) : DavReply
    data class Failed(
        val code: Int,
        val body: String,
        val wwwAuthenticate: String? = null,
        val server: String? = null
    ) : DavReply
}

/** 单个目录扫描结果：条目 + 失败时的 HTTP 码（用于区分 401 认证失败）。 */
private data class DirResult(val entries: List<DavEntry>?, val failCode: Int?)

/** 树形日志用：记录每个被扫描目录的层级、归属与识别结果，扫描结束后输出整棵树。 */
private data class DirNode(
    val display: String,
    val depth: Int,
    var status: String = "OK",      // OK / RETRYING / FAILED
    var failCode: Int? = null,
    var audio: Int = 0,
    var dirs: Int = 0,
    var playlists: Int = 0
)

/** 一次 HTTP 响应的诊断摘要。 */
private data class Resp(
    val code: Int,
    val raw: String,
    val retryAfter: String?,
    val wwwAuthenticate: String?,
    val server: String?
)

/**
 * 网盘风控友好的全局限速器。同一轮扫描里的所有 PROPFIND / M3U GET 共享：
 *  - 保证任意相邻两个请求的「发起时间」间隔不低于 intervalMs（令牌桶，全局 QPS 恒定、不会突发）；
 *  - Semaphore 限制同时在飞的请求数（concurrency），即真正的并行度，慢响应不会堆积成上百并发；
 *  - 命中 429 / 503 等限流后自动把间隔翻倍，连续成功一段时间再缓慢恢复。
 *
 * 并行度由 concurrency 决定：均衡档 6 路、快速档 12 路，比旧版的固定 2 路快很多，
 * 但仍受 intervalMs 限流，且限流会自动退避，不会演变为对网盘的异常流量。
 */
private class ScanThrottle(baseIntervalMs: Long, concurrency: Int) {
    private val gate = Semaphore(concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY))
    private val mu = Mutex()
    private var lastAtNs = 0L
    private val baseMs = baseIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    private var intervalMs = baseMs
    private var successStreak = 0

    @Volatile
    var limitedHits = 0
        private set
    @Volatile
    var currentIntervalMs: Long = baseMs
        private set
    val concurrency: Int = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)

    suspend fun <T> request(block: suspend () -> T): T = gate.withPermit {
        val waitMs = mu.withLock {
            val now = System.nanoTime()
            val next = lastAtNs + intervalMs * 1_000_000L
            val wait = ((next - now) / 1_000_000L).coerceAtLeast(0L)
            lastAtNs = maxOf(now, next)
            wait
        }
        if (waitMs > 0) delay(waitMs)
        block()
    }

    /** 命中限流：立即降速（间隔翻倍，上限 2 秒）。 */
    fun onLimited() {
        limitedHits++
        successStreak = 0
        intervalMs = (intervalMs * 2).coerceAtMost(MAX_INTERVAL_MS)
        currentIntervalMs = intervalMs
    }

    /** 连续成功若干次后，往基础间隔缓慢回落。 */
    fun onOk() {
        successStreak++
        if (successStreak >= RECOVER_AFTER && intervalMs > baseMs) {
            intervalMs = (intervalMs - baseMs / 2).coerceAtLeast(baseMs)
            currentIntervalMs = intervalMs
            successStreak = 0
        }
    }

    companion object {
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 48
        // 允许「极速」档把请求间隔压到 0（STRM / 自建 openlist 无限速诉求）。
        const val MIN_INTERVAL_MS = 0L
        const val MAX_INTERVAL_MS = 2000L
        const val RECOVER_AFTER = 20
    }
}

class WebDavClient(private val okHttp: OkHttpClient, private val registry: AuthRegistry) {

    /**
     * 最近一次 [pickActiveConn] 实际选中的线路地址。
     * 扫描结果里存的是绝对 URL，必须记住它是按哪条线路生成的，
     * 否则主备切换后曲库缓存里的地址会全部指向不可达的机器。
     */
    @Volatile
    var lastActiveBaseUrl: String? = null
        private set

    fun registerAuth(conn: WebDavConnection) {
        registry.put(conn.baseUrl, HostAuth(AuthType.BASIC, conn.username, conn.password))
        conn.backupBaseUrl?.let { registry.put(it, HostAuth(AuthType.BASIC, conn.username, conn.password)) }
    }

    /**
     * 选择可用线路：先探测主线路可达性，主线路连接失败（网络层错误，非 401/403）时切到备用线路。
     * 返回的 connection 已把 host/port/https 换成可用线路，后续 resolveUrl 全部基于它，
     * 保证扫描出的歌曲 / 封面 / 播放地址都指向同一可用线路。
     */
    suspend fun pickActiveConn(conn: WebDavConnection): WebDavConnection = withContext(Dispatchers.IO) {
        if (conn.backupBaseUrl == null) {
            lastActiveBaseUrl = conn.baseUrl
            return@withContext conn
        }
        val ok = runCatching {
            val call = okHttp.newCall(
                Request.Builder().url(conn.baseUrl + conn.rootPath)
                    .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                    .header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8")
                    .header("User-Agent", USER_AGENT).build()
            )
            // 探测只为判断「通不通」，4 秒足够；默认 20 秒 connectTimeout 会让进入曲库白等很久
            call.timeout().timeout(4, TimeUnit.SECONDS)
            val r = call.execute()
            // 网络层连通即视为主线路可用；401/403 是认证问题，不是线路问题，仍走主线路
            r.code != -1
        }.getOrDefault(false)
        if (ok) {
            CrashLogger.info("WebDAV", "主线路可用：${conn.baseUrl}")
            lastActiveBaseUrl = conn.baseUrl
            conn
        } else {
            CrashLogger.info("WebDAV", "主线路不可达，切换到备用线路：${conn.backupBaseUrl}")
            lastActiveBaseUrl = conn.backupBaseUrl
            conn.copy(host = conn.backupHost!!, port = conn.backupPort, useHttps = conn.backupUseHttps)
        }
    }

    /** 把 WebDAV href 变成可直接播放的 URL（href 通常已是 percent-encoded）。 */
    fun resolveUrl(conn: WebDavConnection, href: String): String {
        if (href.startsWith("http", ignoreCase = true)) return href
        val path = if (href.startsWith("/")) href else "/$href"
        return conn.baseUrl + path
    }

    /**
     * 设置页「测试连接」：对根路径发一次 PROPFIND，给出可读结论并写详细诊断日志。
     * 用来区分「账号密码错(401)」「路径错(404)」「WebDAV 未启用(405)」等情况。
     */
    suspend fun testConnection(conn: WebDavConnection): String = withContext(Dispatchers.IO) {
        registerAuth(conn)
        // 主线路不可达时自动用备用线路测试，避免「主线路挂了但备用明明是好的」却报连接失败
        val active = pickActiveConn(conn)
        val switched = active.baseUrl != conn.baseUrl
        val url = active.baseUrl + active.rootPath
        val auth = registry.get(url)
        val hasAuth = auth != null && auth.type == AuthType.BASIC &&
            !(auth.basicUser.isNullOrBlank() && auth.basicPass.isNullOrBlank())
        CrashLogger.info(
            "WebDAV.test",
            "测试连接 PROPFIND $url；认证=" +
                (if (hasAuth) "Basic 用户=${auth?.basicUser}" else "匿名（用户名/密码均为空）")
        )
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body)
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .header("User-Agent", USER_AGENT)
            .build()
        runCatching {
            val resp = okHttp.newCall(req).execute()
            resp.use { r ->
                val raw = r.body?.string().orEmpty()
                CrashLogger.info(
                    "WebDAV.test",
                    "响应 HTTP ${r.code}；WWW-Authenticate=${r.header("WWW-Authenticate") ?: "（无）"}；" +
                        "Server=${r.header("Server") ?: "（无）"}；响应体=${raw.take(200).ifBlank { "（空）" }}"
                )
                when {
                    r.code in 200..299 -> {
                        val n = runCatching { parse(raw) }
                            .getOrDefault(runCatching { fallbackParse(raw) }.getOrDefault(emptyList())).size
                        "✓ 连接成功（HTTP ${r.code}，根目录返回 $n 个条目）" +
                            (if (switched) "。注：主线路不可达，已自动使用备用线路 ${active.baseUrl}" else "") +
                            "。现在可以扫描音乐库了。"
                    }
                    r.code == 401 -> "✗ 认证失败（HTTP 401）。" +
                        (if (!hasAuth)
                            "当前用户名/密码为空，服务器要求登录，请在「编辑 WebDAV 连接」中填写。"
                        else
                            "已发送 Basic 认证（用户 ${auth?.basicUser}）但被拒绝。AList / OpenList 要填后台登录账号密码（不是网盘账号）；部分版本需在 AList 设置里单独设置 WebDAV 密码。")
                    r.code == 403 -> "✗ 无权限（HTTP 403）。账号可能正确但无权访问该路径，或网盘触发了风控，请稍后再试。"
                    r.code == 404 -> "✗ 路径不存在（HTTP 404）。AList 根路径必须以 /dav 开头，例如 /dav 或 /dav/音乐。"
                    r.code == 405 -> "✗ 方法不被允许（HTTP 405）。请确认 AList 已启用 WebDAV 中间件，根路径不要写成 /dav/files。"
                    r.code == 429 -> "✗ 触发限流（HTTP 429）。请稍后再试，并把扫描节奏调为「温和」。"
                    else -> "✗ HTTP ${r.code}：${raw.take(120).ifBlank { "无响应内容" }}"
                }
            }
        }.getOrElse {
            CrashLogger.error("WebDAV.test", it)
            "✗ 无法连接：${it.message ?: it::class.java.simpleName}。请检查地址、端口、是否同一局域网。"
        }
    }

    /** 发一次 PROPFIND，并把结果归类为成功 / 限流 / 其它失败。 */
    private suspend fun propfindOnce(conn: WebDavConnection, path: String): DavReply =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = conn.baseUrl + path
                val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
                val req = Request.Builder().url(url).method("PROPFIND", body)
                    // 逐层扫描，绝不用 Depth: infinity（多数网盘直接拒绝或判为异常流量）
                    .header("Depth", "1")
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .header("User-Agent", USER_AGENT)
                    .build()
                val callResp = okHttp.newCall(req).execute()
                // 失败时也读响应体与认证响应头，便于日志里直接定位 401 的原因
                val resp = callResp.use { r ->
                    Resp(
                        r.code, r.body?.string().orEmpty(), r.header("Retry-After"),
                        r.header("WWW-Authenticate"), r.header("Server")
                    )
                }
                if (resp.code == 401) logAuthFailure(url, resp)
                if (resp.code !in 200..299) {
                    if (isRateLimited(resp.code, resp.raw))
                        DavReply.Limited(resp.code, parseRetryAfter(resp.retryAfter))
                    else DavReply.Failed(resp.code, resp.raw, resp.wwwAuthenticate, resp.server)
                } else {
                    val parsed = runCatching { parse(resp.raw) }.getOrElse {
                        CrashLogger.error("WebDAV.parseDir:$path", it)
                        emptyList()
                    }
                    if (parsed.isNotEmpty()) {
                        DavReply.Ok(parsed)
                    } else {
                        // XML 解析成功却一个 response 都没抽到时再正则兜底，避免静默 0 文件
                        val fb = runCatching { fallbackParse(resp.raw) }.getOrDefault(emptyList())
                        if (fb.isNotEmpty()) {
                            CrashLogger.info("WebDAV", "$path 主解析无条目，正则兜底得到 ${fb.size} 条")
                        } else {
                            CrashLogger.info("WebDAV", "$path 未解析出任何条目（响应 ${resp.raw.length} 字符）")
                        }
                        DavReply.Ok(fb)
                    }
                }
            }.getOrElse {
                CrashLogger.error("WebDAV.listDir:$path", it)
                DavReply.Failed(-1, it.message.orEmpty())
            }
        }

    /** 401 时把「请求是否带认证、账号是否为空、服务器挑战方式」写进日志，便于定位。 */
    private fun logAuthFailure(url: String, resp: Resp) {
        val a = registry.get(url)
        val desc = when {
            a == null -> "未注册认证（AuthRegistry 无此主机）"
            a.type != AuthType.BASIC -> "认证类型=${a.type}（非 Basic）"
            a.basicUser.isNullOrBlank() && a.basicPass.isNullOrBlank() ->
                "匿名请求（用户名与密码均为空，未发送 Authorization 头）"
            else -> "Basic 用户=${a.basicUser}；密码=${if (a.basicPass.isNullOrBlank()) "（空）" else "已填写"}"
        }
        CrashLogger.info(
            "WebDAV.auth",
            "HTTP 401 认证失败：URL=$url；请求认证=$desc；" +
                "WWW-Authenticate=${resp.wwwAuthenticate ?: "（无）"}；Server=${resp.server ?: "（无）"}；" +
                "响应体=${resp.raw.take(200).ifBlank { "（空）" }}"
        )
    }

    /**
     * 单个目录的 PROPFIND：所有请求经全局限速器排队；命中限流时降速并指数退避重试，
     * 网络异常(-1)/5xx 等瞬时错误也退避重试；认证(401)/权限(403)失败不重试直接放弃。
     * 这样扫描过程中偶发的超时或服务器抖动不会导致某个目录（及其整棵子树）被静默丢弃。
     */
    private suspend fun listSafe(
        conn: WebDavConnection,
        path: String,
        throttle: ScanThrottle,
        onNote: suspend (String) -> Unit
    ): DirResult = withContext(Dispatchers.IO) {
        var waitMs = 0L
        var lastCode: Int? = null
        for (attempt in 0..MAX_RETRY) {
            if (attempt > 0) delay(waitMs)
            val reply = throttle.request { propfindOnce(conn, path) }
            when (reply) {
                is DavReply.Ok -> {
                    throttle.onOk()
                    return@withContext DirResult(reply.entries, null)
                }
                is DavReply.Limited -> {
                    throttle.onLimited()
                    lastCode = reply.code
                    if (attempt == MAX_RETRY) {
                        CrashLogger.info(
                            "WebDAV",
                            "放弃目录 $path：持续限流 HTTP ${reply.code}，已退避重试 $MAX_RETRY 次"
                        )
                        return@withContext DirResult(null, reply.code)
                    }
                    waitMs = reply.retryAfterMs ?: backoffMs(attempt)
                    CrashLogger.info(
                        "WebDAV",
                        "$path 命中限流 HTTP ${reply.code}，退避 ${waitMs}ms 后重试（第 ${attempt + 1}/$MAX_RETRY 次）；" +
                            "后续请求间隔已自动降速至 ${throttle.currentIntervalMs}ms"
                    )
                    onNote("服务器限流，已自动降速，${waitMs}ms 后重试…")
                }
                is DavReply.Failed -> {
                    lastCode = reply.code
                    // 401 认证失败：重试无意义，立即放弃
                    if (reply.code == 401) {
                        CrashLogger.info("WebDAV", "放弃目录 $path：HTTP 401 认证失败（请检查账号密码）")
                        return@withContext DirResult(null, 401)
                    }
                    // 403 纯权限错误：重试通常无效，放弃
                    if (reply.code == 403) {
                        CrashLogger.info("WebDAV", "放弃目录 $path：HTTP 403 无权限")
                        return@withContext DirResult(null, 403)
                    }
                    // 其它（网络异常 -1、5xx 超时等）视为瞬时，降速退避重试
                    throttle.onLimited()
                    if (attempt == MAX_RETRY) {
                        CrashLogger.info(
                            "WebDAV",
                            "放弃目录 $path：瞬时错误 HTTP ${reply.code} 重试 $MAX_RETRY 次仍失败：${reply.body.take(120)}"
                        )
                        return@withContext DirResult(null, reply.code)
                    }
                    waitMs = backoffMs(attempt)
                    CrashLogger.info(
                        "WebDAV",
                        "$path 请求失败 HTTP ${reply.code}（瞬时/超时），${waitMs}ms 后重试（第 ${attempt + 1}/$MAX_RETRY 次）"
                    )
                    onNote("目录请求失败(HTTP ${reply.code})，${waitMs}ms 后重试…")
                }
            }
        }
        DirResult(null, lastCode)
    }

    /** 连接校验用：保留抛异常版本，便于给出明确错误。 */
    suspend fun list(conn: WebDavConnection, path: String): List<DavEntry> = withContext(Dispatchers.IO) {
        registerAuth(conn)
        val url = conn.baseUrl + path
        CrashLogger.info("WebDAV", "PROPFIND $url")
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).method("PROPFIND", body)
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .build()
        val resp = okHttp.newCall(req).execute()
        val raw = resp.body?.string().orEmpty()
        if (resp.code !in 200..299) {
            throw Exception("HTTP ${resp.code} ${resp.message}：${raw.take(160).ifBlank { "无响应内容" }}。请检查地址、端口、账号密码与根路径。")
        }
        val entries = runCatching { parse(raw) }.getOrElse {
            CrashLogger.error("WebDAV.parse", it)
            runCatching { fallbackParse(raw) }.getOrDefault(emptyList())
        }
        CrashLogger.info("WebDAV", "HTTP ${resp.code}，返回 ${entries.size} 个条目")
        entries
    }

    /**
     * 递归扫描「整个」WebDAV 音乐库（广度优先逐层下钻所有子目录，直到没有新目录），
     * 自动识别音频文件并按「所有歌曲 / 专辑 / 歌手 / 歌单」归类。
     *
     * 并行扫描：concurrency 路 PROPFIND 真正并发（均衡 6 路 / 快速 12 路），配合全局限速
     * 间隔，既快又不会突发蜂拥；命中 429/503 等限流会指数退避重试并自动降速。
     * onBatch 在每处理完一批目录后回调，把「已找到的歌曲快照」传出去，供界面实时展示。
     */
    suspend fun scanLibrary(
        conn: WebDavConnection,
        paceMs: Long = DEFAULT_PACE_MS,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: (String) -> Unit = {},
        onBatch: (List<Track>) -> Unit = {}
    ): DavLibrary = withContext(Dispatchers.IO) {
        registerAuth(conn)
        // 主备线路：先探测主线路，不可达则整轮扫描都走备用线路（地址/封面/播放统一）
        var conn = pickActiveConn(conn)
        suspend fun report(msg: String) {
            withContext(Dispatchers.Main) { runCatching { onProgress(msg) } }
        }

        val tracks = mutableListOf<Track>()
        val playlists = mutableListOf<DavPlaylist>()
        // 工作队列（BFS）：目录出队即扫描；失败（非认证/权限）在限次内重新入队重试，
        // 避免偶发超时 / 服务器抖动导致某个目录及其整棵子树被静默丢弃（旧版「只扫到一部分」的根因）。
        val visited = LinkedHashSet<String>()
        val dirDepth = HashMap<String, Int>()
        val retryCount = HashMap<String, Int>()
        val audioExtCount = LinkedHashMap<String, Int>()
        val otherExtCount = LinkedHashMap<String, Int>()
        /** 树形日志：每个被扫描目录的归属与识别结果，扫描结束后输出整棵树。 */
        val dirInfo = LinkedHashMap<String, DirNode>()
        /** 父子关系：dirKey -> 父 dirKey（根为 null），用于还原目录树。 */
        val parentOf = LinkedHashMap<String, String?>()
        val queue: ArrayDeque<String> = ArrayDeque<String>().also { it.add(conn.rootPath) }
        val rootKey = dirKey(conn.rootPath)
        visited.add(rootKey)
        dirDepth[rootKey] = 0
        parentOf[rootKey] = null
        var failed = 0
        val throttle = ScanThrottle(paceMs, concurrency)
        var truncatedNote: String? = null
        var authFailures = 0
        var lastEmitted = 0

        CrashLogger.info(
            "WebDAV",
            "开始递归全库扫描（逐层下钻所有子目录 / 并行 $concurrency 路）：根目录=${conn.rootPath}，服务地址=${conn.baseUrl}，" +
                "目录上限=$MAX_DIRS，层级上限=$MAX_DEPTH，" +
                "请求间隔=${throttle.currentIntervalMs}ms，并行=${throttle.concurrency}，" +
                "PROPFIND Depth=1（不使用 infinity）"
        )
        report("正在并行递归扫描全部目录…")

        while (queue.isNotEmpty() && dirInfo.size < MAX_DIRS) {
            // 每次从队列取出一批目录真正并发 PROPFIND；队列本身逐层下钻所有子目录，保证不漏扫。
            val chunk = (1..minOf(BATCH_SIZE, queue.size)).map { queue.removeFirst() }
            if (chunk.isEmpty()) break
                val results = coroutineScope {
                    chunk.map { p -> async { p to listSafe(conn, p, throttle, ::report) } }.awaitAll()
                }
                for ((p, result) in results) {
                    val k = dirKey(p)
                    // 首次出队时登记该目录节点（用于树形日志；重试不会重复登记）
                    val node = dirInfo.getOrPut(k) { DirNode(pathOnly(p), dirDepth[k] ?: 0) }
                    val entries = result.entries
                    if (entries == null) {
                        if (result.failCode == 401) authFailures++
                        // 瞬时失败（网络/5xx/限流重试耗尽）在限次内重新入队，避免丢整棵子树；
                        // 认证(401)/权限(403)失败直接放弃。
                        val rc = retryCount.getOrDefault(k, 0)
                        val canRetry = result.failCode != 401 && result.failCode != 403 &&
                            rc < MAX_RETRY && (dirDepth[k] ?: 0) < MAX_DEPTH
                        if (canRetry) {
                            retryCount[k] = rc + 1
                            node.status = "RETRYING"
                            node.failCode = result.failCode
                            queue.addLast(p)
                            CrashLogger.info("WebDAV", "目录 $p 暂时失败(HTTP ${result.failCode})，重新入队重试（${rc + 1}/$MAX_RETRY）")
                        } else {
                            node.status = "FAILED"
                            node.failCode = result.failCode
                            if (result.failCode != null && result.failCode != 401 && result.failCode != 403) {
                                failed++
                                CrashLogger.info("WebDAV", "目录 $p 重试 $MAX_RETRY 次仍失败，放弃：${result.failCode}")
                            }
                        }
                        continue
                    }
                    // 目录内常见的封面图（cover.jpg / folder.png / front.webp …）：
                    // 作为该目录下所有音频与歌单歌曲的专辑封面，用于列表左侧缩略图。
                    // displayname 常被 NAS 返回为空或非文件名，href 末段也参与判定。
                    val coverUrl = entries.firstOrNull { e ->
                        !(e.isDirectory || e.href.trimEnd().endsWith("/")) &&
                            (isCoverFile(e.name) || isCoverFile(nameFromHref(e.href)))
                    }?.let { resolveUrl(conn, it.href) }
                    var dirAudio = 0
                    var dirPlaylists = 0
                    var dirDirs = 0
                    val otherSample = mutableListOf<String>()
                    // 先顺序分类：目录入队 / 类型统计涉及共享的队列与去重集合，必须串行；
                    // 音频与歌单条目先收集，稍后并行解析标签，避免逐个 await 退化成串行（STRM 尤其慢）。
                    val audioEntries = mutableListOf<DavEntry>()
                    val playlistEntries = mutableListOf<DavEntry>()
                    for (e in entries) {
                        val ep = dirKey(e.href)
                        if (ep.isEmpty() || ep == pathOnly(p)) continue // PROPFIND 会返回自身
                        val isDir = e.isDirectory || e.href.trimEnd().endsWith("/")
                        if (isDir) {
                            dirDirs++
                            val parentDepth = dirDepth[k] ?: 0
                            if (parentDepth < MAX_DEPTH && visited.add(ep)) {
                                dirDepth[ep] = parentDepth + 1
                                parentOf[ep] = k
                                queue.addLast(hrefToPath(conn, e.href))
                            }
                            continue
                        }
                        // 关键：文件类型以 href 里的真实文件名为主、displayname 为辅
                        val ext = entryExt(e)
                        when {
                            ext == "m3u" || ext == "m3u8" -> {
                                dirPlaylists++
                                playlistEntries.add(e)
                            }
                            ext != null && (AUDIO_EXTENSIONS.contains(ext) || ext == "strm") -> {
                                dirAudio++
                                if (ext != "strm") audioExtCount[ext] = (audioExtCount[ext] ?: 0) + 1
                                else audioExtCount["strm"] = (audioExtCount["strm"] ?: 0) + 1
                                audioEntries.add(e)
                            }
                            else -> {
                                if (ext != null) otherExtCount[ext] = (otherExtCount[ext] ?: 0) + 1
                                if (otherSample.size < 6) {
                                    otherSample.add("${nameFromHref(e.href)}(${ext ?: "无扩展名"})")
                                }
                            }
                        }
                    }
                    // 并行解析本目录内的音频 / STRM（每首 1~2 个请求：STRM 读文件 + 目标标签）。
                    // 真实并发度仍由 ScanThrottle（Semaphore + 间隔）统一约束，不会失控；
                    // 这里只是把原本「一首接一首」的串行等待改成并发，歌曲顺序仍按原条目顺序回填。
                    if (audioEntries.isNotEmpty()) {
                        val built = coroutineScope {
                            audioEntries.map { e ->
                                async { runCatching { buildTrack(conn, e, coverUrl, throttle) }.getOrNull() }
                            }.awaitAll()
                        }
                        for (t in built) if (t != null) tracks.add(t)
                    }
                    if (playlistEntries.isNotEmpty()) {
                        val builtPls = coroutineScope {
                            playlistEntries.map { e ->
                                async { runCatching { readPlaylist(conn, e, throttle) }.getOrNull() }
                            }.awaitAll()
                        }
                        for (pl in builtPls) {
                            if (pl == null) continue
                            val filled = if (coverUrl != null)
                                pl.copy(tracks = pl.tracks.map { t -> if (t.coverUrl == null) t.copy(coverUrl = coverUrl) else t })
                            else
                                // 目录无 cover 图：让每首歌在展示时尝试提取文件内嵌封面
                                pl.copy(tracks = pl.tracks.map { t ->
                                    if (t.embeddedAudioUrl == null) t.copy(embeddedAudioUrl = t.streamUrl) else t
                                })
                            playlists.add(filled)
                        }
                    }
                    node.audio = dirAudio
                    node.dirs = dirDirs
                    node.playlists = dirPlaylists
                    node.status = "OK"
                    CrashLogger.info(
                        "WebDAV.dir",
                        "$p → ${entries.size} 条目 / 子目录 $dirDirs / 音频 $dirAudio / 歌单 $dirPlaylists" +
                            (if (otherSample.isNotEmpty()) " / 未识别样本=${otherSample.joinToString(", ")}" else "")
                    )
                }
            // 每批目录处理完，把「已找到的歌曲快照」回传，界面就能实时滚动显示
            if (tracks.size > lastEmitted) {
                runCatching { onBatch(tracks.toList()) }
                lastEmitted = tracks.size
            }
            report(
                "已并行递归扫描 ${dirInfo.size} 个目录 · 找到 ${tracks.size} 首歌曲 · ${playlists.size} 个歌单 · 队列剩余 ${queue.size}" +
                    (if (throttle.limitedHits > 0) " · 已自动降速 ${throttle.limitedHits} 次" else "") +
                    (if (authFailures > 0) " · ⚠ 认证失败(401)，请检查账号密码" else "")
            )
        }

        // 触顶时明确告知，避免出现「歌不全」却没有任何提示
        if (queue.isNotEmpty()) {
            truncatedNote = if (dirInfo.size >= MAX_DIRS)
                "已达到单次扫描目录上限 $MAX_DIRS，还有约 ${queue.size} 个目录未扫描；可把根路径改到更具体的音乐目录后重新扫描"
            else
                "已达到目录层级上限 $MAX_DEPTH，还有约 ${queue.size} 个目录未扫描"
            CrashLogger.info("WebDAV", "警告：$truncatedNote")
        }

        // —— 树形目录日志：把整棵扫描树以缩进树形写进日志，
        //    一眼看出哪些目录被扫到、哪些缺失 / 失败 / 有目录但 0 首歌（便于定位漏扫根因）。 ——
        run {
            val childrenMap = LinkedHashMap<String, MutableList<String>>()
            val roots = mutableListOf<String>()
            for (key in dirInfo.keys) {
                val pk = parentOf[key]
                if (pk == null || pk !in dirInfo) roots.add(key)
                else childrenMap.getOrPut(pk) { mutableListOf() }.add(key)
            }
            roots.sortBy { dirInfo[it]!!.display }
            for (v in childrenMap.values) v.sortBy { dirInfo[it]!!.display }

            fun fmt(info: DirNode): String {
                val tags = buildList {
                    if (info.audio > 0) add("♪${info.audio}")
                    if (info.dirs > 0) add("dir${info.dirs}")
                    if (info.playlists > 0) add("m3u${info.playlists}")
                }.joinToString(" ")
                val st = when (info.status) {
                    "OK" -> ""
                    "RETRYING" -> " ★重试中"
                    else -> " ✗失败(HTTP ${info.failCode})"
                }
                return (if (tags.isNotEmpty()) "  [$tags]" else "") + st
            }

            fun StringBuilder.render(key: String, prefix: String, isLast: Boolean) {
                val info = dirInfo[key]!!
                val isRoot = parentOf[key] == null
                val name = if (isRoot) info.display.ifBlank { "/" } else info.display.substringAfterLast('/')
                val branch = if (isLast) "└─ " else "├─ "
                appendLine("$prefix$branch$name${fmt(info)}")
                val kids = childrenMap[key] ?: return
                val childPrefix = prefix + if (isLast) "   " else "│  "
                kids.forEachIndexed { i, c -> render(c, childPrefix, i == kids.lastIndex) }
            }

            val sb = StringBuilder()
            sb.appendLine("======== WebDAV 扫描目录树（共 ${dirInfo.size} 个目录，失败 $failed，认证失败 $authFailures）========")
            roots.forEachIndexed { i, r -> sb.render(r, "", i == roots.lastIndex) }
            CrashLogger.info("WebDAV.tree", sb.toString().trimEnd())
        }

        val sorted = tracks.sortedWith(
            compareBy<Track> { it.artist.lowercase() }
                .thenBy { it.album.lowercase() }
                .thenBy { it.title.lowercase() }
        )

        // 按「专辑名 + 专辑所在文件夹」聚合。
        // 之前用「歌手 + 专辑」作 key：同一文件夹里放的是合集/不同歌手的歌时，同一张专辑会被
        // 按歌手拆成多个同名条目（列表里看着就是「重复的专辑」）。现在改为以专辑名为主键，
        // 只要在同一目录就合并为一张；歌手不唯一时标为「群星」。
        fun albumFolder(t: Track): String {
            val dir = t.folder
            val parent = dir.substringBeforeLast('/', "")
            val leaf = dir.substringAfterLast('/')
            // ".../专辑/CD1" 与 ".../专辑/CD2" 归到同一张专辑
            return if (DISC_RE.matches(leaf)) parent else dir
        }
        val albumMap = LinkedHashMap<String, MutableList<Track>>()
        for (t in sorted) {
            val key = "${t.album.lowercase()}\u0001${albumFolder(t)}"
            albumMap.getOrPut(key) { mutableListOf() }.add(t)
        }
        val albums = albumMap.map { (_, list) ->
            val album = list.firstOrNull()?.album ?: "未知专辑"
            val names = list.map { it.artist }.filter { it.isNotBlank() }.distinct()
            val artist = when {
                names.size == 1 -> names.first()
                names.isEmpty() -> "未知歌手"
                else -> "群星"
            }
            DavAlbum(album, artist, list)
        }.sortedWith(compareBy<DavAlbum> { it.name.lowercase() })

        val artists = sorted.groupBy { it.artist }
            .map { (name, list) ->
                val byAlbum = LinkedHashMap<String, MutableList<Track>>()
                list.forEach { byAlbum.getOrPut(it.album) { mutableListOf() }.add(it) }
                DavArtist(
                    name = name,
                    albums = byAlbum.map { (a, ts) -> DavAlbum(a, name, ts) }
                        .sortedWith(compareBy<DavAlbum> { it.name.lowercase() }),
                    tracks = list
                )
            }
            .sortedWith(compareBy<DavArtist> { it.name.lowercase() })

        CrashLogger.info(
            "WebDAV",
            "扫描完成：目录=${dirInfo.size}（失败 $failed）歌曲=${sorted.size}" +
                "（Hi-Res ${sorted.count { it.isHiRes }}） " +
                "专辑=${albums.size} 歌手=${artists.size} 歌单=${playlists.size}；" +
                "限流命中=${throttle.limitedHits}，最终请求间隔=${throttle.currentIntervalMs}ms"
        )
        CrashLogger.info(
            "WebDAV",
            "识别到的音频格式=" + audioExtCount.entries
                .sortedByDescending { it.value }
                .joinToString { "${it.key}:${it.value}" }.ifBlank { "（无）" }
        )
        if (otherExtCount.isNotEmpty()) {
            CrashLogger.info(
                "WebDAV",
                "未识别扩展名=" + otherExtCount.entries
                    .sortedByDescending { it.value }.take(20)
                    .joinToString { "${it.key}:${it.value}" }
            )
        }
        if (sorted.isEmpty()) {
            CrashLogger.info(
                "WebDAV",
                "警告：扫描结果为 0 首歌曲。请检查根路径、PROPFIND 是否返回文件条目，" +
                    "以及上方 WebDAV.dir 日志中的 HTTP 状态与未识别样本。"
            )
        }
        val authNote = if (authFailures > 0)
            "认证失败（HTTP 401，共 $authFailures 个目录）：请在「设置 → 连接管理」检查 WebDAV 用户名和密码。" +
                "AList / OpenList 要填后台登录账号密码（不是网盘账号），部分版本还需在 AList 里单独设置 WebDAV 密码。" +
                "可先点「测试连接 / 诊断 401」查看服务器返回的认证方式"
        else null
        val combinedNote = listOfNotNull(
            authNote,
            truncatedNote,
            if (throttle.limitedHits > 0)
                "扫描期间命中服务器限流 ${throttle.limitedHits} 次，已自动降速并退避重试" else null
        ).joinToString("；").ifBlank { null }
        DavLibrary(sorted, albums, artists, playlists, dirInfo.size, failed, combinedNote, throttle.limitedHits)
    }

    /** 构建一首 WebDAV 歌曲：先从文件名/路径推断，再用文件内嵌 metadata 补全歌手/专辑/时长。 */
    private suspend fun buildTrack(
        conn: WebDavConnection,
        entry: DavEntry,
        coverUrl: String?,
        throttle: ScanThrottle
    ): Track {
        val segs = pathOnly(entry.href).trim('/').split('/').filter { it.isNotEmpty() }
        val rootSegs = pathOnly(conn.rootPath).trim('/').split('/').filter { it.isNotEmpty() }
        val rel = if (rootSegs.isNotEmpty() && segs.take(rootSegs.size) == rootSegs)
            segs.drop(rootSegs.size) else segs
        // 所在目录（不含文件名），用于「按文件夹」浏览与专辑归属判断
        val folder = pathOnly(entry.href).substringBeforeLast('/', "")

        val ext = entryExt(entry)
        // .strm（Kodi 风格）文件内容是一行可播放地址，当作远程音频处理
        if (ext == "strm") return buildStrmTrack(conn, entry, coverUrl, throttle, folder, rel)

        val info = inferFromPath(rel, entry.name)
        val audioUrl = resolveUrl(conn, entry.href)
        // 读取文件内嵌 metadata（标题/歌手/专辑/时长），内嵌信息优先于路径推断
        val meta = runCatching { fetchMetadata(conn, entry, throttle) }.getOrNull()
        val title = meta?.title?.takeIf { it.isNotBlank() } ?: info.title
        val artist = meta?.artist?.takeIf { it.isNotBlank() } ?: info.artist ?: "未知歌手"
        val album = meta?.album?.takeIf { it.isNotBlank() } ?: info.album ?: "未知专辑"
        val dur = meta?.durationMs ?: 0L

        return Track(
            id = "webdav:$audioUrl",
            title = title,
            artist = artist,
            album = album,
            durationMs = dur,
            coverUrl = coverUrl,
            streamUrl = audioUrl,
            source = SourceType.WEBDAV,
            // 目录有 cover 图时优先用（快、不占带宽）；没有时才在线提取文件内嵌封面
            embeddedAudioUrl = if (coverUrl == null) audioUrl else null,
            folder = folder,
            sampleRateHz = meta?.sampleRateHz ?: 0,
            bitDepth = meta?.bitDepth ?: 0,
            codec = meta?.codec ?: codecLabel(ext)
        )
    }

    /** 路径推断结果：标题取自文件名，歌手/专辑取自上级目录。 */
    private data class PathInfo(val title: String, val artist: String?, val album: String?)

    /**
     * 从相对路径层级与文件名推断标题 / 歌手 / 专辑。
     * 仅作为内嵌 metadata 缺失时的兜底，保证「有文件就至少有歌名」。
     */
    private fun inferFromPath(rel: List<String>, fallbackName: String): PathInfo {
        val fileName = rel.lastOrNull() ?: fallbackName
        val base = fileName.substringBeforeLast('.').ifBlank { fileName }
        // 去掉 "01 - " / "01. " 这类音轨号前缀
        val withoutNo = base.replace(Regex("""^\d{1,3}\s*[-._]\s*"""), "").trim()
        val split = if (withoutNo.contains(" - ")) withoutNo.split(" - ", limit = 2) else null
        val title = (split?.getOrNull(1) ?: withoutNo).trim().ifBlank { base }
        val artistFromFile = split?.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }

        var albumFromPath = rel.getOrNull(rel.size - 2)
        var artistFromPath = rel.getOrNull(rel.size - 3)
        // 常见 ".../专辑/CD1/01.flac"：把 CD1 当作盘号而非专辑
        if (albumFromPath != null && DISC_RE.matches(albumFromPath)) {
            artistFromPath = rel.getOrNull(rel.size - 4) ?: artistFromPath
            albumFromPath = rel.getOrNull(rel.size - 3) ?: albumFromPath
        }
        // 顶层分类目录（音乐/Music）不作为专辑名
        if (albumFromPath != null && GENERIC_DIRS.contains(albumFromPath.lowercase())) albumFromPath = null
        if (artistFromPath != null && GENERIC_DIRS.contains(artistFromPath.lowercase())) artistFromPath = null
        return PathInfo(title, artistFromFile ?: artistFromPath, albumFromPath)
    }

    /** .strm 文件：内容第一行（去掉 # 注释）即播放地址。 */
    private suspend fun buildStrmTrack(
        conn: WebDavConnection,
        entry: DavEntry,
        coverUrl: String?,
        throttle: ScanThrottle,
        folder: String,
        rel: List<String>
    ): Track {
        val url = resolveUrl(conn, entry.href)
        val raw = runCatching {
            val req = Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build()
            val resp = throttle.request { okHttp.newCall(req).execute() }
            if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
        }.getOrDefault("").lineSequence().map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }.orEmpty()

        val inner = resolveStrmTarget(conn, url, raw)
        val info = inferFromPath(rel, entry.name)
        val innerExt = inner.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 }

        // STRM 本身没有标签，元数据都在它指向的媒体文件里：
        // 同源路径会被还原成绝对 URL（带 Basic 认证），外部 CDN 直接按 Range 拉头部解析。
        val meta = if (inner.isBlank() || isLocalStrmPath(inner)) null
        else runCatching { fetchMetadataByUrl(inner, innerExt, throttle) }.getOrNull()

        val title = meta?.title?.takeIf { it.isNotBlank() } ?: info.title
        val artist = meta?.artist?.takeIf { it.isNotBlank() } ?: info.artist
        val album = meta?.album?.takeIf { it.isNotBlank() } ?: info.album
        CrashLogger.info(
            "WebDAV",
            "STRM $url → 目标 ${inner.ifBlank { "(空)" }}；" +
                "标签=${if (meta != null && !meta.blank()) "已读取" else "无（回退目录推断）"}"
        )
        return Track(
            id = "webdav:strm:$inner",
            title = title,
            artist = artist ?: "未知歌手",
            album = album ?: "未知专辑",
            durationMs = meta?.durationMs ?: 0L,
            coverUrl = coverUrl,
            streamUrl = inner.ifBlank { url },
            source = SourceType.WEBDAV,
            // 封面：目录无 cover 图时，直接去目标媒体文件里取内嵌封面
            embeddedAudioUrl = if (coverUrl == null && inner.isNotBlank() && !isLocalStrmPath(inner))
                inner else null,
            folder = folder,
            sampleRateHz = meta?.sampleRateHz ?: 0,
            bitDepth = meta?.bitDepth ?: 0,
            codec = meta?.codec?.takeIf { it.isNotBlank() } ?: codecLabel(innerExt)
        )
    }

    /** STRM 指向本机磁盘（NAS 上的路径挪到手机上无意义），这类无法读取标签。 */
    private fun isLocalStrmPath(target: String): Boolean {
        if (target.startsWith("file://", ignoreCase = true)) return true
        if (Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(target)) return true
        val lower = target.lowercase()
        return LOCAL_PATH_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * 把 STRM 里的地址还原成可直接请求的绝对 URL：
     * - http(s) 原样返回；
     * - 以 / 开头视为同一台 WebDAV 服务器上的绝对路径；
     * - 其余按 STRM 文件所在目录解析相对路径。
     */
    private fun resolveStrmTarget(conn: WebDavConnection, strmUrl: String, target: String): String {
        val t = target.trim()
        if (t.isBlank()) return ""
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        if (t.startsWith("file://", true) || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(t)) return t
        val path = t.removePrefix("./")
        if (path.startsWith("/")) return conn.baseUrl + path
        val dir = strmUrl.substringBeforeLast('/', "")
        return "$dir/${path}"
    }

    /** 内嵌 metadata 解析结果（含音频规格，用于 Hi-Res 识别）。 */
    private data class TrackMeta(
        val title: String?, val artist: String?, val album: String?, val durationMs: Long,
        val sampleRateHz: Int = 0, val bitDepth: Int = 0, val codec: String = ""
    ) {
        fun blank() = title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()
    }

    /** 音频规格：采样率 / 位深 / 编码标签。 */
    private data class AudioSpec(val sampleRateHz: Int, val bitDepth: Int, val codec: String)

    /**
     * 下载音频文件头部（最多 1MB）到临时文件，用 MediaMetadataRetriever 提取内嵌
     * 标题/歌手/专辑/时长。M4A/MP4 的元数据可能在文件尾部，必要时再补拉尾部 512KB。
     * 所有下载都经限速器，避免对 NAS 突发请求。
     */
    private suspend fun fetchMetadata(
        conn: WebDavConnection,
        entry: DavEntry,
        throttle: ScanThrottle
    ): TrackMeta = fetchMetadataByUrl(resolveUrl(conn, entry.href), entryExt(entry), throttle)

    /** 同 [fetchMetadata]，但直接给定绝对地址（STRM 指向的外部/内部音频也走这里）。 */
    private suspend fun fetchMetadataByUrl(
        url: String,
        ext: String?,
        throttle: ScanThrottle
    ): TrackMeta {
        val front = downloadCapped(url, throttle, 1_048_576, tail = false)
        var m = front?.let { parseMeta(it, ext).also { _ -> runCatching { it.delete() } } }
        if ((m == null || m.blank()) && (ext == "m4a" || ext == "mp4" || ext == "aac")) {
            val back = downloadCapped(url, throttle, 524_288, tail = true)
            val m2 = back?.let { parseMeta(it, ext).also { _ -> runCatching { it.delete() } } }
            if (m2 != null && !m2.blank()) m = m2
            else back?.let { runCatching { it.delete() } }
        }
        return m ?: TrackMeta(null, null, null, 0L, 0, 0, codecLabel(ext))
    }

    /** 限长下载到临时文件（tail=true 时取文件尾部）。失败时返回 null。 */
    private suspend fun downloadCapped(
        url: String,
        throttle: ScanThrottle,
        maxBytes: Int,
        tail: Boolean
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .header("Range", if (tail) "bytes=-$maxBytes" else "bytes=0-${maxBytes - 1}")
                .build()
            val resp = throttle.request { okHttp.newCall(req).execute() }
            if (!resp.isSuccessful && resp.code != 206 && resp.code != 200) return@runCatching null
            val tmp = File(
                MusicBoxApp.instance.cacheDir,
                "wdmeta_${System.nanoTime()}_${(url.hashCode()).absoluteValue}.tmp"
            )
            resp.body?.byteStream()?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (total + n > maxBytes) {
                            out.write(buf, 0, (maxBytes - total).toInt())
                            break
                        }
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }
            if (tmp.length() == 0L) { runCatching { tmp.delete() }; return@runCatching null }
            tmp
        }.getOrNull()
    }

    /** 用 MediaMetadataRetriever 从（部分）音频文件里提取内嵌元数据与音频规格。 */
    private fun parseMeta(file: File, ext: String?): TrackMeta {
        val spec = runCatching { readAudioSpec(file, ext) }.getOrDefault(AudioSpec(0, 0, codecLabel(ext)))
        val retr = MediaMetadataRetriever()
        return try {
            retr.setDataSource(file.absolutePath)
            val title = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            // 有些文件只写专辑艺术家（ALBUMARTIST，API 30+），歌手未知时用它兜底
            val artist = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retr.extractMetadata(13 /* METADATA_KEY_ALBUMARTIST */)
            val album = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val dur = retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            TrackMeta(title, artist, album, dur, spec.sampleRateHz, spec.bitDepth, spec.codec)
        } catch (_: Exception) {
            TrackMeta(null, null, null, 0L, spec.sampleRateHz, spec.bitDepth, spec.codec)
        } finally {
            runCatching { retr.release() }
        }
    }

    /**
     * 从文件头解析采样率 / 位深，用于 Hi-Res 识别。
     *
     * MediaMetadataRetriever 不提供这两个字段，所以对常见无损格式直接读头部：
     * FLAC(STREAMINFO)、WAV(fmt )、DSD(DSF header)、ALAC(moov/alac)。
     * 其余格式用 MediaExtractor 兜底采样率；都拿不到就返回 0（界面不显示规格）。
     */
    private fun readAudioSpec(file: File, ext: String?): AudioSpec {
        val codec = codecLabel(ext)
        var rate = 0
        var depth = 0
        val len = minOf(8192L, file.length()).toInt()
        if (len < 16) return AudioSpec(0, 0, codec)
        val head = ByteArray(len)
        runCatching { java.io.RandomAccessFile(file, "r").use { it.seek(0); it.readFully(head) } }

        fun u8(i: Int) = head[i].toInt() and 0xFF
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le32(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        fun ascii(i: Int, n: Int) =
            runCatching { String(head, i, n, Charsets.US_ASCII) }.getOrDefault("")

        runCatching {
            when {
                head.size >= 42 && ascii(0, 4) == "fLaC" -> {
                    // STREAMINFO: 18 字节后是 20bit 采样率 + 3bit 声道 + 5bit 位深
                    val v = be32(18)
                    rate = v ushr 12
                    depth = (v ushr 4) and 0x1F
                }
                head.size >= 44 && ascii(0, 4) == "RIFF" -> {
                    var i = 12
                    while (i + 24 <= head.size) {
                        val id = ascii(i, 4)
                        val sz = le32(i + 4)
                        if (id == "fmt ") {
                            rate = le32(i + 12)
                            depth = le16(i + 22)
                            break
                        }
                        if (sz <= 0) break
                        i += 8 + sz + (sz and 1)
                    }
                }
                head.size >= 52 && ascii(0, 4) == "DSD " -> {
                    // DSF：DSD 流固定 1bit，按 1bit 展示
                    rate = le32(44)
                    depth = le32(48).takeIf { it in 1..64 } ?: 1
                }
                else -> {
                    // ALAC：moov 里的 alac box 记录位深与采样率
                    var i = 4
                    while (i + 32 <= head.size) {
                        if (ascii(i, 4) == "alac") {
                            val d = u8(i + 13)
                            val r = be32(i + 28)
                            if (d in 1..64 && r in 8_000..768_000) {
                                depth = d; rate = r
                            }
                            break
                        }
                        i++
                    }
                }
            }
        }

        if (rate <= 0) rate = extractorSampleRate(file)
        return AudioSpec(rate, depth, codec)
    }

    /** MediaExtractor 兜底采样率（m4a / ogg / mp3 等解析不到头部规格时）。 */
    private fun extractorSampleRate(file: File): Int = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (f.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                    return f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                }
            }
            0
        } finally {
            runCatching { ex.release() }
        }
    }.getOrDefault(0)

    private fun codecLabel(ext: String?): String = when (ext?.lowercase()) {
        "flac" -> "FLAC"; "alac" -> "ALAC"; "m4a" -> "ALAC/AAC"; "ape" -> "APE"
        "dsf", "dff" -> "DSD"; "wav", "w64", "rf64" -> "WAV"; "aiff", "aif" -> "AIFF"
        "wv" -> "WavPack"; "tta" -> "TTA"; "mp3" -> "MP3"; "aac" -> "AAC"
        "ogg", "oga" -> "OGG"; "opus" -> "Opus"; "wma" -> "WMA"; "dts" -> "DTS"
        "tak" -> "TAK"; "mpc" -> "MPC"; "caf" -> "CAF"; "mka" -> "MKA"
        null, "" -> ""
        else -> ext.uppercase()
    }

    /** 读取目录里的 m3u/m3u8，并把相对路径解析成可播放的绝对 URL。 */
    private suspend fun readPlaylist(
        conn: WebDavConnection,
        entry: DavEntry,
        throttle: ScanThrottle
    ): DavPlaylist? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = resolveUrl(conn, entry.href)
                val req = Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build()
                // M3U GET 同样经限速器，避免歌单多时产生突发请求
                val callResp = throttle.request { okHttp.newCall(req).execute() }
                val content = callResp.use { r ->
                    if (r.isSuccessful) r.body?.string().orEmpty() else ""
                }
                if (content.isBlank()) return@runCatching null
                val parsed = M3uParser.parse(content, nameFromHref(entry.href).substringBeforeLast('.'))
                val dir = pathOnly(entry.href).substringBeforeLast('/', "")
                val fixed = parsed.tracks.map { t ->
                    if (t.streamUrl.startsWith("http", ignoreCase = true)) t
                    else t.copy(streamUrl = absoluteUrl(conn, dir, t.streamUrl))
                }
                DavPlaylist(parsed.name, fixed)
            }.onFailure {
                CrashLogger.error("WebDAV.m3u:${entry.name}", it)
            }.getOrNull()
        }

    private fun absoluteUrl(conn: WebDavConnection, dir: String, raw: String): String {
        val combined = if (raw.startsWith("/")) raw.trimStart('/') else "$dir/$raw"
        val parts = mutableListOf<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return conn.baseUrl + "/" + parts.joinToString("/") { encodeSegment(it) }
    }

    private fun encodeSegment(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------- XML ----------

    private fun parse(xml: String): List<DavEntry> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(sanitizeXml(xml)))
        val entries = mutableListOf<DavEntry>()
        var href: String? = null
        var displayName: String? = null
        var isDir = false
        var size = 0L
        var contentType: String? = null
        var lastModified: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        href = null; displayName = null; isDir = false
                        size = 0L; contentType = null; lastModified = null
                    }
                    "href" -> href = safeNextText(parser)?.trim()
                    "displayname" -> displayName = safeNextText(parser)?.trim()
                    "collection" -> isDir = true
                    "getcontentlength" -> size = safeNextText(parser)?.trim()?.toLongOrNull() ?: 0L
                    "getcontenttype" -> contentType = safeNextText(parser)?.trim()?.ifBlank { null }
                    "getlastmodified" -> lastModified = safeNextText(parser)?.trim()?.ifBlank { null }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase() == "response" && !href.isNullOrBlank()) {
                        val name = displayName?.takeIf { it.isNotBlank() } ?: nameFromHref(href!!)
                        entries.add(DavEntry(name, href!!, isDir, size, contentType, lastModified))
                    }
                }
            }
            event = runCatching { parser.next() }.getOrDefault(XmlPullParser.END_DOCUMENT)
        }
        return entries
    }

    private fun safeNextText(parser: XmlPullParser): String? = runCatching {
        parser.nextText()
    }.getOrNull()

    /** 部分 NAS 返回的 XML 含裸 & 或控制字符，会让解析器直接抛异常。 */
    private fun sanitizeXml(raw: String): String {
        val cleaned = raw.filter { c ->
            c == '\t' || c == '\n' || c == '\r' || c.code >= 0x20
        }
        return cleaned.replace(Regex("""&(?!(?:#\d+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);)"""), "&amp;")
    }

    /** XML 完全不可解析时的正则兜底：至少抽出 href 与目录标记。 */
    private fun fallbackParse(raw: String): List<DavEntry> {
        val blocks = Regex("""<(?:\w+:)?response\b[^>]*>([\s\S]*?)</(?:\w+:)?response>""", RegexOption.IGNORE_CASE)
        return blocks.findAll(raw).mapNotNull { m ->
            val block = m.groupValues[1]
            val href = Regex("""<(?:\w+:)?href\b[^>]*>([\s\S]*?)</(?:\w+:)?href>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val isDir = Regex("""<(?:\w+:)?collection\b""", RegexOption.IGNORE_CASE).containsMatchIn(block) ||
                href.trimEnd().endsWith("/")
            DavEntry(nameFromHref(href), href, isDir, 0L, null, null)
        }.toList()
    }

    private fun nameFromHref(href: String): String {
        val h = href.trimEnd('/').substringBefore('?').substringBefore('#')
        val raw = h.substringAfterLast('/').ifBlank { h }
        return decodePath(raw)
    }

    /** 去掉 scheme://host:port，返回解码后的路径（用于去重与层级推断）。 */
    private fun pathOnly(s: String): String {
        val x = if (s.startsWith("http", ignoreCase = true))
            s.substringAfter("://").substringAfter('/', "") else s.trimStart('/')
        return decodePath(x).trim().trimEnd('/')
    }

    /** 部分服务器把路径里的空格写成 +，URLDecoder 会误判，先归一化再解码。 */
    private fun decodePath(x: String): String = runCatching {
        URLDecoder.decode(
            x.replace("+", "%20").substringBefore('?').substringBefore('#'),
            "UTF-8"
        )
    }.getOrDefault(x)

    private fun hrefToPath(conn: WebDavConnection, href: String): String {
        val h = href.trim()
        val path = if (h.startsWith("http", ignoreCase = true))
            h.substringAfter("://").substringAfter('/', "") else h.trimStart('/')
        // 保留原始 percent-encoding，不解码，避免中文路径被二次编码破坏
        return "/" + path.trimEnd('/')
    }

    /** 扩展名判定：href 里的真实文件名优先，displayname 兜底。 */
    private fun entryExt(e: DavEntry): String? =
        extensionOf(nameFromHref(e.href)) ?: extensionOf(e.name)

    /** 统一去重键：解码 + 去 query + 去尾斜杠 + 小写，避免同一目录被 PROPFIND 两次。 */
    private fun dirKey(pathOrHref: String): String = pathOnly(pathOrHref).lowercase()

    /** 限流响应识别：常见状态码，外加部分网盘用 403 + 提示文案做风控的情况。 */
    private fun isRateLimited(code: Int, body: String): Boolean =
        code == 408 || code == 429 || code == 502 || code == 503 || code == 504 || code == 509 ||
            (code == 403 && RATE_HINTS.any { body.contains(it, ignoreCase = true) })

    private fun parseRetryAfter(value: String?): Long? {
        val secs = value?.trim()?.toLongOrNull() ?: return null
        return (secs * 1000L).coerceIn(200L, 30_000L)
    }

    /** 指数退避：600 / 1200 / 2400 / 4800 … 上限 8 秒。 */
    private fun backoffMs(attempt: Int): Long =
        (600L shl attempt.coerceAtMost(3)).coerceAtMost(8000L)

    companion object {
        /** 递归深度与单次目录上限：较旧版（8 层 / 400 目录）大幅放宽，安全性由限速保证。 */
        private const val MAX_DEPTH = 24
        private const val MAX_DIRS = 8000
        /** 每批提交的目录数；真实并发与 QPS 由 ScanThrottle 控制。 */
        private const val BATCH_SIZE = 32
        /** 默认请求间隔（均衡档）：与 ScanPace.BALANCED_INTERVAL 对应。 */
        const val DEFAULT_PACE_MS = 120L
        /** 默认并行度（均衡档）：与 ScanPace.BALANCED_CONCURRENCY 对应。 */
        const val DEFAULT_CONCURRENCY = 6
        /** 单个目录失败后的重试上限（瞬时错误：网络/5xx/限流退避均在此内重试）。 */
        private const val MAX_RETRY = 4
        private const val USER_AGENT = "AIpod/1.0 (WebDAV music scanner; low-rate)"
        private val RATE_HINTS = listOf(
            "rate limit", "too many", "throttl", "slow down", "请求过于频繁", "限流", "频繁"
        )
        private val DISC_RE = Regex("""^(?:CD|Disc|Disk)\s*\d+$""", RegexOption.IGNORE_CASE)
        /** STRM 指向本机盘符/NAS 挂载路径时无法读取标签（手机上没有该路径）。 */
        private val LOCAL_PATH_PREFIXES = listOf(
            "/mnt/", "/volume", "/vol", "/storage/", "/sdcard/", "/share/", "/usb/", "/private/"
        )
        private val GENERIC_DIRS = setOf(
            "music", "音乐", "musics", "audio", "音频", "songs", "歌曲",
            "all", "全部", "library", "媒体库", "nas", "media", "flac", "无损"
        )
        private val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<D:propfind xmlns:D="DAV:"><D:prop>""" +
                """<D:displayname/><D:resourcetype/><D:getcontentlength/>""" +
                """<D:getcontenttype/><D:getlastmodified/></D:prop></D:propfind>"""

        private val COVER_NAMES = setOf(
            "cover", "folder", "front", "albumart", "albumartsmall", "artwork", "thumb"
        )
        private val COVER_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

        /** 目录内常见封面文件名：cover.jpg / folder.png / front.webp / AlbumArt.jpg … */
        private fun isCoverFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in COVER_EXT) return false
            val base = name.substringBeforeLast('.').lowercase()
            return COVER_NAMES.any { base == it || base.startsWith(it) }
        }
    }
}
