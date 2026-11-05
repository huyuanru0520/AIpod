package com.musicbox.nas.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.musicbox.nas.MusicBoxApp
import com.musicbox.nas.data.Track
import com.musicbox.nas.player.PlayerController
import com.musicbox.nas.util.AudioTags
import com.musicbox.nas.util.CrashLogger
import com.musicbox.nas.util.MediaHead
import com.musicbox.nas.util.formatDuration
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request

/**
 * 歌曲/专辑封面。优先使用 [url]（目录 cover 图或飞牛封面地址）；若传入 [embeddedAudioUrl]，
 * 则先从该音频文件的内嵌元数据提取封面，失败再回退到 [url]。
 *
 * 内存 LRU 缓存：已加载过的封面直接命中，划走再返回（列表滚动、页面切换）不再重新转圈——
 * 这正是飞牛封面「加载完成、划走再回来还转圈」的根因（之前状态随组合项销毁而丢失，每次都重新联网）。
 */
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    embeddedAudioUrl: String? = null,
    /** Fit = 完整展示整张封面（Cover Flow 用），Crop = 裁切填满（列表缩略图用）。 */
    contentScale: ContentScale = ContentScale.Crop,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)
) {
    if (url.isNullOrBlank() && embeddedAudioUrl.isNullOrBlank()) {
        PlaceholderCover(modifier)
        return
    }
    // 内嵌封面用专属 key，使它与目录 cover 图分别缓存、互不覆盖
    val key = coverKey(url, embeddedAudioUrl)!!

    // 按 key 订阅（不再用全局版本号）：只有这张封面就绪/失败时才会重组本组件。
    val bmp = CoverStore.readyOf(key)
    val failedAt = CoverStore.failureOf(key)
    val failed = failedAt != null && CoverStore.hasFailed(key)
    LaunchedEffect(key) {
        if (bmp == null) CoverStore.request(key)
    }
    when {
        bmp != null -> CoverLoadFade(bmp, contentDescription, contentScale, shape, modifier)
        !failed -> LoadingCover(modifier)
        else -> PlaceholderCover(modifier)
    }
}

/** 封面就绪后淡入，避免列表滚动时图片「啪」地跳出。 */
@Composable
private fun CoverLoadFade(
    bitmap: Bitmap,
    contentDescription: String?,
    contentScale: ContentScale,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier
) {
    val alpha = remember(bitmap) { Animatable(0f) }
    LaunchedEffect(bitmap) { alpha.animateTo(1f, tween(durationMillis = 180)) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
            .clip(shape)
            .graphicsLayer { this.alpha = alpha.value }
    )
}

/**
 * 与 [CoverImage] 完全一致的缓存键：内嵌封面用专属前缀，避免与目录 cover 图互相覆盖。
 * [embeddedAudioUrl] 允许用 `\u0001` 连接多个候选音频地址（专辑封面场景）：
 * 前一首没有内嵌封面时会继续试下一首，避免「整张专辑只因第一首没图就全变占位图」。
 */
private fun coverKey(url: String?, embeddedAudioUrl: String?): String? =
    if (embeddedAudioUrl != null) "$EMBEDDED_SCHEME${encode(embeddedAudioUrl)}?fb=${encode(url ?: "")}"
    else url?.takeIf { it.isNotBlank() }

/**
 * 纯图片封面（播放页大图专用）：按图片真实宽高比完整显示，
 * 不加圆角、描边、底板，也不裁切——避免非正方形封面塞进方形框后左右露出黑边。
 */
@Composable
fun BareCover(
    url: String?,
    embeddedAudioUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val bmp = rememberCoverBitmap(url, embeddedAudioUrl)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (bmp == null) {
            if (url.isNullOrBlank() && embeddedAudioUrl.isNullOrBlank()) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier.size(64.dp)
                )
            } else {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.55f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp)
                )
            }
            return@BoxWithConstraints
        }
        val ratio = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
        val w = minOf(maxWidth, maxHeight * ratio)
        val h = w / ratio
        Image(
            bitmap = bmp,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(w, h)
        )
    }
}

/**
 * 取封面原始位图（播放页做「主色调 + 模糊」背景用）。
 * 走同一份 LRU 缓存，未加载时触发后台加载，完成后随版本号自动刷新。
 */
@Composable
fun rememberCoverBitmap(url: String?, embeddedAudioUrl: String?): ImageBitmap? {
    val key = coverKey(url, embeddedAudioUrl) ?: return null
    val bmp = CoverStore.readyOf(key)
    val failed = CoverStore.failureOf(key) != null && CoverStore.hasFailed(key)
    LaunchedEffect(key) {
        if (bmp == null && !failed) CoverStore.request(key)
    }
    return bmp?.asImageBitmap()
}

@Composable
private fun PlaceholderCover(modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingCover(modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private const val EMBEDDED_SCHEME = "wdart://"
/**
 * 已就绪封面（Compose 可观察）。
 *
 * 以前所有 [CoverImage] 都订阅同一个「全局版本号」，任何一张封面加载完成都会让整屏
 * （列表里几十个可见项）全部重组一次——滚动时持续掉帧、耗电。改成按 key 订阅的
 * SnapshotStateMap 后，只有真正显示这张图的组件会刷新。
 */
private val readyCovers = androidx.compose.runtime.mutableStateMapOf<String, Bitmap>()

/** 失败记录（Compose 可观察）：写入后对应封面由「转圈」切到「占位图」。 */
private val failedCovers = androidx.compose.runtime.mutableStateMapOf<String, Long>()

private val coverCache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024

    /** LRU 淘汰时同步移除可观察索引，否则快照状态会一直持有位图导致内存无法释放。 */
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
        if (evicted && readyCovers[key] === oldValue) readyCovers.remove(key)
    }
}

/**
 * 磁盘封面缓存：封面位图（JPEG）落盘到应用 cache 目录，跨进程 / 跨 App 重启命中——
 * 这样「杀掉 App 再打开」不再重新联网拉取，内嵌封面也无需重新下载整首音频去抽取，
 * 列表里封面直接秒出、不再转圈。内存 LruCache 仍负责单次会话内的快速命中。
 */
private val coverDiskDir by lazy {
    File(MusicBoxApp.instance.cacheDir, "cover_cache").also { if (!it.exists()) it.mkdirs() }
}
private const val MAX_DISK_ENTRIES = 2000
private var diskPutCount = 0

/** 封面 key → 磁盘文件名（MD5，隔离特殊字符）。 */
private fun diskName(key: String): String {
    val hex = runCatching {
        val md = MessageDigest.getInstance("MD5")
        md.update(key.toByteArray(Charsets.UTF_8))
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrElse { key.hashCode().absoluteValue.toString(16) }
    return "$hex.jpg"
}

/** 命中磁盘缓存则直接返回位图，否则返回 null。 */
private fun diskGet(key: String): Bitmap? {
    val f = File(coverDiskDir, diskName(key))
    if (!f.exists() || f.length() == 0L) return null
    return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
}

/** 写入磁盘缓存（JPEG 质量 85），并周期性清理最旧条目，上限 MAX_DISK_ENTRIES。 */
private fun diskPut(key: String, bmp: Bitmap) {
    runCatching {
        val f = File(coverDiskDir, diskName(key))
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (++diskPutCount % 50 == 0) pruneDiskCache()
    }
}

/** 超出上限时删除最旧（按修改时间）的磁盘封面。 */
private fun pruneDiskCache() {
    runCatching {
        val files = coverDiskDir.listFiles() ?: return
        if (files.size <= MAX_DISK_ENTRIES) return
        val sorted = files.sortedBy { it.lastModified() }
        val excess = files.size - MAX_DISK_ENTRIES
        for (i in 0 until excess) sorted[i].delete()
    }
}

/** 当前封面缓存占用（内存估算 + 磁盘），用于设置页展示与清理。 */
fun coverCacheSizeBytes(): Long {
    val disk = runCatching { coverDiskDir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    val mem = runCatching {
        coverCache.snapshot().values.sumOf { it.byteCount.toLong() }
    }.getOrDefault(0L)
    return disk + mem
}

/** 清空封面缓存（内存 + 磁盘），返回清理前占用字节数。 */
fun clearCoverCache(): Long {
    val before = coverCacheSizeBytes()
    coverCache.evictAll()
    runCatching { coverDiskDir.listFiles()?.forEach { it.delete() } }
    return before
}

/**
 * 封面加载调度器（App 级作用域）。
 *
 * 以前封面在 Composable 的 LaunchedEffect 里加载，页面切走就被取消，回来重新联网 → 一直转圈。
 * 现在统一交给这里：请求发出后即使界面销毁也会继续加载，结果进 LRU 缓存，
 * 下次组合时直接命中；版本号自增用于通知仍在显示的组件刷新。
 */
private object CoverStore {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    /**
     * 失败记录带时间戳，并区分两种失败：
     * - **确定没有封面**（文件里确实没有 PICTURE、目录也没 cover 图）→ 10 分钟内不再重试，
     *   否则列表每滚动一次就为同一张专辑重复下载 1MB 文件头；
     * - **临时失败**（网络异常 / 403 签名过期 / 5xx）→ 8 秒后自动重试，
     *   这正是「状态栏有封面、列表却是占位图」的来源：通知侧每次都会重新取，
     *   列表侧却被 60 秒的失败锁拦住了。
     */
    private val failed = java.util.Collections.synchronizedMap(HashMap<String, Long>())
    private const val RETRY_DEFINITE_MS = 10 * 60 * 1000L
    private const val RETRY_TRANSIENT_MS = 8_000L

    /** 同时最多 6 个封面下载：列表一次滚动可能触发几十个请求，不设限容易被网盘判为异常流量。 */
    private val gate = kotlinx.coroutines.sync.Semaphore(6)

    private val definiteFailed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 组合中读取：已就绪的封面。读 SnapshotStateMap 即建立「只针对该 key」的订阅。 */
    fun readyOf(key: String): Bitmap? = readyCovers[key]

    /** 组合中读取：失败时间戳（null = 未失败）。 */
    fun failureOf(key: String): Long? = failedCovers[key]

    fun hasFailed(key: String): Boolean {
        val at = failedCovers[key] ?: return false
        val window = if (definiteFailed.contains(key)) RETRY_DEFINITE_MS else RETRY_TRANSIENT_MS
        return System.currentTimeMillis() - at < window
    }

    /** 写入就绪状态：同时更新 LRU、可观察索引与失败标记。 */
    fun markReady(key: String, bmp: Bitmap) {
        coverCache.put(key, bmp)
        readyCovers[key] = bmp
        failed.remove(key)
        failedCovers.remove(key)
        definiteFailed.remove(key)
    }

    private fun markFailed(key: String, definite: Boolean) {
        val now = System.currentTimeMillis()
        failed[key] = now
        failedCovers[key] = now
        if (definite) definiteFailed.add(key) else definiteFailed.remove(key)
    }

    /** 外部（通知封面）绕开队列直接加载成功时，把结果同步给正在显示的列表。 */
    fun notifyLoaded(key: String, bmp: Bitmap) {
        markReady(key, bmp)
    }

    fun request(key: String, attempt: Int = 0) {
        if (coverCache.get(key) != null) {
            // 已在缓存但可观察索引缺失（例如被 LRU 淘汰后又从别处恢复）
            readyCovers.getOrPut(key) { coverCache.get(key)!! }
            return
        }
        if (hasFailed(key)) return
        if (!inFlight.add(key)) return
        scope.launch {
            // 先查磁盘缓存：跨 App 重启命中，避免重新联网（内嵌封面无需重下整首音频）
            val disk = runCatching { diskGet(key) }.getOrNull()
            if (disk != null) {
                markReady(key, disk)
                inFlight.remove(key)
                return@launch
            }
            val load = runCatching {
                gate.withPermit { loadCoverBitmap(key) }
            }.getOrNull()
            val bmp = load?.bmp
            inFlight.remove(key)
            if (bmp != null) {
                markReady(key, bmp)
                diskPut(key, bmp)
            } else {
                val definite = load?.definite == true
                markFailed(key, definite)
                // 临时失败（网络抖动 / 签名过期 / 5xx）自动再试两次：
                // 以前失败后必须等用户重新滚动列表才会重试，表现就是「状态栏都有图了，列表还是占位」。
                if (!definite && attempt < 2) {
                    scope.launch {
                        kotlinx.coroutines.delay(RETRY_TRANSIENT_MS)
                        failed.remove(key)
                        failedCovers.remove(key)
                        request(key, attempt + 1)
                    }
                }
            }
        }
    }
}

private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
private fun decode(s: String) = java.net.URLDecoder.decode(s, "UTF-8")

/** 一次封面加载的结果；[definite] 表示「确认这张专辑/歌曲没有封面」，可长缓存失败。 */
private class CoverLoad(val bmp: Bitmap?, val definite: Boolean)

/** 加载封面位图：支持内嵌元数据 scheme（可含多个候选音频），否则走普通 HTTP 加载。 */
private suspend fun loadCoverBitmap(key: String): CoverLoad = withContext(Dispatchers.IO) {
    if (key.startsWith(EMBEDDED_SCHEME)) {
        val body = key.substring(EMBEDDED_SCHEME.length)
        val q = body.indexOf('?')
        val audioChain = decode(if (q >= 0) body.substring(0, q) else body)
            .split('\u0001').filter { it.isNotBlank() }
        val fb = if (q >= 0) {
            val p = body.substring(q + 1)
            val i = p.indexOf("fb=")
            if (i >= 0) decode(p.substring(i + 3)) else ""
        } else ""
        var transientFail = false
        // 目录 cover 图优先：一次小请求、命中率高，比下载音频文件头快得多。
        if (fb.isNotBlank()) {
            val fbRes = loadPlainCoverResult(fb)
            if (fbRes.bmp != null) return@withContext fbRes
            if (!fbRes.definite) transientFail = true
        }
        for (audio in audioChain) {
            val r = extractEmbeddedCover(audio)
            r.bmp?.let { return@withContext CoverLoad(it, true) }
            if (!r.definite) transientFail = true
        }
        return@withContext CoverLoad(null, definite = !transientFail)
    }
    return@withContext loadPlainCoverResult(key)
}

/**
 * 读取内嵌封面：先取 1MB 头部，解析不出再按需续读（最多 16MB）。
 * 这样既避免了「每首 4MB」的浪费，又能覆盖元数据块前面塞了大 PADDING 的文件——
 * 旧实现一旦块长度超出头部窗口就直接放弃，是「同一个库有的有图有的没图」的主因。
 */
private fun extractEmbeddedCover(audioUrl: String): CoverLoad {
    var bytes = MediaHead.fetch(audioUrl, MediaHead.DEFAULT_CAP)
        ?: return CoverLoad(null, definite = false)   // 网络/HTTP 失败 → 可重试
    val kind = AudioTags.kind(bytes)
    var result = AudioTags.findCover(bytes)
    var caps = 0
    while (result is AudioTags.CoverResult.NeedMore && caps < 2) {
        val need = result.minBytes
        if (need <= bytes.size || need > MediaHead.MAX_CAP) break
        val next = minOf(need + 64 * 1024, MediaHead.MAX_CAP)
        val more = MediaHead.fetch(audioUrl, next) ?: break
        if (more.size <= bytes.size) break
        bytes = more
        caps++
        result = AudioTags.findCover(bytes)
    }
    if (result is AudioTags.CoverResult.Found) {
        decodeDownscaled(result.data)?.let {
            CrashLogger.info("Cover", "内嵌封面已解析 [$kind] ${result.data.size / 1024}KB head=${bytes.size / 1024}KB")
            return CoverLoad(it, true)
        }
    }
    // MP4/M4A：moov（含 covr）常被放在文件尾部，头部窗口找不到时从尾部再试
    if (kind == "mp4") {
        MediaHead.fetchTail(audioUrl)?.let { tail ->
            AudioTags.coverFromTail(tail)?.let { pic ->
                decodeDownscaled(pic)?.let {
                    CrashLogger.info("Cover", "内嵌封面已解析 [mp4/tail] ${pic.size / 1024}KB")
                    return CoverLoad(it, true)
                }
            }
        }
    }
    // 兜底：落盘后用 MediaMetadataRetriever 再试（个别 APE / MP4 变体它更擅长）
    val tmp = File(
        MusicBoxApp.instance.cacheDir,
        "wdart_${System.nanoTime()}_${(audioUrl.hashCode()).absoluteValue}.tmp"
    )
    return try {
        tmp.outputStream().use { it.write(bytes) }
        val retr = MediaMetadataRetriever()
        try {
            retr.setDataSource(tmp.absolutePath)
            val fallback = retr.embeddedPicture
            if (fallback == null) {
                CrashLogger.info(
                    "Cover",
                    "无内嵌封面 [$kind] head=${bytes.size / 1024}KB（MMR 也读不到）"
                )
                CoverLoad(null, definite = true)
            } else {
                val bmp = decodeDownscaled(fallback)
                CrashLogger.info("Cover", "内嵌封面已解析 [MMR/$kind] ${fallback.size / 1024}KB")
                CoverLoad(bmp, bmp != null)
            }
        } finally {
            runCatching { retr.release() }
        }
    } catch (e: Exception) {
        CrashLogger.info("Cover", "封面提取异常 [$kind] ${e.message}")
        CoverLoad(null, definite = false)
    } finally {
        runCatching { tmp.delete() }
    }
}

/** 经鉴权 OkHttp（WebDAV Basic / 飞牛 Cookie）加载普通封面地址。 */
private fun loadPlainCoverResult(url: String): CoverLoad {
    val client = MusicBoxApp.instance.okHttpClient
    return try {
        val resp = client.newCall(Request.Builder().url(url).header("User-Agent", "NASMusic").build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                CrashLogger.info("Cover", "封面 HTTP ${r.code} → $url")
                // 4xx/5xx 多为签名过期/临时故障，允许短间隔重试
                return CoverLoad(null, definite = false)
            }
            val bytes = r.body?.bytes() ?: return CoverLoad(null, definite = false)
            val bmp = decodeDownscaled(bytes)
            // 能下载但解不出图，说明这张图本身坏了，没必要反复重试
            CoverLoad(bmp, definite = true)
        }
    } catch (e: Exception) {
        CoverLoad(null, definite = false)
    }
}

/** 解码并下采样到最大边长，控制内存占用。 */
private fun decodeDownscaled(bytes: ByteArray, maxDim: Int = 512): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    val longer = max(opts.outWidth, opts.outHeight)
    var scale = 1
    var s = longer
    while (s > maxDim && s > 0) { scale *= 2; s /= 2 }
    val d = BitmapFactory.Options().apply { inSampleSize = scale }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d)
}

/**
 * 给系统媒体通知 / 锁屏准备专辑封面字节（JPEG，长边 ≤ 512，通常 20~120KB）。
 *
 * 为什么不能直接用 `MediaMetadata.artworkUri`：Media3 的默认通知只认
 * `artworkData`（或 content:// 本地文件），http 地址根本不会被加载；
 * 而飞牛 / WebDAV 的封面还需要鉴权 Cookie，系统侧没有这些请求头，必然 401。
 * 所以这里复用 App 内带鉴权的 OkHttp（命中内存/磁盘缓存时完全不走网络）取一次图。
 */
suspend fun coverJpegBytes(url: String?, embeddedAudioUrl: String?): ByteArray? =
    withContext(Dispatchers.IO) {
        val key = coverKey(url, embeddedAudioUrl) ?: return@withContext null
        val bmp = coverCache.get(key)
            ?: runCatching { diskGet(key) }.getOrNull()?.also {
                // 通知列表：这张封面其实已经有了（之前可能因为临时失败停在占位图）
                CoverStore.notifyLoaded(key, it)
            }
            ?: runCatching { loadCoverBitmap(key) }.getOrNull()?.bmp?.also {
                diskPut(key, it)
                CoverStore.notifyLoaded(key, it)
            }
            ?: return@withContext null
        runCatching {
            val longest = max(bmp.width, bmp.height)
            val small = if (longest > 512) {
                val s = 512f / longest
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * s).toInt().coerceAtLeast(1),
                    (bmp.height * s).toInt().coerceAtLeast(1),
                    true
                )
            } else bmp
            java.io.ByteArrayOutputStream().use { out ->
                small.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

/**
 * Hi-Res 标识：高解析音频显示「Hi-Res」徽标 + 「24bit / 96kHz」规格。
 * 规格未知但文件名/格式表明是高解析时只显示徽标。
 */
@Composable
fun HiResBadge(track: Track, showSpec: Boolean = true, onDark: Boolean = false) {
    val label = track.qualityLabel
    if (!track.isHiRes && label.isBlank()) return
    val accent = if (onDark) Color(0xFFFF8A98) else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (track.isHiRes) {
            Surface(
                color = accent.copy(alpha = 0.18f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    "Hi-Res",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        if (showSpec && label.isNotBlank()) {
            if (track.isHiRes) Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (onDark) Color.White.copy(alpha = 0.66f)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 歌曲行：左侧固定显示专辑封面（无封面时显示占位图标）。
 *
 * 注意：onClick 必须是最后一个参数，尾随 lambda 才会绑定到它。
 * 早期版本把 trailing 放在最后，导致 TrackRow(...) { playTracks(...) } 的 lambda
 * 在「组合期」就被执行——渲染每一行都会触发播放，滚动列表时不断重置队列与进度。
 */
@Composable
fun TrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    /** 专辑详情按 iOS 5 的做法只列歌曲，不重复展示封面。 */
    showCover: Boolean = true,
    /** 非空时在左侧显示音轨号（仅在 showCover = false 时使用）。 */
    trackNumber: Int? = null,
    onClick: (() -> Unit)? = null
) {
    val clickable = onClick ?: {}
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null, onClick = clickable)
            .padding(horizontal = 12.dp, vertical = if (showCover) 8.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCover) {
            CoverImage(track.coverUrl, Modifier.size(48.dp), embeddedAudioUrl = track.embeddedAudioUrl)
            Spacer(Modifier.width(12.dp))
        } else if (trackNumber != null) {
            Text(
                trackNumber.toString().padStart(2, '0'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(26.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.isHiRes) {
                    Spacer(Modifier.width(6.dp))
                    HiResBadge(track, showSpec = false)
                }
            }
            val sub = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (track.durationMs > 0) {
            Text(formatDuration(track.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxSize().then(modifier), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun MiniPlayer(onClick: () -> Unit) {
    val queue by PlayerController.queue.collectAsState()
    val idx by PlayerController.currentIndex.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val current = if (idx in queue.indices) queue[idx] else null
    // 外壳常驻，可见性交给 AnimatedVisibility：点歌开始播放时从底部滑入，
    // 而不是「突然出现、把下面的内容顶上去」。
    androidx.compose.animation.AnimatedVisibility(
        visible = current != null,
        enter = androidx.compose.animation.slideInVertically(
            androidx.compose.animation.core.tween(220)
        ) { it } + androidx.compose.animation.fadeIn(
            androidx.compose.animation.core.tween(180)
        ),
        exit = androidx.compose.animation.slideOutVertically(
            androidx.compose.animation.core.tween(180)
        ) { it } + androidx.compose.animation.fadeOut(
            androidx.compose.animation.core.tween(140)
        )
    ) {
        val c = current ?: return@AnimatedVisibility
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(c.coverUrl, Modifier.size(44.dp), embeddedAudioUrl = c.embeddedAudioUrl)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.artist.ifBlank { "未知歌手" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    if (isPlaying) PlayerController.pause() else PlayerController.play()
                }) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                }
            }
        }
    }
}
