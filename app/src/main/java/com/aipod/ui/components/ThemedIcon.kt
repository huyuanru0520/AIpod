package com.aipod.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens

/**
 * 列表图标主题：七套互斥单选，不再「风格 × 图标包」两个维度叠加。
 *
 * - 0-4 五套玻璃质感（Material 形状）：透明玻璃 / 磨砂玻璃 / 彩色玻璃 / 棱镜色散 / 霓虹玻璃；
 * - 5 iPod 单色：iPod Classic 线框形状 + 固定透明玻璃质感，融入列表；
 * - 6 iPod 彩色卡片：取当前封面色的「彩色透明玻璃」圆角方块 + 白色图标，最复古。
 *
 * 设计取舍：原先的「图标包」维度里的 Material 项，本质上就是这五套玻璃质感本身；
 * 取消它后，iPod 单色与彩色卡片各自升格为独立主题，于是 5 + 2 = 7 套，单选互不叠加。
 *
 * iPod 图标素材来自 MIT 授权的 ipod-classic-js（Tanner Villarete）。
 * 部分栏目没有专属 iPod 素材时回退到 Material 形状，但仍套用当前主题的视觉风格。
 */
object IconTheme {
    const val CLEAR = 0
    const val FROSTED = 1
    const val COLOR = 2
    const val PRISM = 3
    const val NEON = 4
    const val IPOD_MONO = 5
    const val IPOD_COLOR = 6
    const val COUNT = 7

    fun label(theme: Int): String = when (theme.coerceIn(0, COUNT - 1)) {
        CLEAR -> "透明玻璃"
        FROSTED -> "磨砂玻璃"
        COLOR -> "彩色玻璃"
        PRISM -> "棱镜色散"
        NEON -> "霓虹玻璃"
        IPOD_MONO -> "iPod 单色"
        IPOD_COLOR -> "iPod 彩色卡片"
        else -> "透明玻璃"
    }

    /** 主题对应的玻璃质感档位（0-4）。iPod 单色固定用透明玻璃，彩色卡片不走玻璃质感。 */
    fun glassStyle(theme: Int): Int = when (theme.coerceIn(0, COUNT - 1)) {
        FROSTED -> GlassIconStyle.FROSTED
        COLOR -> GlassIconStyle.COLOR
        PRISM -> GlassIconStyle.PRISM
        NEON -> GlassIconStyle.NEON
        else -> GlassIconStyle.CLEAR
    }
}

/**
 * 列表图标统一入口：按当前「图标主题」渲染（七套互斥单选）。
 *
 * @param material 该栏目对应的 Material 图标（0-4 主题 / iPod 无素材回退时用）
 * @param ipod     该栏目对应的 iPod 图标（5/6 主题时用；null 则回退 material）
 * @param theme    当前图标主题：0-6，见 [IconTheme]
 * @param size     图标外框尺寸（彩色卡片即方块尺寸）
 * @param tint     图标主色（玻璃主题跟随列表前景）
 */
@Composable
fun ThemedIcon(
    material: ImageVector,
    ipod: ImageVector?,
    theme: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    contentDescription: String? = null
) {
    when (theme.coerceIn(0, IconTheme.COUNT - 1)) {
        IconTheme.IPOD_COLOR -> IpodColorCard(material, ipod, size, modifier, contentDescription)
        IconTheme.IPOD_MONO -> GlassIcon(
            ipod ?: material, GlassIconStyle.CLEAR, modifier.size(size),
            tint = tint, contentDescription = contentDescription
        )
        else -> GlassIcon(
            material, IconTheme.glassStyle(theme), modifier.size(size),
            tint = tint, contentDescription = contentDescription
        )
    }
}

/**
 * iPod 彩色卡片：取封面色的「彩色透明玻璃」。
 *
 * 关键：不再用写死的蓝渐变。主页 / 设置页背景都是 [AmbientMeshBackground] 用当前封面取色画出的
 * 环境光流光，且都录制了 [LocalGlassBackdrop] 采样源。这里用同一 backdrop 做真玻璃——卡片直接
 * 透出它正下方那块封面流光，颜色天然跟背景 / 封面一致；再叠一层封面主色的淡色膜强化「彩色」，
 * 白色图标浮在玻璃上。切歌时色板经 [rememberAmbientPaletteSnapshot] 更新，卡片跟着一起变色。
 *
 * 无 backdrop / 玻璃总开关关闭时兜底：用封面主色的半透明渐变，依旧能透出一点背景，不退化成实心块。
 */
@Composable
private fun IpodColorCard(
    material: ImageVector,
    ipod: ImageVector?,
    size: Dp,
    modifier: Modifier,
    contentDescription: String?
) {
    val backdrop = LocalGlassBackdrop.current
    val cfg = LocalGlassBlurConfig.current
    val palette = rememberAmbientPaletteSnapshot().value
    // 取封面色板里明度较高的两档作为玻璃色膜 / 兜底色；无封面时退回经典 iPod 蓝。
    val coverHi = palette?.primary ?: Color(0xFF6BA3D6)
    val coverLo = palette?.secondary ?: Color(0xFF2F5E9E)
    val cardShape = RoundedCornerShape(percent = 22)

    val cardModifier = if (backdrop != null && cfg.enabled) {
        modifier.size(size).drawBackdrop(
            backdrop = backdrop,
            shape = { cardShape },
            effects = {
                // 温和提饱和：让透出的封面流光更鲜活，不用 vibrancy（固定 1.5× 会发油发灰）。
                colorControls(saturation = 1.28f)
                // 极轻模糊，只做玻璃的厚度感，不糊成磨砂板。
                blur(2f.dp.toPx())
                // 小卡片只需细微边缘折射，不开色散以免图标周围出现彩边。
                lens(2.5f.dp.toPx(), 5f.dp.toPx(), chromaticAberration = false)
            },
            onDrawSurface = {
                // 封面主色淡色膜：让「取封面色」更明确，同时保持通透（背景依旧透得出）。
                drawRect(
                    Brush.verticalGradient(
                        listOf(coverHi.copy(alpha = 0.30f), coverLo.copy(alpha = 0.40f))
                    )
                )
                // 左上 → 右下的对角高光，Screen 只抬亮度不改色相，暗示玻璃厚度。
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.16f),
                        0.55f to Color.Transparent,
                        start = Offset.Zero,
                        end = Offset(this.size.width, this.size.height)
                    ),
                    blendMode = BlendMode.Screen
                )
            }
        )
    } else {
        // 无 backdrop / 玻璃关闭：封面主色半透明渐变，仍透出一点背景，不做成实心块。
        modifier.size(size).clip(cardShape).background(
            Brush.verticalGradient(
                listOf(coverHi.copy(alpha = 0.70f), coverLo.copy(alpha = 0.80f))
            )
        )
    }

    Box(cardModifier, contentAlignment = Alignment.Center) {
        Icon(
            ipod ?: material,
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.64f),
            tint = Color.White
        )
    }
}
