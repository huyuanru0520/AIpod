package com.aipod.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 列表「玻璃选中」高亮：选中项罩一层半透明玻璃 + 上亮下暗的描边，
 * 透明度用动画淡入淡出；未选中时完全透明，不占视觉重量。
 *
 * 玻璃感来自三件事：
 * 1. 一层很淡的半透明白底（磨砂玻璃的「雾面」）；
 * 2. 顶部亮、底部暗的竖向渐变描边（玻璃厚度折射的亮线）；
 *
 * @param selected 是否选中，透明度随它动画过渡
 * @param shape 圆角形状，需与列表项裁切一致
 */
@Composable
fun Modifier.glassHighlight(
    selected: Boolean,
    shape: Shape,
    tint: Color = Color.White
): Modifier {
    val a by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(180),
        label = "glassHighlight"
    )
    return this
        .background(tint.copy(alpha = 0.12f * a), shape)
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(
                    tint.copy(alpha = 0.42f * a),
                    tint.copy(alpha = 0.08f * a)
                )
            ),
            shape = shape
        )
}

/**
 * 列表整体 / 单项的淡入：组合首次进入时从 0 淡到 1。
 * 用于「所有列表淡入淡出」——切到新页面、换层、加载完成出现列表时都有一个柔和淡入。
 */
@Composable
fun Modifier.fadeInList(
    durationMs: Int = 260,
    enabled: Boolean = true
): Modifier {
    val a by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(durationMs),
        label = "fadeInList"
    )
    return this.alpha(a)
}

/**
 * 列表上下边缘淡入淡出：把被显示区裁切的行用 alpha 渐变隐去，而不是硬生生切断。
 * 效果类似 Apple Music 歌词的边缘羽化——顶部/底部的行越靠近边界越透明，弱化显示边界，
 * 让列表像是「浮」在背景上、从虚空中滚动出来。
 *
 * 实现：先把内容画到离屏层，再用一条「两端透明、中间不透明」的竖向渐变以 DstIn
 * 混合，只保留中间的 alpha，边缘自然淡出（不覆盖任何底色，背景是什么都能融合）。
 *
 * @param fade 边缘淡出宽度占整体高度的比例（0.06f ≈ 顶部/底部各 6%）
 */
fun Modifier.fadeEdges(fade: Float = 0.12f): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        val f = fade.coerceIn(0f, 0.45f)
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    f to Color.Black,
                    1f - f to Color.Black,
                    1f to Color.Transparent
                ),
                startY = 0f,
                endY = size.height
            ),
            blendMode = BlendMode.DstIn
        )
    }
