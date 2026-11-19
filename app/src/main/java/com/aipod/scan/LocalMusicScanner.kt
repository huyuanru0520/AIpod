package com.aipod.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.aipod.data.SourceType
import com.aipod.data.Track
import com.aipod.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地音乐扫描：从用户通过 SAF 授权的文件夹树递归找出所有音频文件，
 * 用 MediaStore 提供的显示名/时长等元数据构造 [Track]。
 *
 * 无需 READ_EXTERNAL_STORAGE 权限——用户选中的那棵子树已通过 persistable
 * URI 授权，ContentResolver 可直接查询。播放时直接把 content:// _uri 交给
 * ExoPlayer；封面走内嵌标签解析（见 Components 的 embedded 通道）。
 */
object LocalMusicScanner {

    private const val TAG = "LocalMusicScanner"

    /** 常见音频扩展名（小写）。 */
    private val AUDIO_EXT = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "wav", "wma",
        "ape", "alac", "aiff", "aif", "dsf", "dff", "opus", "mp2"
    )

    /**
     * 递归扫描一棵文档树，返回所有音频 Track。
     * @param treeUri SAF ACTION_OPEN_DOCUMENT_TREE 返回的 URI
     * @param onProgress 扫描进度回调（count, 当前文件名）
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        onProgress: suspend (Int, String) -> Unit = { _, _ -> }
    ): List<Track> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Track>()
        var dirsVisited = 0
        var filesSeen = 0
        // 先解析根 tree 的 documentId；始终基于「根 treeUri + docId」构造子 URI，
        // 避免部分 provider 对 document URI 再次 getDocumentId 返回空导致递归提前结束。
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            runCatching { DocumentsContract.getDocumentId(treeUri) }.getOrNull()
        }
        CrashLogger.info("LocalScan", "根 URI=$treeUri 根ID=$rootId")
        if (rootId == null) {
            CrashLogger.info("LocalScan", "无法解析根 documentId，扫描中止")
            return@withContext found
        }
        walk(context, treeUri, rootId, found, onProgress, { dirsVisited++ }, { filesSeen++ })
        found.sortBy { "${it.folder}/${it.title}".lowercase() }
        val summary = "本地扫描完成：${found.size} 首（遍历 $dirsVisited 个目录 / 见到 $filesSeen 个文件）根=$treeUri"
        Log.i(TAG, summary)
        CrashLogger.info("LocalScan", summary)
        found
    }

    private suspend fun walk(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        out: MutableList<Track>,
        onProgress: suspend (Int, String) -> Unit,
        onDir: () -> Unit = {},
        onFile: () -> Unit = {}
    ) {
        onDir()
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        } catch (e: Exception) {
            Log.w(TAG, "构造子目录 URI 失败 $parentDocId: ${e.message}")
            return
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "查询目录失败 $parentDocId: ${e.message}")
            CrashLogger.info("LocalScan", "查询目录失败 $parentDocId: ${e.message}")
            return
        } ?: run {
            CrashLogger.info("LocalScan", "查询返回空 cursor：$parentDocId")
            return
        }

        cursor.use { c ->
            if (!c.moveToFirst()) {
                CrashLogger.info("LocalScan", "目录为空：$parentDocId")
                return
            }
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            do {
                val docId = if (idIdx >= 0) c.getString(idIdx) else null
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (docId == null) continue
                onFile()
                if (isDir) {
                    walk(context, treeUri, docId, out, onProgress, onDir, onFile)
                } else {
                    val isAudio = mime.startsWith("audio/") || name.extensionLower() in AUDIO_EXT
                    // 逐文件判定写日志：flac/m4a 扫不到时能直接从「运行日志」看出是没遍历到还是没判定为音频
                    CrashLogger.info("LocalScan", "文件[$name] mime=$mime 音频=$isAudio")
                    if (isAudio) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val track = toTrack(context, uri, name, out.size)
                        if (track != null) {
                            out.add(track)
                            onProgress(out.size, name)
                        }
                    }
                }
            } while (c.moveToNext())
        }
    }

    private fun toTrack(context: Context, uri: Uri, displayName: String, index: Int): Track? {
        val retr = android.media.MediaMetadataRetriever()
        var title = displayName.substringBeforeLast('.')
        var artist = ""
        var album = ""
        var durationMs = 0L
        var sampleRate = 0
        var bitDepth = 0
        var codec = displayName.substringAfterLast('.', "").uppercase()
        try {
            retr.setDataSource(context, uri)
            retr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            retr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }?.let { album = it }
            retr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { durationMs = it }
            retr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                ?.toIntOrNull()?.let { sampleRate = it }
        } catch (e: Exception) {
            Log.w(TAG, "读取元数据失败 $displayName: ${e.message}")
        } finally {
            runCatching { retr.release() }
        }
        // 内嵌封面：把 content:// 地址交给 Components 的内嵌解析通道
        val url = uri.toString()
        return Track(
            id = "local:$url",
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUrl = null,
            streamUrl = url,
            source = SourceType.LOCAL,
            embeddedAudioUrl = url,
            folder = "",
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            codec = codec
        )
    }

    private fun String.extensionLower(): String = substringAfterLast('.', "").lowercase()
}
