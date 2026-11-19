package com.musicbox.nas.ui.components

import android.graphics.Rect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.musicbox.nas.data.PlayMode
import com.musicbox.nas.ui.theme.BrandRed
import com.musicbox.nas.util.CrashLogger
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 播放模式提示环：**平时完全隐藏**，仅在 [trigger] 变化（摇一摇切换模式）时短暂浮现，
 * 约 1.8 秒后淡出。环紧贴屏幕摄像头挖孔的外圈绘制，整体使用进度条同款品牌红。
 *
 * 环下方标注当前播放模式符号：
 * - 单曲循环：圆圈箭头指向数字 1；
 * - 列表循环：两个首尾相接的循环箭头；
 * - 随机播放：箭头指向大写字母 R。
 *
 * 摄像头外径优先取 DisplayCutout 的 boundingRect（居中挖孔通常落在状态栏中央），
 * 半径直接取外接圆半径使其紧贴外圈；取不到才回退到顶部中央的小环。
 * Canvas 铺满全屏且不吃触摸事件，可安全叠在播放页最上层。
 */
@Composable
fun PlayModeHalo(
    mode: PlayMode,
    trigger: Long,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val measurer = rememberTextMeasurer()

    // 触发后短暂可见，然后淡出；trigger=0（从未摇过）时始终隐藏
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger <= 0L) return@LaunchedEffect
        visible = true
        delay(1_800)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 220 else 520),
        label = "haloAlpha"
    )

    // Canvas 在窗口坐标系中的原点：DisplayCutout 的坐标是相对整个窗口的，
    // 而 Canvas 内部绘制坐标相对自身，绘制时必须减去这个原点才能对上摄像头。
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    // 摄像头（挖孔）信息：中心 + 外接圆半径。首次组合 + 每次窗口 insets 变化时重读并打印诊断。
    var cutoutCenter by remember { mutableStateOf<Offset?>(null) }
    var cutoutRadius by remember { mutableStateOf(0f) }
    fun readCutout(tag: String) {
        val insets = ViewCompat.getRootWindowInsets(view)
        val cutout = insets?.displayCutout
        val rects = cutout?.boundingRects
        val statusTop = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: -1
        val rectsDesc = rects?.mapIndexed { i, r ->
            "[$i]L${r.left},T${r.top},R${r.right},B${r.bottom},W${r.width()},H${r.height()}"
        }?.joinToString(" ") ?: "null"
        val rect: Rect? = rects?.firstOrNull()
        if (rect != null && rect.width() > 0) {
            // 居中圆形挖孔：宽度即真实孔径（本机 W=78）。
            // 高度常被 ROM 撑满整条状态栏（本机 H=134 恰好等于 statusTop），不能当孔径用，
            // 否则半径取 max(W,H)/2=67，环被放大近一倍、圆心也被拉到状态栏中部而偏下。
            cutoutRadius = rect.width() / 2f
            // 水平中心可信；垂直方向 rect.top/bottom 被撑满状态栏，取中点会偏下，
            // 改为贴矩形顶部 + 半径，让环贴着屏幕顶部（真实挖孔就是贴顶的）。
            cutoutCenter = Offset(
                (rect.left + rect.right) / 2f,
                rect.top + cutoutRadius
            )
        } else {
            cutoutCenter = null
            cutoutRadius = 0f
        }
        CrashLogger.info(
            "HaloDiag",
            "$tag statusTop=$statusTop cutoutNull=${cutout == null} rectCount=${rects?.size ?: 0} rects=$rectsDesc " +
                "safeInsetT=${cutout?.safeInsetTop} L=${cutout?.safeInsetLeft} R=${cutout?.safeInsetRight} " +
                "viewW=${view.width} viewH=${view.height} canvasOrigin=(${canvasOrigin.x.toInt()},${canvasOrigin.y.toInt()}) " +
                "-> center=(${cutoutCenter?.x?.toInt()},${cutoutCenter?.y?.toInt()}) r=${cutoutRadius.toInt()}"
        )
    }
    LaunchedEffect(view) { readCutout("init") }
    // 摇一摇触发时再读一次并打印，确保能拿到稳定的 insets
    LaunchedEffect(trigger) {
        if (trigger > 0L) readCutout("shake#$trigger")
    }

    // 完全隐藏时不绘制，避免一个全屏透明 Canvas 常驻
    if (alpha <= 0.001f) return

    Canvas(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .onGloballyPositioned { canvasOrigin = it.positionInWindow() }
    ) {
        val c = cutoutCenter
        val cx: Float
        val cy: Float
        if (c != null) {
            // cutout 坐标相对窗口 → 减去 Canvas 窗口原点，得到 Canvas 内坐标
            cx = c.x - canvasOrigin.x
            cy = c.y - canvasOrigin.y
        } else {
            // 兜底：屏幕水平中央、状态栏中部
            val statusTopPx = (ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0).toFloat()
            cx = size.width / 2f
            cy = max(statusTopPx / 2f, 26.dp.toPx())
        }
        // 紧贴摄像头外径：读取到挖孔则用其半径，否则给一个略小的兜底半径
        val r = if (cutoutRadius > 0f) cutoutRadius else 14.dp.toPx()
        val center = Offset(cx, cy)
        val stroke = 2.6.dp.toPx()

        // 一圈完整细红环，紧贴摄像头外圈
        drawCircle(
            color = BrandRed,
            radius = r + stroke / 2f,
            center = center,
            style = Stroke(width = stroke)
        )
        // 模式符号放在环下方，避开摄像头开孔
        val symbolCenter = Offset(cx, cy + r + 11.dp.toPx())
        drawModeSymbol(measurer, mode, symbolCenter)
    }
}

private fun DrawScope.drawModeSymbol(
    measurer: TextMeasurer,
    mode: PlayMode,
    pos: Offset
) {
    val red = BrandRed
    val redSoft = BrandRed.copy(alpha = 0.55f)
    val arrow = 10.dp.toPx()
    when (mode) {
        PlayMode.ONE -> {
            // 圆圈箭头（留缺口）+ 指向数字 1
            drawArc(
                color = red,
                startAngle = 40f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(pos.x - arrow, pos.y - arrow),
                size = Size(arrow * 2, arrow * 2),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArrowHead(Offset(pos.x - arrow, pos.y - arrow * 0.2f), angleDeg = 40f, red, 4.dp.toPx())
            drawCenteredText(measurer, "1", pos, 12.sp, red)
        }
        PlayMode.LIST -> {
            // 两个首尾相接的循环箭头，构成一个循环圆环
            val rad = 9.dp.toPx()
            drawArc(
                color = red, startAngle = 0f, sweepAngle = 165f, useCenter = false,
                topLeft = Offset(pos.x - rad, pos.y - rad), size = Size(rad * 2, rad * 2),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = redSoft, startAngle = 180f, sweepAngle = 165f, useCenter = false,
                topLeft = Offset(pos.x - rad, pos.y - rad), size = Size(rad * 2, rad * 2),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            // 右端箭头（顺时针终点）与左端箭头
            drawArrowHead(Offset(pos.x + rad, pos.y), angleDeg = 90f, red, 4.dp.toPx())
            drawArrowHead(Offset(pos.x - rad, pos.y), angleDeg = 270f, redSoft, 4.dp.toPx())
        }
        PlayMode.SHUFFLE -> {
            // 大写 R + 指向它的箭头
            drawCenteredText(measurer, "R", pos, 13.sp, red)
            drawArrowHead(
                Offset(pos.x - 12.dp.toPx(), pos.y + 1.dp.toPx()),
                angleDeg = 0f, redSoft, 4.5.dp.toPx()
            )
        }
    }
}

/** 在中心位置绘制文字（居中对齐）。 */
private fun DrawScope.drawCenteredText(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    size: TextUnit,
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
