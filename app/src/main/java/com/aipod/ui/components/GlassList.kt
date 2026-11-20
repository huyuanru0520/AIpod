package com.aipod.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

/**
 * 列表「液态玻璃选中」高亮（Apple Liquid Glass 观感）。
 *
 * 与滚轮 [com.aipod.ui.components.GlassSurface] 用**同一套 backdrop 真折射方案**：
 * 选中项是一小片玻璃，中心清晰透出背景、只在边缘做 lens 折射 + 色散，观感与滚轮一致。
 *
 * ★ 离散控制（v5.0.3）：选中是离散状态，**不能用淡入淡出动画**——backdrop 是坐标依赖的，
 * 滑动时多个 item 的渐变中间态（a∈(0,1)）会各自采到边缘背景，叠加成一串残留框。
 * 现在 selected=true 才挂 drawBackdrop、false 立即卸载，任何时刻恰好只有 1 项在采样，
 * 从根上消除滑动残留。
 *
 * @param selected 是否选中（离散，无渐变）
 * @param shape 圆角形状，需与列表项裁切一致（CornerBasedShape，lens 才能生效）
 */
@Composable
fun Modifier.glassHighlight(
    selected: Boolean,
    shape: Shape,
    tint: Color = Color.White
): Modifier {
    val cfg = LocalGlassBlurConfig.current
    val intensity = if (cfg.enabled) cfg.intensity else 0f
    val backdrop = LocalGlassBackdrop.current

    // 未选中 / 开关关闭：直接返回原 modifier，不挂任何绘制节点，零残留。
    if (!selected || intensity <= 0f) return this

    // 有 backdrop：和滚轮同款真折射。lens 高度受限于最小圆角半径（列表项 12~14dp，取 6dp）。
    if (backdrop != null) {
        return this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // 清透优先：极小 blur + 边缘 lens，不用 vibrancy（避免发白油腻）。
                blur(1f.dp.toPx())
                lens(5f.dp.toPx(), 10f.dp.toPx(), chromaticAberration = true)
            },
            // 极淡提亮底提升可读性。不画任何黑描边，杜绝黑框。
            onDrawSurface = { drawRect(tint.copy(alpha = 0.10f)) }
        )
    }

    // 无 backdrop 页面兜底：淡色底，不复用会留黑边的 Canvas 光学描边。
    return this.background(tint.copy(alpha = 0.12f), shape)
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
