package com.aipod.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipod.util.Haptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

/**
 * 透明玻璃 iPod 点击轮。
 *
 * - 转动：18° 一格，细密震动（无可见刻度线），具体行为由页面决定（音量 / 选歌 / 进度）；
 * - 短按左 / 右：上一首 / 下一首；长按：连续快退 / 快进，只在按下瞬间震一次；
 * - 短按 MENU：页面内打开 / 关闭播放队列；长按 MENU：回曲库根；
 * - 下键：播放 / 暂停；
 * - 中心：iPod touch 4 Home 键式**内凹**按钮，短按切换歌曲进度模式，长按切换歌词界面。
 *
 * 轮体投影用自绘圆形径向渐变，不用 Modifier.shadow——后者在圆形上会出现明显的多边形
 * （六边形）轮廓，为了那点性能把玻璃质感毁掉不值得。
 */
@Composable
fun ClickWheel(
    diameter: Dp,
    isPlaying: Boolean,
    centerActive: Boolean = false,
    /** 轮体所在背景是否为暗色：播放页是暗色封面传 true，首页跟随明暗主题。图标/描边据此反色。 */
    dark: Boolean = true,
    onRotate: (Int) -> Unit,
    /** 滚轮转动结束（手指离开环体）：进度模式据此把本地预览位置一次性提交为真正的 seek。 */
    onRotateEnd: () -> Unit = {},
    onPlayPause: () -> Unit,
    /** 底部播放键长按：播放页用它进入歌曲详情；不传则沿用播放/暂停。 */
    onPlayLongPress: () -> Unit = {},
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onMenu: () -> Unit,
    onMenuHome: () -> Unit,
    onConfirm: () -> Unit,
    /** 中心键长按：播放页用它切换歌词界面；不传则无行为。 */
    onCenterLongPress: () -> Unit = {},
    /** 左右键长按连续动作时是否每次震动（列表逐行移动需要；播放页快进快退保持默认 false）。 */
    hapticOnLongRepeat: Boolean = false,
    /**
     * 滚轮上报 / 音效震动的最小间隔（毫秒）。0 = 不节流，每格都上报、音效按 60ms 节流（默认，
     * 设置页 / 曲库 / 歌词 / 进度 / 音量等逐格场景用）。>0 时上报与音效共用同一节流点，
     * 做到「上报一次 = 响一声」——播放页封面切歌传 CoverFlow 动画时长，保证切歌节奏与音效严格对齐。
     */
    rotateIntervalMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // ★ 手势回调必须用 rememberUpdatedState 保鲜：pointerInput(Unit) 的 key 固定，
    // 手势 lambda 只在首次组合创建一次，若直接捕获 onRotate / onConfirm，会永远停在
    // 第一次组合时的旧回调（其中 node 是 Root），导致进入搜索页后字母盘不跟随滚轮。
    // 下面统一用 xxx.value()，每次重组都能拿到页面最新派发的回调。
    val onRotateRef = rememberUpdatedState(onRotate)
    val onRotateEndRef = rememberUpdatedState(onRotateEnd)
    val onPlayPauseRef = rememberUpdatedState(onPlayPause)
    val onPlayLongPressRef = rememberUpdatedState(onPlayLongPress)
    val onPreviousRef = rememberUpdatedState(onPrevious)
    val onNextRef = rememberUpdatedState(onNext)
    val onRewindRef = rememberUpdatedState(onRewind)
    val onForwardRef = rememberUpdatedState(onForward)
    val onMenuRef = rememberUpdatedState(onMenu)
    val onMenuHomeRef = rememberUpdatedState(onMenuHome)
    val onConfirmRef = rememberUpdatedState(onConfirm)
    val onCenterLongPressRef = rememberUpdatedState(onCenterLongPress)
    val hapticOnLongRepeatRef = rememberUpdatedState(hapticOnLongRepeat)
    val rotateIntervalRef = rememberUpdatedState(rotateIntervalMs)
    var longFired by remember { mutableStateOf(false) }
    var centerDown by remember { mutableStateOf(false) }
    var bottomDown by remember { mutableStateOf(false) }
    val centerScale by animateFloatAsState(
        targetValue = if (centerDown) 0.95f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.7f),
        label = "wheelCenter"
    )
    val bottomScale by animateFloatAsState(
        targetValue = if (bottomDown) 0.90f else 1f,
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.7f),
        label = "wheelBottom"
    )
    Box(modifier.size(diameter)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 转动：累计角度，每 18° 一格，固定每次 ±1 项（纯逐行）。
                    // 曾尝试 iPod 式加速度（快转跨多行），但触摸事件采样间隔普遍 <24ms，
                    // 慢转也被误判为快转、档位长期顶在上限，结果「一滑就跳三行、越过目标」。
                    // 现在统一逐行，与设置页手感一致；想快速移动就连续多转几格。
                    var lastAngle = 0f
                    var accum = 0f
                    var lastTickAt = 0L
                    var onRing = false
                    detectDragGestures(
                        onDragStart = { p ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val radius = minOf(size.width, size.height) / 2f
                            val r = hypot(p.x - c.x, p.y - c.y)
                            onRing = r > radius * 0.38f && r <= radius * 1.04f
                            lastAngle = angleOf(p, c)
                            accum = 0f
                        },
                        onDragEnd = {
                            if (onRing) onRotateEndRef.value()
                            onRing = false
                        },
                        onDragCancel = {
                            if (onRing) onRotateEndRef.value()
                            onRing = false
                        }
                    ) { change, _ ->
                        change.consume()
                        if (!onRing) return@detectDragGestures
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val a = angleOf(change.position, c)
                        var delta = a - lastAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        lastAngle = a
                        accum += delta

                        val now = System.currentTimeMillis()
                        val reportInterval = rotateIntervalRef.value
                        while (abs(accum) >= STEP_DEGREES) {
                            val forward = accum > 0
                            accum -= if (forward) STEP_DEGREES else -STEP_DEGREES
                            if (reportInterval > 0L) {
                                // 节流模式（播放页切歌）：上报与音效共用同一节流点，
                                // 被间隔吞掉的格既不响也不上报 —— 切一首 = 响一声，节奏严格对齐。
                                if (now - lastTickAt >= reportInterval) {
                                    lastTickAt = now
                                    Haptics.tick(context, view)
                                    onRotateRef.value(if (forward) 1 else -1)
                                }
                            } else {
                                // 非节流模式（默认，逐格场景）：每格都上报，音效仅按 60ms 节流。
                                if (now - lastTickAt >= MIN_TICK_INTERVAL_MS) {
                                    lastTickAt = now
                                    Haptics.tick(context, view)
                                }
                                onRotateRef.value(if (forward) 1 else -1)
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { p ->
                            longFired = false
                            val zone = hitZone(p, size)
                            if (zone == WheelZone.CENTER) centerDown = true
                            if (zone == WheelZone.BOTTOM) bottomDown = true
                            var longJob: Job? = null
                            when (zone) {
                                WheelZone.LEFT, WheelZone.RIGHT, WheelZone.TOP -> {
                                    longJob = scope.launch {
                                        delay(LONG_PRESS_MS)
                                        longFired = true
                                        Haptics.click(context, view) // 长按只震一次，之后连续动作不再震
                                        while (isActive) {
                                            when (zone) {
                                                WheelZone.LEFT -> onRewindRef.value()
                                                WheelZone.RIGHT -> onForwardRef.value()
                                                WheelZone.TOP -> onMenuHomeRef.value()
                                                else -> Unit
                                            }
                                            // 列表界面逐行移动时每行给一次震动反馈；播放页等保持默认关闭。
                                            if (hapticOnLongRepeatRef.value && zone != WheelZone.TOP) {
                                                Haptics.tick(context, view)
                                            }
                                            delay(REPEAT_MS)
                                        }
                                    }
                                }
                                WheelZone.CENTER -> {
                                    // 中心键长按：只触发一次（切换歌词），不循环
                                    longJob = scope.launch {
                                        delay(LONG_PRESS_MS)
                                        longFired = true
                                        Haptics.click(context, view)
                                        onCenterLongPressRef.value()
                                    }
                                }
                                WheelZone.BOTTOM -> {
                                    // 播放键长按：只触发一次（进歌曲详情），不循环
                                    longJob = scope.launch {
                                        delay(LONG_PRESS_MS)
                                        longFired = true
                                        Haptics.click(context, view)
                                        onPlayLongPressRef.value()
                                    }
                                }
                                else -> Unit
                            }
                            tryAwaitRelease()
                            longJob?.cancel()
                            centerDown = false
                            bottomDown = false
                        },
                        onTap = { p ->
                            if (longFired) {
                                longFired = false
                                return@detectTapGestures
                            }
                            Haptics.click(context, view)
                            when (hitZone(p, size)) {
                                WheelZone.TOP -> onMenuRef.value()
                                WheelZone.LEFT -> onPreviousRef.value()
                                WheelZone.RIGHT -> onNextRef.value()
                                WheelZone.BOTTOM -> onPlayPauseRef.value()
                                WheelZone.CENTER -> onConfirmRef.value()
                                WheelZone.NONE -> Unit
                            }
                        }
                    )
                }
        ) {
            val d = maxWidth
            // 轮体上的文字/图标颜色：暗底用白、浅底用深灰，避免浅色模式白底白字。
            val onColor = if (dark) Color.White else Color(0xFF2B2B30)
            val onStrong = if (dark) Color.White else Color(0xFF1A1A1F)
            GlassSurface(
                modifier = Modifier.fillMaxSize(),
                // 用 50% 圆角而非 CircleShape：正方形上视觉仍是正圆，但它是 CornerBasedShape，
                // 能给出圆角半径 → backdrop 的 lens 折射才生效（CircleShape 会被库拒绝并抛异常）。
                shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                tint = Color.White,
                tintAlpha = if (dark) 0.09f else 0.46f,
                elevation = 0.dp,
                // 圆形滚轮：GlassSurface 内部用 backdrop 库做真背景采样（有 backdrop 时），
                // 否则退回 Canvas 窄环高光兜底。
                blur = true,
                circular = true
            ) {
                Text(
                    "MENU",
                    color = onColor.copy(alpha = 0.90f),
                    fontSize = (d.value * 0.040f).sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = d * 0.115f)
                )
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一首 / 长按快退",
                    tint = onColor.copy(alpha = 0.95f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = d * 0.105f)
                        .size(d * 0.088f)
                )
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一首 / 长按快进",
                    tint = onColor.copy(alpha = 0.95f),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = d * 0.105f)
                        .size(d * 0.088f)
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = d * 0.105f)
                        .size(d * 0.16f)
                        .scale(bottomScale),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = onStrong,
                        modifier = Modifier.size(d * 0.088f)
                    )
                }

                // 中心 Home 键：液态透明玻璃（不是黑色内凹圆）。
                // 走 backdrop 真采样 + 16dp 背景模糊，让底部流光色柔和透出；
                // 表面层只画「水透色底 + 左上亮/右下暗内阴影 + 渐变张力描边 + 极淡色散」，
                // 中心保持通透。浅色模式降低整体强度避免白轮上的白雾。
                val backdrop = LocalGlassBackdrop.current
                val cfg = LocalGlassBlurConfig.current
                val centerIntensity = if (cfg.enabled) cfg.intensity * (if (dark) 1f else 0.72f) else 0f
                val centerShape = RoundedCornerShape(percent = 50)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(d * 0.22f)
                        .scale(centerScale)
                        .then(
                            if (backdrop != null && centerIntensity > 0f) {
                                // 与外圈滚轮完全相同的 backdrop 管线：真采样 + 轻模糊 + lens 边缘折射，
                                // 背景清晰透出（中心只做内凹光影，不糊成磨砂）。depthEffect 保持默认 false——
                                // 圆心 normalize(0,0)=NaN 会产生纯白盘，绝不能开。
                                Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { centerShape },
                                    effects = {
                                        colorControls(saturation = 1.25f)
                                        blur(4f.dp.toPx())
                                        lens(
                                            refractionHeight = 8f.dp.toPx(),
                                            refractionAmount = 16f.dp.toPx(),
                                            chromaticAberration = true
                                        )
                                    },
                                    onDrawSurface = {
                                        drawCenterGlassSurface(
                                            intensity = centerIntensity,
                                            active = centerActive,
                                            unit = 1.dp.toPx()
                                        )
                                    }
                                )
                            } else {
                                // 无 backdrop（首页未录制源 / 开关关闭）：兜底仍是水透色玻璃观感
                                Modifier.drawBehind {
                                    drawCircle(
                                        Color.White.copy(alpha = 0.12f * centerIntensity),
                                        radius = min(size.width, size.height) / 2f, center = center
                                    )
                                    drawCenterGlassSurface(
                                        intensity = centerIntensity,
                                        active = centerActive,
                                        unit = 1.dp.toPx()
                                    )
                                }
                            }
                        )
                )
            }
        }
    }
}

/**
 * 中心 Home 键的液态玻璃表面层（画在 backdrop 模糊采样之上）。
 *
 * 结构（iTouch 式内凹 Home 键，光从左上打进凹槽——注意与「凸起玻璃珠」完全相反）：
 *  1. 水透色底：极低透明白，中心透出背景（坑底）、边缘微亮（坑壁）；
 *  2. 内阴影：左上内壁背光 → 暗（黑内阴影），右下内壁受光 → 亮（白反弹高光），
 *     用「clip 到圆内 + 粗描边只留内侧一半」实现真正的内阴影；
 *  3. 边缘张力高光：1px 描边同样是左上暗、右下亮，整体读作一个凹坑的唇边；
 *  4. 色散：右下受光侧一段青→品红极淡 fringe，Plus 混合只提亮。
 * [active] 按下时高光提亮一档，产生光泽「流动」感。
 */
private fun DrawScope.drawCenterGlassSurface(intensity: Float, active: Boolean, unit: Float) {
    if (intensity <= 0f) return
    val s = intensity * (if (active) 1.18f else 1f)
    val c = center
    val r = min(size.width, size.height) / 2f
    val rim = r * 0.26f

    // 1) 水透色底：凹坑底部（中心）更透、坑壁（边缘）微亮，极低透明白不做白雾
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to Color.White.copy(alpha = 0.08f * s),
                0.64f to Color.White.copy(alpha = 0.11f * s),
                1.00f to Color.White.copy(alpha = 0.17f * s)
            ),
            center = c, radius = r
        ),
        radius = r, center = c
    )

    // 2) 内凹厚度：clip 到圆内、粗描边只留内侧 → 左上暗（背光内壁）、右下亮（受光内壁）。
    //    这正是「凹进去」与「凸玻璃珠」的分水岭：方向反了看起来就是凸起。
    val circle = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
    clipPath(circle) {
        // 左上暗内阴影：凹槽左上内壁被自身挡住光，聚一道深阴影
        drawCircle(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to Color.Black.copy(alpha = 0.42f * s),
                    0.34f to Color.Black.copy(alpha = 0.12f * s),
                    0.62f to Color.Transparent
                ),
                start = Offset(c.x - r, c.y - r),
                end = Offset(c.x + r * 0.55f, c.y + r * 0.55f)
            ),
            center = c, radius = r,
            style = Stroke(width = rim)
        )
        // 右下亮内高光：光穿过凹槽打在右下内壁，反弹一道柔白高光
        drawCircle(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.52f to Color.White.copy(alpha = 0.12f * s),
                    1.00f to Color.White.copy(alpha = 0.55f * s)
                ),
                start = Offset(c.x - r * 0.55f, c.y - r * 0.55f),
                end = Offset(c.x + r, c.y + r)
            ),
            center = c, radius = r,
            style = Stroke(width = rim)
        )
    }

    // 3) 唇边张力线：凹坑边缘同样左上暗、右下亮（右下 bounce 最强），整体读作凹陷
    drawCircle(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Color.Black.copy(alpha = 0.34f * s),
                0.30f to Color.Black.copy(alpha = 0.08f * s),
                0.66f to Color.White.copy(alpha = 0.10f * s),
                1.00f to Color.White.copy(alpha = 0.62f * s)
            ),
            start = Offset(c.x - r, c.y - r),
            end = Offset(c.x + r, c.y + r)
        ),
        radius = r - 0.7f * unit, center = c,
        style = Stroke(width = 1.3f * unit)
    )

    // 4) 色散彩边：挪到右下受光侧，青→品红极淡，Plus 提亮
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color(0xFF8FE8FF).copy(alpha = 0.10f * s),
                Color(0xFFFF9AD8).copy(alpha = 0.20f * s)
            ),
            start = Offset(c.x - r * 0.25f, c.y - r * 0.25f),
            end = Offset(c.x + r, c.y + r)
        ),
        radius = r - 1.4f * unit, center = c,
        style = Stroke(width = 1.8f * unit),
        blendMode = BlendMode.Plus
    )
}

private enum class WheelZone { NONE, TOP, LEFT, RIGHT, BOTTOM, CENTER }

private fun hitZone(p: Offset, size: IntSize): WheelZone {
    val c = Offset(size.width / 2f, size.height / 2f)
    val dx = p.x - c.x
    val dy = p.y - c.y
    val r = hypot(dx, dy)
    val radius = minOf(size.width, size.height) / 2f
    if (r > radius * 1.05f) return WheelZone.NONE
    if (r <= radius * 0.31f) return WheelZone.CENTER
    val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    return when {
        deg in -135f..-45f -> WheelZone.TOP
        deg in 45f..135f -> WheelZone.BOTTOM
        deg < -135f || deg > 135f -> WheelZone.LEFT
        else -> WheelZone.RIGHT
    }
}

/** 18° 一格：20 格一圈，慢转更稳、不易一甩跨过一堆选项。 */
private const val STEP_DEGREES = 18f
private const val MIN_TICK_INTERVAL_MS = 60L
private const val LONG_PRESS_MS = 420L
private const val REPEAT_MS = 240L

private fun angleOf(p: Offset, c: Offset): Float =
    Math.toDegrees(atan2(p.y - c.y, p.x - c.x).toDouble()).toFloat()
