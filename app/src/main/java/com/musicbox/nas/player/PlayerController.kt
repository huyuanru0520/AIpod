package com.musicbox.nas.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musicbox.nas.AudioQuality
import com.musicbox.nas.MusicBoxApp
import com.musicbox.nas.data.SourceType
import com.musicbox.nas.data.Track
import com.musicbox.nas.data.rebasedTo
import com.musicbox.nas.ui.components.coverJpegBytes
import com.musicbox.nas.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * UI 与播放器之间的状态镜像。
 *
 * 播放控制直接作用于 PlayerHolder 的 ExoPlayer（同步生效）；
 * MediaController 只用于建立 MediaSessionService 的后台通知 / 锁屏控制，
 * 连接失败不会影响前台播放。
 */
object PlayerController {

    private const val TAG = "NASMusic"

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var sessionController: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollJob: Job? = null
    private var transcodeJob: Job? = null

    /** 本次队列里已写入通知封面的曲目 id：避免重复下载，也避免 replaceMediaItem 回调绕回自身。 */
    private val artworkDone: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet<String>())

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle = _shuffle.asStateFlow()

    /** 最近一次播放错误（错误码 + 描述），UI 直接展示，便于定位。 */
    private val _error = MutableStateFlow<String?>(null)

    /** 本首歌是否已经因「线路不可达」自动换线路重试过一次，防止反复触发形成死循环。 */
    private var backupRetryDone = false
    val error = _error.asStateFlow()

    val current: Track?
        get() {
            val idx = _currentIndex.value
            return if (idx in _queue.value.indices) _queue.value[idx] else null
        }

    /**
     * 主备线路切换后，把队列里的绝对地址换到新线路。
     *
     * 只改队列数据，不动正在播放的 MediaItem —— 强行 setMediaItems 会打断当前这首歌。
     * 当前曲目仍然指向旧线路（若旧线路已断，本首会播完/播错），从下一首开始全部走新地址。
     */
    fun rebaseQueue(oldBase: String, newBase: String) {
        if (oldBase.isBlank() || newBase.isBlank() || oldBase == newBase) return
        val q = _queue.value
        if (q.isEmpty()) return
        _queue.value = q.map { it.rebasedTo(oldBase, newBase) }
        CrashLogger.info(TAG, "播放队列地址已随线路切换：$oldBase → $newBase（${q.size} 首）")
    }

    /**
     * 当前这首歌的地址不可达（线路挂了）时，自动换到配置里的另一条线路重试一次。
     *
     * 只在 WebDAV 音源上生效：M3U / 本地文件的地址不属于主备线路体系。
     * 重试后仍失败就把错误抛给 UI，不再循环尝试。
     */
    private fun tryBackupLineRetry(posBeforeError: Long) {
        if (backupRetryDone) return
        val conn = MusicBoxApp.instance.webDavConn.value ?: return
        val backup = conn.backupBaseUrl ?: return
        val t = current ?: return
        if (t.source != SourceType.WEBDAV) return

        val oldBase: String
        val altBase: String
        if (t.streamUrl.startsWith(conn.baseUrl)) {
            oldBase = conn.baseUrl
            altBase = backup
        } else if (t.streamUrl.startsWith(backup)) {
            oldBase = backup
            altBase = conn.baseUrl
        } else {
            return
        }

        backupRetryDone = true
        CrashLogger.info(TAG, "线路不可达，自动换线路重试一次：$oldBase → $altBase")
        rebaseQueue(oldBase, altBase)

        val q = _queue.value
        val idx = _currentIndex.value
        if (idx !in q.indices) return
        playTracks(q, idx)
        if (posBeforeError > 0L) {
            runCatching { player?.seekTo(posBeforeError) }
        }
    }

    /** 组合完成后调用：创建/复用 player、注册监听、异步建立媒体会话。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        val p = PlayerHolder.getOrCreate(context.applicationContext)
        if (p == null) {
            CrashLogger.error("PlayerController.attach", IllegalStateException("ExoPlayer 创建失败"))
            _error.value = "播放器初始化失败：" + (PlayerHolder.lastError ?: "请查看日志")
            return
        }
        if (player === p) return
        player = p
        runCatching {
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        _duration.value = p.duration.coerceAtLeast(0)
                        backupRetryDone = false
                    }
                    CrashLogger.info("Player", "播放状态=$state (${stateName(state)})")
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _currentIndex.value = if (mediaItem != null) p.currentMediaItemIndex else -1
                    _error.value = null
                    CrashLogger.info(
                        "Player",
                        "切歌 index=${_currentIndex.value} title=${mediaItem?.mediaMetadata?.title}"
                    )
                    // 通知栏 / 锁屏的专辑封面：Media3 只认 artworkData，这里补上
                    _queue.value.getOrNull(_currentIndex.value)?.let {
                        pushArtwork(_currentIndex.value, it)
                    }
                    // AAC 256k 档：切歌后继续为后面几首准备转码版本（不打断当前播放）
                    if (MusicBoxApp.instance.audioQuality.value == AudioQuality.AAC_256) {
                        scheduleAacTranscodes(_queue.value, _currentIndex.value)
                    }
                }

                override fun onPlayerError(e: PlaybackException) {
                    val msg = "code=${e.errorCode} (${e.errorCodeName})｜${e.message ?: "无消息"}"
                    _error.value = msg
                    CrashLogger.error("PlayerError", e)
                    tryBackupLineRetry(posBeforeError = _position.value)
                    CrashLogger.info("PlayerError", msg)
                }

                override fun onRepeatModeChanged(mode: Int) { _repeatMode.value = mode }
                override fun onShuffleModeEnabledChanged(enabled: Boolean) { _shuffle.value = enabled }
            })
        }.onFailure { CrashLogger.error("PlayerController.listener", it) }

        _connected.value = true
        startPolling()
        CrashLogger.info("PlayerController", "已绑定 ExoPlayer")

        // 仅用于后台通知/锁屏控制，全程兜底，失败不影响播放
        scope.launch { runCatching { connectSession(context.applicationContext) } }
    }

    /** 兼容旧调用；现在只是确保 player 已就绪，播放不依赖此连接。 */
    suspend fun connect(context: Context) { attach(context) }

    private suspend fun connectSession(context: Context) {
        val c = runCatching {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            suspendCancellableCoroutine<MediaController?> { cont ->
                future.addListener({
                    if (!cont.isActive) return@addListener
                    runCatching { future.get() }
                        .onSuccess { runCatching { cont.resume(it) } }
                        .onFailure {
                            CrashLogger.error("MediaController", it)
                            runCatching { cont.resume(null) }
                        }
                }, { mainHandler.post(it) })
            }
        }.onFailure {
            CrashLogger.error("MediaController.init", it)
        }.getOrNull()
        sessionController = c
        CrashLogger.info(
            "MediaController",
            if (c != null) "已连接，后台通知/锁屏控制可用" else "连接失败（前台播放不受影响）"
        )
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                runCatching {
                    player?.let {
                        _position.value = it.currentPosition.coerceAtLeast(0)
                        _duration.value = it.duration.coerceAtLeast(0)
                    }
                }
                // 播放中 500ms 刷新一次进度；暂停时降到 1.5s —— 暂停状态下没人需要实时进度，
                // 却会每 0.5s 唤醒一次线程并触发界面重组，长时间挂着是纯浪费电。
                delay(if (_isPlaying.value) 500L else 1500L)
            }
        }
    }

    /** 替换队列并从指定位置开始播放；直接作用于 ExoPlayer，同步生效。 */
    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) {
            CrashLogger.info("Play", "播放请求为空队列，已忽略")
            return
        }
        val idx = startIndex.coerceIn(0, tracks.lastIndex)
        _queue.value = tracks
        _currentIndex.value = idx
        _error.value = null
        // 新队列 = 全新的 MediaItem（不含 artworkData），允许重新写入封面
        artworkDone.clear()

        // 启动阶段初始化失败时，点播放再尝试一次，并补注册监听
        val resolved: ExoPlayer? = player
            ?: PlayerHolder.get()
            ?: appContext?.let { ctx ->
                PlayerHolder.getOrCreate(ctx)?.also { attach(ctx) }
            }
        if (resolved == null) {
            CrashLogger.error("Play", IllegalStateException("ExoPlayer 未初始化"))
            _error.value = "播放器未初始化：" + (PlayerHolder.lastError ?: "请重启应用")
            return
        }
        val p = resolved
        val first = tracks[idx]
        CrashLogger.info(
            "Play",
            "请求播放 ${tracks.size} 首，起始=$idx「${first.title}」URL=${first.streamUrl}"
        )
        CrashLogger.info(
            "Play",
            "FFmpeg=${PlayerHolder.ffmpegAvailable} 版本=${PlayerHolder.ffmpegVersion}；" +
                "解码策略=MediaCodec 优先 / FFmpeg 兜底"
        )
        runCatching {
            p.setMediaItems(tracks.map { it.toMediaItem() }, idx, 0L)
            p.prepare()
            p.play()
        }.onFailure {
            CrashLogger.error("Play.setMediaItems", it)
            _error.value = CrashLogger.describe("播放启动失败", it)
        }
        // 兜底：setMediaItems 之后即便没有触发 onMediaItemTransition，也把首曲封面写进通知
        pushArtwork(idx, first)
        // 选「AAC 256kbps」时，后台把后面几首转成 256k AAC，轮到播放时自动换成缓存文件；
        // 当前这首直接原码率播放（转码要下载整文件，等待会明显拖慢起播）。
        if (MusicBoxApp.instance.audioQuality.value == AudioQuality.AAC_256) {
            scheduleAacTranscodes(tracks, idx)
        }
        // 3 秒后仍停在 IDLE 且没有报错 → 记一条诊断，便于定位「点播放没反应」
        scope.launch {
            delay(3000)
            val cur = player ?: return@launch
            CrashLogger.info(
                "Play.check",
                "3s 后状态=${stateName(cur.playbackState)} playWhenReady=${cur.playWhenReady} " +
                    "isPlaying=${cur.isPlaying} 当前项=${cur.currentMediaItemIndex} " +
                    "错误=${_error.value ?: "无"}"
            )
        }
    }

    fun clearError() { _error.value = null }

    fun play() { runCatching { player?.play() }.onFailure { logCtrl("play", it) } }
    fun pause() { runCatching { player?.pause() }.onFailure { logCtrl("pause", it) } }
    fun next() { runCatching { player?.seekToNextMediaItem() }.onFailure { logCtrl("next", it) } }
    fun previous() { runCatching { player?.seekToPreviousMediaItem() }.onFailure { logCtrl("previous", it) } }
    fun seekTo(posMs: Long) { runCatching { player?.seekTo(posMs) }.onFailure { logCtrl("seekTo", it) } }
    fun setRepeat(mode: Int) { runCatching { player?.repeatMode = mode }.onFailure { logCtrl("setRepeat", it) } }

    fun cycleRepeat(): Int {
        val next = when (player?.repeatMode ?: Player.REPEAT_MODE_OFF) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        runCatching { player?.repeatMode = next }.onFailure { logCtrl("cycleRepeat", it) }
        return next
    }

    fun toggleShuffle() {
        runCatching { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
            .onFailure { logCtrl("toggleShuffle", it) }
    }

    fun stop() {
        runCatching {
            player?.stop()
            player?.clearMediaItems()
        }
        _queue.value = emptyList()
        _currentIndex.value = -1
    }

    /**
     * 为当前位置之后的若干首歌准备 AAC 256k 版本，转好一首就把队列里对应项替换成本地文件。
     * 只替换「尚未播放」的项，当前正在播的一律不动，避免打断。
     */
    private fun scheduleAacTranscodes(queue: List<Track>, from: Int) {
        if (queue.isEmpty() || from < 0) return
        transcodeJob?.cancel()
        val end = minOf(queue.size, from + 5)
        if (end <= from + 1) return
        transcodeJob = scope.launch(Dispatchers.IO) {
            for (i in (from + 1) until end) {
                val t = queue[i]
                if (!t.streamUrl.startsWith("http", ignoreCase = true)) continue
                val file = AacTranscoder.cached(t) ?: AacTranscoder.ensure(t) ?: continue
                replaceQueuedItem(i, t, file)
            }
        }
    }

    /**
     * 把专辑封面写进 MediaItem 的 metadata（系统通知栏 / 锁屏 / 车机都读这里）。
     *
     * `Track.toMediaItem()` 里设置的是 `artworkUri`（http 地址），但 Media3 的默认通知
     * 只会加载 `artworkData`（或 content:// 本地文件），且飞牛/WebDAV 的封面还要鉴权 Cookie，
     * 系统侧拿不到 → 通知里只剩一个音符占位图标。所以这里用带鉴权的 OkHttp 取一次图，
     * 压成 ≤512px 的 JPEG（几十 KB，避免 Binder 传输超限）后更新元数据。
     *
     * 只改 metadata（LocalConfiguration 不变），ExoPlayer 不会重新 prepare，不会打断播放。
     */
    private fun pushArtwork(index: Int, track: Track) {
        if (track.coverUrl.isNullOrBlank() && track.embeddedAudioUrl == null) return
        if (!artworkDone.add(track.id)) return
        scope.launch {
            val bytes = runCatching { coverJpegBytes(track.coverUrl, track.embeddedAudioUrl) }
                .getOrNull()
            if (bytes == null) {
                // 网络/解码失败时放开，允许下次切到这首时重试
                artworkDone.remove(track.id)
                CrashLogger.info("Artwork", "封面获取失败：${track.title}")
                return@launch
            }
            withContext(Dispatchers.Main) {
                val p = player ?: return@withContext
                if (index !in 0 until p.mediaItemCount) return@withContext
                val item = p.getMediaItemAt(index)
                val meta = item.mediaMetadata.buildUpon()
                    .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
                runCatching {
                    p.replaceMediaItem(index, item.buildUpon().setMediaMetadata(meta).build())
                }.onSuccess {
                    CrashLogger.info("Artwork", "通知封面已写入：${track.title}（${bytes.size / 1024}KB）")
                }.onFailure {
                    artworkDone.remove(track.id)
                    CrashLogger.info("Artwork", "通知封面写入失败：${it.message}")
                }
            }
        }
    }

    private fun replaceQueuedItem(index: Int, track: Track, file: java.io.File) {
        mainHandler.post {
            val p = player ?: return@post
            if (index !in 0 until p.mediaItemCount) return@post
            if (index == p.currentMediaItemIndex) return@post
            runCatching {
                val old = p.getMediaItemAt(index)
                // 保留原 metadata（含已写入的通知封面），只换成本地转码文件
                p.replaceMediaItem(
                    index,
                    old.buildUpon().setUri(Uri.fromFile(file)).build()
                )
                CrashLogger.info("Transcode", "队列第 ${index + 1} 首已替换为 AAC 256k 缓存")
            }.onFailure { CrashLogger.info("Transcode", "替换队列项失败：${it.message}") }
        }
    }

    private fun logCtrl(name: String, t: Throwable) = CrashLogger.error("Ctrl.$name", t)
}
