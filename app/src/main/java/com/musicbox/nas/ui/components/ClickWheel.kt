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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicbox.nas.util.Haptics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * iPod 式点击转轮（Click Wheel）。
 *
 * 交互：
 * - 外圈拖动旋转，每转过 [STEP_DEGREES] 度算「一格」，回调 [onNext] / [onPrevious] 并轻震一下；
 * - 中心圆：播放 / 暂停；
 * - 环带上 / 下：上一首 / 下一首；
 * - 环带左：MENU（返回）；环带右：确认（打开播放队列）。
 *
 * 视觉刻意做成经典的白色转轮 + 灰色符号，与深色封面背景形成对比；
 * 转轮本身不跟着转，只有拖动时环上的一枚小圆点跟随手指，提示「正在滚」。
 */
@Composable
fun ClickWheel(
    diameter: Dp,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMenu: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 拖动时手指在环上的角度，用于画跟随的小圆点；松手置空
    var dragAngle by remember { mutableStateOf<Float?>(null) }
    var centerDown by remember { mutableStateOf(false) }
    val centerScale by animateFloatAsState(
        targetValue = if (centerDown) 0.94f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.7f),
        label = "wheelCenter"
    )

    BoxWithConstraints(
        modifier = modifier
            .size(diameter)
            .pointerInput(Unit) {
                // —— 转动切歌 ——
                var lastAngle = 0f
                var accum = 0f
                var lastTickAt = 0L
                var onRing = false
                detectDragGestures(
                    onDragStart = { p ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = hypot(p.x - c.x, p.y - c.y)
                        val radius = minOf(size.width, size.height) / 2f
                        onRing = r > radius * 0.36f && r <= radius * 1.02f
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
                    // 处理 ±180° 跨界
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastAngle = a
                    dragAngle = a
                    accum += delta

                    // 一格一格的「咔哒」：角度累积够一格就触发，但两次触发之间留最短间隔，
                    // 免得手指甩一大圈时连续切掉半张专辑。
                    while (abs(accum) >= STEP_DEGREES) {
                        val forward = accum > 0
                        accum -= if (forward) STEP_DEGREES else -STEP_DEGREES
                        val now = System.currentTimeMillis()
                        if (now - lastTickAt < MIN_TICK_INTERVAL_MS) continue
                        lastTickAt = now
                        Haptics.tick(context)
                        if (forward) onNext() else onPrevious()
                    }
                }
            }
            .pointerInput(Unit) {
                // —— 五个点击区 ——
                detectTapGestures(
                    onPress = { p ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        if (hypot(p.x - c.x, p.y - c.y) <= minOf(size.width, size.height) * 0.17f) {
                            centerDown = true
                            tryAwaitRelease()
                            centerDown = false
                        }
                    },
                    onTap = { p ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val dx = p.x - c.x
                        val dy = p.y - c.y
                        val r = hypot(dx, dy)
                        val radius = minOf(size.width, size.height) / 2f
                        if (r > radius * 1.02f) return@detectTapGestures

                        if (r <= radius * 0.34f) {
                            Haptics.click(context)
                            onPlayPause()
                            return@detectTapGestures
                        }
                        // 屏幕上 y 轴向下：atan2 的 90° 指向正下方
                        val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        Haptics.click(context)
                        when {
                            deg in -135f..-45f -> onPrevious()
                            deg in 45f..135f -> onNext()
                            deg < -135f || deg > 135f -> onMenu()
                            else -> onSelect()
                        }
                    }
                )
            }
    ) {
        val d = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val radius = minOf(size.width, size.height) / 2f
            val c = center
            val rim = 1.dp.toPx()

            // 落在封面背景上的投影，让转轮「浮」起来
            drawCircle(
                color = Color.Black.copy(alpha = 0.34f),
                radius = radius * 0.985f,
                center = Offset(c.x, c.y + 7.dp.toPx())
            )
            // 轮体：左上方受光，右下方渐暗
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFFFFFF), Color(0xFFF4F4F5), Color(0xFFD9D9DC)),
                    center = Offset(c.x - radius * 0.36f, c.y - radius * 0.42f),
                    radius = radius * 1.75f
                ),
                radius = radius,
                center = c
            )
            // 外沿：上缘一道白高光 + 整体一圈极细的灰边
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = radius - rim / 2f,
                center = c,
                style = Stroke(rim)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.10f),
                radius = radius - rim,
                center = c,
                style = Stroke(rim)
            )

            // 中心按钮：略微内凹的一圈 + 自己的受光面
            drawCircle(
                color = Color.Black.copy(alpha = 0.12f),
                radius = radius * 0.355f,
                center = c,
                style = Stroke(rim)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = radius * 0.355f + rim,
                center = c,
                style = Stroke(rim)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFDFDFE), Color(0xFFEFEFF0), Color(0xFFDCDCDE)),
                    center = Offset(c.x - radius * 0.12f, c.y - radius * 0.16f),
                    radius = radius * 0.6f
                ),
                radius = radius * 0.335f,
                center = c
            )

            // 拖动跟随点：让「在转」这件事有即时反馈
            dragAngle?.let { a ->
                val rad = a * PI.toFloat() / 180f
                val p = Offset(
                    c.x + cos(rad) * radius * 0.72f,
                    c.y + sin(rad) * radius * 0.72f
                )
                drawCircle(Color.Black.copy(alpha = 0.16f), radius * 0.075f, p)
                drawCircle(Color.White.copy(alpha = 0.95f), radius * 0.055f, p)
            }
        }

        // 环带上的四个符号：位置在环带中线上（距边缘约 0.145×直径）
        val symbolInset = d * 0.135f
        Icon(
            Icons.Default.SkipPrevious,
            contentDescription = "上一首",
            tint = WheelInk,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = symbolInset)
                .size(d * 0.085f)
        )
        Icon(
            Icons.Default.SkipNext,
            contentDescription = "下一首",
            tint = WheelInk,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = symbolInset)
                .size(d * 0.085f)
        )
        Text(
            "MENU",
            color = WheelInk,
            fontSize = (d.value * 0.042f).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = symbolInset * 0.82f)
        )
        Text(
            "✓",
            color = WheelInk,
            fontSize = (d.value * 0.062f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = symbolInset * 0.92f)
        )

        // 中心：播放 / 暂停
        Box(
            Modifier
                .align(Alignment.Center)
                .size(d * 0.40f)
                .scale(centerScale),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = WheelInk,
                modifier = Modifier.size(d * 0.13f)
            )
        }
    }
}

private val WheelInk = Color(0xFF4A4A4E)

/** 每格角度：约 9.5 格一圈，转动一格切一首，手感接近 iPod 的段落感。 */
private const val STEP_DEGREES = 38f

/** 两次切歌的最短间隔，避免猛甩一圈连切十几首。 */
private const val MIN_TICK_INTERVAL_MS = 190L

private fun angleOf(p: Offset, c: Offset): Float =
    Math.toDegrees(atan2(p.y - c.y, p.x - c.x).toDouble()).toFloat()
