package com.musicbox.nas.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
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
 * iPod 原生交互的透明玻璃点击轮。
 *
 * 完全照 classic iPod 的 Now Playing 界面来：
 * - 转轮转动：**调音量**（iPod 播放界面转轮只调音量，进度靠快进/快退）；
 * - 短按左 / 右：上一首 / 下一首；
 * - 长按左 / 右：连续快退 / 快进；
 * - 短按 MENU：返回上一级；长按 MENU：直接回曲库根；
 * - 下键：播放 / 暂停；
 * - 中心 Select：确认（打开播放队列）。
 *
 * 转轮上没有刻度线：每转过一格由一次震动给出「咔哒」的手感，和实体滚轮一样。
 * 轮体是透明玻璃——完全靠半透明面、上亮下暗的描边和投影勾出轮廓。
 */
@Composable
fun ClickWheel(
    diameter: Dp,
    isPlaying: Boolean,
    onRotate: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onMenu: () -> Unit,
    onMenuHome: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var longFired by remember { mutableStateOf(false) }
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
                // 转动：累计角度，每过一格震动一次并回调（一格 = 4% 音量）
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
                        val now = System.currentTimeMillis()
                        if (now - lastTickAt < MIN_TICK_INTERVAL_MS) continue
                        lastTickAt = now
                        Haptics.tick(context, view)
                        onRotate(if (forward) 1 else -1)
                    }
                }
            }
            .pointerInput(Unit) {
                // 短按 / 长按：用 onPress 里启动的循环做原生式「按住连续快进/快退」
                detectTapGestures(
                    onPress = { p ->
                        longFired = false
                        val zone = hitZone(p, size)
                        if (zone == WheelZone.CENTER) centerDown = true
                        if (zone == WheelZone.BOTTOM) bottomDown = true
                        var longJob: Job? = null
                        if (zone == WheelZone.LEFT || zone == WheelZone.RIGHT || zone == WheelZone.TOP) {
                            longJob = scope.launch {
                                delay(LONG_PRESS_MS)
                                longFired = true
                                while (isActive) {
                                    Haptics.tick(context, view)
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
        // 透明玻璃轮体：GlassSurface 给半透明面、上亮下暗的描边和投影，不画任何刻度线
        GlassSurface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            tint = Color.White,
            tintAlpha = 0.09f,
            elevation = 18.dp,
            forceDark = true
        ) {
            Text(
                "MENU",
                color = Color.White.copy(alpha = 0.90f),
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
                tint = Color.White.copy(alpha = 0.95f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = d * 0.105f)
                    .size(d * 0.088f)
            )
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "下一首 / 长按快进",
                tint = Color.White.copy(alpha = 0.95f),
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
                    tint = Color.White,
                    modifier = Modifier.size(d * 0.088f)
                )
            }
            // 中心 Select：一块更亮的透明玻璃圆，凹在轮体中央
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(d * 0.22f)
                    .scale(centerScale),
                shape = CircleShape,
                tint = Color.White,
                tintAlpha = 0.14f,
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

/** 每格角度：10°，对应一次震动的「咔哒」。 */
private const val STEP_DEGREES = 24f
private const val MIN_TICK_INTERVAL_MS = 120L
private const val LONG_PRESS_MS = 420L
private const val REPEAT_MS = 240L

private fun angleOf(p: Offset, c: Offset): Float =
    Math.toDegrees(atan2(p.y - c.y, p.x - c.x).toDouble()).toFloat()
