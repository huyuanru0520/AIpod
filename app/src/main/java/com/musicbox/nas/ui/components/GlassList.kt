package com.musicbox.nas.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
