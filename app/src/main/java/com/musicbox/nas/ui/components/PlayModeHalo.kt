package com.musicbox.nas.ui.components

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.musicbox.nas.data.PlayMode
import com.musicbox.nas.ui.theme.BrandRed
import com.musicbox.nas.util.CrashLogger
import kotlinx.coroutines.delay

/**
 * 播放模式提示环：平时完全隐藏，仅在 [trigger] 变化（摇一摇切换模式）时短暂浮现，
 * 约 1.8 秒后淡出。**只画一个红环**紧贴屏幕顶部灵动岛（居中挖孔）的外圈，
 * 不再绘制模式符号圆——之前的「两个圆」即主环 + 下方符号圆，本版已去掉符号圆。
 *
 * 定位说明：Magic8 Pro Air 屏幕 1216×2640，实测灵动岛黑色区域 x423..791、y21..133，
 * 圆心 (607,77)，左右半圆半径 56.5px。该系统 DisplayCutout 报的矩形宽 78px 是错误值
 * （实拍宽 369px），不能拿来画环，故改为按实拍标定的屏幕比例定位，跨分辨率等比缩放。
 * Canvas 铺满全屏且不吃触摸事件，可安全叠在播放页最上层。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun PlayModeHalo(
    mode: PlayMode,
    trigger: Long,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current

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

    // Canvas 在窗口坐标系中的原点，仅用于诊断日志（DisplayCutout 坐标相对整个窗口）。
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    // 摇一摇时打印系统 cutout 原始值，便于按机型核对标定比例。
    LaunchedEffect(trigger) {
        if (trigger <= 0L) return@LaunchedEffect
        val insets = ViewCompat.getRootWindowInsets(view)
        val cutout = insets?.displayCutout
        val rects = cutout?.boundingRects
        val statusTop = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: -1
        val rectsDesc = rects?.mapIndexed { i, r ->
            "[$i]L${r.left},T${r.top},R${r.right},B${r.bottom},W${r.width()},H${r.height()}"
        }?.joinToString(" ") ?: "null"
        CrashLogger.info(
            "HaloDiag",
            "shake#$trigger statusTop=$statusTop cutoutNull=${cutout == null} rects=$rectsDesc " +
                "viewW=${view.width} viewH=${view.height} canvasOrigin=(${canvasOrigin.x.toInt()},${canvasOrigin.y.toInt()}) " +
                "-> useCenter=(607,77) r=56.5 (scaled by view size)"
        )
    }

    // 完全隐藏时不绘制，避免一个全屏透明 Canvas 常驻
    if (alpha <= 0.001f) return

    Canvas(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .onGloballyPositioned { canvasOrigin = it.positionInWindow() }
    ) {
        // Canvas 铺满窗口，size 即屏幕像素；按 1216×2640 实拍标定等比缩放。
        val sx = size.width / 1216f
        val sy = size.height / 2640f
        val cx = 607f * sx
        val cy = 77f * sy
        val r = 56.5f * sx
        val stroke = 2.6.dp.toPx()
        drawCircle(
            color = BrandRed,
            radius = r + stroke / 2f,
            center = Offset(cx, cy),
            style = Stroke(width = stroke)
        )
    }
}
