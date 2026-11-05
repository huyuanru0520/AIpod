package com.musicbox.nas.util

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
import java.util.Date
import java.util.Locale

/**
 * 全量运行日志：从 Application.attachBaseContext 起记录所有事件
 * （启动、登录、扫描、播放、切歌、解码器状态、错误），**启动即生成文件**，
 * 不依赖是否发生异常或报错。
 *
 * 固定导出到 /storage/emulated/0/Download/NASMusic_log.txt。
 * Android 11+ 分区存储下单一路径可能写不进去，因此同时走三条通道：
 *   1) MediaStore.Downloads（标准做法，IS_PENDING 流程 + 不带 mode 的 openOutputStream）
 *   2) 直接 File 写公共 Download 目录（部分 ROM 允许）
 *   3) 应用私有外部目录（adb 可 pull）
 * 每次刷新三条都尝试，任意一条成功即可；结果写入 writeStatus 供设置页显示。
 */
object CrashLogger {

    private const val TAG = "NASMusic"
    private const val FILE_NAME = "NASMusic_log.txt"
    private const val FLAG = "NASMusic_had_crash"
    private const val MAX_BYTES = 512 * 1024L
    private const val FLUSH_DELAY_MS = 1200L

    private val lock = Any()
    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 调试模式总开关。**默认关闭**：关闭时不生成日志文件、不向下载目录写入，
     * 仅保留 Logcat 输出，避免常驻 I/O 与体积庞大的日志文件。
     * 设置页可随时切换；切换为开时立即落盘一个标记行。
     * 例外：未捕获的崩溃（writeCrash）无论开关与否都会写盘，确保致命问题不被漏掉。
     */
    @Volatile var enabled = false
        private set

    fun setEnabled(b: Boolean) {
        enabled = b
        if (b) {
            append("调试模式已开启 · Matrix Music v${com.musicbox.nas.BuildConfig.VERSION_NAME}" +
                " (${com.musicbox.nas.BuildConfig.VERSION_CODE})")
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

    /** 尽早安装未捕获异常处理器，并立即生成下载目录日志文件。 */
    fun install(context: Context) {
        appContext = context.applicationContext
        internalDir = runCatching { context.filesDir }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(thread, throwable)
            flushNow() // 同步落盘，不依赖 Looper（崩溃时主线程可能已停）
            previous?.uncaughtException(thread, throwable)
        }
        append(header())
        append("[${stamp()}] SESSION_START")
        flushNow() // 启动即生成文件，不需要发生任何异常或报错
    }

    private fun header(): String = buildString {
        append("====== Matrix Music 运行日志 ======\n")
        append("时间: ").append(stampDate()).append('\n')
        append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / ").append(Build.DEVICE).append('\n')
        append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        append("日志位置: ").append(logPath()).append('\n')
        append("说明: 本文件记录全部运行事件，并非只在异常时生成。\n")
        append("--------------------------------\n")
    }

    fun mark(msg: String) = append("[${stamp()}] $msg")
    fun info(tag: String, msg: String) = append("[${stamp()}] INFO/$tag: $msg")

    fun error(tag: String, t: Throwable) {
        val text = stack(t)
        append("=== ERROR: $tag ===\n$text")
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
        appendForce("=== UNCAUGHT on ${thread.name} ===\n$text")
        Log.e(TAG, "UNCAUGHT\n$text")
        hadCrash = true
        runCatching { internalDir?.let { File(it, FLAG).writeText("1") } }
        flushNowForce()
    }

    fun hasCrash(): Boolean = runCatching {
        hadCrash || (internalDir?.let { File(it, FLAG).exists() } ?: false)
    }.getOrDefault(false)

    fun dismissCrash() {
        hadCrash = false
        runCatching { internalDir?.let { File(it, FLAG).delete() } }
    }

    /** 全量日志文本（尾部截断，供复制 / 诊断页 / 另存使用）。 */
    fun read(): String? = runCatching {
        val f = internalDir?.let { File(it, FILE_NAME) } ?: return@runCatching null
        val text = synchronized(lock) {
            if (f.exists() && f.length() > 0L) f.readText() else null
        }
        text?.takeLast(60000)
    }.getOrNull()

    /** 完整日志（不截断），用于写入下载目录。 */
    fun fullText(): String = runCatching {
        val f = internalDir?.let { File(it, FILE_NAME) } ?: return@runCatching ""
        synchronized(lock) { if (f.exists()) f.readText() else "" }
    }.getOrDefault("")

    /** 用户明确要求的真实路径。 */
    fun logPath(): String = "/storage/emulated/0/Download/$FILE_NAME"

    /** 立即把当前全量日志写入所有可能的下载目录通道。调试模式关闭时为 no-op。 */
    fun flushNow() {
        if (!enabled) return
        flushNowForce()
    }

    /** 强制写盘（崩溃兜底用，不受 enabled 影响）。 */
    private fun flushNowForce() {
        val text = fullText()
        if (text.isBlank()) return
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

        // 优先看直接文件
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
        runCatching {
            val d = internalDir ?: return@runCatching
            synchronized(lock) {
                File(d, FLAG).delete()
                File(d, FILE_NAME).writeText("")
            }
        }
        hadCrash = false
        append(header())
        append("[${stamp()}] 日志已清除并重新开始")
        flushNow()
    }

    private fun append(line: String) {
        // 调试模式关闭时连 Logcat 都不写（Log.d 仍要走一遍 log buffer，高频调用会拖慢主线程）。
        // 需要看 Logcat 时用 `adb shell setprop log.tag.NASMusic DEBUG` 打开 isLoggable 分支。
        if (!enabled) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, line)
            return
        }
        Log.d(TAG, line)
        writeFile(line)
    }

    /** 强制落盘（忽略 enabled 开关），用于崩溃兜底。 */
    private fun appendForce(line: String) {
        Log.d(TAG, line)
        writeFile(line)
    }

    private fun writeFile(line: String) {
        runCatching {
            synchronized(lock) {
                val d = internalDir
                if (d != null) {
                    val f = File(d, FILE_NAME)
                    if (f.exists() && f.length() > MAX_BYTES) {
                        val tail = f.readText().takeLast((MAX_BYTES / 2).toInt())
                        f.writeText("(日志已截断，保留最近部分)\n$tail")
                    }
                    f.appendText(line + "\n")
                }
            }
        }
        scheduleFlush()
    }

    /** 日志合并写：1.2 秒内的多条事件只做一次 I/O。 */
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
     * 之前用 openOutputStream(uri, "rwt") 在 Android 16 被 MediaProvider 拒绝，
     * 写入失败又被 runCatching 吞掉，于是下载目录里始终没有文件。
     * 现在用不带 mode 的 openOutputStream（默认 "w"，自动 truncate）+ 标准 IS_PENDING 流程。
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
            // 不带 mode：默认 "w"，MediaProvider 必然支持
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
            // 记录存在但底层文件不可写（如被用户删除后 MediaStore 残留）：删掉重建
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

    /**
     * 把任意文本以指定文件名写入下载目录（MediaStore），返回可读路径；失败返回 null。
     * 供「设置 JSON 导出」等场景复用，与日志写盘走同一套已验证的 MediaStore 流程。
     */
    fun saveTextToDownloads(filename: String, mime: String, text: String): String? {
        val ctx = appContext ?: return null
        val ok = writeTextViaMediaStore(ctx, filename, mime, text)
        return if (ok) "/storage/emulated/0/Download/$filename" else null
    }

    /** 通用 MediaStore 文本写入（文件名 / MIME 可指定），复用通道 1 的已验证流程。 */
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

    /**
     * 通道 2：直接写 /storage/emulated/0/Download（用户指定的路径）。
     * Android 11+ 分区存储下可能被拒绝，但不少 ROM 允许，成功即可，失败不影响其它通道。
     */
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
