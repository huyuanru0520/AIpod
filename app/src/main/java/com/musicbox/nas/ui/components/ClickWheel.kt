package com.musicbox.nas.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicbox.nas.util.Haptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 透明玻璃 iPod 点击轮。
 *
 * - 转动：12° 一格，细密震动（无可见刻度线），具体行为由页面决定（音量 / 选歌 / 进度）；
 * - 短按左 / 右：上一首 / 下一首；长按：连续快退 / 快进，只在按下瞬间震一次；
 * - 短按 MENU：页面内打开 / 关闭播放队列；长按 MENU：回曲库根；
 * - 下键：播放 / 暂停；
 * - 中心：iPod touch 4 Home 键式**内凹**按钮，短按切换歌曲进度模式，长按切换歌词界面。
 *
 * 轮体投影用自绘圆形径向渐变，不用 Modifier.shadow——后者在圆形上会出现明显的多边形
 * （六边形）轮廓，为了那点性能把玻璃质感毁掉不值得。
 */
@Composable
fun ClickWheel(
    diameter: Dp,
    isPlaying: Boolean,
    centerActive: Boolean = false,
    /** 轮体所在背景是否为暗色：播放页是暗色封面传 true，首页跟随明暗主题。图标/描边据此反色。 */
    dark: Boolean = true,
    onRotate: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onMenu: () -> Unit,
    onMenuHome: () -> Unit,
    onConfirm: () -> Unit,
    /** 中心键长按：播放页用它切换歌词界面；不传则无行为。 */
    onCenterLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var longFired by remember { mutableStateOf(false) }
    var centerDown by remember { mutableStateOf(false) }
    var bottomDown by remember { mutableStateOf(false) }
    val centerScale by animateFloatAsState(
        targetValue = if (centerDown) 0.95f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.7f),
        label = "wheelCenter"
    )
    val bottomScale by animateFloatAsState(
        targetValue = if (bottomDown) 0.90f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.7f),
        label = "wheelBottom"
    )

    Box(modifier.size(diameter)) {
        // 圆形投影：径向渐变，避免 Modifier.shadow 在 CircleShape 上画出六边形
        Canvas(Modifier.fillMaxSize()) {
            val r = minOf(size.width, size.height) / 2f
            val c = center
            val shadowA = if (dark) 0.42f else 0.20f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = shadowA),
                        Color.Black.copy(alpha = shadowA * 0.38f),
                        Color.Transparent
                    ),
                    center = Offset(c.x, c.y + r * 0.12f),
                    radius = r * 1.18f
                ),
                radius = r * 1.10f,
                center = Offset(c.x, c.y + r * 0.06f)
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 转动：累计角度，每 12° 一格，一次细震 + 一次回调
                    var lastAngle = 0f
                    var accum = 0f
                    var lastTickAt = 0L
                    var onRing = false
                    detectDragGestures(
                        onDragStart = { p ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val radius = minOf(size.width, size.height) / 2f
                            val r = hypot(p.x - c.x, p.y - c.y)
                            onRing = r > radius * 0.38f && r <= radius * 1.04f
                            lastAngle = angleOf(p, c)
                            accum = 0f
                        },
                        onDragEnd = { onRing = false },
                        onDragCancel = { onRing = false }
                    ) { change, _ ->
                        change.consume()
                        if (!onRing) return@detectDragGestures
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val a = angleOf(change.position, c)
                        var delta = a - lastAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        lastAngle = a
                        accum += delta

                        while (abs(accum) >= STEP_DEGREES) {
                            val forward = accum > 0
                            accum -= if (forward) STEP_DEGREES else -STEP_DEGREES
                            // 震动/音效做节流，但每一格都必须上报 onRotate：
                            // 之前把 onRotate 也放在节流判断之后，连续转动时多格被 continue 吞掉，
                            // 表现就是搜索字母盘 / 选歌几乎不跟着转（只有偶尔一格生效）。
                            val now = System.currentTimeMillis()
                            if (now - lastTickAt >= MIN_TICK_INTERVAL_MS) {
                                lastTickAt = now
                                Haptics.tick(context, view)
                            }
                            onRotate(if (forward) 1 else -1)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { p ->
                            longFired = false
                            val zone = hitZone(p, size)
                            if (zone == WheelZone.CENTER) centerDown = true
                            if (zone == WheelZone.BOTTOM) bottomDown = true
                            var longJob: Job? = null
                            when (zone) {
                                WheelZone.LEFT, WheelZone.RIGHT, WheelZone.TOP -> {
                                    longJob = scope.launch {
                                        delay(LONG_PRESS_MS)
                                        longFired = true
                                        Haptics.click(context, view) // 长按只震一次，之后连续动作不再震
                                        while (isActive) {
                                            when (zone) {
                                                WheelZone.LEFT -> onRewind()
                                                WheelZone.RIGHT -> onForward()
                                                WheelZone.TOP -> onMenuHome()
                                                else -> Unit
                                            }
                                            delay(REPEAT_MS)
                                        }
                                    }
                                }
                                WheelZone.CENTER -> {
                                    // 中心键长按：只触发一次（切换歌词），不循环
                                    longJob = scope.launch {
                                        delay(LONG_PRESS_MS)
                                        longFired = true
                                        Haptics.click(context, view)
                                        onCenterLongPress()
                                    }
                                }
                                else -> Unit
                            }
                            tryAwaitRelease()
                            longJob?.cancel()
                            centerDown = false
                            bottomDown = false
                        },
                        onTap = { p ->
                            if (longFired) {
                                longFired = false
                                return@detectTapGestures
                            }
                            Haptics.click(context, view)
                            when (hitZone(p, size)) {
                                WheelZone.TOP -> onMenu()
                                WheelZone.LEFT -> onPrevious()
                                WheelZone.RIGHT -> onNext()
                                WheelZone.BOTTOM -> onPlayPause()
                                WheelZone.CENTER -> onConfirm()
                                WheelZone.NONE -> Unit
                            }
                        }
                    )
                }
        ) {
            val d = maxWidth
            // 轮体上的文字/图标颜色：暗底用白、浅底用深灰，避免浅色模式白底白字。
            val onColor = if (dark) Color.White else Color(0xFF2B2B30)
            val onStrong = if (dark) Color.White else Color(0xFF1A1A1F)
            GlassSurface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                tint = Color.White,
                tintAlpha = if (dark) 0.09f else 0.46f,
                elevation = 0.dp,
                forceDark = dark
            ) {
                Text(
                    "MENU",
                    color = onColor.copy(alpha = 0.90f),
                    fontSize = (d.value * 0.040f).sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = d * 0.115f)
                )
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一首 / 长按快退",
                    tint = onColor.copy(alpha = 0.95f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = d * 0.105f)
                        .size(d * 0.088f)
                )
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一首 / 长按快进",
                    tint = onColor.copy(alpha = 0.95f),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = d * 0.105f)
                        .size(d * 0.088f)
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = d * 0.105f)
                        .size(d * 0.16f)
                        .scale(bottomScale),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = onStrong,
                        modifier = Modifier.size(d * 0.088f)
                    )
                }

                // 中心 Home 键：上缘阴影、下缘高光，做成 iTouch 4 那种内凹，而不是凸起的玻璃珠。
                // 浅色模式降低黑影不透明度、提高下缘白光，避免深灰圆斑糊在白轮体上。
                val insetTop = if (centerActive) 0.58f else 0.42f
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(d * 0.22f)
                        .scale(centerScale)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = if (dark) insetTop else insetTop * 0.55f),
                                1f to Color.White.copy(alpha = if (dark) 0.14f else 0.85f)
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = if (dark) 0.18f else 0.9f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(d * 0.19f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        Color.Black.copy(alpha = if (dark) 0.20f else 0.10f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
            }
        }
    }
}

private enum class WheelZone { NONE, TOP, LEFT, RIGHT, BOTTOM, CENTER }

private fun hitZone(p: Offset, size: IntSize): WheelZone {
    val c = Offset(size.width / 2f, size.height / 2f)
    val dx = p.x - c.x
    val dy = p.y - c.y
    val r = hypot(dx, dy)
    val radius = minOf(size.width, size.height) / 2f
    if (r > radius * 1.05f) return WheelZone.NONE
    if (r <= radius * 0.31f) return WheelZone.CENTER
    val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    return when {
        deg in -135f..-45f -> WheelZone.TOP
        deg in 45f..135f -> WheelZone.BOTTOM
        deg < -135f || deg > 135f -> WheelZone.LEFT
        else -> WheelZone.RIGHT
    }
}

/** 12° 一格：30 格一圈，震动像细密的滚花，而不是粗颗粒的咔哒。 */
private const val STEP_DEGREES = 12f
private const val MIN_TICK_INTERVAL_MS = 60L
private const val LONG_PRESS_MS = 420L
private const val REPEAT_MS = 240L

private fun angleOf(p: Offset, c: Offset): Float =
    Math.toDegrees(atan2(p.y - c.y, p.x - c.x).toDouble()).toFloat()
