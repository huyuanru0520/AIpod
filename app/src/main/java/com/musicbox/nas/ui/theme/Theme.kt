package com.musicbox.nas.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Brand red used across the app (matches FnOS music accent). */
val BrandRed = Color(0xFFFA2D48)

/** 深色模式：所有主要显示区域一律纯黑。 */
private val DarkColorScheme = darkColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    secondary = BrandRed,
    onSecondary = Color.White,
    tertiary = BrandRed,
    onTertiary = Color.White,
    background = Color.Black,
    onBackground = Color(0xFFF5F5F5),
    surface = Color.Black,
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFB9B9B9),
    surfaceTint = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF0A0A0A),
    surfaceContainerHighest = Color(0xFF141414),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF242424),
    scrim = Color.Black,
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color.Black,
    inversePrimary = Color(0xFFFF8A98),
    error = Color(0xFFFF6B6B),
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    secondary = BrandRed,
    tertiary = BrandRed
)

@Composable
fun MusicBoxTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
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
