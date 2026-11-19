package com.aipod.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aipod.MusicBoxApp
import com.aipod.audio.UsbExclusiveAudio
import com.aipod.util.CrashLogger
import com.decent.usbaudio.media3.UsbAudioSink
import com.decent.usbaudio.media3.UsbAudioSinkConfig

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
 *
 * USB 独占（实验性，默认关）：仅当设置开关开启 AND UsbExclusiveAudio 报告
 * DAC 已授权就绪时，才尝试用 UsbAudioSink 包装音频链路直送 DAC；
 * 任何失败都逐级回退到标准 ExoPlayer，绝不影响普通播放。
 */
@UnstableApi
object PlayerHolder {

    @Volatile private var player: ExoPlayer? = null

    /** 当前实际生效的链路：标准 / USB 独占。 */
    @Volatile var activeOutput: String = "标准"
        private set

    /** 持有 USB sink 引用，重建播放器时需 detach。 */
    @Volatile private var usbSink: UsbAudioSink? = null

    @Volatile var ffmpegAvailable: Boolean = false
        private set
    @Volatile var ffmpegVersion: String = "未加载"
        private set
    @Volatile var renderMode: String = "未初始化"
        private set
    @Volatile var lastError: String? = null
        private set

    /** 是否应按 USB 独占链路构建：开关开 + DAC 就绪。 */
    private fun usbWanted(ctx: Context): Boolean = runCatching {
        val app = ctx.applicationContext as? MusicBoxApp ?: return false
        app.usbExclusive.value && UsbExclusiveAudio.isReady()
    }.getOrDefault(false)

    @Synchronized
    fun getOrCreate(context: Context): ExoPlayer? {
        player?.let { return it }
        return createInternal(context)
    }

    /**
     * 强制重建播放器（设置页切换 USB 独占开关后调用）。
     * 保留队列 / 进度由调用方负责（PlayerController 会从快照恢复）。
     * 返回是否成功创建。
     */
    @Synchronized
    fun rebuild(context: Context): ExoPlayer? {
        val old = player
        // 先 detach USB sink 监听器，再释放旧播放器
        runCatching { usbSink?.detachFromPlayer() }
        usbSink = null
        player = null
        if (old != null) {
            runCatching { old.release() }
                .onFailure { CrashLogger.error("PlayerHolder.release", it) }
        }
        return createInternal(context)
    }

    private fun createInternal(context: Context): ExoPlayer? {
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

        // 2) USB 独占优先尝试（实验性，默认关），失败自动落到标准链路
        if (usbWanted(ctx)) {
            CrashLogger.info("PlayerHolder", "USB 独占开关已开且 DAC 就绪，尝试独占链路")
            val usb = runCatching { buildUsbPlayer(ctx, ffmpegOk) }
                .onFailure {
                    CrashLogger.error("PlayerHolder.buildUsb", it)
                    lastError = "USB 独占构建失败，已回退：${it::class.java.simpleName}: ${it.message}"
                }
                .getOrNull()
            if (usb != null) {
                player = usb
                activeOutput = "USB 独占"
                renderMode = "MediaCodec 优先 + FFmpeg 兜底 → USB DAC 直出"
                CrashLogger.info("PlayerHolder", "✅ USB 独占链路已启用")
                return usb
            }
            // USB 失败：把状态降级，继续走标准链路
            UsbExclusiveAudio.refreshStatus(ctx, enabled = true)
        }

        // 3) 标准链路：逐级降级构建
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
                activeOutput = "标准"
                renderMode = label
                ffmpegAvailable = ffmpegOk && mode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                CrashLogger.info("PlayerHolder", "ExoPlayer 创建成功，解码策略=$label（标准输出）")
                return p
            }
        }

        ffmpegAvailable = false
        renderMode = "初始化失败"
        activeOutput = "无"
        lastError = lastError ?: "ExoPlayer 构建失败（FFmpeg 与 MediaCodec 两种方案均不可用）"
        CrashLogger.error("PlayerHolder", IllegalStateException(lastError))
        return null
    }

    /**
     * 构建 USB 独占播放器：用 UsbAudioSink 包装 DefaultAudioSink，
     * 把 PCM 直送 USB DAC。任何一步抛错都向上抛，由 createInternal 回退。
     */
    private fun buildUsbPlayer(context: Context, ffmpegOk: Boolean): ExoPlayer {
        val app = context as? MusicBoxApp
            ?: throw IllegalStateException("Application 未初始化，无法取得认证 OkHttpClient")

        val sourceFactory = dataSourceFactory(context, app)
        val baseLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 30_000, 2_500, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 自定义 RenderersFactory：override buildAudioSink 返回 UsbAudioSink
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // 无 native FLAC decoder 时，FFmpeg float 路径才能保住 24bit 精度
                val useFloat = true
                val delegate = DefaultAudioSink.Builder(ctx)
                    .setEnableFloatOutput(useFloat)
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(ctx))
                    .build()
                val sink = UsbAudioSink(
                    delegate,
                    ctx.applicationContext,
                    UsbAudioSinkConfig(
                        bitPerfectEnabled = true,
                        forceRouteToSpeaker = true,
                        highGain = app.usbHighGain.value
                    )
                )
                usbSink = sink
                return sink
            }
        }.setEnableDecoderFallback(true)
            // FFmpeg 优先，保证非 FLAC 无损格式也走高精度解码
            .setExtensionRendererMode(
                if (ffmpegOk) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

        // wrapLoadControl：native FLAC 引擎直接解码输出时，阻止 ExoPlayer 并行读文件
        val wrappedLoadControl: LoadControl = UsbAudioSink.wrapLoadControl(baseLoadControl) {
            usbSink?.isNativeEngineActive == true
        }

        val p = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sourceFactory))
            .setLoadControl(wrappedLoadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .build()

        // 注册 Player.Listener：切歌时让 sink 提取本地 FLAC 路径 / 管理 native 引擎
        runCatching { usbSink?.attachToPlayer(p) }
            .onFailure { CrashLogger.error("PlayerHolder.attachUsbSink", it) }
        return p
    }

    private fun dataSourceFactory(
        context: Context,
        app: MusicBoxApp
    ): androidx.media3.datasource.DataSource.Factory {
        // 网络音源（http/https）走带认证的 OkHttp；本地 content:// / file:// 走系统数据源
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(app.okHttpClient)
            .setUserAgent("AIpod/1.0.6")
        return DefaultDataSource.Factory(context, okHttpDataSourceFactory)
    }

    private fun buildPlayer(context: Context, extensionMode: Int): ExoPlayer {
        val app = context as? MusicBoxApp
            ?: throw IllegalStateException("Application 未初始化，无法取得认证 OkHttpClient")

        val dataSourceFactory = dataSourceFactory(context, app)

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(extensionMode)

        // 无损音频码率高且来自网络：保留够用的缓冲防止卡顿，但不再攒到 60s。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 30_000, 2_500, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .build()
    }

    fun get(): ExoPlayer? = player

    /**
     * USB 独占链路当前的音质状态，供设置页标注：
     * - null：当前不是 USB 独占链路
     * - true：USB 独占且音量 100%（严格 bit-perfect）
     * - false：USB 独占但音量被调低（软件增益，非 bit-perfect）
     */
    fun usbBitPerfectNow(): Boolean? {
        if (activeOutput != "USB 独占") return null
        return runCatching { usbSink?.isUsbBitPerfectNow() == true }.getOrNull()
    }

    /** 实时切换 HIGH GAIN（满幅 bit-perfect / 音量可调），无需重建播放器。 */
    fun applyHighGain(on: Boolean) {
        runCatching { usbSink?.setHighGain(on) }
            .onFailure { CrashLogger.error("PlayerHolder.applyHighGain", it) }
    }
}
