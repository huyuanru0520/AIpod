package com.aipod.player

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
import com.aipod.AudioQuality
import com.aipod.MusicBoxApp
import com.aipod.data.PlaybackSnapshot
import com.aipod.data.PlayMode
import com.aipod.data.SourceType
import com.aipod.data.Track
import com.aipod.data.rebasedTo
import com.aipod.ui.components.coverJpegBytes
import com.aipod.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * UI 与播放器之间的状态镜像。
 *
 * 播放控制直接作用于 PlayerHolder 的 ExoPlayer（同步生效）；
 * MediaController 只用于建立 MediaSessionService 的后台通知 / 锁屏控制，
 * 连接失败不会影响前台播放。
 */
object PlayerController {

    private const val TAG = "AIpod"

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var sessionController: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollJob: Job? = null
    private var transcodeJob: Job? = null

    /** 播放快照节流：记录上次写盘时间，播放中最多每 4 秒落一次盘，避免 500ms 轮询刷爆 DataStore。 */
    private var lastPersistAt = 0L
    private val PERSIST_INTERVAL_MS = 4_000L

    /**
     * 冷启动恢复窗口：prepare 完成前 currentPosition 可能是 0，且切歌/播放回调会接连触发，
     * 若此刻落盘会把刚读出的正确进度覆盖成 0。恢复期间暂停一切自动落盘，
     * 待位置稳定后（下方 1.2s 任务）统一落一次正确的快照再解除。
     */
    @Volatile private var restoring = false

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

    /** 统一播放模式（三态）：随机 / 列表循环 / 单曲循环。摇一摇切换，队列页右上角显示。 */
    private val _playMode = MutableStateFlow(PlayMode.LIST)
    val playMode = _playMode.asStateFlow()

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
                    // 播放 / 暂停切换：强制落盘一次（快速捕捉「是否在播」状态）
                    persistSnapshot(force = true)
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
                    // 切歌：强制落盘（捕捉当前索引 + 新歌位置 0）
                    persistSnapshot(force = true)
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

                override fun onRepeatModeChanged(mode: Int) {
                    _repeatMode.value = mode
                    syncPlayMode()
                }
                override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                    _shuffle.value = enabled
                    syncPlayMode()
                }
            })
        }.onFailure { CrashLogger.error("PlayerController.listener", it) }

        // 转轮调出来的音量要在播放器重建后仍然生效
        runCatching { p.volume = MusicBoxApp.instance.volume.value.coerceIn(0f, 1f) }
        // 与底层 ExoPlayer 对齐 shuffle/repeat 及统一播放模式（listener 回调未必在这里之前触发）
        _shuffle.value = p.shuffleModeEnabled
        _repeatMode.value = p.repeatMode
        syncPlayMode()
        _connected.value = true
        startPolling()
        CrashLogger.info("PlayerController", "已绑定 ExoPlayer")

        // 冷启动恢复播放队列 / 当前歌曲 / 播放位置（等鉴权会话恢复就绪后再恢复）
        scope.launch { runCatching { restorePlayback() }.onFailure { CrashLogger.error("restorePlayback", it) } }

        // 仅用于后台通知/锁屏控制，全程兜底，失败不影响播放
        scope.launch { runCatching { connectSession(context.applicationContext) } }
    }

    /**
     * 冷启动时恢复上次的播放状态（三音源通用）。
     *
     * 必须等 App.sessionRestored=true 再读快照：WebDAV / 飞牛的鉴权在那之前尚未注册，
     * 过早恢复会让队列请求全部 401。最多等 8 秒，超时也放行，避免永久卡住。
     * 全程只执行一次（playbackRestoreDone 守卫），attach 被重复调用也不会重复恢复。
     */
    private suspend fun restorePlayback() {
        val app = MusicBoxApp.instance
        if (app.playbackRestoreDone) return
        withTimeoutOrNull(8_000) { app.sessionRestored.first { it } }
        // 抢占守卫：两个 attach 并发时只有一个真的去恢复
        if (app.playbackRestoreDone) return
        app.playbackRestoreDone = true

        val snap = withContext(Dispatchers.IO) { app.loadPlayback() }
        if (snap == null || snap.queue.isEmpty()) {
            CrashLogger.info("RestorePlayback", "无播放快照（首次启动或从未播放），跳过恢复")
            return
        }
        CrashLogger.info(
            "RestorePlayback",
            "恢复队列 ${snap.queue.size} 首，index=${snap.index}，position=${snap.positionMs}ms，playing=${snap.playing}，源=${snap.queue[snap.index].source}"
        )
        withContext(Dispatchers.Main) {
            // 恢复窗口内抑制一切自动落盘：切歌/播放回调此刻读到的位置可能还是 0
            restoring = true
            // 恢复时直接带起始进度，避免先从头播再 seek 造成「闪一下开头」
            playTracks(snap.queue, snap.index, snap.positionMs, autoPlay = snap.playing)
            // 1.2 秒后位置已稳定，落一次正确的快照并解除抑制。
            // 这段时长要大于 playTracks 里 600ms 的兜底 seek。
            scope.launch {
                delay(1_200)
                restoring = false
                persistSnapshot(force = true)
                CrashLogger.info("RestorePlayback", "恢复落盘完成，解除抑制")
            }
        }
    }

    /** 把当前播放状态写成快照（在轮询/切歌/播放暂停时调用）。 */
    private fun persistSnapshot(force: Boolean = false) {
        if (restoring) return // 冷启动恢复窗口内不落盘，等位置稳定后统一写
        val q = _queue.value
        if (q.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        val p = player
        val snapshot = PlaybackSnapshot(
            queue = q,
            index = _currentIndex.value.coerceIn(0, q.lastIndex),
            positionMs = (p?.currentPosition ?: _position.value).coerceAtLeast(0L),
            playing = p?.isPlaying ?: _isPlaying.value
        )
        MusicBoxApp.instance.savePlayback(snapshot)
    }

    /** 退到后台 / 页面暂停时强制落盘一次（Activity.onStop 调用）。 */
    fun persistOnBackground() = persistSnapshot(force = true)

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
                // 播放进度节流落盘：最多每 4 秒一次，进程被杀时最多丢 4 秒进度
                if (_isPlaying.value) persistSnapshot()
                // 播放中 500ms 刷新一次进度；暂停时降到 1.5s —— 暂停状态下没人需要实时进度，
                // 却会每 0.5s 唤醒一次线程并触发界面重组，长时间挂着是纯浪费电。
                delay(if (_isPlaying.value) 500L else 1500L)
            }
        }
    }

    /**
     * 从任意列表点击一首歌的统一入口。
     *
     * 若点击的正是当前正在播放的曲目（同 id + 同音源），**不重建队列、不 seek**，
     * 保持当前进度继续播放（暂停态则恢复播放），仅返回 true 让调用方进入播放页。
     * 这样避免「点正在播放的歌却从头开始」。其余情况按普通播放处理。
     *
     * @return true=点击的是当前曲目（已保持进度，调用方只需进入播放页）；false=普通起播。
     */
    fun playFromList(tracks: List<Track>, index: Int): Boolean {
        if (tracks.isEmpty()) return false
        val i = index.coerceIn(0, tracks.lastIndex)
        val target = tracks[i]
        val cur = current
        if (cur != null && cur.id == target.id && cur.source == target.source) {
            CrashLogger.info("Play", "点击列表中的当前曲目「${target.title}」，保持进度继续播放，不从头")
            // 暂停态下恢复播放；已播放时 play() 无副作用。进度保持不变。
            if (!_isPlaying.value) play()
            return true
        }
        playTracks(tracks, i)
        return false
    }

    /**
     * 替换队列并从指定位置开始播放；直接作用于 ExoPlayer，同步生效。
     *
     * @param startPositionMs 起始播放进度（冷启动恢复用，普通播放为 0）。
     * @param autoPlay 是否立即播放（冷启动恢复时若上次是暂停态则为 false，只恢复不播）。
     */
    fun playTracks(
        tracks: List<Track>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true
    ) {
        if (tracks.isEmpty()) {
            CrashLogger.info("Play", "播放请求为空队列，已忽略")
            return
        }
        val idx = startIndex.coerceIn(0, tracks.lastIndex)
        val startPos = startPositionMs.coerceAtLeast(0L)
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
            p.setMediaItems(tracks.map { it.toMediaItem() }, idx, startPos)
            p.prepare()
            if (autoPlay) p.play() else p.pause()
            // 恢复场景：位置可能在 prepare 完成前被 ExoPlayer 归零，稍后再 seek 一次兜底。
            // 只在位置仍接近 0（说明起始进度没生效）时才 seek，避免覆盖用户在这 600ms 内的手动操作。
            if (startPos > 0L) {
                scope.launch {
                    delay(600)
                    runCatching {
                        if (p.currentMediaItemIndex == idx && p.currentPosition < 1_000L) {
                            p.seekTo(idx, startPos)
                        }
                    }
                }
            }
            // 恢复完成后立即落盘一次，防止「恢复后立刻被杀」导致快照停留在旧数据
            persistSnapshot(force = true)
        }.onFailure {
            CrashLogger.error("Play.setMediaItems", it)
            _error.value = CrashLogger.describe("播放启动失败", it)
        }
        // 兜底：setMediaItems 之后即便没有触发 onMediaItemTransition，也把首曲封面写进通知
        pushArtwork(idx, first)
        // 音源真实性探测（仅调试模式、仅当前首曲、异步，不影响播放链路）：
        // 用一次极小 Range 请求读取响应头与文件头魔数，判定飞牛/WebDAV 返回的是原始无损字节
        // 还是被服务端转码成了 mp3/aac。结论写入日志，供设置页查看/导出。
        if (MusicBoxApp.instance.debugMode.value && first.streamUrl.startsWith("http", ignoreCase = true)) {
            scope.launch {
                runCatching {
                    com.aipod.data.AudioSourceProbe.probeAndLog(
                        first,
                        MusicBoxApp.instance.okHttpClient,
                        "Probe.${first.source}"
                    )
                }.onFailure { CrashLogger.error("Probe", it) }
            }
        }
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
    fun next() = skip(forward = true)
    fun previous() = skip(forward = false)

    /**
     * 手动切歌：**无视单曲循环**。
     *
     * Media3 的 `seekToNextMediaItem()` 会遵守 repeat mode，单曲循环时会「切」回同一首，
     * 表现为按钮 / 转轮转了却没反应。这里临时把模式切成列表循环，切完立刻还原原模式。
     */
    private fun skip(forward: Boolean) {
        val p = player ?: return
        runCatching {
            val one = p.repeatMode == Player.REPEAT_MODE_ONE
            if (one) p.repeatMode = Player.REPEAT_MODE_ALL
            try {
                if (forward) p.seekToNextMediaItem() else p.seekToPreviousMediaItem()
            } finally {
                if (one) p.repeatMode = Player.REPEAT_MODE_ONE
            }
        }.onFailure { logCtrl(if (forward) "next" else "previous", it) }
    }
    /**
     * @param persist 是否立即落盘。滚轮连续调进度时逐格写整份队列 JSON 会明显卡顿，
     *                调用方在滚动过程中传 false，退出进度模式时再统一落盘一次。
     */
    fun seekTo(posMs: Long, persist: Boolean = true) {
        runCatching { player?.seekTo(posMs) }.onFailure { logCtrl("seekTo", it) }
        // 暂停态下轮询不会落盘，手动拖动进度后强制保存一次
        if (persist) persistSnapshot(force = true)
    }
    /** 转轮调整音量：ExoPlayer 的 0f~1f 线性增益。 */
    fun setVolume(v: Float) {
        runCatching { player?.volume = v.coerceIn(0f, 1f) }.onFailure { logCtrl("setVolume", it) }
    }
    /** 转轮调整进度时读取当前位置（不订阅 StateFlow，避免整页每 500ms 重组）。 */
    fun currentPositionMs(): Long = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
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

    /** 由底层 ExoPlayer 的 shuffle / repeat 状态反推统一播放模式。 */
    private fun syncPlayMode() {
        _playMode.value = when {
            _shuffle.value -> PlayMode.SHUFFLE
            _repeatMode.value == Player.REPEAT_MODE_ONE -> PlayMode.ONE
            else -> PlayMode.LIST
        }
    }

    /** 应用播放模式到底层 ExoPlayer：随机=洗牌+列表循环，列表=列表循环，单曲=单曲循环。 */
    fun setPlayMode(mode: PlayMode) {
        val p = player
        if (p == null) {
            // 播放器尚未连接：先更新 StateFlow，attach 后再由 listener 对齐
            _shuffle.value = mode == PlayMode.SHUFFLE
            _repeatMode.value = if (mode == PlayMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
            _playMode.value = mode
            return
        }
        runCatching {
            p.shuffleModeEnabled = mode == PlayMode.SHUFFLE
            p.repeatMode = if (mode == PlayMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        }.onFailure { logCtrl("setPlayMode", it) }
        // listener 回调会同步 _shuffle/_repeatMode，这里直接置权威值，避免 UI 等下一帧
        _shuffle.value = mode == PlayMode.SHUFFLE
        _repeatMode.value = if (mode == PlayMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        _playMode.value = mode
        CrashLogger.info("PlayMode", "切换播放模式 → ${mode.name}")
    }

    /** 摇一摇：按 随机 → 列表循环 → 单曲循环 → 随机 循环切换。返回切换后的模式。 */
    fun cyclePlayMode(): PlayMode {
        val next = when (_playMode.value) {
            PlayMode.SHUFFLE -> PlayMode.LIST
            PlayMode.LIST -> PlayMode.ONE
            PlayMode.ONE -> PlayMode.SHUFFLE
        }
        setPlayMode(next)
        return next
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
