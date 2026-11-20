package com.aipod.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aipod.MusicBoxApp
import com.aipod.data.Track
import com.aipod.player.PlayerController
import com.aipod.ui.components.ClickWheel
import com.aipod.ui.components.fadeEdges
import com.aipod.ui.components.glassBackdropSource
import com.aipod.ui.components.rememberGlassBackdrop
import com.aipod.ui.components.LocalGlassBackdrop
import com.aipod.ui.components.rememberCoverBitmap
import com.aipod.ui.theme.BrandRed
import kotlinx.coroutines.launch

private data class MetaRow(val label: String, val value: String)

/**
 * 歌曲详细信息页：iPod 转轮驱动的 metadata 列表。
 * 第一行是收藏（行末星号，与文字同高），其余为只读元数据。
 * 背景与首页 / 播放页一致：取当前封面做真高斯模糊铺满 + 暗化蒙层。
 * 转轮上下滚动、中心键在首行切换收藏、MENU 返回播放页。
 */
@Composable
fun SongDetailScreen(navController: NavController) {
    val queue by PlayerController.queue.collectAsState()
    val idx by PlayerController.currentIndex.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val track: Track? = queue.getOrNull(idx)
    val app = MusicBoxApp.instance
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(0) }
    var favorite by remember { mutableStateOf(track?.let { app.isFavorite(it) } ?: false) }
    val listState = rememberLazyListState()

    LaunchedEffect(track?.id) { selected = 0; favorite = track?.let { app.isFavorite(it) } ?: false }

    val rows = remember(track) { buildMetaRows(track) }
    // 首行=收藏，之后是 metadata。收藏行也算一个可滚动项。
    val itemCount = rows.size + 1

    fun toggleFav() {
        val t = track ?: return
        favorite = !favorite
        scope.launch { app.toggleFavorite(t) }
    }

    // 液态玻璃采样源：背景层录制进该 backdrop，滚轮玻璃从同一实例采样（兄弟配对）。
    val glassBackdrop = rememberGlassBackdrop()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassBackdrop provides glassBackdrop
    ) {
    Box(Modifier.fillMaxSize()) {
        // 与首页 / 播放页一致的模糊封面背景
        SongDetailBackdrop(track)
        Column(Modifier.fillMaxSize()) {
            // 顶部标题
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "歌曲信息",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    track?.title ?: "无歌曲",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }

            // metadata 列表（首行收藏，星号在行末、与文字同高）
            if (track == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text("队列为空", color = Color.White.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).fadeEdges(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 首行：收藏（与 metadata 行同款，value 位置放星号）
                    item {
                        val sel = selected == 0
                        val a by animateFloatAsState(if (sel) 1f else 0.72f, tween(150), label = "favRow")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                                .clickable { selected = 0; toggleFav() }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                                .alpha(a),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("收藏", color = Color.White.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(80.dp))
                            Box(Modifier.weight(1f), Alignment.CenterEnd) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = if (favorite) "取消收藏" else "收藏",
                                    tint = if (favorite) BrandRed else Color.White.copy(alpha = 0.8f),
                                    // 与行内文字同高（bodyMedium 约 16sp → 20dp），放行末即可
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    itemsIndexed(rows) { i, row ->
                        val sel = selected == i + 1
                        val a by animateFloatAsState(if (sel) 1f else 0.72f, tween(150), label = "metaRow")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                                .alpha(a),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(row.label, color = Color.White.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(80.dp))
                            Text(row.value, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 2)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // 转轮：唯一交互（与首页 / 设置页统一 264dp、底部 64dp）
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp), Alignment.Center) {
                ClickWheel(
                    diameter = 264.dp,
                    isPlaying = isPlaying,
                    centerActive = selected == 0 && favorite,
                    dark = true,
                    onRotate = { steps ->
                        selected = (selected + steps).coerceIn(0, itemCount - 1)
                        scope.launch { runCatching { listState.animateScrollToItem(selected) } }
                    },
                    onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
                    // 非播放页长按播放键：直接跳到「正在播放」
                    onPlayLongPress = {
                        navController.navigate("player") {
                            popUpTo("library") { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onPrevious = { PlayerController.previous() },
                    onNext = { PlayerController.next() },
                    onRewind = { PlayerController.seekTo((PlayerController.currentPositionMs() - 5_000L).coerceAtLeast(0)) },
                    onForward = { PlayerController.seekTo(PlayerController.currentPositionMs() + 5_000L) },
                    onMenu = { navController.popBackStack() },
                    onMenuHome = { navController.popBackStack("library", false) },
                    onConfirm = { if (selected == 0) toggleFav() }
                )
            }
        }
    }
    } // CompositionLocalProvider(LocalGlassBackdrop)
}

/**
 * 歌曲详情背景：与首页 WheelBackdrop 同款——当前封面真高斯模糊铺满 + 暗化蒙层，
 * 走 rememberCoverBitmap 的 LRU 缓存，切歌自动刷新。
 */
@Composable
private fun SongDetailBackdrop(track: Track?) {
    // 封面层 + 暗蒙层；整块录制进 backdrop，供兄弟节点的滚轮玻璃从同一实例采样。
    Box(Modifier.fillMaxSize().glassBackdropSource(LocalGlassBackdrop.current)) {
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF151517)))
        val bmp = rememberCoverBitmap(track?.coverUrl, track?.embeddedAudioUrl)
        val backdropBlur = com.aipod.ui.components.LocalGlassBlurConfig.current.backdropBlur
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                    .blur(backdropBlur)
            )
        }
      }
      // 暗蒙层：保证白字可读
      Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
    }
}

private fun buildMetaRows(track: Track?): List<MetaRow> {
    if (track == null) return emptyList()
    val out = mutableListOf<MetaRow>()
    out += MetaRow("标题", track.title.ifBlank { "未知" })
    out += MetaRow("歌手", track.artist.ifBlank { "未知歌手" })
    out += MetaRow("专辑", track.album.ifBlank { "未知专辑" })
    if (track.durationMs > 0) {
        val s = track.durationMs / 1000
        out += MetaRow("时长", "${s / 60}:${(s % 60).toString().padStart(2, '0')}")
    }
    if (track.codec.isNotBlank()) out += MetaRow("格式", track.codec.uppercase())
    if (track.sampleRateHz > 0) out += MetaRow("采样率", formatRate(track.sampleRateHz))
    if (track.bitDepth > 0) out += MetaRow("位深", "${track.bitDepth} bit")
    out += MetaRow("音源", when (track.source.name) {
        "WEBDAV" -> "WebDAV"
        "FNOS" -> "飞牛音乐"
        "LOCAL" -> "本地音乐"
        else -> track.source.name
    })
    return out
}

private fun formatRate(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)
