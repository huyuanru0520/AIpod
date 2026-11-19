package com.musicbox.nas.ui.components

import android.graphics.Rect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.musicbox.nas.data.PlayMode
import com.musicbox.nas.ui.theme.BrandRed
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 播放模式提示环：环绕屏幕摄像头（挖孔）绘制一圈持续旋转的玻璃质感圆环，
 * 并在环下方标注当前播放模式符号：
 * - 单曲循环：圆圈箭头指向数字 1；
 * - 列表循环：两个首尾相接的循环箭头；
 * - 随机播放：箭头指向大写字母 R。
 *
 * 摄像头位置优先取 DisplayCutout 的精确 boundingRect（各机型居中挖孔通常落在状态栏中央），
 * 取不到时回退到「屏幕顶部水平中央 + 状态栏高度一半」。整个 Canvas 铺满全屏且不吃触摸事件，
 * 因此可以安全叠在播放页最上层。
 */
@Composable
fun PlayModeHalo(mode: PlayMode, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val measurer = rememberTextMeasurer()

    // 环持续缓慢旋转 + 轻微呼吸
    val infinite = rememberInfiniteTransition(label = "playModeHalo")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(7200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloRotate"
    )
    val breathe by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloBreathe"
    )

    // 切换模式瞬间放大一下作为反馈
    var pop by remember { mutableStateOf(0f) }
    LaunchedEffect(mode) {
        pop = 1f
        delay(380)
        pop = 0f
    }

    // 读取摄像头（挖孔）精确位置；读不到则留空由绘制时兜底
    var cutoutCenter by remember { mutableStateOf<Offset?>(null) }
    var cutoutRadius by remember { mutableStateOf(0f) }
    LaunchedEffect(view) {
        val insets = ViewCompat.getRootWindowInsets(view)
        val rect: Rect? = insets?.displayCutout?.boundingRects?.firstOrNull()
        if (rect != null && rect.width() > 0) {
            cutoutCenter = Offset((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
            cutoutRadius = max(rect.width(), rect.height()) / 2f
        }
    }

    val statusTopPx = remember {
        (ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0).toFloat()
    }

    Canvas(modifier) {
        val cx = cutoutCenter?.x ?: (size.width / 2f)
        val cy = cutoutCenter?.y ?: max(statusTopPx / 2f, 26.dp.toPx())
        val baseR = if (cutoutRadius > 0f) cutoutRadius + 13.dp.toPx() else 27.dp.toPx()
        val r = baseR * breathe * (1f + pop * 0.12f)
        val c = Offset(cx, cy)
        val stroke = 3.2.dp.toPx()

        // 暗色垫底：保证在浅色封面/状态栏上也能看清环
        drawCircle(
            color = Color.Black.copy(alpha = 0.32f),
            radius = r + 5.dp.toPx(),
            center = c
        )
        // 三段旋转弧：红 / 白 / 半透红，形成流动玻璃环
        val sweep = 96f
        val colors = listOf(BrandRed, Color.White.copy(alpha = 0.9f), BrandRed.copy(alpha = 0.45f))
        colors.forEachIndexed { i, col ->
            drawArc(
                color = col,
                startAngle = rotation + i * 120f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        // 模式符号放在环下方，完全避开摄像头开孔
        val symbolCenter = Offset(cx, cy + r + 11.dp.toPx())
        drawModeSymbol(measurer, mode, symbolCenter)
    }
}

private fun DrawScope.drawModeSymbol(
    measurer: TextMeasurer,
    mode: PlayMode,
    pos: Offset
) {
    val accent = BrandRed
    val fg = Color.White
    val arrow = 11.dp.toPx()
    when (mode) {
        PlayMode.ONE -> {
            // 圆圈箭头（留缺口）+ 指向数字 1
            drawArc(
                color = accent,
                startAngle = 40f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(pos.x - arrow, pos.y - arrow),
                size = Size(arrow * 2, arrow * 2),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArrowHead(Offset(pos.x - arrow, pos.y - arrow * 0.2f), angleDeg = 40f, accent, 4.dp.toPx())
            drawCenteredText(measurer, "1", pos, 13.sp, fg)
        }
        PlayMode.LIST -> {
            // 两个首尾相接的循环箭头，构成一个循环圆环
            val rad = 10.dp.toPx()
            drawArc(
                color = accent, startAngle = 0f, sweepAngle = 165f, useCenter = false,
                topLeft = Offset(pos.x - rad, pos.y - rad), size = Size(rad * 2, rad * 2),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = fg, startAngle = 180f, sweepAngle = 165f, useCenter = false,
                topLeft = Offset(pos.x - rad, pos.y - rad), size = Size(rad * 2, rad * 2),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            // 右端箭头（顺时针终点）与左端箭头
            drawArrowHead(Offset(pos.x + rad, pos.y), angleDeg = 90f, accent, 4.dp.toPx())
            drawArrowHead(Offset(pos.x - rad, pos.y), angleDeg = 270f, fg, 4.dp.toPx())
        }
        PlayMode.SHUFFLE -> {
            // 大写 R + 指向它的箭头
            drawCenteredText(measurer, "R", pos, 14.sp, fg)
            drawArrowHead(
                Offset(pos.x - 13.dp.toPx(), pos.y + 1.dp.toPx()),
                angleDeg = 0f, accent, 4.5.dp.toPx()
            )
        }
    }
}

/** 在中心位置绘制文字（居中对齐）。 */
private fun DrawScope.drawCenteredText(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color
) {
    val layout = measurer.measure(
        text,
        style = TextStyle(fontSize = size, color = color, textAlign = TextAlign.Center)
    )
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/** 画一个指向 angleDeg 方向的实心三角箭头（angleDeg：0=右, 90=下, 180=左, 270=上）。 */
private fun DrawScope.drawArrowHead(
    tip: Offset,
    angleDeg: Float,
    color: Color,
    length: Float
) {
    val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
    val dx = kotlin.math.cos(rad)
    val dy = kotlin.math.sin(rad)
    val base = Offset(tip.x - dx * length, tip.y - dy * length)
    val perpX = -dy * length * 0.55f
    val perpY = dx * length * 0.55f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + perpX, base.y + perpY)
        lineTo(base.x - perpX, base.y - perpY)
        close()
    }
    drawPath(path, color)
}
