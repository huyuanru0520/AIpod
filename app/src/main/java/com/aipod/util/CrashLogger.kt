package com.aipod.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 全量运行日志的统一切面。
 *
 * ★ 功耗重构：日志先写入**内存环形缓冲**，这是唯一的常驻存储；只有「调试模式」开启时才
 *   额外落盘 + 打 Logcat。关闭调试（默认）时 append 不碰磁盘、不打 Logcat、不调度 flush，
 *   实现「关闭调试 = 零日志 IO」。日志页的查看 / 复制 / 另存始终从内存缓冲读，功能不变。
 *
 * 落盘策略：2 秒内多条合并为一次整段 writeText（不再逐条 appendText），也不再有
 *   「超过 512KB 就 readText 全量 + writeText 全量重写」的放大——环形缓冲自身限长，
 *   落盘就是一次顺序写。崩溃（writeCrash）无视开关强制落盘，保证致命问题不丢。
 */
object CrashLogger {

    private const val TAG = "AIpod"
    private const val FILE_NAME = "AIpod_log.txt"
    private const val FLAG = "AIpod_had_crash"
    /** 内存环形缓冲保留上限（字符），约 250KB，足够日志页查看最近若干事件。 */
    private const val MEM_MAX_CHARS = 256 * 1024
    /** 下载目录文件的软上限（字符），落盘时只保留尾部，避免文件无限增长。 */
    private const val FILE_MAX_CHARS = 512 * 1024
    /** 批量落盘间隔：2 秒内的多条合并为一次写（日志页读内存，不受此间隔影响）。 */
    private const val FLUSH_DELAY_MS = 2000L

    private val lock = Any()
    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile var enabled = false
        private set

    fun setEnabled(b: Boolean) {
        val prev = enabled
        enabled = b
        if (b && !prev) {
            append("调试模式已开启 · AIpod v${com.aipod.BuildConfig.VERSION_NAME}" +
                " (${com.aipod.BuildConfig.VERSION_CODE})")
            flushNow()
        }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var internalDir: File? = null
    @Volatile private var hadCrash = false
    @Volatile private var scheduled = false
    @Volatile private var writeStatus = "尚未写入"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val flushRunnable = Runnable {
        scheduled = false
        ioScope.launch { flushNow() }
    }

    /** 内存环形缓冲：最近若干条日志。所有读取（日志页 / 导出）都走这里，零磁盘 IO。 */
    private val ring = ArrayDeque<String>()
    private var ringChars = 0

    /** 尽早安装未捕获异常处理器。启动只在内存写头，不再强制落盘（关闭调试零 IO）。 */
    fun install(context: Context) {
        appContext = context.applicationContext
        internalDir = runCatching { context.filesDir }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(thread, throwable)
            flushNowForce() // 同步落盘，不依赖 Looper（崩溃时主线程可能已停）
            previous?.uncaughtException(thread, throwable)
        }
        append(header())
        append("[${stamp()}] SESSION_START")
    }

    private fun header(): String = buildString {
        append("====== AIpod 运行日志 ======\n")
        append("时间: ").append(stampDate()).append('\n')
        append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / ").append(Build.DEVICE).append('\n')
        append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        append("日志位置: ").append(logPath()).append('\n')
        append("说明: 关闭调试时日志仅存内存、不写文件；开启调试后同步到下载目录。\n")
        append("--------------------------------\n")
    }

    // ---------- 统一入口 ----------

    fun mark(msg: String) = append("[${stamp()}] $msg")
    fun info(tag: String, msg: String) = append("[${stamp()}] INFO/$tag: $msg")

    /**
     * 调试级日志：高频诊断专用（如 WaveProbe、每次网络请求）。
     * msg 为惰性 lambda —— 关闭调试时连字符串都不会拼接，彻底零开销。
     */
    fun debug(tag: String, msg: () -> String) {
        if (!enabled) return
        append("[${stamp()}] DEBUG/$tag: ${msg()}")
    }

    fun error(tag: String, t: Throwable) {
        val text = stack(t)
        append("=== ERROR: $tag ===\n$text")
        // error 始终打 Logcat（稀有事件，且崩溃排查需要）
        Log.e(TAG, "$tag\n$text")
    }

    /** 生成给用户看的可读错误（不触发诊断模式）。 */
    fun describe(prefix: String, t: Throwable): String {
        error(prefix, t)
        val detail = t.message?.takeIf { it.isNotBlank() }
            ?: t.cause?.message?.takeIf { it.isNotBlank() }
            ?: t::class.java.simpleName
        return "$prefix：$detail"
    }

    private fun writeCrash(thread: Thread, t: Throwable) {
        val text = stack(t)
        // 崩溃强制写盘（无视 enabled 开关），保证致命问题一定能留下记录
        val line = "=== UNCAUGHT on ${thread.name} ===\n$text"
        pushRing(line)
        Log.e(TAG, "UNCAUGHT\n$text")
        hadCrash = true
        runCatching { internalDir?.let { File(it, FLAG).writeText("1") } }
        writeFileForce(line)
        flushNowForce()
    }

    fun hasCrash(): Boolean = runCatching {
        hadCrash || (internalDir?.let { File(it, FLAG).exists() } ?: false)
    }.getOrDefault(false)

    fun dismissCrash() {
        hadCrash = false
        runCatching { internalDir?.let { File(it, FLAG).delete() } }
    }

    /** 全量日志文本（尾部截断，供复制 / 诊断页 / 另存使用）——从内存缓冲读，零磁盘 IO。 */
    fun read(): String? = synchronized(lock) {
        if (ring.isEmpty()) null else ring.joinToString("\n").takeLast(60000)
    }

    /** 完整日志（不截断），用于写入下载目录。 */
    fun fullText(): String = synchronized(lock) { ring.joinToString("\n") }

    /** 用户明确要求的真实路径。 */
    fun logPath(): String = "/storage/emulated/0/Download/$FILE_NAME"

    /** 立即同步到下载目录。调试模式关闭时为 no-op。 */
    fun flushNow() {
        if (!enabled) return
        flushNowForce()
    }

    /** 强制写盘（崩溃兜底用，不受 enabled 影响）。 */
    private fun flushNowForce() {
        val text = fullText().takeLast(FILE_MAX_CHARS)
        if (text.isBlank()) return
        // 先写内部文件（一次整段写），再同步下载目录三通道
        writeFileForce(text, replace = true)
        val ctx = appContext ?: return
        val a = writeViaMediaStore(ctx, text)
        val b = writeViaDirectFile(ctx, text)
        val c = writeViaAppExternal(ctx, text)
        writeStatus = "MediaStore=${yn(a)} 公共目录直写=${yn(b)} 私有目录=${yn(c)}"
        Log.d(TAG, "日志刷新：$writeStatus")
    }

    private fun yn(b: Boolean) = if (b) "成功" else "失败"

    fun exportNow() = flushNow()

    /** 查询下载目录里日志文件的真实大小，方便确认「确实生成了」。 */
    fun logFileInfo(): String {
        val ctx = appContext
        val sb = StringBuilder(logPath()).append('\n')

        val direct = runCatching {
            File("/storage/emulated/0/Download", FILE_NAME).takeIf { it.exists() }?.length()
        }.getOrNull()
        if (direct != null && direct > 0) {
            sb.append("实际文件大小：").append(kb(direct)).append(" KB\n")
        } else if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val size = runCatching {
                ctx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads.SIZE, MediaStore.Downloads.RELATIVE_PATH),
                    "${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(FILE_NAME), null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }.getOrNull()
            if (size != null && size > 0) sb.append("MediaStore 文件大小：").append(kb(size)).append(" KB\n")
            else sb.append("文件尚未生成，点下方按钮刷新\n")
        }
        sb.append("写入通道：").append(writeStatus)
        return sb.toString()
    }

    private fun kb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1024.0)

    fun clear() {
        synchronized(lock) {
            ring.clear()
            ringChars = 0
        }
        runCatching {
            val d = internalDir ?: return@runCatching
            synchronized(lock) { File(d, FLAG).delete() }
        }
        hadCrash = false
        append(header())
        append("[${stamp()}] 日志已清除并重新开始")
        flushNow()
    }

    /**
     * 写入统一入口：无论开关与否都进内存缓冲（日志页随时可读）。
     * 关闭调试时不打 Logcat、不调度 flush —— 这是「关闭调试 = 零 IO」的关键。
     */
    private fun append(line: String) {
        pushRing(line)
        if (enabled) {
            Log.d(TAG, line)
            scheduleFlush()
        }
    }

    /** 入环形缓冲并限长（纯内存操作）。 */
    private fun pushRing(line: String) {
        synchronized(lock) {
            ring.addLast(line)
            ringChars += line.length
            while (ringChars > MEM_MAX_CHARS && ring.size > 1) {
                ringChars -= ring.removeFirst().length
            }
        }
    }

    /** 整段写内部文件：replace=true 覆盖（合并落盘），否则追加（崩溃兜底单条）。 */
    private fun writeFileForce(line: String, replace: Boolean = false) {
        runCatching {
            synchronized(lock) {
                val d = internalDir ?: return@runCatching
                val f = File(d, FILE_NAME)
                if (replace) f.writeText(line + "\n") else f.appendText(line + "\n")
            }
        }
    }

    /** 日志合并写：1.2 秒内的多条事件只做一次 I/O（仅 enabled 时会被调度）。 */
    private fun scheduleFlush() {
        if (scheduled) return
        scheduled = true
        runCatching { mainHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS) }
            .onFailure {
                scheduled = false
                ioScope.launch { flushNow() }
            }
    }

    /**
     * 通道 1：MediaStore.Downloads。
     * 用不带 mode 的 openOutputStream（默认 "w"，自动 truncate）+ 标准 IS_PENDING 流程。
     */
    private fun writeViaMediaStore(ctx: Context, content: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching false
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val bytes = content.toByteArray(Charsets.UTF_8)

        fun find(): Uri? = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(FILE_NAME), null
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                ContentUris.withAppendedId(collection, id)
            } else null
        }

        fun insert(): Uri? = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        })

        fun write(uri: Uri): Boolean = runCatching {
            resolver.openOutputStream(uri)?.use { os ->
                os.write(bytes)
                os.flush()
                true
            } ?: false
        }.getOrElse {
            Log.w(TAG, "MediaStore 写入失败", it)
            false
        }

        fun publish(uri: Uri) {
            runCatching {
                val pub = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, pub, null, null)
            }
        }

        var uri = find() ?: insert() ?: return@runCatching false
        var ok = write(uri)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            val fresh = insert() ?: return@runCatching false
            uri = fresh
            ok = write(fresh)
        }
        publish(uri)
        ok
    }.getOrElse {
        Log.w(TAG, "MediaStore 通道异常", it)
        false
    }

    /** 通用 MediaStore 文本写入（文件名 / MIME 可指定），供设置 JSON 导出复用。 */
    fun saveTextToDownloads(filename: String, mime: String, text: String): String? {
        val ctx = appContext ?: return null
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeTextViaMediaStore(ctx, filename, mime, text)
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                File(dir, filename).writeText(text)
                true
            }.getOrDefault(false)
        }
        return if (ok) "/storage/emulated/0/Download/$filename" else null
    }

    private fun writeTextViaMediaStore(ctx: Context, filename: String, mime: String, content: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching false
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val bytes = content.toByteArray(Charsets.UTF_8)

            fun find(): Uri? = resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(filename), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }

            fun insert(): Uri? = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            })

            fun write(uri: Uri): Boolean = runCatching {
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(bytes); os.flush(); true
                } ?: false
            }.getOrElse { false }

            fun publish(uri: Uri) {
                runCatching {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
            }

            var uri = find() ?: insert() ?: return@runCatching false
            var ok = write(uri)
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                val fresh = insert() ?: return@runCatching false
                uri = fresh
                ok = write(fresh)
            }
            publish(uri)
            ok
        }.getOrElse {
            Log.w(TAG, "MediaStore 文本写入异常", it)
            false
        }

    /** 通道 2：直接写 /storage/emulated/0/Download（分区存储下部分 ROM 允许）。 */
    private fun writeViaDirectFile(ctx: Context, content: String): Boolean {
        val candidates = mutableListOf(File("/storage/emulated/0/Download", FILE_NAME))
        runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.let { candidates.add(File(it, FILE_NAME)) }

        var anyOk = false
        for (f in candidates.distinctBy { it.absolutePath }) {
            runCatching {
                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                f.writeText(content)
                runCatching {
                    MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("text/plain"), null)
                }
                anyOk = true
            }.onFailure { Log.w(TAG, "直接写 ${f.absolutePath} 失败：${it.message}") }
        }
        return anyOk
    }

    /** 通道 3：应用私有外部目录，无需权限且 adb 一定能取到。 */
    private fun writeViaAppExternal(ctx: Context, content: String): Boolean = runCatching {
        val d = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        File(d, FILE_NAME).writeText(content)
        true
    }.getOrElse {
        Log.w(TAG, "私有目录写入失败", it)
        false
    }

    private fun stamp(): String = synchronized(lock) { tsFormat.format(Date()) }
    private fun stampDate(): String = synchronized(lock) { dateFormat.format(Date()) }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return buildString {
            append("Android ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
            append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" / ")
                .append(Build.DEVICE).append('\n')
            append(sw.toString())
        }
    }
}
