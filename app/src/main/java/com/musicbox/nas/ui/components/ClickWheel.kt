package com.musicbox.nas.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicbox.nas.util.Haptics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 转轮当前控制的量：播放时调音量，暂停时调进度。 */
enum class WheelMode { VOLUME, PROGRESS }

/**
 * 深色液态玻璃 iPod 转轮。
 *
 * 按键位置（对照经典 iPod）：
 * - 上：MENU（返回）
 * - 左：上一首 / 倒退（由页面按当前模式决定）
 * - 右：下一首 / 快进
 * - 下：开始 / 暂停（同时切换转轮的音量 / 进度模式）
 * - 中心：确认
 *
 * 转动：外圈每转过 [STEP_DEGREES] 记一格，环上的刻度一格一格走，同时轻震一下。
 */
@Composable
fun ClickWheel(
    diameter: Dp,
    mode: WheelMode,
    isPlaying: Boolean,
    onRotate: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMenu: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var dragAngle by remember { mutableStateOf<Float?>(null) }
    var tickPhase by remember { mutableStateOf(0) }
    var centerDown by remember { mutableStateOf(false) }
    var bottomDown by remember { mutableStateOf(false) }
    val centerScale by animateFloatAsState(
        targetValue = if (centerDown) 0.93f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.7f),
        label = "wheelCenter"
    )
    val bottomScale by animateFloatAsState(
        targetValue = if (bottomDown) 0.90f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.7f),
        label = "wheelBottom"
    )

    BoxWithConstraints(
        modifier = modifier
            .size(diameter)
            .pointerInput(Unit) {
                // —— 转动：累计角度，每过一格触发一次 ——
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
                        if (onRing) dragAngle = lastAngle
                    },
                    onDragEnd = { dragAngle = null },
                    onDragCancel = { dragAngle = null }
                ) { change, _ ->
                    change.consume()
                    if (!onRing) return@detectDragGestures
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val a = angleOf(change.position, c)
                    var delta = a - lastAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastAngle = a
                    dragAngle = a
                    accum += delta

                    while (abs(accum) >= STEP_DEGREES) {
                        val forward = accum > 0
                        accum -= if (forward) STEP_DEGREES else -STEP_DEGREES
                        val now = System.currentTimeMillis()
                        if (now - lastTickAt < MIN_TICK_INTERVAL_MS) continue
                        lastTickAt = now
                        tickPhase += if (forward) 1 else -1
                        Haptics.tick(context)
                        onRotate(if (forward) 1 else -1)
                    }
                }
            }
            .pointerInput(Unit) {
                // —— 五个点击区 ——
                detectTapGestures(
                    onPress = { p ->
                        when (hitZone(p, size)) {
                            WheelZone.CENTER -> {
                                centerDown = true
                                tryAwaitRelease()
                                centerDown = false
                            }
                            WheelZone.BOTTOM -> {
                                bottomDown = true
                                tryAwaitRelease()
                                bottomDown = false
                            }
                            else -> Unit
                        }
                    },
                    onTap = { p ->
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
        // 深色液态玻璃轮体：GlassSurface 提供投影、边缘亮线和内侧竖向高光
        GlassSurface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            tint = Color(0xFF141417),
            tintAlpha = 0.74f,
            elevation = 20.dp,
            forceDark = true
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = minOf(size.width, size.height) / 2f
                val c = center

                // 外圈刻度：36 根短刻线，当前一格高亮，转起来能「看见」每一下
                val ticks = 36
                val current = ((tickPhase % ticks) + ticks) % ticks
                // 高亮刻度随模式换色：红=音量，青=进度，一眼知道现在转轮在调什么
                val activeTick = if (mode == WheelMode.VOLUME) Color(0xFFFA2D48) else Color(0xFF62D8FF)
                for (i in 0 until ticks) {
                    val a = (-90f + i * (360f / ticks)) * PI.toFloat() / 180f
                    val active = i == current
                    val start = radius * if (active) 0.845f else 0.875f
                    val end = radius * 0.955f
                    drawLine(
                        color = if (active) activeTick else Color.White.copy(alpha = 0.24f),
                        start = Offset(c.x + cos(a) * start, c.y + sin(a) * start),
                        end = Offset(c.x + cos(a) * end, c.y + sin(a) * end),
                        strokeWidth = if (active) 2.6.dp.toPx() else 1.1.dp.toPx()
                    )
                }

                // 中心凹陷圆：内外两道细边 + 径向渐变，像 iPod 中间那颗 Select 键
                drawCircle(Color.Black.copy(alpha = 0.45f), radius * 0.325f, c, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.16f), radius * 0.345f, c, style = Stroke(1.dp.toPx()))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF242429), Color(0xFF101013)),
                        center = Offset(c.x - radius * 0.08f, c.y - radius * 0.1f),
                        radius = radius * 0.5f
                    ),
                    radius = radius * 0.30f,
                    center = c
                )

                // 拖动时的跟随点
                dragAngle?.let { a ->
                    val rad = a * PI.toFloat() / 180f
                    val p = Offset(c.x + cos(rad) * radius * 0.74f, c.y + sin(rad) * radius * 0.74f)
                    drawCircle(Color.Black.copy(alpha = 0.4f), radius * 0.08f, p)
                    drawCircle(Color.White.copy(alpha = 0.95f), radius * 0.055f, p)
                }
            }

            // MENU：上
            Text(
                "MENU",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = (d.value * 0.040f).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = d * 0.115f)
            )
            // 上一首 / 倒退：左
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "上一首 / 倒退",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = d * 0.105f)
                    .size(d * 0.088f)
            )
            // 下一首 / 快进：右
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "下一首 / 快进",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = d * 0.105f)
                    .size(d * 0.088f)
            )
            // 开始 / 暂停：下
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
                    tint = Color.White,
                    modifier = Modifier.size(d * 0.088f)
                )
            }
            // 中心确认：再叠一层小玻璃，按下去会轻微缩放
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(d * 0.22f)
                    .scale(centerScale),
                shape = CircleShape,
                tint = Color(0xFF2A2A30),
                tintAlpha = 0.85f,
                elevation = 6.dp,
                forceDark = true
            ) { }
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

/** 每格角度：10°，与外圈 36 根刻度一一对应。 */
private const val STEP_DEGREES = 36f

/** 两次反馈的最短间隔，避免猛甩一圈连调过头。 */
private const val MIN_TICK_INTERVAL_MS = 150L

private fun angleOf(p: Offset, c: Offset): Float =
    Math.toDegrees(atan2(p.y - c.y, p.x - c.x).toDouble()).toFloat()
