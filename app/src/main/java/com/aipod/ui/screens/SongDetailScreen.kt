package com.aipod.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aipod.MusicBoxApp
import com.aipod.data.Track
import com.aipod.player.PlayerController
import com.aipod.ui.components.ClickWheel
import com.aipod.ui.components.CoverImage
import com.aipod.ui.components.fadeEdges
import com.aipod.ui.theme.BrandRed
import kotlinx.coroutines.launch

private data class MetaRow(val label: String, val value: String)

/**
 * 歌曲详细信息页：iPod 转轮驱动的 metadata 列表。
 * 第一栏是星标收藏圆按钮（按当前音源写入对应收藏表），其余为只读元数据。
 * 转轮上下滚动、中心键在首栏切换收藏、MENU 返回播放页。
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
    // 首行=收藏按钮，之后是 metadata。收藏行也算一个可滚动项。
    val itemCount = rows.size + 1

    fun toggleFav() {
        val t = track ?: return
        favorite = !favorite
        scope.launch { app.toggleFavorite(t) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF121215))) {
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

            // metadata 列表（首行收藏圆按钮）
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
                    item {
                        FavoriteButton(
                            favorite = favorite,
                            selected = selected == 0,
                            onClick = { selected = 0; toggleFav() }
                        )
                        Spacer(Modifier.height(14.dp))
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

            // 转轮：唯一交互
            Box(Modifier.fillMaxWidth().padding(bottom = 40.dp), Alignment.Center) {
                ClickWheel(
                    diameter = 240.dp,
                    isPlaying = isPlaying,
                    centerActive = selected == 0 && favorite,
                    onRotate = { steps ->
                        selected = (selected + steps).coerceIn(0, itemCount - 1)
                        scope.launch { runCatching { listState.animateScrollToItem(selected) } }
                    },
                    onPlayPause = { if (isPlaying) PlayerController.pause() else PlayerController.play() },
                    onPrevious = { PlayerController.previous() },
                    onNext = { PlayerController.next() },
                    onRewind = { PlayerController.seekTo((PlayerController.currentPositionMs() - 5_000L).coerceAtLeast(0)) },
                    onForward = { PlayerController.seekTo(PlayerController.currentPositionMs() + 5_000L) },
                    onMenu = { navController.popBackStack() },
                    onMenuHome = { navController.popBackStack("library", false) },
                    onConfirm = { if (selected == 0) toggleFav() },
                    modifier = Modifier
                )
            }
        }
    }
}

@Composable
private fun FavoriteButton(favorite: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, tween(150), label = "favScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(84.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(if (favorite) BrandRed else Color.White.copy(alpha = 0.12f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) Color.White else Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (favorite) "已收藏 · 中心键取消" else "收藏这首歌 · 中心键收藏",
            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall
        )
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
