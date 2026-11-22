package com.aipod.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 玻璃质感图标风格：与设置页「图标风格」选项一一对应。
 * 0=透明玻璃 1=磨砂玻璃 2=彩色玻璃 3=棱镜色散 4=霓虹玻璃。
 */
object GlassIconStyle {
    const val CLEAR = 0
    const val FROSTED = 1
    const val COLOR = 2
    const val PRISM = 3
    const val NEON = 4
    const val COUNT = 5

    fun label(style: Int): String = when (style.coerceIn(0, COUNT - 1)) {
        CLEAR -> "透明玻璃"
        FROSTED -> "磨砂玻璃"
        COLOR -> "彩色玻璃"
        PRISM -> "棱镜色散"
        NEON -> "霓虹玻璃"
        else -> "透明玻璃"
    }
}

/** 按图标名派生稳定色相：同一图标每次颜色一致，不同图标各异。 */
private fun ImageVector.hue(): Float =
    ((name ?: "icon").hashCode() and 0x7fffffff) % 360 / 360f

/**
 * 玻璃质感图标。
 *
 * 关键：形状、尺寸、位置完全交给系统原生 [Icon] 处理——绝不自绘 ImageVector 路径，
 * 否则缩放/描边坐标系一算错就会「图标消失」或「图标飘到文字外」。
 * 五套质感只通过一层 Blend 蒙版实现：先用 Icon 画出形状（tint 白 = 纯色蒙版），
 * 再用 [BlendMode.SrcIn] 把渐变精确裁进图标轮廓内，形状与位置零改动。
 *
 * [tint] 作为透明/磨砂/棱镜风格的主色基底；彩色/霓虹按图标名派生色相。
 */
@Composable
fun GlassIcon(
    image: ImageVector,
    style: Int,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    contentDescription: String? = null
) {
    val sc = style.coerceIn(0, GlassIconStyle.COUNT - 1)
    val hue = image.hue()
    Icon(
        image,
        contentDescription = contentDescription,
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithGlass(sc, hue, tint),
        // 白色实心 = 干净的 alpha 蒙版，SrcIn 渐变只落在图标形状内。
        tint = Color.White
    )
}

/**
 * 画完原生 Icon（形状蒙版）后，用 SrcIn 把质感渐变裁进轮廓。
 * 渐变坐标基于当前 DrawScope.size（即 modifier 尺寸），不再依赖任何外部坐标系。
 */
private fun Modifier.drawWithGlass(style: Int, hue: Float, tint: Color): Modifier =
    drawWithContent {
        drawContent()
        val w = size.width
        val h = size.height
        val brush: Brush = when (style) {
            // 透明玻璃：白色半透明竖向渐变，顶部亮、底部淡，通透厚度感。
            GlassIconStyle.CLEAR -> Brush.linearGradient(
                colors = listOf(tint.copy(alpha = 0.96f), tint.copy(alpha = 0.52f)),
                start = Offset(0f, 0f), end = Offset(0f, h)
            )
            // 磨砂玻璃：低对比柔白，朦胧克制。
            GlassIconStyle.FROSTED -> Brush.verticalGradient(
                colors = listOf(tint.copy(alpha = 0.74f), tint.copy(alpha = 0.46f))
            )
            // 彩色玻璃：按图标名派生稳定色相的双色柔和渐变。
            GlassIconStyle.COLOR -> Brush.linearGradient(
                colors = listOf(
                    Color.hsv(hue, 0.58f, 1f, 0.96f),
                    Color.hsv((hue + 0.08f) % 1f, 0.52f, 1f, 0.72f)
                ),
                start = Offset(0f, 0f), end = Offset(w, h)
            )
            // 棱镜色散：粉→黄→绿→蓝→紫五色渐变，最吸睛。
            GlassIconStyle.PRISM -> Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFF6B9D), Color(0xFFFFD93D), Color(0xFF6BCB77),
                    Color(0xFF4D96FF), Color(0xFFB983FF)
                ),
                start = Offset(0f, 0f), end = Offset(w, h)
            )
            // 霓虹玻璃：高饱和径向亮渐变，中心亮、边缘霓虹色。
            GlassIconStyle.NEON -> Brush.radialGradient(
                colors = listOf(
                    Color.White,
                    Color.hsv(hue, 0.9f, 1f, 1f),
                    Color.hsv(hue, 0.85f, 0.82f, 1f)
                ),
                center = Offset(w / 2f, h / 2f), radius = maxOf(w, h) * 0.72f
            )
            else -> Brush.linearGradient(
                colors = listOf(tint.copy(alpha = 0.96f), tint.copy(alpha = 0.52f)),
                start = Offset(0f, 0f), end = Offset(0f, h)
            )
        }
        drawRect(brush = brush, blendMode = BlendMode.SrcIn)
    }
