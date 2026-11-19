package com.aipod.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipod.player.PlayerController
import com.aipod.ui.theme.BrandRed

/**
 * 「液态玻璃」外壳。
 *
 * 说明：真正的背景模糊（backdrop blur）需要把背后的内容录进一个 GraphicsLayer 再重放，
 * 依赖 Android 12+ 的 RenderEffect 和第三方库。这里用**半透明 + 高光描边 + 投影 + 内侧光泽**
 * 来还原观感——在深色/浅色底上都成立，且不引入新依赖、不牺牲滚动性能。
 * 视觉上的关键其实是「描边」和「投影」：玻璃感来自边缘那道亮线，而不是模糊本身。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tintAlpha: Float = 0.62f,
    elevation: Dp = 20.dp,
    /** 播放页背景永远是封面暗色，即使浅色主题也要用深色玻璃。 */
    forceDark: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val dark = forceDark || MaterialTheme.colorScheme.background.luminance() < 0.5f
    val rimTop = if (dark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.9f)
    val rimBottom = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val sheen = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.5f)

    Box(
        modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.45f)
            )
            .clip(shape)
            .background(tint.copy(alpha = tintAlpha))
    ) {
        // 顶部一道极淡的竖向高光，模拟玻璃厚度的折射
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to sheen,
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (dark) 0.12f else 0.02f)
                    )
                )
        )
        // 边缘亮线：上亮下暗，比单色描边更像真实玻璃
        Box(
            Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(listOf(rimTop, rimBottom)),
                    shape = shape
                )
        )
        content()
    }
}

/** 悬浮 Dock 的一项。 */
data class DockItem(val route: String, val label: String, val icon: ImageVector)

/**
 * 悬浮玻璃 Dock：胶囊形，浮在内容之上，选中项用一个滑动的「药丸」高亮。
 *
 * 交互参照 iOS 26：整体是一个整体容器，高亮块随选中项平滑移动；
 * 点击才有导航行为，滑动不会误触发页面切换。
 */
@Composable
fun LiquidGlassDock(
    selected: Int,
    items: List<DockItem>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 强调色：默认苹果音乐红，让二级页 Dock 与红色主界面呼应；不传则跟随主题主色。 */
    accent: Color = BrandRed
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    GlassSurface(
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(percent = 50),
        tint = if (dark) colors.surfaceContainerHighest else colors.surface,
        tintAlpha = if (dark) 0.72f else 0.78f,
        elevation = 24.dp
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
            val itemWidth = maxWidth / items.size
            val highlightOffset by animateDpAsState(
                targetValue = itemWidth * selected,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
                label = "dockHighlight"
            )
            // 选中高亮：主色淡染 + 一圈更亮的边，像玻璃下压着一片彩色
            Box(
                Modifier
                    .offset(x = highlightOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accent.copy(alpha = if (dark) 0.24f else 0.16f))
                    .border(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.40f),
                        shape = RoundedCornerShape(percent = 50)
                    )
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    val active = index == selected
                    val scale by animateFloatAsState(
                        targetValue = if (active) 1f else 0.96f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
                        label = "dockItem"
                    )
                    Column(
                        modifier = Modifier
                            .width(itemWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(index) }
                            .scale(scale),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = if (active) accent else colors.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.label,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) accent else colors.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * 玻璃迷你播放器：浮在 Dock 上方的一张独立卡片，底部有一条播放进度线。
 * 点击整卡进入播放页，右侧按钮直接控制播放/暂停与下一首。
 */
@Composable
fun GlassMiniPlayer(
    title: String,
    artist: String,
    coverUrl: String?,
    embeddedAudioUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    GlassSurface(
        modifier = modifier.height(62.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        tint = if (dark) colors.surfaceContainerHighest else colors.surface,
        tintAlpha = if (dark) 0.74f else 0.82f,
        elevation = 18.dp
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(
                coverUrl,
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)),
                embeddedAudioUrl = embeddedAudioUrl
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    artist.ifBlank { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = colors.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { if (isPlaying) PlayerController.pause() else PlayerController.play() }
                    .padding(10.dp)
                    .size(24.dp)
            )
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "下一首",
                tint = colors.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { PlayerController.next() }
                    .padding(8.dp)
                    .size(22.dp)
            )
        }
        // 进度线贴在卡片底部，颜色用主题主色
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.onSurface.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxSize()
                    .background(BrandRed)
            )
        }
    }
}
