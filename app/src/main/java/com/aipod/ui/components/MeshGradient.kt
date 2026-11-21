package com.aipod.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.palette.graphics.Palette
import com.aipod.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 环境光背景（Mesh Gradient / 动态流光渐变）。
 *
 * 不再用「整张封面做高斯模糊」当背景——模糊封面有两个死穴：① 大面积涂抹感、颜色浑浊；
 * ② 模糊后亮度不可控，白字不是发灰就是糊成一团。
 *
 * 这里改成分两步：
 *  1. **取色**：用 AndroidX Palette 从封面里挑出鲜艳 / 深艳 / 明亮三档色，再把它们统一
 *     收敛到主色附近（色相最多偏 30° 左右）并映射到「深色背景上的流光」区间
 *     （夹逼明度、提饱和）。色相收敛很关键：直接用互补色会混成灰绿泥浆。
 *  2. **成图**：深色基底 + 四团大半径径向渐变，各自沿缓慢的 Lissajous 轨道游走并轻微呼吸
 *     缩放，颜色的长尾在中间交融，就是 iOS「环境光」那种在屏幕上游走的通透感。
 *     定死不动也比单张模糊图干净得多。
 *
 * 性能：整块用 Canvas 绘制，不采样、不做真实高斯模糊；时钟降到 25fps（柔光每秒只移动几个
 * 像素，肉眼完全看不出顿），把每帧的整屏径向渐变重绘量砍掉一半以上——这点很关键，因为这块
 * 背景同时还是滚轮玻璃的 backdrop 采样源，每帧重绘都会被玻璃重新采样一次。
 */

/** 封面提取出的「环境光」色板：基色（压暗的同色系）+ 四团流光（同一色相族，深浅分开）。 */
data class AmbientPalette(
    val base: Color,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val accent2: Color
)

/** HSV 三通道（H 0~360，S/V 0~1），直接用 Android 的实现避免自己写色彩空间转换。 */
private fun hsvOf(color: Color): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv
}

/**
 * 把 [target] 的色相拉向 [base]，偏移量限制在 ±[maxDelta] 度内。
 *
 * ★ 取 |maxDelta|：调用侧有传负值表示"反向偏移"的写法，若直接 coerceIn(-maxDelta, maxDelta)
 *   会得到 coerceIn(44f, -44f) 这种空区间，Kotlin 直接抛 IllegalArgumentException，
 *   整条取色链路被打断、退回兜底色——这就是"背景一直不跟封面变色"的真正原因。
 */
private fun hueNear(base: Float, target: Float, maxDelta: Float): Float {
    val span = kotlin.math.abs(maxDelta)
    var d = ((target - base) % 360f + 540f) % 360f - 180f // 归一化到 -180..180
    d = d.coerceIn(-span, span)
    return ((base + d) % 360f + 360f) % 360f
}

/** 用 HSV 直接构造颜色：明度夹逼是防过曝/防死黑最稳的手段，比调 alpha 可靠得多。 */
private fun hsvColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f,
        s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

/**
 * 由一组封面取色推导出四色流光。
 *
 * 三团副色都以主色相为锚点（最多偏 34°~44°），保证整体是一个「色相家族」——
 * 这是 iOS 环境光的核心观感：颜色丰富但绝不脏。明度全部夹逼在暗部区间，
 * 既保留饱和的彩色，又不会把白字压得读不出来。
 */
private fun buildPalette(
    vibrant: Color,
    dark: Color,
    light: Color,
    dominant: Color
): AmbientPalette {
    val ph = hsvOf(vibrant)
    val dh = hsvOf(dark)
    val lh = hsvOf(light)
    val mh = hsvOf(dominant)
    // 黑白/灰阶封面（爵士、古典、黑白唱片）：不强行染成随机彩色，只留极淡的中性偏色，
    // 否则会得到一片毫无道理的紫红或墨绿。
    val gray = mh[1] < 0.10f
    fun satOf(raw: Float, floor: Float): Float =
        if (gray) (raw * 0.6f).coerceIn(0f, 0.14f) else max(raw, floor)
    return AmbientPalette(
        base = hsvColor(mh[0], mh[1] * 0.90f, mh[2].coerceIn(0.10f, 0.16f)),
        primary = hsvColor(ph[0], satOf(ph[1], 0.55f) * 1.15f, ph[2].coerceIn(0.60f, 0.88f)),
        secondary = hsvColor(hueNear(ph[0], dh[0], 34f), satOf(dh[1], 0.55f) * 1.10f, dh[2].coerceIn(0.48f, 0.62f)),
        accent = hsvColor(hueNear(ph[0], lh[0], 44f), satOf(lh[1], 0.52f) * 1.15f, lh[2].coerceIn(0.62f, 0.90f)),
        // 第二团副色固定落在主色相的反侧 40°，否则 light 与 vibrant 同色相时两团光会完全重合，
        // 画面又退回单色渐变。
        accent2 = hsvColor(((ph[0] - 40f) % 360f + 360f) % 360f, satOf(lh[1], 0.55f) * 1.10f, lh[2].coerceIn(0.55f, 0.80f))
    )
}

/** 没有封面时（未播放 / 加载中）用主题色兜底，仍是同一套流光，只是色相来自 [seed]。 */
private fun fallbackPalette(seed: Color): AmbientPalette {
    val sh = hsvOf(seed)
    return AmbientPalette(
        base = Color(0xFF0B0B0E),
        primary = hsvColor(sh[0], max(sh[1], 0.6f), 0.62f),
        secondary = hsvColor(hueNear(sh[0], sh[0] + 26f, 26f), max(sh[1], 0.6f), 0.44f),
        accent = hsvColor(hueNear(sh[0], sh[0] + 34f, 34f), max(sh[1], 0.55f), 0.72f),
        accent2 = hsvColor(hueNear(sh[0], sh[0] - 34f, 34f), max(sh[1], 0.55f), 0.56f)
    )
}

/**
 * Palette 失败时的兜底取色：按色相分 12 个桶做加权聚类，取权重最高的三桶平均色。
 *
 * 有些封面（极端单色、纯黑边、Palette 的 filter 全部过滤掉）会一个 swatch 都给不出来，
 * 直接退回主题色就等于「背景不跟封面变」——这正是之前的现象之一，所以必须留一条纯像素的退路。
 */
private fun pixelPaletteOf(bmp: Bitmap, fallback: Color): AmbientPalette {
    val small = runCatching { Bitmap.createScaledBitmap(bmp, 48, 48, true) }.getOrNull()
        ?: return fallbackPalette(fallback)
    val w = small.width
    val h = small.height
    if (w <= 0 || h <= 0) return fallbackPalette(fallback)
    val px = IntArray(w * h)
    val ok = runCatching { small.getPixels(px, 0, w, 0, 0, w, h) }.isSuccess
    if (!ok) return fallbackPalette(fallback)

    val weight = FloatArray(12)
    val sr = FloatArray(12)
    val sg = FloatArray(12)
    val sb = FloatArray(12)
    val sv = FloatArray(12)
    val hsv = FloatArray(3)
    for (p in px) {
        if ((p ushr 24) and 0xFF < 40) continue
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val i = (hsv[0] / 30f).toInt().coerceIn(0, 11)
        val wgt = hsv[1] * hsv[2] + 0.02f // 无彩色像素也给一点点权重，避免全黑封面取不到色
        weight[i] += wgt
        sr[i] += r * wgt; sg[i] += g * wgt; sb[i] += b * wgt
        sv[i] += hsv[2] * wgt
    }
    val order = (0 until 12).sortedByDescending { weight[it] }.filter { weight[it] > 0f }
    if (order.isEmpty()) return fallbackPalette(fallback)
    fun avg(i: Int) = Color(
        (sr[i] / weight[i]).toInt().coerceIn(0, 255),
        (sg[i] / weight[i]).toInt().coerceIn(0, 255),
        (sb[i] / weight[i]).toInt().coerceIn(0, 255)
    )
    val top = order.take(3)
    val main = avg(top[0])
    val lightest = top.maxByOrNull { sv[it] / weight[it] }?.let { avg(it) } ?: main
    val darkest = top.minByOrNull { sv[it] / weight[it] }?.let { avg(it) } ?: main
    return buildPalette(main, darkest, lightest, main)
}

/** 真取色：Palette 跑在 Default 线程，取不到（异常 / 硬件位图）时退回像素兜底。 */
private fun ambientPaletteOf(bmp: Bitmap, fallback: Color): AmbientPalette {
    // 硬件位图不能 getPixels，Palette 内部会直接抛；先转软件位图。
    val soft = if (android.os.Build.VERSION.SDK_INT >= 26 && bmp.config == Bitmap.Config.HARDWARE) {
        bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return fallbackPalette(fallback)
    } else bmp

    val p = runCatching { Palette.from(soft).maximumColorCount(28).generate() }.getOrNull()
        ?: return pixelPaletteOf(soft, fallback)
    val vibrant = p.vibrantSwatch
    val lightVibrant = p.lightVibrantSwatch
    val darkVibrant = p.darkVibrantSwatch
    val muted = p.mutedSwatch
    val dominant = p.dominantSwatch ?: vibrant ?: muted ?: return pixelPaletteOf(soft, fallback)

    val vibColor = vibrant?.let { Color(it.rgb) } ?: Color(dominant.rgb)
    val darkColor = darkVibrant?.let { Color(it.rgb) } ?: muted?.let { Color(it.rgb) } ?: Color(dominant.rgb)
    val lightColor = lightVibrant?.let { Color(it.rgb) } ?: muted?.let { Color(it.rgb) } ?: Color(dominant.rgb)
    return buildPalette(vibColor, darkColor, lightColor, Color(dominant.rgb))
}

/**
 * 取色结果缓存。
 *
 * 为什么必须有：从播放页切回主界面时，`WheelHomeScreen` 是一套**全新的组合**，
 * produceState 会从 initialValue 重新开始——而 initialValue 是兜底色（主题红）。
 * 异步取色算完之前，背景就会先红一下再变回封面色。把算好的结果按封面 key 存下来，
 * 新组合直接命中缓存作为初值，这一闪就没了。
 *
 * [last] 是"最近一次成功的取色"：切到一首还没算过的新歌时先用它顶着，
 * 保证画面任何时刻都是彩色渐变，不会退回主题红（除非冷启动第一首）。
 */
private object AmbientPaletteStore {
    private val cache = android.util.LruCache<String, AmbientPalette>(12)

    @Volatile
    var last: AmbientPalette? = null

    fun get(key: String?): AmbientPalette? {
        if (key == null) return null
        return runCatching { cache.get(key) }.getOrNull()
    }

    fun put(key: String?, palette: AmbientPalette) {
        last = palette
        if (key != null) runCatching { cache.put(key, palette) }
    }
}

/** 取封面环境色（异步，切歌自动重新取）；同一张封面只算一次，结果进 [AmbientPaletteStore]。 */
@Composable
fun rememberAmbientPalette(
    bitmap: ImageBitmap?,
    fallback: Color,
    cacheKey: String? = null
): AmbientPalette {
    val fb = remember(fallback) { fallbackPalette(fallback) }
    // ★ 关键修复（"背景一直不变色"的根因）：CoverStore 每次重组都会新建一个 ImageBitmap 包装对象，
    //   如果直接拿它当 produceState 的 key，每次重组都会重启取色协程——播放中每秒都有进度/状态刷新，
    //   协程反复被取消重启，永远停在上一次的兜底色（苹果红），看起来就是"背景一直是同一个颜色"。
    //   转成底层 android Bitmap 后是同一个实例，key 才真正稳定。
    val src: Bitmap? = remember(bitmap) {
        bitmap?.let { runCatching { it.asAndroidBitmap() }.getOrNull() }
    }
    // 初值优先级：这张封面的缓存 → 最近一次成功的取色 → 兜底色。
    // 有了前两级，切页/切歌都不会先闪一下主题红（只有冷启动第一首才会用到兜底色）。
    val seed = remember(cacheKey, src, fb) {
        AmbientPaletteStore.get(cacheKey) ?: AmbientPaletteStore.last ?: fb
    }
    val state = produceState(seed, src, cacheKey, fallback) {
        if (src == null) {
            value = AmbientPaletteStore.get(cacheKey) ?: AmbientPaletteStore.last ?: fb
            return@produceState
        }
        // 这张封面算过：直接命中，不再起协程、不再闪
        val hit = AmbientPaletteStore.get(cacheKey)
        if (hit != null) {
            value = hit
            return@produceState
        }
        // 保留旧色直到新色算完，切歌时是平滑过渡而不是先闪回兜底红
        value = withContext(Dispatchers.Default) {
            runCatching { ambientPaletteOf(src, fallback) }
                .onSuccess {
                    AmbientPaletteStore.put(cacheKey, it)
                    CrashLogger.info(
                        "Mesh",
                        "封面取色 base=${hex(it.base)} primary=${hex(it.primary)} " +
                            "secondary=${hex(it.secondary)} accent=${hex(it.accent)}"
                    )
                }
                .onFailure {
                    // 失败不入缓存：下次进来还要再试，不能把兜底色写死
                    CrashLogger.info("Mesh", "取色失败，退回兜底色：${it.javaClass.simpleName}: ${it.message}")
                }
                .getOrDefault(AmbientPaletteStore.last ?: fb)
        }
    }
    return state.value
}

private fun hex(c: Color): String = "#%06X".format(c.toArgb() and 0xFFFFFF)

/**
 * 一次性生成的细颗粒噪点贴图（12dp×12dp、RGBA 随机透明度），用 ImageShader 平铺整屏。
 * 作用是消除大面积渐变的「涂抹感」——透明度只有 1%~6%，肉眼几乎不可辨，
 * 但能显著削弱塑料感。只画在**背景层**，绝不加在玻璃盘上（避免提亮成白雾）。
 */
private var noiseBitmapCache: ImageBitmap? = null
private fun noiseBitmap(): ImageBitmap {
    noiseBitmapCache?.let { return it }
    val px = 12
    val buf = IntArray(px * px)
    val rnd = kotlin.random.Random(20261120L) // 固定种子：颗粒稳定、不闪烁
    for (i in buf.indices) {
        val a = (rnd.nextFloat() * 0.06f * 255).toInt()
        buf[i] = (a shl 24) or 0x00FFFFFF
    }
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    bmp.setPixels(buf, 0, px, 0, 0, px, px)
    return bmp.asImageBitmap().also { noiseBitmapCache = it }
}

/** 画一层极淡的细颗粒噪点（供背景层内部复用）。 */
private fun DrawScope.drawGrain() {
    drawRect(ShaderBrush(ImageShader(noiseBitmap(), TileMode.Repeated, TileMode.Repeated)))
}

/**
 * 环境光背景本体：深色基底 + 四团缓慢游走的流光 + 中央补光 + 压暗 + 噪点。
 *
 * 整块挂在 backdrop 采样源上（[glassBackdropSource]），滚轮玻璃从同一实例采样，
 * 因此玻璃边缘的折射/色散带的是**流光**的颜色，而不是一张糊掉的封面。
 *
 * @param bitmap 当前曲目封面（null 则用 [fallback] 主题色兜底）
 * @param fallback 无封面时的种子色（首页/设置传苹果红）
 * @param cacheKey 封面唯一标识（coverUrl 优先），用于跨页面复用取色结果，避免切页时闪兜底色
 */
@Composable
fun AmbientMeshBackground(
    bitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
    fallback: Color = Color(0xFF2B2B31),
    cacheKey: String? = null
) {
    val extracted = rememberAmbientPalette(bitmap, fallback, cacheKey)
    // 切歌/封面加载完成时颜色不要"啪"地一跳：五色各自 600ms 过渡。
    // 这些 State 只在 draw 里读，过渡期间的逐帧变化只触发重绘、不触发重组。
    val baseS = animateColorAsState(extracted.base, tween(600), label = "meshBase")
    val primaryS = animateColorAsState(extracted.primary, tween(600), label = "meshPrimary")
    val secondaryS = animateColorAsState(extracted.secondary, tween(600), label = "meshSecondary")
    val accentS = animateColorAsState(extracted.accent, tween(600), label = "meshAccent")
    val accent2S = animateColorAsState(extracted.accent2, tween(600), label = "meshAccent2")
    // 档位沿用设置里的「背景」一项：0 清透 → 3 浓郁，控制光团扩散、亮度与压暗程度。
    val level = LocalGlassBlurConfig.current.backdropLevel.coerceIn(0, 3)
    val spread = 0.94f + level * 0.13f
    val glow = 0.72f + level * 0.07f
    val dim = 0.32f - level * 0.06f

    // 低频时钟：40ms 一帧。大尺度柔光每秒只移动几个像素，25fps 完全看不出顿，
    // 却把整屏径向渐变的重绘量砍掉一半以上（这块还是玻璃的采样源，省的是双份）。
    val phase by produceState(0f) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            value = (SystemClock.uptimeMillis() - start) / 1000f
            delay(40)
        }
    }

    Canvas(modifier.glassBackdropSource(LocalGlassBackdrop.current)) {
        val w = size.width
        val h = size.height
        val t = phase
        val p = AmbientPalette(baseS.value, primaryS.value, secondaryS.value, accentS.value, accent2S.value)

        // 1) 基底：竖直方向极深的同色系渐变，给流光一个「沉得下去」的底
        drawRect(
            Brush.verticalGradient(
                0f to p.base,
                0.62f to darken(p.base, 0.30f),
                1f to darken(p.base, 0.55f)
            )
        )

        // 2) 流光：半径以**屏宽**为单位（不是长边），否则大半径会把整屏铺成一片平色。
        //    每团都是「同色 + 多段 alpha 长尾」，画面上看不到任何圆的边界。
        fun glow(color: Color, x: Float, y: Float, r: Float, a: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to color.copy(alpha = a),
                        0.45f to color.copy(alpha = a * 0.72f),
                        0.80f to color.copy(alpha = a * 0.28f),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(x, y),
                    radius = r
                ),
                radius = r,
                center = Offset(x, y)
            )
        }

        // 四个角各一团，色相在同一个家族里错开，中间自然交融出第三种颜色。
        val a1 = t * 0.085f
        glow(
            p.primary,
            x = w * (0.05f + 0.10f * sin(a1)),
            y = h * (0.04f + 0.06f * cos(a1 * 1.2f)),
            r = w * 1.12f * spread,
            a = glow
        )

        val a2 = t * 0.062f + 2.1f
        glow(
            p.secondary,
            x = w * (1.00f + 0.08f * cos(a2)),
            y = h * (0.33f + 0.14f * sin(a2 * 0.8f)),
            r = w * 0.96f * spread,
            a = glow * 0.92f
        )

        val a3 = t * 0.048f + 4.4f
        glow(
            p.accent,
            x = w * (0.12f + 0.12f * sin(a3 * 0.7f)),
            y = h * (0.97f + 0.06f * cos(a3)),
            r = w * 1.05f * spread,
            a = glow * 0.90f
        )

        val a4 = t * 0.071f + 1.2f
        glow(
            p.accent2,
            x = w * (1.02f + 0.06f * sin(a4)),
            y = h * (0.93f + 0.05f * cos(a4 * 1.1f)),
            r = w * 0.80f * spread,
            a = glow * 0.55f
        )

        // 3) 中央补光：同主色、很淡，把中间填成「深色」而不是死黑
        glow(p.primary, w * 0.52f, h * 0.52f, w * 1.30f, 0.28f)

        // 4) 整体压暗：把流光压进暗部，白字才有足够对比度（iOS 也是这么做的）
        drawRect(Color.Black.copy(alpha = dim))

        // 4.5) 上下收边：顶部压住状态栏、底部压住滚轮，避免白字/白图标压在亮光斑上
        //      （中间 0.28~0.62 完全透明，流光最亮的中段不受影响）
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.22f),
                0.18f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.40f)
            )
        )

        // 5) 细颗粒噪点：消除大面积渐变的塑料感
        drawGrain()
    }
}

/** 按比例压暗一个颜色（保持色相，只降明度）。 */
private fun darken(color: Color, factor: Float): Color =
    Color(color.red * factor, color.green * factor, color.blue * factor, 1f)
