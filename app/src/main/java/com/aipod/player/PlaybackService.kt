package com.aipod.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aipod.MainActivity
import com.aipod.MusicBoxApp
import com.aipod.util.CrashLogger

/**
 * 后台媒体会话服务。
 * 复用 PlayerHolder 中唯一的 ExoPlayer：不重新创建播放器，也不在 onDestroy 释放它，
 * 这样 UI 与服务的播放状态始终一致，且不会出现「服务没连上 → 完全没有声音」。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // 服务与 UI 同进程：这里任何未捕获异常都会杀掉整个 App，必须兜底
        runCatching {
            val app = application as MusicBoxApp
            val player = PlayerHolder.getOrCreate(app)
                ?: throw IllegalStateException("ExoPlayer 创建失败，无法建立媒体会话")

            val contentIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                this, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(pending)
                .build()
            CrashLogger.info(
                "PlaybackService",
                "MediaSession 已创建；FFmpeg=${PlayerHolder.ffmpegAvailable}"
            )
        }.onFailure { e ->
            CrashLogger.error("PlaybackService.onCreate", e)
            runCatching { mediaSession?.release() }
            mediaSession = null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = PlayerHolder.get()
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        CrashLogger.info("PlaybackService", "onDestroy（保留 ExoPlayer 单例）")
        // 只释放会话，player 由 PlayerHolder 持有，供 UI 继续使用
        runCatching { mediaSession?.release() }
        mediaSession = null
        super.onDestroy()
    }
}
