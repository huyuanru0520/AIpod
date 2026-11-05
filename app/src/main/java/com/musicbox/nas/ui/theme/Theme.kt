package com.musicbox.nas.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** 默认主色：飞牛红的近似值。用户可在「设置 → 外观」里换成任意预设色。 */
val BrandRed = Color(0xFFFA2D48)

/** 可选主题色。顺序即设置页的展示顺序。 */
val AccentPresets: List<Pair<String, Color>> = listOf(
    "绯红" to Color(0xFFFA2D48),
    "珊瑚" to Color(0xFFFF6B4A),
    "琥珀" to Color(0xFFF2A93B),
    "青柠" to Color(0xFF8CC63F),
    "松绿" to Color(0xFF2FB98A),
    "湖蓝" to Color(0xFF2FA8D8),
    "靛青" to Color(0xFF4A7DFF),
    "紫罗兰" to Color(0xFF8B5CF6),
    "品红" to Color(0xFFE0479E),
    "石墨" to Color(0xFF7A8699)
)

/** 文字色：亮底用深字，暗底用白字。 */
private fun Color.readableOn(): Color =
    if (luminance() > 0.55f) Color(0xFF141416) else Color.White

/**
 * 从「一个主色」推导整套深色配色。
 *
 * 不引入 dynamicColor / material-color-utilities：那套要额外依赖且结果不可控。
 * 这里用最朴素的办法——主色与黑白做线性插值得到各级容器色，视觉上足够稳，
 * 也保证任何主色下都不会出现「彩色卡片上放白字」这类对比度事故。
 */
private fun darkSchemeFrom(accent: Color): androidx.compose.material3.ColorScheme {
    // 纯黑底上太暗的主色会糊成一片，先提亮一点再当 primary
    val primary = if (accent.luminance() < 0.28f) lerp(accent, Color.White, 0.18f) else accent
    val black = Color.Black
    return darkColorScheme(
        primary = primary,
        onPrimary = primary.readableOn(),
        primaryContainer = lerp(primary, black, 0.62f),
        onPrimaryContainer = lerp(primary, Color.White, 0.55f),
        inversePrimary = lerp(primary, Color.White, 0.35f),
        secondary = lerp(primary, Color.White, 0.12f),
        onSecondary = primary.readableOn(),
        secondaryContainer = lerp(primary, black, 0.7f),
        onSecondaryContainer = lerp(primary, Color.White, 0.6f),
        tertiary = lerp(primary, Color(0xFF7C5CFF), 0.35f),
        onTertiary = Color.White,
        tertiaryContainer = lerp(lerp(primary, Color(0xFF7C5CFF), 0.35f), black, 0.66f),
        onTertiaryContainer = lerp(primary, Color.White, 0.65f),
        background = black,
        onBackground = Color(0xFFF5F5F5),
        surface = black,
        onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF121214),
        onSurfaceVariant = Color(0xFFB9B9BE),
        surfaceTint = black,
        surfaceContainerLowest = black,
        surfaceContainerLow = black,
        surfaceContainer = Color(0xFF08080A),
        surfaceContainerHigh = Color(0xFF101013),
        surfaceContainerHighest = Color(0xFF17171B),
        outline = Color(0xFF3A3A40),
        outlineVariant = Color(0xFF24242A),
        scrim = black,
        inverseSurface = Color(0xFFEDEDED),
        inverseOnSurface = black,
        error = Color(0xFFFF6B6B),
        onError = Color.Black
    )
}

private fun lightSchemeFrom(accent: Color): androidx.compose.material3.ColorScheme {
    // 浅底上太浅的主色同样看不清，压暗一档
    val primary = if (accent.luminance() > 0.62f) lerp(accent, Color.Black, 0.22f) else accent
    return lightColorScheme(
        primary = primary,
        onPrimary = primary.readableOn(),
        primaryContainer = lerp(primary, Color.White, 0.78f),
        onPrimaryContainer = lerp(primary, Color.Black, 0.25f),
        inversePrimary = lerp(primary, Color.White, 0.15f),
        secondary = lerp(primary, Color.Black, 0.08f),
        onSecondary = primary.readableOn(),
        secondaryContainer = lerp(primary, Color.White, 0.84f),
        onSecondaryContainer = lerp(primary, Color.Black, 0.3f),
        tertiary = lerp(primary, Color(0xFF6A4CF5), 0.35f),
        onTertiary = Color.White,
        tertiaryContainer = lerp(primary, Color.White, 0.86f),
        onTertiaryContainer = lerp(primary, Color.Black, 0.3f),
        background = Color(0xFFF7F7F9),
        onBackground = Color(0xFF151517),
        surface = Color(0xFFFCFCFE),
        onSurface = Color(0xFF151517),
        surfaceVariant = Color(0xFFEFEFF3),
        onSurfaceVariant = Color(0xFF5F5F68),
        surfaceTint = primary,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFAFAFC),
        surfaceContainer = Color(0xFFF3F3F6),
        surfaceContainerHigh = Color(0xFFEDEDF1),
        surfaceContainerHighest = Color(0xFFE6E6EB),
        outline = Color(0xFFC9C9D1),
        outlineVariant = Color(0xFFE0E0E6),
        scrim = Color.Black,
        inverseSurface = Color(0xFF2A2A2E),
        inverseOnSurface = Color(0xFFF2F2F5),
        error = Color(0xFFC62828),
        onError = Color.White
    )
}

@Composable
fun MusicBoxTheme(
    darkTheme: Boolean,
    accent: Color = BrandRed,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) darkSchemeFrom(accent) else lightSchemeFrom(accent)
    MaterialTheme(colorScheme = colors) {
        // Surface 会实际绘制背景色并设置正确的 LocalContentColor，
        // 避免内容区透出 Activity 窗口的浅色底色（深色模式出现白块）。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onBackground
        ) { content() }
    }
}
