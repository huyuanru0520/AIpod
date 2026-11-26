package com.aipod.ui.components

import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * 液态玻璃配置（Apple Liquid Glass 观感）。
 *
 * 玻璃组件（滚轮 / 列表选中项）通过 backdrop 库采样正下方背景，做极轻 blur + 边缘 lens
 * 折射与色散，中心清晰透出背景、只在边缘把背景扭曲，这才是液态玻璃与「把背景糊掉」的毛玻璃的本质区别。
 * 无 backdrop 的页面（如底部 Dock / 迷你播放器）退回轻量 Canvas 边缘高光兜底。
 *
 * - [enabled]：液态玻璃总开关（关闭时玻璃组件退化为普通半透明底）；
 * - [intensity]：光学层强度倍率；
 * - [backdropLevel]：背景流光浓度档位（设置项「背景流光」控制，0 清透 ~ 3 浓郁）。
 *
 * 注：背景已从「模糊封面」改为「封面取色 + 动态流光」，组件不再需要模糊半径参数。
 */
/**
 * 亮面玻璃三档预设（照 demo：A Aura同款 / B 更亮 / C 极透）。
 *
 * 三档只在三个参数上不同，背景采样管线完全相同：
 *  - [blurDp]          backdrop 模糊半径（越低背景越清晰）
 *  - [tintAlpha]       表面极淡黑膜 alpha（越低越通透）
 *  - [highlightAlpha]  45° 轮廓高光强度（Plus 混合，只勾边缘、盘心零绘制）
 */
data class GlassTierSpec(
    val blurDp: Float,
    val tintAlpha: Float,
    val highlightAlpha: Float,
    /** 高光线宽 dp：决定「釉面亮边」的粗细，是三档最直观的差异。 */
    val highlightWidthDp: Float,
    /** 高光模糊半径 dp：越大越像柔和的亮面釉光。 */
    val highlightBlurDp: Float,
    /** 自绘光影强度倍率：A 玻璃感重=1，C 极透=0.25（削弱自绘、让背景透出）。 */
    val surfaceScale: Float = 1f,
    /** 是否启用 backdrop 真采样。false = 关闭真玻璃，走轻量兜底（省电）。 */
    val backdropEnabled: Boolean = true
)

/**
 * 亮面玻璃四档：0=A 厚玻璃、1=B 亮面、2=C 极透、3=关闭（省电）。
 * 差异刻意拉大——参数太接近时真机上肉眼分辨不出，等于没生效。
 */
object GlassTiers {
    const val COUNT = 4
    const val OFF = 3
    private val SPECS = listOf(
        // A 厚玻璃：背景柔化、细亮边，玻璃感最重
        GlassTierSpec(10f, 0.10f, 0.75f, 0.5f, 0f, 1.00f),
        // B 亮面：宽而亮的釉面高光、背景仍清晰（最接近「潜规则」）
        GlassTierSpec(4f, 0.04f, 1.20f, 1.6f, 0.8f, 0.70f),
        // C 极透：零模糊零色膜，背景全透、只剩极细高光勾边
        GlassTierSpec(0f, 0.00f, 0.90f, 0.4f, 0f, 0.25f),
        // 关闭：不采样 backdrop，走 Canvas 兜底观感，功耗最低
        GlassTierSpec(0f, 0f, 0f, 0f, 0f, 0f, backdropEnabled = false)
    )
    fun spec(tier: Int): GlassTierSpec = SPECS[tier.coerceIn(0, COUNT - 1)]
    fun label(tier: Int): String = when (tier.coerceIn(0, COUNT - 1)) {
        0 -> "A · 厚玻璃"
        1 -> "B · 亮面"
        2 -> "C · 极透"
        else -> "关闭 · 省电"
    }
    fun summary(tier: Int): String = when (tier.coerceIn(0, COUNT - 1)) {
        0 -> "柔化背景 + 细亮边，玻璃感最重"
        1 -> "宽亮釉面 + 背景清晰（潜规则同款）"
        2 -> "零模糊零色膜，背景全透"
        else -> "不采样背景，走轻量兜底，功耗最低"
    }
}

/** 按档位参数构造高光（width/blurRadius/alpha 都随档位变化）。 */
fun GlassTierSpec.highlight(alphaScale: Float = 1f) = Highlight.Default.copy(
    width = highlightWidthDp.dp,
    blurRadius = highlightBlurDp.dp,
    alpha = (highlightAlpha * alphaScale).coerceAtMost(2f)
)

data class GlassBlurConfig(
    val enabled: Boolean,
    val intensity: Float,
    val backdropLevel: Int = 2,
    /** 亮面玻璃档位 0~2（Aura同款 / 更亮 / 极透）。 */
    val tier: Int = 0
) {
    /** 当前档位的模糊/色膜/高光参数。 */
    val tierSpec: GlassTierSpec get() = GlassTiers.spec(tier)

    companion object {
        /**
         * @param glassOn 液态玻璃开关
         * @param backdropLevel 背景档位 0~3：清透 / 柔和 / 均衡 / 浓郁
         * @param tier 亮面玻璃档位 0~2
         */
        fun of(glassOn: Boolean, backdropLevel: Int, tier: Int = 0): GlassBlurConfig = GlassBlurConfig(
            enabled = glassOn,
            intensity = if (glassOn) 1f else 0f,
            backdropLevel = backdropLevel.coerceIn(0, 3),
            tier = tier.coerceIn(0, GlassTiers.COUNT - 1)
        )
    }
}

/** 当前液态玻璃配置；由根容器按设置下发。 */
val LocalGlassBlurConfig = compositionLocalOf { GlassBlurConfig.of(true, 2) }

/**
 * 页面级 backdrop（Kyant0 backdrop 库）：由每个需要玻璃的页面顶层用 [rememberGlassBackdrop] 创建并通过
 * CompositionLocalProvider 下发；页面背景层用 [Modifier.glassBackdropSource] 录制，玻璃组件（滚轮/卡片）
 * 从同一个 backdrop 采样。这样背景与玻璃共享同一实例，是 backdrop 库的正确配对用法。
 * null 表示该页面没有提供采样源，玻璃退化为 Canvas 高光兜底。
 */
val LocalGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/** 在页面顶层创建一个玻璃采样 backdrop。 */
@Composable
fun rememberGlassBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * 背景层调用：把“背景 + 暗蒙层”这一节点的内容录制进 backdrop，供同页玻璃组件采样。
 * 必须挂在背景节点上，且与玻璃组件在同一 Composable 树内共享 [LocalGlassBackdrop] 实例。
 */
fun Modifier.glassBackdropSource(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) this.layerBackdrop(backdrop) else this

/**
 * 把 Compose [Shape] 转成 Canvas 可绘制的 [Path]。CircleShape / RoundedCornerShape /
 * 一般 Shape 都通过 createOutline 统一处理，参数是屏幕像素尺寸。
 */
internal fun DrawScope.shapePath(shape: Shape): Path {
    val outline = shape.createOutline(size, LayoutDirection.Ltr, this)
    return when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }
}

/**
 * 绘制 Apple Liquid Glass（iOS 26/27）观感的核心。全部是 Canvas 光学绘制，不采样背景。
 *
 * ★ 核心原则（v4.7.1 修正）：真正的液态玻璃 **中心完全透明**，背景原样透出；
 * 实体感只来自「轮廓边缘的一圈玻璃厚度」。此前版本在中心叠了多层白色渐变
 * （径向白底 + 顶部白幕 + Plus 高光圈 + 底部反光），叠加成一层白灰雾——正是
 * 用户看到的「磨砂塑料盘」，与 Apple 液态玻璃完全相反，故全部移除。
 *
 * 图层（由内到外，全部只作用在轮廓附近，中心零绘制）：
 *  1. 内侧折射光带——沿轮廓内缘的粗描边（clip 到形状内只留内侧一半），偏左上聚光；
 *  2. 顶面镜面高光弧——更窄的一条内缘描边，竖向渐变只提亮上缘；
 *  3. 色散彩边——左上一段青→品红极淡 fringe（spherochromatism），Plus 提亮；
 *  4. iOS27 深色描边——极细暗线压在轮廓上，强化与背景的分区；
 *  5. 菲涅尔亮边——上缘最亮、底部一道 bounce 反光。
 *
 * @param intensity 高光强度倍率（0=不画）；按压时临时增大即可产生光泽「流动」感。
 */
private fun DrawScope.drawLiquidGlass(shape: Shape, intensity: Float) {
    if (intensity <= 0f) return
    val path = shapePath(shape)
    val w = size.width
    val h = size.height
    val s = intensity
    val unit = 1.dp.toPx()
    // 边缘厚度带宽度：随短边缩放。中心大片区域保持完全透明，绝不着色。
    val edge = min(w, h) * 0.13f

    // (1)(2) 边缘带：clip 到形状内，用「粗描边只保留内侧一半」画出沿轮廓的光学厚度。
    // 这是玻璃唯一的实体感来源；中心区域没有任何绘制，背景 100% 透出。
    clipPath(path) {
        // 内侧折射光：偏左上的径向聚光，模拟光在玻璃厚边里汇聚
        drawPath(
            path = path,
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.34f * s),
                    Color.White.copy(alpha = 0.13f * s),
                    Color.Transparent
                ),
                center = Offset(w * 0.28f, h * 0.16f),
                radius = max(w, h) * 0.78f
            ),
            style = Stroke(width = edge)
        )
        // 顶面镜面高光弧：更窄的一条，竖向渐变只提亮上缘那一弯
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.55f * s),
                0.16f to Color.White.copy(alpha = 0.10f * s),
                0.34f to Color.Transparent
            ),
            style = Stroke(width = edge * 0.55f)
        )
    }

    // (3) 色散彩边：左上一段青→品红 fringe，用 Plus 混合只提亮不压暗
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF7FE8FF).copy(alpha = 0.30f * s),
                Color(0xFFFF9AD5).copy(alpha = 0.14f * s),
                Color.Transparent
            ),
            start = Offset(0f, 0f),
            end = Offset(w * 0.8f, h * 0.8f)
        ),
        style = Stroke(width = 2.2f * unit),
        blendMode = BlendMode.Plus
    )
    // (4) iOS27 深色描边：极细暗线压在轮廓上，强化分区
    drawPath(
        path = path,
        color = Color.Black.copy(alpha = 0.32f),
        style = Stroke(width = 1.8f * unit)
    )
    // (5) 菲涅尔亮边：上缘最亮、两侧渐弱、底部一道 bounce 反光
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.95f * s),
            0.30f to Color.White.copy(alpha = 0.26f * s),
            0.72f to Color.White.copy(alpha = 0.10f * s),
            1f to Color.White.copy(alpha = 0.60f * s)
        ),
        style = Stroke(width = 1.4f * unit)
    )
}

/**
 * 把液态玻璃光学层作为 Modifier 挂到组件上（画在底色之上、内容之下）。
 * 单独抽成 Modifier 便于 GlassSurface 与列表选中项共用同一套观感。
 */
fun Modifier.liquidGlassLayer(shape: Shape, intensity: Float): Modifier =
    this.drawBehind { drawLiquidGlass(shape, intensity) }

/**
 * 圆形滚轮专用液态玻璃（iPod Click Wheel）。与卡片用 [drawLiquidGlass] 分开，因为大圆盘上
 * 按「短边比例」取边缘宽度会得到一条几十 dp 的宽灰带——就是此前「太假」的根源。
 *
 * 真实玻璃滚轮的观感只来自三样东西，且**全部集中在最外圈、中心完全透明**：
 *  1. 菲涅尔亮线——沿圆周一圈细亮边，上缘最亮；
 *  2. 镜面高光弧——只占顶部一段（约 10 点~2 点方向）、两端自然淡入淡出的弧形反光，
 *     用一串短弧 + sin 包络实现，两端不会硬切；
 *  3. 内缘暗线 + 极淡色散——勾出玻璃厚度与冷暖色散。
 * 除此之外不再有任何填充或大面积渐变，背景 100% 透出。
 *
 * @param intensity 高光强度（0=不画）
 */
private fun DrawScope.drawWheelGlass(intensity: Float) {
    if (intensity <= 0f) return
    val s = intensity
    val r = min(size.width, size.height) / 2f
    val c = center
    val unit = 1.dp.toPx()

    // 关闭档兜底：只画一圈连续、干净的细描边勾出滚轮轮廓，背景 100% 透出。
    // ★ 绝不用分段短弧（drawArc + StrokeCap.Round）：段长小于线宽时会变成一圈虚线圆点，
    //   那正是「点状边缘」的来源。这里每一笔都是一次成型的连续 drawCircle，无接缝。
    // 外缘：上缘略亮、两侧与底部极淡的连续细边。
    drawCircle(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.26f * s),
            0.25f to Color.White.copy(alpha = 0.10f * s),
            0.70f to Color.White.copy(alpha = 0.05f * s),
            1f to Color.White.copy(alpha = 0.14f * s)
        ),
        radius = r - 0.75f * unit,
        center = c,
        style = Stroke(width = 1.1f * unit)
    )
    // 内缘：极淡一圈，勾出玻璃厚度，连续无接缝。
    drawCircle(
        color = Color.White.copy(alpha = 0.05f * s),
        radius = r - 3.2f * unit,
        center = c,
        style = Stroke(width = 0.9f * unit)
    )
}

/** 圆形滚轮专用 Modifier：画在底色之上、内容之下，中心零绘制。 */
fun Modifier.liquidWheelLayer(intensity: Float): Modifier =
    this.drawBehind { drawWheelGlass(intensity) }

/**
 * 「液态玻璃」外壳（Apple Liquid Glass 观感）。
 *
 * 两种实现自动切换：
 *  - 页面提供了 backdrop 且开关开启（[circular]）：用 backdrop 库做**真背景采样**——
 *    玻璃盘内背景保持清晰、盘外也清晰，只在边缘由 lens 产生折射位移与色散。
 *    纯 Compose 实现，不与 View 绘制互相重入。
 *  - 无采样源 / 开关关闭：退回轻量 Canvas 边缘高光兜底，零采样开销。
 *
 * [blur] 仅兼容既有调用点（表示主要玻璃组件）；总开关由 [LocalGlassBlurConfig] 决定。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tintAlpha: Float = 0.62f,
    elevation: Dp = 20.dp,
    /** 主要玻璃组件（滚轮/Dock/迷你播放器）传 true，光学层强度略高。 */
    blur: Boolean = false,
    /** 滚轮传 true：用 backdrop 真采样折射（配合 RoundedCornerShape(percent=50) 正方形上的正圆）。 */
    circular: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val cfg = LocalGlassBlurConfig.current
    val spec = cfg.tierSpec
    val intensity = if (cfg.enabled) cfg.intensity * (if (blur) 1f else 0.85f) else 0f
    val backdrop = LocalGlassBackdrop.current
    // 真玻璃：滚轮 + 有 backdrop + 开关开启 + 档位未关闭 → 用 backdrop 库采样折射背景。
    // 关闭档（spec.backdropEnabled=false）走下面的 Canvas 兜底，零 backdrop 采样开销。
    val useBackdrop = circular && intensity > 0f && backdrop != null && spec.backdropEnabled

    Box(
        if (useBackdrop) {
            // 严格顺序：color filter → blur → lens（库渲染管线有依赖）。
            // 关键修复（v5.1.1）：depthEffect 必须保持默认 false。库 shader 里
            //   grad = normalize(gradSdRoundedRect + depthEffect * normalize(centeredCoord))
            // 圆心 centeredCoord=(0,0) 时 normalize 产生 NaN，折射采样被污染成整盘纯白。
            // 一律不用 depthEffect；边缘立体感交给库默认 highlight（仅轮廓 0.5dp、中心零绘制）。
            modifier.drawBackdrop(
                backdrop = backdrop!!,
                shape = { shape },
                effects = {
                    // 温和提饱和，不用 vibrancy（固定 1.5×，发油发灰）；纯 ColorMatrix，安全。
                    colorControls(saturation = 1.3f)
                    // 亮面玻璃三档模糊：A 10dp 柔化 / B 4dp 清晰 / C 0dp 全透。
                    blur(spec.blurDp.dp.toPx())
                    // 与 5.0.5 已验证可透出背景的参数一致，仅开色散、不开 depthEffect。
                    lens(
                        refractionHeight = 12f.dp.toPx(),
                        refractionAmount = 24f.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                // 亮面玻璃三档高光：width/blurRadius/alpha 随档位变化，切档一眼可见。
                highlight = { spec.highlight(alphaScale = intensity) },
                // 厚度暗示：仅左上角一抹对角微光，55% 处归零、盘心完全透明。
                // ★ 绝不做整盘白渐变填充——那正是「磨砂不透明」的成因。这里 alpha≤0.07、
                // 普通混合（非 Plus），只让玻璃左上有一点厚薄不均的光泽，背景依然清晰透出。
                onDrawSurface = {
                    // 三档色膜：A .10 / B .04 / C 0，越低越通透。
                    if (spec.tintAlpha > 0f) drawRect(Color.Black.copy(alpha = spec.tintAlpha * intensity))
                    drawRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.00f to Color.White.copy(alpha = 0.07f),
                                0.30f to Color.White.copy(alpha = 0.025f),
                                0.55f to Color.Transparent,
                                1.00f to Color.Transparent
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        )
                    )
                }
                // highlight/shadow 用库默认（与 5.0.5 一致，已验证不铺白）。
            )
        } else {
            modifier
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.30f),
                    spotColor = Color.Black.copy(alpha = 0.40f)
                )
                .clip(shape)
                .background(if (intensity > 0f) Color.Transparent else tint.copy(alpha = tintAlpha))
                .then(if (circular) Modifier.liquidWheelLayer(intensity)
                      else Modifier.liquidGlassLayer(shape, intensity))
        }
    ) {
        // 注意：不在真玻璃盘上叠加自绘 Plus 白色光学层——大半径 radialGradient + Plus 会
        // 把整盘提亮成白雾（v5.1.0 纯白盘的第二元凶）。边缘立体感完全交给库默认 highlight，
        // 它只在轮廓 0.5dp 内画高光，中心零绘制，保持背景真实透出。
        content()
    }
}
