package com.aipod.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aipod.MusicBoxApp
import com.aipod.util.CrashLogger

/**
 * 进程内唯一的 ExoPlayer 实例。
 *
 * 关键修复：FFmpeg 扩展是「可选增强」，绝不能让它拖垮基础播放。
 * 之前直接开 EXTENSION_RENDERER_MODE_ON，一旦 native 库加载失败
 * （UnsatisfiedLinkError / 反射实例化异常），ExoPlayer.Builder.build() 抛错，
 * getOrCreate 返回 null，UI 侧所有播放请求都变成「播放器未初始化」——
 * 表现就是任何格式都放不出来。
 *
 * 现在先探测 FfmpegLibrary.isAvailable()，再按
 * 「MediaCodec + FFmpeg 兜底」→「仅 MediaCodec」逐级降级，
 * 保证哪怕 FFmpeg 完全不可用，FLAC / WAV / AAC / MP3 等仍能正常出声。
 */
@UnstableApi
object PlayerHolder {

    @Volatile private var player: ExoPlayer? = null

    @Volatile var ffmpegAvailable: Boolean = false
        private set
    @Volatile var ffmpegVersion: String = "未加载"
        private set
    @Volatile var renderMode: String = "未初始化"
        private set
    @Volatile var lastError: String? = null
        private set

    @Synchronized
    fun getOrCreate(context: Context): ExoPlayer? {
        player?.let { return it }
        val ctx = context.applicationContext
        lastError = null
        CrashLogger.info("PlayerHolder", "开始创建 ExoPlayer")

        // 1) 先探测 FFmpeg native 库。isAvailable() 内部会 System.loadLibrary，
        //    失败可能抛 UnsatisfiedLinkError（是 Error 而非 Exception），runCatching 一并兜住。
        val ffmpegOk = runCatching {
            val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
            val available = clazz.getMethod("isAvailable").invoke(null) as? Boolean ?: false
            if (available) {
                ffmpegVersion = runCatching {
                    clazz.getMethod("getVersion").invoke(null) as? String ?: "unknown"
                }.getOrDefault("unknown")
            }
            available
        }.getOrElse {
            CrashLogger.error("PlayerHolder.ffmpegProbe", it)
            false
        }
        ffmpegAvailable = ffmpegOk
        CrashLogger.info("PlayerHolder", "FFmpeg native 探测=$ffmpegOk 版本=$ffmpegVersion")

        // 2) 逐级降级构建
        val modes = buildList {
            if (ffmpegOk) {
                add(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON to "MediaCodec 优先 + FFmpeg 兜底")
            }
            add(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF to "仅 MediaCodec")
        }

        for ((mode, label) in modes) {
            val p = runCatching { buildPlayer(ctx, mode) }
                .onFailure {
                    CrashLogger.error("PlayerHolder.build:$label", it)
                    lastError = "${it::class.java.simpleName}: ${it.message ?: "无消息"}"
                }
                .getOrNull()
            if (p != null) {
                player = p
                renderMode = label
                ffmpegAvailable = ffmpegOk && mode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                CrashLogger.info("PlayerHolder", "ExoPlayer 创建成功，解码策略=$label")
                return p
            }
        }

        ffmpegAvailable = false
        renderMode = "初始化失败"
        lastError = lastError ?: "ExoPlayer 构建失败（FFmpeg 与 MediaCodec 两种方案均不可用）"
        CrashLogger.error("PlayerHolder", IllegalStateException(lastError))
        return null
    }

    private fun buildPlayer(context: Context, extensionMode: Int): ExoPlayer {
        val app = context as? MusicBoxApp
            ?: throw IllegalStateException("Application 未初始化，无法取得认证 OkHttpClient")

        // 所有字节区间请求统一走带认证的 OkHttp 客户端（WebDAV Basic / FnOS Cookie）
        val dataSourceFactory = OkHttpDataSource.Factory(app.okHttpClient)
            .setUserAgent("AIpod/1.0.6")

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(extensionMode)

        // 无损音频码率高且来自网络，适当放大缓冲以减少卡顿
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            // 不自动托管音频焦点：部分 ROM 上焦点被其它应用长期占用会让 ExoPlayer
            // 保持 playWhenReady=true 却永远不出声。本地 NAS 播放器优先保证能出声。
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .build()
    }

    fun get(): ExoPlayer? = player
}
