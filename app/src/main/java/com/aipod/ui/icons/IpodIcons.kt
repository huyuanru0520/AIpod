package com.aipod.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * iPod 主题列表图标（单色符号）。
 *
 * 早期版本直接从 ipod-classic-js 的 280×280 SVG 转换，那些 SVG 是
 * 「浅灰方块底 + 深蓝符号」的分层设计，且各图标 content 坐标范围不一。
 * 全部 path 染成白色后，方块底盖住了符号（显示为白块），坐标小的图标
 * （心形/文件夹/音量，坐标仅 0~51）套 280 viewport 又缩成了小点。
 *
 * 现统一改用 24×24 viewport 的纯符号路径（无方块底），任何 tint 下都清晰：
 * - 单色玻璃主题：符号 + 玻璃质感蒙版；
 * - 彩色卡片主题：渐变圆角方块 + 白色符号。
 * 语义与原图标一一对应，保留 iPod 主题的专属形状区分。
 */

private fun icon(name: String, path: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.White),
        stroke = null,
        pathFillType = PathFillType.NonZero
    )
}.build()

/** 歌曲：单音符 */
val ipod_song = icon(
    "ipod_song",
    "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"
)

/** 专辑：唱片圆盘 + 中心孔 */
val ipod_album = icon(
    "ipod_album",
    "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z"
)

/** 歌手：人像 */
val ipod_artist = icon(
    "ipod_artist",
    "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"
)

/** 歌单：列表 */
val ipod_playlist = icon(
    "ipod_playlist",
    "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"
)

/** 搜索：放大镜 */
val ipod_search = icon(
    "ipod_search",
    "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
)

/** 收藏：心形 */
val ipod_favorite = icon(
    "ipod_favorite",
    "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
)

/** 文件夹 */
val ipod_folder = icon(
    "ipod_folder",
    "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
)

/** 风格：星形 */
val ipod_genre = icon(
    "ipod_genre",
    "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"
)

/** 正在播放：播放圆圈 */
val ipod_nowplaying = icon(
    "ipod_nowplaying",
    "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"
)

/** 音量：喇叭 + 声波 */
val ipod_volume = icon(
    "ipod_volume",
    "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"
)
