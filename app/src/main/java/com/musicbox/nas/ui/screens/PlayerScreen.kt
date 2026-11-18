package com.musicbox.nas.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import com.musicbox.nas.util.CrashLogger
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.musicbox.nas.MusicBoxApp
import com.musicbox.nas.data.LyricsRepo
import com.musicbox.nas.data.SourceType
import com.musicbox.nas.data.Track
import com.musicbox.nas.player.PlayerController
import com.musicbox.nas.ui.components.BareCover
import com.musicbox.nas.ui.components.ClickWheel
import com.musicbox.nas.ui.components.WheelMode
import com.musicbox.nas.ui.components.CoverImage
import com.musicbox.nas.ui.components.HiResBadge
import com.musicbox.nas.ui.components.rememberCoverBitmap
import com.musicbox.nas.util.formatDuration
import kotlinx.coroutines.launch
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
    val playError by PlayerController.error.collectAsState()
    val current = if (idx in queue.indices) queue[idx] else null

    var showQueue by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val volume by MusicBoxApp.instance.volume.collectAsState()
    // 进度模式下不订阅 position（避免整页每 500ms 重组）：暂停瞬间取一次快照，
    // 之后转轮每格自己推进快照并 seek，界面跟手更新。
    var progressSnapshot by remember { mutableLongStateOf(PlayerController.currentPositionMs()) }
    var volumeHint by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) progressSnapshot = PlayerController.currentPositionMs()
    }
    LaunchedEffect(volumeHint) {
        if (volumeHint) {
            kotlinx.coroutines.delay(1400L)
            volumeHint = false
        }
    }

    // 横屏：整页改为「左侧封面 + 右侧歌词」的沉浸式布局，背景取当前封面主色调并模糊
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        LandscapePlayer(
            navController = navController,
            track = current,
            duration = duration,
            isPlaying = isPlaying,
            playError = playError,
            onQueueClick = { showQueue = true }
        )
        if (showQueue) {
            QueueHost(
                showQueue = true, queue = queue, currentIndex = idx,
                shuffle = shuffle, repeatMode = repeatMode
            ) { showQueue = false }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        // 竖屏背景与横屏一致：模糊当前封面 + 主色调 + 暗化渐变
        CoverBackdrop(current)
        // 播放页保留状态栏（见 MainActivity.keepStatusBarVisible）：内容从状态栏下方开始，
        // 但背景仍在 Box 里铺满整屏，状态栏透出的是封面模糊图而不是黑条。
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
            val wheel = minOf(maxWidth * 0.64f, maxHeight * 0.40f, 286.dp)
            var showLyrics by remember { mutableStateOf(false) }
            // 播放时转轮调音量；按下「下」键暂停后，中间切到大进度条，转轮改为调进度
            val wheelMode = if (isPlaying) WheelMode.VOLUME else WheelMode.PROGRESS
            val body = when {
                wheelMode == WheelMode.PROGRESS -> "progress"
                showLyrics -> "lyrics"
                else -> "cover"
            }
            fun seekBy(deltaMs: Long) {
                val next = (progressSnapshot + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
                progressSnapshot = next
                PlayerController.seekTo(next)
            }

            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                IpodTopBar(
                    track = current,
                    showLyrics = showLyrics,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onQueueClick = { showQueue = true },
                    onBack = { navController.popBackStack() }
                )

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
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("队列为空", color = Color.White.copy(alpha = 0.7f))
                    }
                } else {
                    val c = current
                    // 中间区域：音量模式显示封面 / 歌词；进度模式显示大进度条
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Crossfade(
                            targetState = body,
                            animationSpec = tween(durationMillis = 180),
                            label = "ipodBody"
                        ) { view ->
                            when (view) {
                                "progress" -> WheelProgressPanel(
                                    positionMs = progressSnapshot,
                                    durationMs = duration
                                )
                                "lyrics" -> Box(Modifier.fillMaxSize()) {
                                    LyricsView(
                                        current = c,
                                        textColor = Color.White.copy(alpha = 0.55f),
                                        highlightColor = Color.White,
                                        contentVertical = 0.dp,
                                        largeText = true,
                                        fadeByDistance = true
                                    )
                                    Text(
                                        "封面",
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 2.dp, end = 20.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Color.White.copy(alpha = 0.14f))
                                            .clickable { showLyrics = false }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                else -> NowPlayingArt(track = c, onShowLyrics = { showLyrics = true })
                            }
                        }
                    }

                    // 转动音量时给一个短暂反馈，松手 1.4 秒后淡出
                    if (wheelMode == WheelMode.VOLUME && volumeHint) {
                        Row(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.Black.copy(alpha = 0.42f))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "音量 ${(volume * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }

                    // 进度模式下大进度条已经占用中间区，这里不再重复绘制细进度条
                    if (wheelMode == WheelMode.VOLUME) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
                            SeekBar(
                                duration = duration,
                                inactiveColor = Color.White.copy(alpha = 0.22f),
                                labelColor = Color.White.copy(alpha = 0.6f),
                                onSeek = { PlayerController.seekTo(it) }
                            )
                        }
                    } else {
                        Spacer(Modifier.height(24.dp))
                    }
                    Spacer(Modifier.height(2.dp))

                    ClickWheel(
                        diameter = wheel,
                        mode = wheelMode,
                        isPlaying = isPlaying,
                        onRotate = { steps ->
                            if (wheelMode == WheelMode.VOLUME) {
                                val next = MusicBoxApp.instance.volume.value + steps * 0.04f
                                volumeHint = true
                                scope.launch { MusicBoxApp.instance.setVolume(next) }
                            } else {
                                seekBy(steps * 5000L)
                            }
                        },
                        onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
                        onPrevious = {
                            if (wheelMode == WheelMode.VOLUME) PlayerController.previous() else seekBy(-10_000L)
                        },
                        onNext = {
                            if (wheelMode == WheelMode.VOLUME) PlayerController.next() else seekBy(10_000L)
                        },
                        onMenu = { navController.popBackStack() },
                        onConfirm = { showQueue = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // 播放队列：随机 / 循环等模式按钮都在这一页
    QueueHost(
        showQueue = showQueue, queue = queue, currentIndex = idx,
        shuffle = shuffle, repeatMode = repeatMode
    ) { showQueue = false }
}

/**
 * 播放页顶栏：返回 / 「正在播放」/ 歌词切换 / 队列 / 更多。
 * 不再显示小封面与歌名——它们已经放大到中间区域，顶栏只负责导航与杂项。
 */
@Composable
private fun IpodTopBar(
    track: Track?,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onQueueClick: () -> Unit,
    onBack: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var showArt by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "正在播放",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            Text(
                if (showLyrics) "歌词" else "iPod 转轮",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f)
            )
        }
        IconButton(onClick = onToggleLyrics) {
            Icon(
                if (showLyrics) Icons.Default.Album else Icons.Default.Subject,
                contentDescription = if (showLyrics) "显示封面" else "显示歌词",
                tint = Color.White
            )
        }
        IconButton(onClick = onQueueClick) {
            Icon(Icons.Default.QueueMusic, contentDescription = "播放队列", tint = Color.White)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (track != null) {
                    DropdownMenuItem(
                        text = { Text("查看专辑封面") },
                        onClick = { menu = false; showArt = true }
                    )
                    DropdownMenuItem(
                        text = { Text("复制歌曲信息") },
                        onClick = {
                            menu = false
                            runCatching {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "歌曲信息",
                                        "${track.title} - ${track.artist.ifBlank { "未知歌手" }} · ${track.album}"
                                    )
                                )
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("返回曲库") },
                        onClick = { menu = false; onBack() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("清除播放错误") },
                    onClick = { menu = false; PlayerController.clearError() }
                )
            }
        }
    }
    // 大图预览（纯图片、无边框）
    if (showArt && track != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showArt = false }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                BareCover(
                    url = track.coverUrl,
                    embeddedAudioUrl = track.embeddedAudioUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = track.title
                )
            }
        }
    }
}

/**
 * 暂停后的「进度模式」：中间区从封面切成大进度条，转轮每格前进/后退 5 秒。
 * 这里刻意不订阅 position，位置由页面里的 progressSnapshot 推着走。
 */
@Composable
private fun WheelProgressPanel(positionMs: Long, durationMs: Long) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "进度调整 · 转动转轮",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.55f)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            formatDuration(positionMs),
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(Color(0xFFFA2D48))
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "左键倒退 10 秒 · 右键快进 10 秒 · 下键返回播放",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 中间主区域：大封面 + 歌名 / 歌手。轻点封面切到歌词页。
 * 封面尺寸取「可用宽」与「可用高 - 文字区」的较小值，小屏上不会把歌名挤出去。
 */
@Composable
private fun NowPlayingArt(
    track: Track,
    onShowLyrics: () -> Unit
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
            Box(
                Modifier
                    .size(art)
                    .shadow(20.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onShowLyrics() }
            ) {
                CoverImage(
                    track.coverUrl,
                    Modifier.fillMaxSize(),
                    embeddedAudioUrl = track.embeddedAudioUrl
                )
            }
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
            Spacer(Modifier.height(8.dp))
            Text(
                "轻点封面查看歌词",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.35f)
            )
        }
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
                if (i != currentIndex) PlayerController.playTracks(queue, i)
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
 * 播放页背景：封面降到 24×24 再拉伸（双线性插值本身就是最好的模糊）+ 主色调叠加 + 暗化渐变。
 *
 * 刻意**不用** `Modifier.blur(56.dp)`：全屏高斯模糊在 Android 12+ 是离屏 RenderEffect，
 * 每次重绘都在 GPU 上多跑一遍全屏 pass，长时间播放明显费电、低端机还会掉帧。
 * 24×24 的图拉伸到全屏后视觉几乎一致，成本却接近于零。
 * 切歌时用 260ms 交叉淡入，避免背景"啪"地换色。
 */
@Composable
private fun CoverBackdrop(track: Track?) {
    Box(Modifier.fillMaxSize().background(Color(0xFF131316)))
    Crossfade(
        targetState = track,
        animationSpec = tween(durationMillis = 260),
        label = "coverBackdrop"
    ) { t -> BackdropLayer(t) }
}

@Composable
private fun BackdropLayer(track: Track?) {
    val bitmap = rememberCoverBitmap(track?.coverUrl, track?.embeddedAudioUrl)
    val tiny = remember(bitmap) {
        runCatching {
            bitmap?.asAndroidBitmap()?.let { Bitmap.createScaledBitmap(it, 24, 24, true) }
        }.getOrNull()
    }
    val dominant = remember(tiny) { tiny?.let { dominantColorOf(it) } ?: Color(0xFF2B2B31) }

    // 封面就绪 / 切歌时让背景图淡入，避免从纯色底"啪"地跳到图片
    val bgAlpha by animateFloatAsState(
        targetValue = if (tiny != null) 0.92f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "backdropAlpha"
    )
    if (tiny != null) {
        Image(
            bitmap = tiny.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(bgAlpha)
        )
    }
    // 主色调统一整体色调
    Box(Modifier.fillMaxSize().background(dominant.copy(alpha = 0.42f)))
    // 上下暗化，保证白色文字可读
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.74f))
            )
        )
    )
}

/** 取主色调：偏好「有饱和度且亮度适中」的像素，并与平均色混合避免过艳。 */
private fun dominantColorOf(bmp: Bitmap): Color {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return Color(0xFF2B2B31)
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    var r = 0L
    var g = 0L
    var b = 0L
    var bestScore = -1f
    var best = px[0]
    for (p in px) {
        val rr = (p shr 16) and 0xFF
        val gg = (p shr 8) and 0xFF
        val bb = p and 0xFF
        val mx = max(rr, max(gg, bb))
        val mn = min(rr, min(gg, bb))
        val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
        val lum = (0.299f * rr + 0.587f * gg + 0.114f * bb) / 255f
        val score = sat * (1f - kotlin.math.abs(lum - 0.55f))
        if (score > bestScore) {
            bestScore = score
            best = p
        }
        r += rr; g += gg; b += bb
    }
    val n = px.size
    val avg = Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    return lerp(avg, Color(best), 0.55f)
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
    onSeek: (Long) -> Unit
) {
    // 进度在此处自行订阅：500ms 的更新只会让这条进度条重组，不再牵动整页。
    val livePosition by PlayerController.position.collectAsState()
    var drag by remember(duration) { mutableStateOf<Float?>(null) }
    val maxRange = duration.coerceAtLeast(1).toFloat()
    val shown = drag ?: livePosition.toFloat().coerceIn(0f, maxRange)

    Column(modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { onSeek(it.toLong()) }
                drag = null
            },
            valueRange = 0f..maxRange,
            modifier = Modifier.fillMaxWidth().height(26.dp),
            thumb = {
                Box(
                    Modifier
                        .size(10.dp)
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
                        .height(3.dp)
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
    fadeByDistance: Boolean = false
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
                // 没有时间轴的纯文本歌词：居中排版，可手动上下滚动
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
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
    onSeek: (Long) -> Unit
) {
    val scroll = rememberScrollState()
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
