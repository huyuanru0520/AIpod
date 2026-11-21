package com.aipod.ui.screens

import android.content.res.Configuration
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.drawPlainBackdrop
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import com.aipod.ui.components.rememberAmbientPalette
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
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

    // 倒影随动：倾斜检测提升到页面顶层，背景环境光与封面倒影共用同一份采样（只占一个传感器、省电），
    // 并让「背景微移 / 封面微移 / 倒影大移」形成速度差，骗出空间感。
    // 三个 MutableFloatState 由传感器回调直写，消费端只在 graphicsLayer lambda 内读取，不触发整页重组。
    val reflectionTiltOn by MusicBoxApp.instance.reflectionTilt.collectAsState()
    val tiltX = remember { mutableFloatStateOf(0f) }
    val tiltY = remember { mutableFloatStateOf(0f) }
    val tiltSpeed = remember { mutableFloatStateOf(0f) }
    val tiltContext = LocalContext.current
    val tiltDetector = remember { com.aipod.util.TiltDetector(tiltContext) }
    DisposableEffect(tiltDetector, reflectionTiltOn) {
        tiltDetector.onTilt = { x, y, s ->
            tiltX.floatValue = x
            tiltY.floatValue = y
            tiltSpeed.floatValue = s
        }
        if (reflectionTiltOn && tiltDetector.isAvailable()) tiltDetector.start()
        onDispose {
            tiltDetector.stop()
            tiltDetector.onTilt = null
        }
    }
    LaunchedEffect(reflectionTiltOn) {
        // 关闭开关：立即回正、速度归零，倒影静止但照常显示；传感器已在上面 stop，不耗电。
        if (!reflectionTiltOn) {
            tiltX.floatValue = 0f
            tiltY.floatValue = 0f
            tiltSpeed.floatValue = 0f
        }
    }

    // 液态玻璃采样源：背景层录制进该 backdrop，滚轮玻璃从同一实例采样（兄弟配对）。
    val glassBackdrop = rememberGlassBackdrop()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassBackdrop provides glassBackdrop
    ) {
    Box(Modifier.fillMaxSize()) {
        // 竖屏背景与横屏一致：模糊当前封面 + 主色调 + 暗化渐变
        CoverBackdrop(current, tiltX, tiltY)
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
                                else -> NowPlayingArt(track = c, tiltX, tiltY, tiltSpeed)
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
 * 中间主区域：大封面 + 紧贴倒影 + 单行信息（Hi-Res / 歌名 / 歌手）。
 * 歌词页一律通过长按中心键切换，封面不再可点，避免与「短按中心键切进度」的滚轮交互冲突。
 * 封面尺寸预留倒影与单行信息的高度，小屏上也不会挤出屏幕。
 *
 * 倾斜值由页面顶层的单个传感器提供（[tiltX] 左右 / [tiltY] 前后 / [tiltSpeed] 角速度），
 * 全部在 graphicsLayer / RenderEffect 的 lambda 内读快照值，只更新绘制图层、不重组本页。
 */
@Composable
private fun NowPlayingArt(
    track: Track,
    tiltX: androidx.compose.runtime.MutableFloatState,
    tiltY: androidx.compose.runtime.MutableFloatState,
    tiltSpeed: androidx.compose.runtime.MutableFloatState
) {
    val density = LocalDensity.current
    // 动态模糊档位预建一次、按速度复用引用：绝不每帧 new BlurEffect（那是卡顿主因）。
    val blurStages = remember(density) {
        val d = density.density
        listOf(2.2f, 3.0f, 3.8f, 4.6f, 5.4f).map { r ->
            androidx.compose.ui.graphics.BlurEffect(r * d, r * d)
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        // 倒影缩短到封面的 18%，单行信息约 40dp；据可用高反推封面尺寸，给倒影下方留呼吸空间。
        val art = minOf(maxWidth, ((maxHeight - 52.dp) / 1.22f).coerceAtLeast(72.dp))
        val reflectionH = art * 0.18f
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 封面本体随倾斜只在水平方向做「反向、更小」的移动：与倒影的「同向、更大」移动形成相对错位（视差）；垂直方向不动。
            Box(
                Modifier.graphicsLayer {
                    translationX = tiltX.floatValue * art.toPx() * 0.035f
                }
            ) {
                // 切歌封面采用与专辑列表同一套连续 3D 动画，但只显示当前一张、不漏出邻封。
                CoverFlowSwitcher(art = art)
            }

            // 倒影直接紧贴封面底边，中间不加任何高光/分隔线。
            // 玻璃台面式倒影：封面垂直翻转 + 压缩 + 随速度变化的动态模糊；
            // 遮罩顶部透明度提到 0.42 并指数衰减（上半保留车顶/文字轮廓），下半快速融进背景。
            // 纯平面视差：倾斜只驱动水平位移，倒影始终是一张平面镜像、不做 3D 透视、垂直方向不动。
            val hasCover = !track.coverUrl.isNullOrBlank() || !track.embeddedAudioUrl.isNullOrBlank()
            if (hasCover) {
                Box(
                    Modifier
                        .width(art)
                        .height(reflectionH)
                        // 只做水平位移、无旋转：圆角保持平面裁切即可。
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            translationX = tiltX.floatValue * art.toPx() * 0.13f
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .drawWithContent {
                            drawContent()
                            // 指数式衰减：0.42 起步、前段缓慢（保留 Antique/车顶轮廓），后段快速归零。
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Black.copy(alpha = 0.42f),
                                        0.22f to Color.Black.copy(alpha = 0.30f),
                                        0.50f to Color.Black.copy(alpha = 0.12f),
                                        0.72f to Color.Black.copy(alpha = 0.03f),
                                        0.88f to Color.Transparent,
                                        1f to Color.Transparent
                                    )
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    CoverImage(
                        track.coverUrl,
                        Modifier
                            .fillMaxSize()
                            // 动态模糊：从预建的 5 档里按角速度选一档复用引用，只跨档时切换、不逐帧分配 native 对象。
                            .graphicsLayer {
                                val i = (tiltSpeed.floatValue * (blurStages.size - 1)).toInt()
                                    .coerceIn(0, blurStages.lastIndex)
                                renderEffect = blurStages[i]
                                // 垂直翻转 = 镜像；scaleY 绝对值 0.80 → 竖直压短 20%，台面透视更强。
                                scaleY = -0.80f
                            },
                        embeddedAudioUrl = track.embeddedAudioUrl,
                        contentScale = ContentScale.Crop,
                        // 内层不裁角（交外层统一裁），避免双层圆角叠加出现直角残边。
                        shape = androidx.compose.ui.graphics.RectangleShape
                    )
                }
            }

            Spacer(Modifier.height(if (hasCover) 10.dp else 18.dp))
            // Hi-Res 徽章 + 歌名 + 歌手名合并到同一行。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 只保留 Hi-Res 徽章，不再显示 24bit / 48kHz 等采样率规格文字。
                if (track.isHiRes) {
                    HiResBadge(track, showSpec = false, onDark = true)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    track.artist.ifBlank { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
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
private fun CoverBackdrop(
    track: Track?,
    tiltX: androidx.compose.runtime.MutableFloatState = androidx.compose.runtime.mutableFloatStateOf(0f),
    tiltY: androidx.compose.runtime.MutableFloatState = androidx.compose.runtime.mutableFloatStateOf(0f)
) {
    val bitmap = rememberCoverBitmap(track?.coverUrl, track?.embeddedAudioUrl)
    AmbientMeshBackground(
        bitmap = bitmap,
        // 背景环境光随倾斜只在水平方向做「反向、更慢」的微小移动：与前景倒影形成速度差，骗出空间感；垂直方向不动。
        // graphicsLayer 在 lambda 内读快照值，只更新绘制图层、不重组；放大 6% 防止平移露边。
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.06f
                scaleY = 1.06f
                translationX = -tiltX.floatValue * 26f
            },
        fallback = BrandRed,
        // 用封面 URL 做缓存 key：从别的页面切回来时直接命中上次的取色，不再闪兜底红
        cacheKey = track?.coverUrl ?: track?.embeddedAudioUrl
    )
}

/**
 * 液态玻璃进度条：胶囊底槽 + 缓冲层 + 流动液体 Fill + 玻璃水珠。
 *
 * 分层（都画在一条 7~10dp 的胶囊里）：
 * - 底槽：极低透明度（10%）白/黑玻璃 + 真实背景模糊（drawPlainBackdrop blur）+ 顶部内阴影
 *   + 上下 0.6px 极细描边（上亮下暗），是一层"浅浅的凹槽"而不是实心塑料底座；
 * - 缓冲层：强调色 30% alpha；
 * - Fill：70~80% 半透明、左暗右亮的强调色渐变（背景环境色透出）+ 横向游走光斑
 *   + 顶部 1px 锐利白色高光实线；
 * - Thumb：高透水珠（微弱白色径向渐变）+ 左上极小纯白高光点 + 右下暗边内阴影 + 极淡拖尾，
 *   去掉了原来那圈像玩具塑料珠的均匀泛光。
 *
 * 交互：按下时整条从 7dp 变 10dp、底槽提亮；拖动时水珠按横向速度拉伸、纵向挤压，
 * 松手用 spring 回弹。触控热区 36dp，视觉高度只有 7~10dp。
 *
 * 取色：复用 [rememberAmbientPalette] 的按封面 LRU 缓存，切页不闪兜底红；
 * 强调色与底色亮度差不足时自动提亮/压暗，避免进度条在流光背景上隐形。
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
    /** 进度模式：把进度标记加粗，作为「正在调进度」的就地反馈。 */
    emphasized: Boolean = false,
    /** 滚轮调进度时的本地累积位置：非 null 时优先显示它，做到逐格跟手。 */
    wheelPosition: Long? = null,
    onSeek: (Long) -> Unit
) {
    val livePosition by PlayerController.position.collectAsState()
    val buffered by PlayerController.buffered.collectAsState()

    // 强调色跟着当前封面走。取色本身有 LRU 缓存，切页/重载不会重新算也不会闪红。
    val queue by PlayerController.queue.collectAsState()
    val idx by PlayerController.currentIndex.collectAsState()
    val current = if (idx in queue.indices) queue[idx] else null
    val coverKey = current?.coverUrl ?: current?.embeddedAudioUrl
    val cover = rememberCoverBitmap(current?.coverUrl, current?.embeddedAudioUrl)
    val palette = rememberAmbientPalette(cover, BrandRed, coverKey)
    val accent = contrastingAccent(palette.primary, palette.base)
    // 极端亮色封面时把玻璃反过来（黑槽 + 黑描边）；当前流光背景恒暗，这条是保险。
    val glass = if (luma(palette.base) > 0.45f) Color.Black else Color.White

    var drag by remember(duration) { mutableStateOf<Float?>(null) }
    var optimisticSeek by remember(duration) { mutableStateOf<Long?>(null) }
    LaunchedEffect(livePosition, optimisticSeek) {
        val target = optimisticSeek
        if (target != null && kotlin.math.abs(livePosition - target) <= 700L) optimisticSeek = null
    }
    LaunchedEffect(optimisticSeek) {
        if (optimisticSeek != null) {
            kotlinx.coroutines.delay(2500L)
            optimisticSeek = null
        }
    }
    val maxRange = duration.coerceAtLeast(1).toFloat()
    val displayMs = wheelPosition ?: optimisticSeek ?: livePosition
    val shown = drag ?: displayMs.toFloat().coerceIn(0f, maxRange)

    // 按压与拖拽形变
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed || drag != null || wheelPosition != null
    var stretch by remember { mutableFloatStateOf(1f) }
    var tailDir by remember { mutableFloatStateOf(0f) }
    var lastDragAt by remember { mutableLongStateOf(0L) }
    var lastDragValue by remember { mutableFloatStateOf(0f) }
    val stretchAnim by animateFloatAsState(
        stretch, spring(dampingRatio = 0.5f, stiffness = 420f), label = "seekStretch"
    )
    val tailAnim by animateFloatAsState(
        tailDir, spring(dampingRatio = 0.6f, stiffness = 340f), label = "seekTail"
    )

    // 液体光斑的横向游走：4.8s 一个来回，只重绘这条小进度条
    val flow by produceState(0f) {
        var t = 0f
        while (true) {
            t += 0.009f
            if (t > 1f) t -= 1f
            value = t
            delay(60)
        }
    }

    val trackH = if (active || emphasized) 10.dp else 7.dp
    val thumbS = if (active || emphasized) 20.dp else 16.dp
    val bufFrac = (buffered.toFloat() / maxRange).coerceIn(0f, 1f)

    Column(modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { v ->
                val now = android.os.SystemClock.uptimeMillis()
                val first = drag == null
                val dt = (now - lastDragAt).coerceIn(1L, 120L)
                // 第一次按下时 lastDragValue 还是 0，不能拿它算速度，否则水珠一按就被拉满
                val delta = if (first) 0f else v - lastDragValue
                val speed = if (first) 0f else kotlin.math.abs(delta) / dt
                lastDragAt = now
                lastDragValue = v
                // 拖得越快拉伸越明显（上限 +26%，比之前更克制，避免像橡皮筋），纵向等量挤压
                stretch = 1f + (speed * 150f).coerceIn(0f, 0.26f).toFloat()
                tailDir = (delta * 900f).coerceIn(-1f, 1f)
                drag = v
            },
            onValueChangeFinished = {
                drag?.let { target ->
                    optimisticSeek = target.toLong()
                    onSeek(target.toLong())
                }
                drag = null
                stretch = 1f
                tailDir = 0f
            },
            valueRange = 0f..maxRange,
            interactionSource = interactionSource,
            // 36dp 隐形触控热区，视觉仍只有 7~10dp
            modifier = Modifier.fillMaxWidth().height(36.dp),
            thumb = {
                Box(
                    Modifier
                        .size(thumbS)
                        .graphicsLayer {
                            scaleX = stretchAnim
                            scaleY = 1f / stretchAnim.coerceAtLeast(1f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawLiquidDroplet(center, size.minDimension / 2f, glass, tailAnim)
                    }
                }
            },
            track = { state ->
                val range = state.valueRange
                val frac = if (range.endInclusive > range.start)
                    ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                else 0f
                Box(
                    Modifier.liquidTrackModifier(
                        backdrop = LocalGlassBackdrop.current,
                        shape = RoundedCornerShape(percent = 50),
                        frac = frac,
                        bufFrac = bufFrac,
                        accent = accent,
                        glass = glass,
                        active = active,
                        flow = flow,
                        height = trackH
                    )
                )
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shown.toLong()), style = MaterialTheme.typography.bodySmall, color = labelColor)
            Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
    }
}

/**
 * 液态轨道的四层绘制（底槽 / 缓冲 / Fill / 顶部高光），进度条与音量条共用。
 *
 * ★ 底槽的真实背景模糊由调用方 [drawPlainBackdrop] 的 effects 完成（只 blur、不 lens、
 *   不带库默认高光阴影），这里只画极低透明度的玻璃层与描边——避免「玻璃叠玻璃」发灰。
 *
 * @param frac 已播放比例 0..1
 * @param bufFrac 缓冲比例 0..1（音量条传 0）
 * @param accent 强调色（进度条取封面色，音量条用品牌红）
 * @param glass 玻璃基色（亮底反相为黑，暗底为白）
 * @param active 按压/拖拽态：底槽提亮
 */
private fun DrawScope.drawLiquidTrack(
    frac: Float,
    bufFrac: Float,
    accent: Color,
    glass: Color,
    active: Boolean,
    flow: Float
) {
    val w = size.width
    val h = size.height
    val rr = h / 2f
    val radius = CornerRadius(rr, rr)

    // 1) 底槽：极低透明度的磨砂凹槽（背景模糊来自 drawPlainBackdrop）
    drawRoundRect(glass.copy(alpha = if (active) 0.16f else 0.10f), cornerRadius = radius)
    // 顶部内阴影：极弱，制造凹陷
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.12f),
            0.40f to Color.Transparent
        ),
        cornerRadius = radius
    )
    // 2) 上下极细描边：上亮下暗，像玻璃表面的一层浅凹槽
    drawLine(
        color = glass.copy(alpha = 0.34f),
        start = Offset(rr, 0.6f), end = Offset(w - rr, 0.6f),
        strokeWidth = 0.6f
    )
    drawLine(
        color = glass.copy(alpha = 0.10f),
        start = Offset(rr, h - 0.6f), end = Offset(w - rr, h - 0.6f),
        strokeWidth = 0.6f
    )

    // 3) 缓冲层：未播放区域的 30% 强调色
    if (bufFrac > frac + 0.005f) {
        drawRoundRect(
            color = accent.copy(alpha = 0.30f),
            size = Size(w * bufFrac, h),
            cornerRadius = radius
        )
    }

    val fw = w * frac
    if (fw > 1f) {
        // 4) Fill：70~80% 半透明的左暗右亮渐变，让背景环境色透进来（玻璃折射感）
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(
                    darken(accent, 0.72f).copy(alpha = 0.72f),
                    accent.copy(alpha = 0.80f)
                ),
                startX = 0f, endX = fw
            ),
            size = Size(fw, h),
            cornerRadius = radius
        )
        // 缓慢横向游走的光斑（液体流动）
        val shift = (flow * 1.6f - 0.3f) * fw
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.22f), Color.Transparent),
                startX = shift - fw * 0.35f, endX = shift + fw * 0.35f
            ),
            size = Size(fw, h),
            cornerRadius = radius,
            blendMode = BlendMode.Screen
        )
    }

    // 5) 顶部锐利高光：1px 实线，只在最顶部，贯穿底槽与已播放部分
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(
                glass.copy(alpha = 0.28f),
                Color.White.copy(alpha = 0.72f),
                Color.White.copy(alpha = 0.42f),
                glass.copy(alpha = 0.14f)
            )
        ),
        start = Offset(rr, 1.2f), end = Offset(w - rr, 1.2f),
        strokeWidth = 1f
    )
}

/**
 * 水珠 Thumb：中心完全透出下方 Fill，只在边缘做菲涅尔折射。
 *
 * 之前那颗"塑料珠"的假感来自两处：①整圈均匀的 accent 描边；②中心一团白雾。
 * 现在改为——中心透明、边缘由内到外微微提亮（光在珠体边缘折射增厚），
 * 再叠一段左上锐利亮弧 + 一段右下极淡暗弧（光从左上打来的方向感），
 * 配一颗左上小高光点与右下环境反光点。没有均匀外环、没有白心。
 *
 * @param tail 拖拽方向带符号的形变（-1..1），0 为静止（音量条端点传 0）
 */
private fun DrawScope.drawLiquidDroplet(
    c: Offset,
    r: Float,
    glass: Color,
    tail: Float
) {
    val u = 1.dp.toPx()
    // 0) 拖拽粘性拖尾（很淡，只在真正拖动时出现）
    val t = kotlin.math.abs(tail)
    if (t > 0.02f) {
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(
                    Color.Transparent,
                    glass.copy(alpha = 0.11f * t),
                    Color.Transparent
                )
            ),
            topLeft = Offset(
                if (tail > 0) c.x - r * 2.8f else c.x - r * 0.2f,
                c.y - r * 0.38f
            ),
            size = Size(r * 3f, r * 0.76f),
            cornerRadius = CornerRadius(r * 0.38f, r * 0.38f)
        )
    }

    val circle = androidx.compose.ui.graphics.Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r))
    }

    // 1) 玻璃体液：中心透明（透出下方 Fill），边缘菲涅尔渐亮；叠一层极淡水透色底给体积。
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to glass.copy(alpha = 0.05f),
                0.52f to glass.copy(alpha = 0.03f),
                0.80f to glass.copy(alpha = 0.10f),
                0.93f to glass.copy(alpha = 0.20f),
                1.00f to glass.copy(alpha = 0.32f)
            ),
            center = c, radius = r
        ),
        radius = r, center = c
    )

    // 2) 弯月面：clip 到珠体内，上缘一道亮内阴影、下缘一道暗，让液滴「鼓」起来。
    clipPath(circle) {
        val rim = r * 0.5f
        drawCircle(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to Color.White.copy(alpha = 0.42f),
                    0.40f to Color.White.copy(alpha = 0.06f),
                    0.70f to Color.Transparent
                ),
                start = Offset(c.x - r, c.y - r),
                end = Offset(c.x + r * 0.4f, c.y + r * 0.4f)
            ),
            center = c, radius = r, style = Stroke(width = rim)
        )
        drawCircle(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.55f to Color.Black.copy(alpha = 0.04f),
                    1.00f to Color.Black.copy(alpha = 0.20f)
                ),
                start = Offset(c.x - r * 0.4f, c.y - r * 0.4f),
                end = Offset(c.x + r, c.y + r)
            ),
            center = c, radius = r, style = Stroke(width = rim)
        )
    }

    // 3) 色散彩边：左上一段极淡青→品红 fringe（spherochromatism），Plus 只提亮。
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF8FE8FF).copy(alpha = 0.34f),
                Color(0xFFFF9AD8).copy(alpha = 0.16f),
                Color.Transparent
            ),
            start = Offset(c.x - r, c.y - r),
            end = Offset(c.x + r * 0.3f, c.y + r * 0.3f)
        ),
        radius = r - 0.9f * u, center = c,
        style = Stroke(width = 1.7f * u), blendMode = BlendMode.Plus
    )

    // 4) 边缘张力线：1px 极细、不均匀的描边，左上全白 → 右下透明，底部留一道 bounce。
    drawCircle(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Color.White.copy(alpha = 0.92f),
                0.32f to Color.White.copy(alpha = 0.34f),
                0.68f to Color.White.copy(alpha = 0.06f),
                1.00f to Color.White.copy(alpha = 0.50f)
            ),
            start = Offset(c.x - r, c.y - r),
            end = Offset(c.x + r, c.y + r)
        ),
        radius = r - 0.6f * u, center = c,
        style = Stroke(width = 1.1f * u)
    )

    // 5) 左上锐利高光点：柔光晕 + 双层高光核（大白点 + 更小的纯白核），近看才有层次。
    val hl = Offset(c.x - r * 0.31f, c.y - r * 0.34f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.40f), Color.Transparent),
            center = hl, radius = r * 0.34f
        ),
        radius = r * 0.34f, center = hl
    )
    drawCircle(color = Color.White.copy(alpha = 0.88f), radius = r * 0.13f, center = hl)
    drawCircle(color = Color.White.copy(alpha = 0.98f), radius = r * 0.07f,
        center = Offset(hl.x - r * 0.02f, hl.y - r * 0.02f))

    // 6) 右下环境反光点：底部透进来的背景光斑 + 一颗极小的次级高光核。
    val gl = Offset(c.x + r * 0.33f, c.y + r * 0.40f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(glass.copy(alpha = 0.22f), Color.Transparent),
            center = gl, radius = r * 0.30f
        ),
        radius = r * 0.30f, center = gl
    )
    drawCircle(color = glass.copy(alpha = 0.34f), radius = r * 0.055f, center = gl)

    // 7) 底部焦散弧：一道极窄的反光弧，模拟液滴底部的光线汇聚。
    drawArc(
        brush = Brush.linearGradient(
            listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.40f),
                Color.Transparent
            ),
            start = Offset(c.x - r * 0.55f, c.y + r * 0.7f),
            end = Offset(c.x + r * 0.55f, c.y + r * 0.7f)
        ),
        startAngle = 42f, sweepAngle = 96f, useCenter = false,
        topLeft = Offset(c.x - r * 0.82f, c.y - r * 0.82f),
        size = Size(r * 1.64f, r * 1.64f),
        style = Stroke(width = 1.2f * u, cap = StrokeCap.Round)
    )
}

/** 给轨道挂真实背景模糊（只 blur 不 lens），返回已绘制四层的 Modifier。 */
private fun Modifier.liquidTrackModifier(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    shape: Shape,
    frac: Float,
    bufFrac: Float,
    accent: Color,
    glass: Color,
    active: Boolean,
    flow: Float,
    height: Dp
): Modifier {
    val base = this.fillMaxWidth().height(height)
    if (backdrop == null) {
        return base.drawBehind { drawLiquidTrack(frac, bufFrac, accent, glass, active, flow) }
    }
    return base.drawPlainBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = { blur(4f.dp.toPx()) },
        onDrawSurface = { drawLiquidTrack(frac, bufFrac, accent, glass, active, flow) }
    )
}

/** 强调色太接近底色时自动拉开至少三个色阶（亮度差 0.25），避免进度条隐形。 */
private fun contrastingAccent(accent: Color, background: Color): Color {
    val av = luma(accent)
    val bv = luma(background)
    if (kotlin.math.abs(av - bv) >= 0.25f) return accent
    val target = if (bv < 0.5f) (av + 0.34f).coerceAtMost(0.95f) else (av - 0.34f).coerceAtLeast(0.20f)
    if (target == av) return accent
    val k = target / av.coerceAtLeast(0.01f)
    return Color(
        (accent.red * k).coerceIn(0f, 1f),
        (accent.green * k).coerceIn(0f, 1f),
        (accent.blue * k).coerceIn(0f, 1f),
        accent.alpha
    )
}

private fun luma(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

private fun darken(c: Color, factor: Float): Color =
    Color(c.red * factor, c.green * factor, c.blue * factor, c.alpha)

/**
 * 音量条：与播放进度条完全同源的液态玻璃——封面取色渐变 Fill + 真实背景模糊底槽
 * + 顶部锐利高光 + 缓慢流光 + 同款水珠端点；音量由转轮调节，这里只读展示百分比。
 */
@Composable
private fun VolumeBar(volume: Float) {
    val v by animateFloatAsState(
        targetValue = volume.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 330f),
        label = "volBarFill"
    )
    // 与进度条同源：封面取色 + 缓存，切页不闪、颜色统一
    val queue by PlayerController.queue.collectAsState()
    val idx by PlayerController.currentIndex.collectAsState()
    val current = if (idx in queue.indices) queue[idx] else null
    val coverKey = current?.coverUrl ?: current?.embeddedAudioUrl
    val cover = rememberCoverBitmap(current?.coverUrl, current?.embeddedAudioUrl)
    val palette = rememberAmbientPalette(cover, BrandRed, coverKey)
    val accent = contrastingAccent(palette.primary, palette.base)
    val glass = if (luma(palette.base) > 0.45f) Color.Black else Color.White
    // 与进度条一致的缓慢流光，只重绘这一小条
    val flow by produceState(0f) {
        var t = 0f
        while (true) {
            t += 0.009f
            if (t > 1f) t -= 1f
            value = t
            delay(60)
        }
    }

    val thumbS = 15.dp
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(36.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val trackW = maxWidth
            Box(
                Modifier.liquidTrackModifier(
                    backdrop = LocalGlassBackdrop.current,
                    shape = RoundedCornerShape(percent = 50),
                    frac = v,
                    bufFrac = 0f,
                    accent = accent,
                    glass = glass,
                    active = false,
                    flow = flow,
                    height = 8.dp
                )
            )
            // 与进度条同款水珠端点（静止，不响应拖拽）
            Box(
                Modifier.offset(x = (trackW - thumbS) * v).size(thumbS),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLiquidDroplet(center, size.minDimension / 2f, glass, 0f)
                }
            }
        }
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
