package com.aipod.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aipod.MainActivity
import com.aipod.MusicBoxApp
import com.aipod.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 扫描前台服务：让 WebDAV 扫描/封面获取在退到后台、锁屏时也能继续跑完。
 *
 * 只做「保活 + 通知进度」两件事，真正的扫描仍在 MusicBoxApp 的 App 级作用域里执行。
 * 扫描结束（webDavScanning=false）后自动停止。
 */
class ScanService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 通知刷新节流状态（见 onStartCommand）。 */
    private var lastNotifyAt = 0L
    private var lastNotifiedText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("正在扫描音乐库…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as? MusicBoxApp ?: run { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            app.webDavScanProgress.collect { msg ->
                val text = if (msg.isBlank()) "正在扫描音乐库…" else msg.take(140)
                // 扫描时进度可能每几十毫秒变一次（极速档尤其明显）。
                // 通知栏刷新是跨进程操作，过频既费电又会被系统限流，这里节流到最多 800ms 一次，
                // 且文本没变就不刷新。
                val now = android.os.SystemClock.elapsedRealtime()
                if (text == lastNotifiedText) return@collect
                if (now - lastNotifyAt < 800L) return@collect
                lastNotifyAt = now
                lastNotifiedText = text
                notify(text)
            }
        }
        scope.launch {
            // 等扫描状态由 true 变为 false 再收尾，避免启动瞬间误停
            runCatching { app.webDavScanning.drop(1).first { !it } }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("AIpod")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "音乐库扫描", NotificationManager.IMPORTANCE_LOW).apply {
                description = "扫描/刷新音乐库时显示进度"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "nas_scan"
        private const val NOTIF_ID = 0x5C4A
        private const val ACTION_STOP = "com.aipod.scan.STOP"

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, ScanService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { CrashLogger.info("ScanService", "启动前台服务失败：${it.message}") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ScanService::class.java)) }
                .onFailure { CrashLogger.info("ScanService", "停止前台服务失败：${it.message}") }
        }
    }
}
