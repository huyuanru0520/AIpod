package com.aipod.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import com.aipod.util.CrashLogger
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.aipod.MusicBoxApp
import com.aipod.data.LyricsRepo
import com.aipod.data.PlayMode
import com.aipod.data.SourceType
import com.aipod.data.Track
import com.aipod.player.PlayerController
import com.aipod.ui.components.BareCover
import com.aipod.ui.components.ClickWheel
import com.aipod.ui.components.CoverImage
import com.aipod.ui.components.HiResBadge
import com.aipod.ui.components.fadeInList
import com.aipod.ui.components.glassHighlight
import com.aipod.ui.components.rememberGlassBackdrop
import com.aipod.ui.components.LocalGlassBackdrop
import com.aipod.ui.components.AmbientMeshBackground
import com.aipod.ui.components.rememberCoverBitmap
import com.aipod.ui.theme.BrandRed
import com.aipod.util.Haptics
import com.aipod.util.ShakeDetector
import com.aipod.util.formatDuration
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 播放页：专辑封面与歌词整合在同一屏 —— 顶部是当前歌曲的小封面与信息，
 * 中间是随播放进度滚动的歌词，底部是进度条与「上一首 / 播放 / 下一首」三个按钮。
 * 随机、循环等模式开关统一收进「播放队列」列表页（见 [QueueSheet]）。
 *
 * 页面滚动 / 歌词滚动 / 弹出队列都不会触碰播放器状态，
 * 只有显式点击播放控制、歌词行或队列中的歌曲才会改变播放进度 / 歌曲。
 *
 * 封面切歌动画采用 spring（与专辑列表同一手感），滚轮切歌不节流——
 * 与专辑一致：每格 18° 即切一首，快速连转时 spring 平滑追逐目标，动画连续不被打断。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(navController: NavController) {
    val queue by PlayerController.queue.collectAsState()
    val idx by PlayerController.currentIndex.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    // 注意：这里刻意**不**收集 position。进度每 500ms 变一次，若在页面顶层读取，
    // 整个播放页（模糊封面背景 + 整段歌词 + 控制排）都会跟着重组。改为让进度条与
    // 歌词自己订阅（见 SeekBar / LyricsView），重组范围限制在真正显示进度的极小区域。
    val duration by PlayerController.duration.collectAsState()
    val repeatMode by PlayerController.repeatMode.collectAsState()
    val shuffle by PlayerController.shuffle.collectAsState()
    val playMode by PlayerController.playMode.collectAsState()
    val playError by PlayerController.error.collectAsState()
    val current = if (idx in queue.indices) queue[idx] else null

    var showQueue by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shakeContext = LocalContext.current
    val shakeView = LocalView.current
    val volume by MusicBoxApp.instance.volume.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }      // MENU：本页队列列表
    // 滚轮模式：0=默认（封面页滚轮切歌 / 歌词页滚轮滚歌词），1=音量，2=进度。
    // 中心键短按在 0→2→1→0 间循环（进度在前、音量在后）；静止 1.5s 自动回到 0。
    var wheelMode by remember { mutableIntStateOf(0) }
    val volumeActive = wheelMode == 1
    val progressMode = wheelMode == 2
    // 模式空闲计时令牌：每次进入模式或操作滚轮都 +1，重置 1.5s 倒计时。
    var modeIdleToken by remember { mutableIntStateOf(0) }
    // 摇一摇切换播放模式（随机 / 列表循环 / 单曲循环）：仅在播放队列页打开时监听。
    DisposableEffect(menuOpen) {
        if (!menuOpen) return@DisposableEffect onDispose { }
        val detector = ShakeDetector(shakeContext) {
            PlayerController.cyclePlayMode()
            Haptics.click(shakeContext, shakeView)
        }
        if (detector.isAvailable()) detector.start()
        onDispose { detector.stop() }
    }
    // 进度模式下滚轮调进度的本地累积值：不依赖播放器异步位置，做到逐格连续、精确到秒、快速滑动跟手。
    // null = 尚未进入进度调节（进度条照常显示实时播放位置）；非 null = 用户正在用滚轮调，显示这个值。
    var wheelSeekMs by remember { mutableStateOf<Long?>(null) }
    // 进入进度模式时的位置基准：退出时只有真正调整过进度才 seek。
    var progressStartMs by remember { mutableStateOf(0L) }
    // 相邻滚轮事件时间戳：用于判定快慢转（慢转 1 秒/格精调，快转 5 秒/格快进）
    var lastSeekAt by remember { mutableStateOf(0L) }
    // 静止 1.5s 自动恢复默认滚轮模式：token 变化会取消旧倒计时并重新开始。
    // 能执行到 delay 之后，说明期间没有新的滚轮操作/模式切换，即真正「静止」了 1.5s。
    LaunchedEffect(modeIdleToken) {
        if (wheelMode == 0 || modeIdleToken == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(1500L)
        // 恢复前先提交未决的进度调整（若确实调整过），避免丢失用户最后的滚动位置
        if (wheelMode == 2) {
            val target = wheelSeekMs
            if (target != null && kotlin.math.abs(target - progressStartMs) >= 250L) {
                PlayerController.seekTo(target, persist = true)
            }
        }
        wheelSeekMs = null
        wheelMode = 0
    }
    var selectedQueueIndex by remember { mutableStateOf(idx.coerceAtLeast(0)) }
    LaunchedEffect(menuOpen) { if (menuOpen) selectedQueueIndex = idx.coerceAtLeast(0) }
    LaunchedEffect(idx) { if (!menuOpen) selectedQueueIndex = idx.coerceAtLeast(0) }

    // 横屏：不再展示「横屏播放页」。转横屏时直接退回曲库首页的全屏 CoverFlow
    //（点击封面进专辑、左右滑动切换）。竖屏仍保留完整的 iPod 转轮播放页。
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        LaunchedEffect(Unit) {
            navController.navigate("library") {
                popUpTo("player") { inclusive = true }
                launchSingleTop = true
            }
        }
        // 转场瞬间先给一块中性深灰底，避免闪一下黑屏
        Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1D)))
        return
    }

    // 液态玻璃采样源：背景层录制进该 backdrop，滚轮玻璃从同一实例采样（兄弟配对）。
    val glassBackdrop = rememberGlassBackdrop()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassBackdrop provides glassBackdrop
    ) {
    Box(Modifier.fillMaxSize()) {
        // 竖屏背景与横屏一致：模糊当前封面 + 主色调 + 暗化渐变
        CoverBackdrop(current)
        // 播放页保留状态栏（见 MainActivity.keepStatusBarVisible）：内容从状态栏下方开始，
        // 但背景仍在 Box 里铺满整屏，状态栏透出的是封面模糊图而不是黑条。
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
            // 滚轮尺寸与设置页 / 列表页统一为 264dp（不再随屏自适应），三处交互手感一致
            val wheel = 264.dp
            var showLyrics by remember { mutableStateOf(false) }
            // 歌词滚动由外部驱动：滚轮在歌词界面直接滚动歌词（见 onRotate）。
            val lyricsScroll = rememberScrollState()
            // 进度模式：封面 / 歌词界面短按中心键切换，滚轮调歌曲进度；只在原进度条上加粗红色标记，
            // 不再弹出独立 HUD。非进度模式下歌词界面滚轮滚歌词、封面界面滚轮调音量。
            val body = when {
                menuOpen -> "menu"
                showLyrics -> "lyrics"
                else -> "cover"
            }
            // 按键快退/快进（上一首/下一首/±键）：固定 5 秒一格，基于本地累积值推进。
            fun seekBy(deltaMs: Long) {
                val base = wheelSeekMs ?: PlayerController.currentPositionMs()
                val next = (base + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
                wheelSeekMs = next
                progressStartMs = next   // 已立即 seek，同步基准，退出时不再重复 seek
                // 按键离散、频率低，正常落盘即可
                PlayerController.seekTo(next, persist = true)
            }

            // 播放队列（MENU 打开）逐行移动选中项：与主页列表滚轮行为一致，
            // 左右键短按一行、长按连续移动（震动由 ClickWheel.hapticOnLongRepeat 负责）。
            fun moveQueueSelection(steps: Int) {
                selectedQueueIndex =
                    (selectedQueueIndex + steps).coerceIn(0, queue.lastIndex.coerceAtLeast(0))
            }

            // 滚轮调进度：基于本地累积值 wheelSeekMs 逐格推进，不读播放器异步位置。
            // 慢转（间隔 ≥ 100ms）精确到 1 秒/格；快速滑动（间隔 < 100ms）自动放大步长快进。
            // 关键是快速连续事件都在上一格基础上累加，进度条即时跟手、不回跳、不卡顿。
            fun seekByWheel(steps: Int) {
                modeIdleToken++   // 有滚轮操作 → 重置 1.5s 空闲倒计时
                val now = System.currentTimeMillis()
                val fast = lastSeekAt > 0L && (now - lastSeekAt) < 100L
                lastSeekAt = now
                val stepMs = if (fast) 5_000L else 1_000L
                val base = wheelSeekMs ?: PlayerController.currentPositionMs()
                val next = (base + steps * stepMs).coerceIn(0L, duration.coerceAtLeast(0L))
                // 滚动期间只更新本地累积值驱动进度条即时跟手，不调用 player.seekTo。
                // 网络流（飞牛/WebDAV）每次 seek 都要丢弃缓冲+重发 Range 请求+重新解码，
                // 逐格真 seek 会造成「转一格卡一下」。真正的 seek 延后到松手/空闲时一次性提交。
                wheelSeekMs = next
            }

            // 滚轮调音量：即时写音量，同样重置空闲倒计时。
            fun rotateVolume(steps: Int) {
                modeIdleToken++
                val next = (MusicBoxApp.instance.volume.value + steps * 0.04f).coerceIn(0f, 1f)
                scope.launch { MusicBoxApp.instance.setVolume(next) }
            }

            // 封面页默认滚轮行为：切歌。上一格 = 上一首，下一格 = 下一首。
            // 不节流（与专辑列表一致）：每格立即切一首，动画由 spring 连续追逐目标，
            // 快速连转也不会被反复打断；音效震动按 ClickWheel 默认的 60ms 触发。
            fun rotateSwitchSong(steps: Int) {
                if (steps > 0) PlayerController.next() else PlayerController.previous()
            }

            // 中心键短按：在 默认 → 进度 → 音量 → 默认 间循环切换，并重置空闲倒计时。
            // 进度更常用排在前面，音量不常用排在后面（模式数值不变：1=音量、2=进度）。
            fun switchWheelMode() {
                // 从进度模式切走时先提交未决进度：新顺序下进度不再是最后一档，
                // idle 恢复分支只在 wheelMode==2 时兜底，这里补一次以防丢失。
                // 正常松手已提交并同步基准，此处 abs<250 不会重复 seek。
                if (progressMode) {
                    val target = wheelSeekMs
                    if (target != null && kotlin.math.abs(target - progressStartMs) >= 250L) {
                        PlayerController.seekTo(target, persist = true)
                        progressStartMs = target
                    }
                }
                wheelMode = when (wheelMode) {
                    0 -> 2
                    2 -> 1
                    else -> 0
                }
                modeIdleToken++
                // 进入进度模式时以当前播放位置作为本地累积起点 + 基准
                if (wheelMode == 2) {
                    lastSeekAt = 0L
                    val pos = PlayerController.currentPositionMs()
                    wheelSeekMs = pos
                    progressStartMs = pos
                }
            }

            // 离开调节模式（MENU / 长按中心键 / 返回库首页）：提交未决进度并复位。
            fun leaveWheelMode() {
                if (progressMode) {
                    val target = wheelSeekMs
                    if (target != null && kotlin.math.abs(target - progressStartMs) >= 250L) {
                        PlayerController.seekTo(target, persist = true)
                    }
                }
                wheelSeekMs = null
                wheelMode = 0
                modeIdleToken++
            }

            // 滚轮转动结束（松手）：进度模式下立即提交当前预览位置为真正的 seek，
            // 但保持在进度模式（不切换 UI），直到静止 1.5s 由 idle 自动恢复默认模式。
            fun commitWheelRelease() {
                if (progressMode) {
                    val target = wheelSeekMs
                    if (target != null && kotlin.math.abs(target - progressStartMs) >= 250L) {
                        PlayerController.seekTo(target, persist = true)
                        progressStartMs = target   // 同步基准，空闲恢复时不再重复 seek
                    }
                    modeIdleToken++   // 松手也算一次操作，重新计时 1.5s
                }
            }

            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                // 播放错误直接可见（错误码 + 描述），完整堆栈写入「下载」目录日志
                if (playError != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "播放失败：$playError\n完整堆栈见「下载」目录日志",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { PlayerController.clearError() }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }

                if (current == null) {
                    // 队列为空也必须有滚轮：iPod 界面的唯一交互就是转轮，
                    // 没有滚轮时用户只能靠系统手势返回（不符合纯转轮交互）。
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("队列为空\n转动滚轮选择 · MENU 键返回", color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp), Alignment.Center) {
                        ClickWheel(
                            diameter = wheel,
                            isPlaying = false,
                            centerActive = false,
                            onRotate = { },
                            onPlayPause = { },
                            onPrevious = { },
                            onNext = { },
                            onRewind = { },
                            onForward = { },
                            onMenu = { },
                            onMenuHome = { navController.popBackStack("library", false) },
                            onConfirm = { }
                        )
                    }
                } else {
                    val c = current
                    // 中间区域：显示封面 / 歌词 / 队列；菜单打开时底部留白，避免与滚轮重叠
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(if (menuOpen) Modifier.padding(bottom = 10.dp) else Modifier)
                    ) {
                        Crossfade(
                            targetState = body,
                            animationSpec = tween(durationMillis = 180),
                            label = "ipodBody"
                        ) { view ->
                            when (view) {
                                "menu" -> QueueMenuList(
                                    queue = queue,
                                    selectedIndex = selectedQueueIndex,
                                    currentIndex = idx,
                                    playMode = playMode,
                                    onSelect = { selectedQueueIndex = it }
                                )
                                "lyrics" -> LyricsView(
                                    current = c,
                                    textColor = Color.White.copy(alpha = 0.55f),
                                    highlightColor = Color.White,
                                    contentVertical = 0.dp,
                                    largeText = true,
                                    fadeByDistance = true,
                                    scrollState = lyricsScroll
                                )
                                else -> NowPlayingArt(track = c)
                            }
                        }
                    }

                    // 进度条 / 音量条常驻（封面 / 歌词都显示）。两者放进固定高度容器，
                    // 切换时高度恒定，上方 weight(1f) 的封面区不会被挤压缩放（需求3）。
                    if (!menuOpen) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 26.dp)
                                .height(52.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (volumeActive) {
                                VolumeBar(volume = volume)
                            } else {
                                SeekBar(
                                    duration = duration,
                                    activeColor = BrandRed,
                                    thumbColor = BrandRed,
                                    inactiveColor = Color.White.copy(alpha = 0.22f),
                                    labelColor = Color.White.copy(alpha = 0.6f),
                                    emphasized = progressMode,
                                    // 进度模式滚轮调进度时，用本地累积值即时驱动进度条，做到连续跟手；
                                    // 非调进度时传 null，进度条照常显示实时播放位置。
                                    wheelPosition = if (progressMode) wheelSeekMs else null,
                                    onSeek = { PlayerController.seekTo(it, persist = true) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))

                    // 滚轮容器与设置页 / 列表页一致：底部 64dp + navigationBarsPadding
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp), Alignment.Center) {
                    ClickWheel(
                        diameter = wheel,
                        isPlaying = isPlaying,
                        centerActive = wheelMode != 0,
                        onRotate = { steps ->
                            when {
                                menuOpen -> moveQueueSelection(steps)
                                wheelMode == 2 -> {
                                    // 进度模式：滚轮调歌曲进度，慢转 1 秒/格、快滑自动放大步长，
                                    // 全程本地累积不卡顿，松手/空闲时一次性提交。
                                    seekByWheel(steps)
                                }
                                wheelMode == 1 -> {
                                    // 音量模式：滚轮即时调音量
                                    rotateVolume(steps)
                                }
                                showLyrics -> {
                                    // 默认模式 + 歌词界面：滚轮滚动歌词（保留原行为）
                                    scope.launch { lyricsScroll.scrollBy(steps * 120f) }
                                }
                                else -> {
                                    // 默认模式 + 封面界面：滚轮直接切歌
                                    rotateSwitchSong(steps)
                                }
                            }
                        },
                        // 滚轮松手即提交进度：滚到目标位置后手指离开环体，立即 seek
                        onRotateEnd = { commitWheelRelease() },
                        onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
                        // 长按播放键：进入歌曲详情（metadata + 收藏）
                        onPlayLongPress = { if (current != null) navController.navigate("songdetail") },
                        // 队列界面（menuOpen）：左右键与主页列表一致——短按上一行/下一行，
                        // 长按连续逐行移动；非队列界面保持原切歌/快进快退行为。
                        onPrevious = { if (menuOpen) moveQueueSelection(-1) else if (progressMode) seekBy(-5_000L) else PlayerController.previous() },
                        onNext = { if (menuOpen) moveQueueSelection(1) else if (progressMode) seekBy(5_000L) else PlayerController.next() },
                        onRewind = { if (menuOpen) moveQueueSelection(-1) else seekBy(-5_000L) },
                        onForward = { if (menuOpen) moveQueueSelection(1) else seekBy(5_000L) },
                        onMenu = { menuOpen = !menuOpen; if (menuOpen) leaveWheelMode() },
                        onMenuHome = { menuOpen = false; leaveWheelMode(); navController.popBackStack("library", false) },
                        onConfirm = {
                            when {
                                menuOpen -> {
                                    if (selectedQueueIndex in queue.indices) {
                                        PlayerController.playFromList(queue, selectedQueueIndex)
                                        menuOpen = false
                                    }
                                }
                                // 短按中心键：默认 → 进度 → 音量 → 默认，循环切换
                                else -> switchWheelMode()
                            }
                        },
                        // 长按中心键：切换歌词界面（同时退出调节模式）
                        onCenterLongPress = {
                            showLyrics = !showLyrics
                            leaveWheelMode()
                        },
                        // 队列界面长按逐行移动时每行震动，与主页列表一致
                        hapticOnLongRepeat = menuOpen
                    )
                    }
                }
            }
        }
    }

    // 播放队列：随机 / 循环等模式按钮都在这一页
    QueueHost(
        showQueue = showQueue, queue = queue, currentIndex = idx,
        shuffle = shuffle, repeatMode = repeatMode
    ) { showQueue = false }
    } // CompositionLocalProvider(LocalGlassBackdrop)
}

/**
 * MENU 打开的本页队列：滚轮上下选歌，中心键确认播放。
 * 不跳转主界面，选完直接回到封面。
 */
@Composable
private fun QueueMenuList(
    queue: List<Track>,
    selectedIndex: Int,
    currentIndex: Int,
    playMode: PlayMode,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in queue.indices) {
            runCatching { listState.animateScrollToItem(selectedIndex) }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("播放队列", style = MaterialTheme.typography.titleSmall, color = Color.White)
            Spacer(Modifier.weight(1f))
            // 播放模式指示取代原「滚轮选歌 · 中心确认」：摇一摇在本页切换，图标沿用既有的三态样式
            val modeIcon = when (playMode) {
                PlayMode.SHUFFLE -> Icons.Default.Shuffle
                PlayMode.LIST -> Icons.Default.Repeat
                PlayMode.ONE -> Icons.Default.RepeatOne
            }
            val modeLabel = when (playMode) {
                PlayMode.SHUFFLE -> "随机播放"
                PlayMode.LIST -> "列表循环"
                PlayMode.ONE -> "单曲循环"
            }
            Icon(modeIcon, contentDescription = modeLabel, tint = BrandRed, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(modeLabel, style = MaterialTheme.typography.labelSmall, color = BrandRed)
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadeInList(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(queue) { i, t ->
                val selected = i == selectedIndex
                val rowShape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassHighlight(selected, rowShape)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverImage(
                        t.coverUrl,
                        Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)),
                        embeddedAudioUrl = t.embeddedAudioUrl
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.width(22.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            t.artist.ifBlank { "未知歌手" },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (i == currentIndex) {
                        Text("♪", color = Color(0xFFFA2D48), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/**
 * 中心 Home 键触发的「就地进度 HUD」：贴在转轮上方的一小块玻璃浮层，不占用 / 替换封面区。
 * 只有这一块订阅 position（500ms 一次），不会牵动整页重组。
 */
@Composable
private fun WheelProgressHud(durationMs: Long) {
    val positionMs by PlayerController.position.collectAsState()
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.34f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("歌曲进度", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
        Spacer(Modifier.height(6.dp))
        Text(formatDuration(positionMs), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFFFA2D48)))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

/**
 * 中间主区域：大封面 + 歌名 / 歌手。
 * 歌词页一律通过长按中心键切换，封面不再可点，避免与「短按中心键切进度」的滚轮交互冲突。
 * 封面尺寸取「可用宽」与「可用高 - 文字区」的较小值，小屏上不会把歌名挤出去。
 */
@Composable
private fun NowPlayingArt(
    track: Track
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        val art = minOf(maxWidth, (maxHeight - 108.dp).coerceAtLeast(72.dp))
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 切歌封面采用与专辑列表同一套连续 3D 动画，但只显示当前一张、不漏出邻封。
            CoverFlowSwitcher(art = art)
            Spacer(Modifier.height(18.dp))
            if (track.isHiRes || track.qualityLabel.isNotBlank()) {
                HiResBadge(track, onDark = true)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                track.artist.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * CoverFlow 切歌动画（与专辑列表同一手感，但不露出邻封）：
 * 维护一个连续的「虚拟唱片架位置」[position]（浮点队列索引），切歌只是改变它的目标值，
 * 用 spring 平滑追逐。这样快速连转滚轮时目标连续更新、动画连续跟随，不会像旧的一次性
 * tween 那样每次从 0 重新翻、被反复打断。3D 参数（42° / 0.28 缩放 / 14f 景深）与专辑
 * ClickCoverFlow 完全一致；唯一区别是相邻封面在 norm=±1 时 alpha 归零——静止时屏幕上
 * 只有当前一张，不漏出别的封面。
 */
@Composable
private fun CoverFlowSwitcher(art: Dp) {
    val idx by PlayerController.currentIndex.collectAsState()
    val queue by PlayerController.queue.collectAsState()
    val density = LocalDensity.current
    val artPx = with(density) { art.toPx() }

    // 连续虚拟位置：spring 追逐目标索引；仅当队列整体替换造成超大跳变（>10 首）时才直接
    // 定位，避免跨越整段动画。正常滚轮连转时即便一帧跨几首也照常动画——spring 会平滑
    // 追逐、逐首划过，与专辑 LazyRow 的连续滚动手感一致。
    val position = remember { Animatable(idx.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(idx) {
        if (idx < 0) return@LaunchedEffect
        if (abs(idx - position.value) > 10f) position.snapTo(idx.toFloat())
        else position.animateTo(
            targetValue = idx.toFloat(),
            animationSpec = spring(stiffness = 300f, dampingRatio = 0.78f)
        )
    }

    // 渲染窗口跟随虚拟位置逐首推进：只在跨越整数首时重组一次，3D 变换仍由 graphicsLayer
    // 每帧快照读取 position.value 更新——与专辑同样的「只动图层、不重组整页」做法。
    var centerIndex by remember { mutableIntStateOf(idx.coerceAtLeast(0)) }
    LaunchedEffect(Unit) {
        snapshotFlow { position.value.roundToInt() }
            .distinctUntilChanged()
            .collect { centerIndex = it }
    }

    Box(Modifier.size(art), contentAlignment = Alignment.Center) {
        // 仅渲染当前索引 ±1；norm=±1 时 alpha=0，静止时只有当前封面可见。
        for (i in (centerIndex - 1)..(centerIndex + 1)) {
            val t = queue.getOrNull(i) ?: continue
            CoverFlowCard(
                track = t,
                art = art,
                modifier = Modifier.graphicsLayer {
                    // 与专辑 ClickCoverFlow 同一套 3D 参数；i 在位置右侧 norm>0（下一首从右进入）。
                    val norm = (i - position.value).coerceIn(-1f, 1f)
                    rotationY = -norm * 42f
                    val s = 1f - abs(norm) * 0.28f
                    scaleX = s
                    scaleY = s
                    // 不漏出别的封面：相邻封面 norm=±1 时归零，翻转途中两张平滑交叉淡化。
                    alpha = (1f - norm * norm).coerceIn(0f, 1f)
                    translationX = norm * artPx * 0.4f
                    cameraDistance = 14f * density.density
                }
            )
        }
    }
}

/** CoverFlow 单张封面卡片：圆角 + 投影，变换由外部 modifier 注入。 */
@Composable
private fun CoverFlowCard(track: Track, art: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(art)
            .shadow(20.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
    ) {
        CoverImage(
            track.coverUrl,
            Modifier.fillMaxSize(),
            embeddedAudioUrl = track.embeddedAudioUrl
        )
    }
}

/** 播放队列弹层：横竖屏共用。随机 / 循环开关收在这一页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueHost(
    showQueue: Boolean,
    queue: List<Track>,
    currentIndex: Int,
    shuffle: Boolean,
    repeatMode: Int,
    onDismiss: () -> Unit
) {
    if (!showQueue) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        QueueSheet(
            queue = queue,
            currentIndex = currentIndex,
            shuffle = shuffle,
            repeatMode = repeatMode,
            onSelect = { i ->
                // 点当前曲目：playFromList 保持进度、暂停态恢复播放；点其它曲目正常起播
                PlayerController.playFromList(queue, i)
                onDismiss()
            }
        )
    }
}

/**
 * 横屏播放页：左侧封面（完整显示）+ 右侧滚动歌词，整页沉浸式。
 * 背景取当前封面的主色调，并把封面降采样后拉伸 + 模糊作为底图。
 */
@Composable
private fun LandscapePlayer(
    navController: NavController,
    track: Track?,
    duration: Long,
    isPlaying: Boolean,
    playError: String?,
    onQueueClick: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // 诊断：对比实际可用宽度与屏幕宽度（仅在尺寸变化时记一次，
        // 不要放在组合体里——每次重组都写日志会拖慢主线程）
        val cfg = LocalConfiguration.current
        LaunchedEffect(maxWidth, cfg.screenWidthDp, cfg.screenHeightDp) {
            CrashLogger.info(
                "Player.land",
                "maxW=${maxWidth.value.toInt()}dp maxH=${maxHeight.value.toInt()}dp " +
                    "screenW=${cfg.screenWidthDp}dp screenH=${cfg.screenHeightDp}dp"
            )
        }
        CoverBackdrop(track)

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        track?.title ?: "队列为空",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (track != null) {
                        Text(
                            track.artist.ifBlank { "未知歌手" },
                            color = Color.White.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onQueueClick) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "播放队列", tint = Color.White)
                }
            }

            if (playError != null) {
                Text(
                    "播放失败：$playError",
                    color = Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            if (track == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("队列为空", color = Color.White.copy(alpha = 0.7f))
                }
            } else {
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左：完整封面（按真实比例，无边框、无底板）
                    Box(
                        Modifier.weight(0.44f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        BareCover(
                            url = track.coverUrl,
                            embeddedAudioUrl = track.embeddedAudioUrl,
                            modifier = Modifier.fillMaxSize(),
                            contentDescription = track.title
                        )
                    }

                    Spacer(Modifier.width(18.dp))

                    // 右：规格徽标 + 歌词
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (track.isHiRes || track.qualityLabel.isNotBlank()) {
                            HiResBadge(track, onDark = true)
                            Spacer(Modifier.height(4.dp))
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LyricsView(
                                current = track,
                                textColor = Color.White.copy(alpha = 0.55f),
                                highlightColor = Color.White,
                                contentVertical = 48.dp
                            )
                        }
                    }
                }

                LandscapeControls(
                    duration = duration,
                    isPlaying = isPlaying
                )
            }
        }
    }
}

/** 横屏底部：一条细进度条 + 一排控制按钮（同样只保留上一首 / 播放 / 下一首）。 */
@Composable
private fun LandscapeControls(
    duration: Long,
    isPlaying: Boolean
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SeekBar(
            duration = duration,
            activeColor = Color.White,
            inactiveColor = Color.White.copy(alpha = 0.25f),
            thumbColor = Color.White,
            labelColor = Color.White.copy(alpha = 0.6f),
            onSeek = { PlayerController.seekTo(it) }
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { PlayerController.previous() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = { if (isPlaying) PlayerController.pause() else PlayerController.play() }) {
                Crossfade(
                    targetState = isPlaying,
                    animationSpec = tween(durationMillis = 140),
                    label = "playIconLand"
                ) { playing ->
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            IconButton(onClick = { PlayerController.next() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "下一首", tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
    }
}

/**
 * 播放页背景（Apple Music 风格）：封面取色 → 环境光流光渐变。
 *
 * 与首页/设置共用同一套 [AmbientMeshBackground]：不再把整张封面糊一层铺满，而是取封面的
 * 主色/鲜艳色/柔和色做三团缓慢游走的流光——颜色更干净、亮度可控，白字也不会糊。
 * 播放页的大封面本身就在前景显示，背景再糊一张只是重复。
 *
 * 这一层同时作为滚轮玻璃的 backdrop 采样源（采样录制在组件内部完成）。
 */
@Composable
private fun CoverBackdrop(track: Track?) {
    val bitmap = rememberCoverBitmap(track?.coverUrl, track?.embeddedAudioUrl)
    AmbientMeshBackground(
        bitmap = bitmap,
        modifier = Modifier.fillMaxSize(),
        fallback = BrandRed
    )
}

/**
 * 统一进度条：细轨道 + 小圆点滑块（替代 Material3 1.3 默认的 44dp 高竖条，避免上下突兀）。
 * 拖动时只更新本地值，松手才 seek。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    duration: Long,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f),
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 进度模式：把红色进度标记加粗，作为「正在调进度」的就地反馈。 */
    emphasized: Boolean = false,
    /** 滚轮调进度时的本地累积位置：非 null 时优先显示它，做到逐格跟手、不受 500ms 轮询滞后影响。 */
    wheelPosition: Long? = null,
    onSeek: (Long) -> Unit
) {
    // 进度在此处自行订阅：500ms 的更新只会让这条进度条重组，不再牵动整页。
    val livePosition by PlayerController.position.collectAsState()
    var drag by remember(duration) { mutableStateOf<Float?>(null) }
    // 乐观预览值：手指滑动松手后先固定显示目标位置，等真实进度追上来再交回实时显示。
    // 避免「滑到新位置 → 松手瞬间跳回旧位置 → 500ms 后才刷新到新位置」的回跳（需求4）。
    var optimisticSeek by remember(duration) { mutableStateOf<Long?>(null) }
    LaunchedEffect(livePosition, optimisticSeek) {
        val target = optimisticSeek
        if (target != null && kotlin.math.abs(livePosition - target) <= 700L) {
            optimisticSeek = null   // 真实进度已追上目标，平滑交回实时显示
        }
    }
    LaunchedEffect(optimisticSeek) {
        if (optimisticSeek != null) {
            kotlinx.coroutines.delay(2500L)
            optimisticSeek = null   // seek 失败/超时兜底，避免永久卡在预览值
        }
    }
    val maxRange = duration.coerceAtLeast(1).toFloat()
    // 优先级：本地拖动 > 滚轮累积值 > 乐观预览 > 实时播放位置
    val displayMs = wheelPosition ?: optimisticSeek ?: livePosition
    val shown = drag ?: displayMs.toFloat().coerceIn(0f, maxRange)
    val trackH = if (emphasized) 8.dp else 3.dp
    val thumbS = if (emphasized) 16.dp else 10.dp

    Column(modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { target ->
                    optimisticSeek = target.toLong()
                    onSeek(target.toLong())
                }
                drag = null
            },
            valueRange = 0f..maxRange,
            modifier = Modifier.fillMaxWidth().height(26.dp),
            thumb = {
                Box(
                    Modifier
                        .size(thumbS)
                        .clip(CircleShape)
                        .background(thumbColor)
                )
            },
            track = { state ->
                val range = state.valueRange
                val frac = if (range.endInclusive > range.start)
                    ((state.value - range.start) / (range.endInclusive - range.start))
                        .coerceIn(0f, 1f)
                else 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(trackH)
                        .clip(CircleShape)
                        .background(inactiveColor)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(activeColor)
                    )
                }
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shown.toLong()), style = MaterialTheme.typography.bodySmall, color = labelColor)
            Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
    }
}

/**
 * 音量条：进度条就地的 iPod 式替换。滚轮调音量时显示，值为 0..1。
 * 只读展示（音量由转轮调节），高亮段用品牌红，标签显示百分比。
 */
@Composable
private fun VolumeBar(volume: Float) {
    val v = volume.coerceIn(0f, 1f)
    val a by animateFloatAsState(targetValue = 1f, animationSpec = tween(160), label = "volBar")
    Column(Modifier.fillMaxWidth().alpha(a)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(v)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(BrandRed)
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("音量", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            Text("${(v * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/**
 * 播放队列弹层：顶部是随机 / 循环三个模式开关（从播放页底部迁移过来），
 * 下方列表左侧显示专辑封面，点击条目才切歌，当前项高亮。
 */
@Composable
private fun QueueSheet(
    queue: List<Track>,
    currentIndex: Int,
    shuffle: Boolean,
    repeatMode: Int,
    onSelect: (Int) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 给头部 + 列表一个有限高度上限：Column 在 BottomSheet 里是 wrap-content，
    // 若内层 LazyColumn 收到无限高度约束会测量异常。
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        Text(
            "播放队列 · 共 ${queue.size} 首",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        // 模式开关：随机播放、单曲循环、列表循环（点击循环图标在 关→列表→单曲 间轮换）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (shuffle) primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { PlayerController.toggleShuffle() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "随机播放",
                    tint = if (shuffle) primary else dim,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "随机播放",
                    color = if (shuffle) primary else onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (repeatMode != Player.REPEAT_MODE_OFF) primary.copy(alpha = 0.14f)
                        else Color.Transparent
                    )
                    .clickable { PlayerController.cycleRepeat() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "循环模式",
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) primary else dim,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> "单曲循环"
                        Player.REPEAT_MODE_ALL -> "列表循环"
                        else -> "不循环"
                    },
                    color = if (repeatMode != Player.REPEAT_MODE_OFF) primary else onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .padding(bottom = 24.dp)
        ) {
        itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(t.coverUrl, Modifier.size(44.dp), embeddedAudioUrl = t.embeddedAudioUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (i == currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (t.artist.isNotBlank()) {
                        Text(
                            t.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (i == currentIndex) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "正在播放",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
}

/**
 * 歌词页：LRC 逐行高亮，随播放进度平滑滚动到当前行（居中）；
 * 点击某行可跳转到对应时间；手动拖动歌词时暂停自动跟随 4 秒。
 * 飞牛走歌词接口，WebDAV / M3U 尝试读取歌曲同目录下的同名 .lrc。
 */
@Composable
private fun LyricsView(
    current: Track,
    textColor: Color = Color.White.copy(alpha = 0.62f),
    highlightColor: Color = Color(0xFFFF6B7F),
    contentVertical: Dp = 140.dp,
    /** 竖屏下歌词在第二页：只有真正滑到这一页才去拉歌词，避免每切一首就白下 2MB 文件头。 */
    active: Boolean = true,
    /** 整合版大歌词：字号更大、左对齐、离当前句越远越淡（贴近参考图）。 */
    largeText: Boolean = false,
    fadeByDistance: Boolean = false,
    /** 外部滚动状态：播放页滚轮可直接滚动歌词。 */
    scrollState: ScrollState? = null
) {
    // 进度只在这里被「间接」读取：derivedStateOf 内部取进度算当前行号，
    // 结果（Int）不变时不会让歌词树重组——省掉每 500ms 一次的全量歌词重排。
    val posState = PlayerController.position.collectAsState()
    var text by remember(current) { mutableStateOf<String?>(null) }
    var loading by remember(current) { mutableStateOf(true) }

    LaunchedEffect(current, active) {
        if (!active) return@LaunchedEffect
        loading = true
        text = fetchLyrics(current)
        loading = false
    }

    when {
        loading -> CenteredHint("加载歌词中…", color = textColor)
        text.isNullOrBlank() -> CenteredHint(
            if (current.source == SourceType.FNOS) "暂无歌词"
            else "暂无歌词\n（可将与歌曲同名的 .lrc 放在同一目录）",
            color = textColor
        )
        else -> {
            val raw = text!!
            // 播放进度每 500ms 触发一次重组，解析结果按歌词原文缓存，避免反复 parse
            val lines = remember(raw) { parseLrc(raw) }
            // 当前行号用 derivedStateOf 计算：进度每 500ms 变一次，但只要行号没变，
            // 读取它的歌词树就不会失效，避免了整段歌词每 500ms 重新排版。
            val currentLine by remember(lines) {
                derivedStateOf {
                    if (lines.isEmpty()) 0
                    else lines.indexOfLast { it.first <= posState.value }.coerceAtLeast(0)
                }
            }
            if (lines.isEmpty()) {
                // 没有时间轴的纯文本歌词：居中排版，滚轮 / 手动都能上下滚动
                val plainScroll = scrollState ?: rememberScrollState()
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(plainScroll)
                        .padding(24.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        raw,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                TimedLyrics(
                    lines = lines,
                    currentLine = currentLine,
                    textColor = textColor,
                    highlightColor = highlightColor,
                    minVerticalPadding = contentVertical,
                    largeText = largeText,
                    fadeByDistance = fadeByDistance,
                    scrollState = scrollState,
                    onSeek = { PlayerController.seekTo(it) }
                )
            }
        }
    }
}

/**
 * 带时间轴的歌词：当前句**始终严格居中**。
 *
 * 之前用 LazyColumn + `animateScrollToItem(index, -(viewport/2) + 48)` 靠估算偏移，
 * 一是忽略了行高（首尾、换行行高各不相同），二是懒加载下未测量的行拿不到位置，
 * 结果当前句总是偏上、且行高变化时会跳。这里改为：
 * 1. 整段歌词放进一个可滚动 Column（歌词行数很少，全量布局开销可忽略）；
 * 2. 每行布局完成后记录它在内容坐标系里的**中心 Y**；
 * 3. 滚动目标 = 行中心 - 视口高度/2，即让该行中心与视口中心重合；
 * 4. 首尾各留「半个视口」空白，保证第一句 / 最后一句也能居中。
 */
@Composable
private fun TimedLyrics(
    lines: List<Pair<Long, String>>,
    currentLine: Int,
    textColor: Color,
    highlightColor: Color,
    minVerticalPadding: Dp,
    largeText: Boolean = false,
    fadeByDistance: Boolean = false,
    scrollState: ScrollState? = null,
    onSeek: (Long) -> Unit
) {
    val fallbackScroll = rememberScrollState()
    val scroll = scrollState ?: fallbackScroll
    val centers = remember(lines) { mutableStateMapOf<Int, Int>() }
    // 程序滚动时 isScrollInProgress 也会变 true，不能把它当成「用户拖动」
    var programmatic by remember { mutableStateOf(false) }
    var resumeAt by remember(lines) { mutableLongStateOf(0L) }

    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress && !programmatic) {
            resumeAt = System.currentTimeMillis() + 4000L
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportPx = with(density) { maxHeight.roundToPx() }
        // 上下留白至少半个视口，才能把第一句和最后一句也顶到正中间
        val pad = maxOf(maxHeight / 2, minVerticalPadding)

        LaunchedEffect(currentLine, viewportPx) {
            if (viewportPx <= 0) return@LaunchedEffect
            if (System.currentTimeMillis() < resumeAt) return@LaunchedEffect
            // 行位置要等布局完成才拿得到：等该行的中心出现
            val center = snapshotFlow { centers[currentLine] }.filterNotNull().first()
            val target = (center - viewportPx / 2).coerceIn(0, scroll.maxValue)
            if (abs(scroll.value - target) <= 2) return@LaunchedEffect
            programmatic = true
            try {
                scroll.animateScrollTo(target)
            } finally {
                programmatic = false
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(scroll),
            horizontalAlignment = if (largeText) Alignment.Start else Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(pad))
            lines.forEachIndexed { i, (time, line) ->
                val active = i == currentLine
                // 离当前句越远越淡，营造参考图里「近处清晰、远处隐入背景」的层次
                val distance = abs(i - currentLine)
                val fade = if (fadeByDistance)
                    (1f - distance * 0.22f).coerceAtLeast(0.16f) else 1f
                Text(
                    line.ifBlank { "♪" },
                    style = when {
                        largeText && active -> MaterialTheme.typography.headlineSmall
                        largeText -> MaterialTheme.typography.titleLarge
                        active -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) highlightColor else textColor.copy(alpha = textColor.alpha * fade),
                    textAlign = if (largeText) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            val c = coords.positionInParent().y.roundToInt() + coords.size.height / 2
                            if (centers[i] != c) centers[i] = c
                        }
                        .clickable { onSeek(time) }
                        .padding(
                            horizontal = if (largeText) 24.dp else 0.dp,
                            vertical = if (largeText) 14.dp else 8.dp
                        )
                )
            }
            Spacer(Modifier.height(pad))
        }
    }
}

/** 歌词加载：内嵌 metadata 优先，其次同目录同名 .lrc；飞牛走接口（见 [LyricsRepo]）。 */
private suspend fun fetchLyrics(track: Track): String? = LyricsRepo.load(track)

@Composable
private fun CenteredHint(message: String, color: Color = Color.White.copy(alpha = 0.6f)) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = color, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

private fun parseLrc(text: String): List<Pair<Long, String>> {
    val regex = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")
    val out = mutableListOf<Pair<Long, String>>()
    for (raw in text.lineSequence()) {
        val matches = regex.findAll(raw).toList()
        if (matches.isEmpty()) continue
        val content = raw.replace(regex, "").trim()
        for (m in matches) {
            val min = m.groupValues[1].toLongOrNull() ?: 0
            val sec = m.groupValues[2].toDoubleOrNull() ?: 0.0
            out.add((min * 60_000 + (sec * 1000).toLong()) to content)
        }
    }
    return out.sortedBy { it.first }
}
