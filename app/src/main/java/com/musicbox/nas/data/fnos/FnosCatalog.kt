package com.musicbox.nas.data.fnos

import com.musicbox.nas.MusicBoxApp
import com.musicbox.nas.data.Track
import com.musicbox.nas.util.CrashLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 飞牛曲库的客户端本地索引。
 *
 * 飞牛原生 API 没有「文件夹视图」接口（社区增强插件才补了 /folder/list），
 * 但 /track/list 返回的 `audioSpec.path` 带完整文件路径，因此这里拉一次全量曲目，
 * 在本地按目录层级建树；「漫游」同样基于这份索引做全库随机。
 *
 * 全量拉取只在首次进入文件夹 / 漫游时发生，结果缓存在进程内；
 * 重新登录或重新扫描后调用 [invalidate] 即可。
 */
object FnosCatalog {

    private val mutex = Mutex()
    private var cached: List<Track>? = null

    suspend fun tracks(): List<Track> = mutex.withLock {
        val hit = cached
        if (hit != null) return@withLock hit

        val client = MusicBoxApp.instance.fnosClient
        val loaded = runCatching { client.allTracks().map { client.toTrack(it) } }
            .getOrElse {
                CrashLogger.error("FnOS.catalog", it)
                emptyList()
            }
        cached = loaded
        CrashLogger.info("FnOS.catalog", "本地索引就绪：${loaded.size} 首")
        return@withLock loaded
    }

    /** 从全库里随机取一批，用于「漫游」栏目。 */
    suspend fun roam(count: Int = 60): List<Track> {
        val all = tracks()
        if (all.isEmpty()) return emptyList()
        return all.shuffled().take(count.coerceIn(1, all.size))
    }

    fun invalidate() {
        cached = null
    }
}
