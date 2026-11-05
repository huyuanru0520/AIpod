package com.musicbox.nas.data.fnos

import com.musicbox.nas.data.AuthRegistry
import com.musicbox.nas.data.AuthType
import com.musicbox.nas.data.HostAuth
import com.musicbox.nas.data.SourceType
import com.musicbox.nas.data.Track
import com.musicbox.nas.data.normalizeHost
import com.musicbox.nas.util.CrashLogger
import com.musicbox.nas.util.randomDeviceId
import com.musicbox.nas.util.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.EOFException
import java.net.URLEncoder
import javax.net.ssl.SSLHandshakeException

class SessionExpiredException : Exception("登录已失效，请重新登录")

// ---- DTOs（仅作为 UI 与播放层的数据容器，不再参与 JSON 反序列化） ----

@Serializable
data class FnosAlbum(
    val guid: String = "",
    val name: String = "",
    val coverId: String? = null,
    val releaseDate: Long? = null,
    val trackCount: Int? = null
)

@Serializable
data class FnosArtist(
    val guid: String = "",
    val name: String = "",
    val coverId: String? = null,
    val trackCount: Int? = null,
    val albumCount: Int? = null
)

@Serializable
data class FnosAudioSpec(
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val channel: Int? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    val format: String? = null,
    val duration: Long? = null,
    val size: Long? = null,
    val path: String? = null
)

@Serializable
data class FnosTrack(
    val guid: String = "",
    val title: String = "",
    val coverId: String? = null,
    val year: Int? = null,
    val discNo: Int? = null,
    val trackNo: Int? = null,
    val duration: Long? = null,
    val album: FnosAlbum = FnosAlbum(),
    val artists: List<FnosArtist> = emptyList(),
    val audioSpec: FnosAudioSpec? = null,
    val isrc: String? = null
)

@Serializable
data class FnosGenre(
    val guid: String = "",
    val name: String = "",
    val trackCount: Int? = null
)

@Serializable
data class FnosPlaylist(
    val guid: String = "",
    val name: String = "",
    val coverId: String? = null,
    val trackCount: Int? = null,
    val description: String? = null
)

data class FnosConnection(
    val host: String,
    val port: Int,
    val useHttps: Boolean,
    val username: String,
    val password: String = "",
    /** 备用线路：主线路连接失败时自动切换（同一账号，仅地址/端口/协议不同）。 */
    val backupHost: String? = null,
    val backupPort: Int = 0,
    val backupUseHttps: Boolean = false
) {
    val baseUrl: String
        get() = buildBase(host, port, useHttps)

    val backupBaseUrl: String?
        get() = if (backupHost.isNullOrBlank()) null else buildBase(backupHost, backupPort, backupUseHttps)

    private fun buildBase(h: String, p: Int, https: Boolean): String =
        "http${if (https) "s" else ""}://${normalizeHost(h)}:$p/music/api/v1"
}

/**
 * 飞牛音乐客户端。
 *
 * JSON 全部采用「宽松手写提取」：不同固件版本同一字段可能是数字、字符串、数组甚至缺失，
 * 用 @Serializable 强类型映射时任意一个字段类型不符都会让整页抛出
 * "Failed to parse literal as 'long' value"（专辑页截图里的报错）。
 * 现在任何字段取值失败都退化为 null，绝不会因为一个字段毁掉整个列表。
 */
class FnosClient(private val okHttp: OkHttpClient, private val registry: AuthRegistry) {

    private var baseUrl = ""
    /** 主线路（登录/会话恢复时的配置地址），用于和备用线路互相切换。 */
    private var primaryBase = ""
    private var backupBase: String? = null
    var token: String = ""
        private set
    var connection: FnosConnection? = null
        private set

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // ---------- 宽松取值 ----------

    private fun str(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun longVal(el: JsonElement?): Long? {
        val s = (el as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
    }

    private fun intVal(el: JsonElement?): Int? = longVal(el)?.toInt()

    private fun JsonObject.pickStr(vararg keys: String): String? {
        for (k in keys) str(this[k])?.let { return it }
        return null
    }

    private fun JsonObject.pickLong(vararg keys: String): Long? {
        for (k in keys) longVal(this[k])?.let { return it }
        return null
    }

    private fun JsonObject.pickInt(vararg keys: String): Int? = pickLong(*keys)?.toInt()

    private fun JsonObject.pickObj(vararg keys: String): JsonObject? {
        for (k in keys) (this[k] as? JsonObject)?.let { return it }
        return null
    }

    private fun JsonObject.pickArr(vararg keys: String): JsonArray? {
        for (k in keys) (this[k] as? JsonArray)?.let { return it }
        return null
    }

    // ---------- 实体解析 ----------

    private fun parseAlbum(o: JsonObject): FnosAlbum = FnosAlbum(
        guid = o.pickStr("guid", "GUID", "id", "albumGUID", "albumId") ?: "",
        name = o.pickStr("name", "title", "albumName", "album_name") ?: "",
        coverId = o.pickStr("coverId", "cover", "cover_id", "coverGuid", "albumCoverId", "picId"),
        // releaseDate 可能是时间戳、字符串日期或数组，取不到就留空，绝不抛异常
        releaseDate = o.pickLong("releaseDate", "release_date", "publishDate"),
        trackCount = o.pickInt("trackCount", "track_count", "songCount", "musicCount")
    )

    private fun parseArtist(o: JsonObject): FnosArtist = FnosArtist(
        guid = o.pickStr("guid", "GUID", "id", "artistGUID", "artistId") ?: "",
        name = o.pickStr("name", "title", "artistName", "artist_name") ?: "",
        coverId = o.pickStr("coverId", "cover", "coverGuid", "artistCoverId"),
        trackCount = o.pickInt("trackCount", "track_count", "songCount"),
        albumCount = o.pickInt("albumCount", "album_count")
    )

    private fun parseAudioSpec(o: JsonObject): FnosAudioSpec = FnosAudioSpec(
        bitDepth = o.pickInt("bitDepth", "bits", "bit_depth"),
        sampleRate = o.pickInt("sampleRate", "sample_rate", "samplingRate"),
        channel = o.pickInt("channel", "channels"),
        bitrate = o.pickInt("bitrate", "bitRate", "bit_rate"),
        codec = o.pickStr("codec", "audioCodec"),
        format = o.pickStr("format", "container", "extension"),
        duration = o.pickLong("duration", "length"),
        size = o.pickLong("size", "fileSize", "file_size"),
        path = o.pickStr("path", "filePath", "file_path")
    )

    private fun parseTrack(o: JsonObject): FnosTrack {
        val albumObj = o.pickObj("album", "albumInfo", "albumDetail")
        val albumName = albumObj?.pickStr("name", "title", "albumName", "album_name")
            ?: o.pickStr("albumName", "album_name", "album")
        val albumGuid = albumObj?.pickStr("guid", "GUID", "id", "albumGUID", "albumId")
            ?: o.pickStr("albumGUID", "albumGuid", "albumId")
        val albumCover = albumObj?.pickStr("coverId", "cover", "coverGuid")

        val artists = mutableListOf<FnosArtist>()
        val artistArr = o.pickArr("artists", "artist", "artistList", "artistInfoList")
        if (artistArr != null) {
            for (el in artistArr) {
                when (el) {
                    is JsonObject -> artists.add(parseArtist(el))
                    is JsonPrimitive -> str(el)?.let { artists.add(FnosArtist(name = it)) }
                    else -> Unit
                }
            }
        } else {
            o.pickStr("artistName", "artist", "artists", "artist_name", "singer")?.let { s ->
                s.split('/', ';', '、', ',').map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals("null", true) }
                    .forEach { artists.add(FnosArtist(name = it)) }
            }
        }

        val specObj = o.pickObj("audioSpec", "audioInfo", "spec", "audio")
        val spec = specObj?.let { parseAudioSpec(it) }

        return FnosTrack(
            guid = o.pickStr("guid", "GUID", "id", "trackGUID", "trackId", "musicId") ?: "",
            title = o.pickStr("title", "name", "trackName", "fileName", "songName") ?: "",
            coverId = o.pickStr("coverId", "cover", "coverGuid", "trackCoverId") ?: albumCover,
            year = o.pickInt("year"),
            discNo = o.pickInt("discNo", "disc"),
            trackNo = o.pickInt("trackNo", "track"),
            duration = o.pickLong("duration", "durationMs", "length", "time") ?: spec?.duration,
            album = FnosAlbum(
                guid = albumGuid ?: "",
                name = albumName ?: "",
                coverId = albumCover
            ),
            artists = artists,
            audioSpec = spec,
            isrc = o.pickStr("isrc")
        )
    }

    private fun parsePlaylist(o: JsonObject): FnosPlaylist = FnosPlaylist(
        guid = o.pickStr("guid", "GUID", "id", "playlistGUID", "playlistId") ?: "",
        name = o.pickStr("name", "title", "playlistName") ?: "",
        coverId = o.pickStr("coverId", "cover", "coverGuid"),
        trackCount = o.pickInt("trackCount", "track_count", "songCount"),
        description = o.pickStr("description", "desc", "remark")
    )

    /** 兼容 data 为数组、data.list、根级 list 等多种分页结构；单条解析失败只跳过该条。 */
    private fun <T> pageOf(root: JsonObject, decode: (JsonObject) -> T): Pair<List<T>, Int> {
        val data = root["data"]
        val arr: JsonArray? = when {
            data is JsonArray -> data
            data is JsonObject -> data.pickArr(
                "list", "items", "records", "rows", "data", "result",
                "songs", "tracks", "albums", "artists", "playlists", "content"
            )
            else -> null
        } ?: root.pickArr("list", "items", "records", "rows", "data", "result")

        val list: List<T> = arr?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            runCatching { decode(obj) }
                .onFailure { CrashLogger.error("FnOS.parseItem", it) }
                .getOrNull()
        } ?: emptyList()

        val dataObj = data as? JsonObject
        val total = dataObj?.pickInt("total", "totalCount", "count", "totalSize", "totalNum")
            ?: root.pickInt("total", "totalCount", "count")
            ?: list.size
        return list to total
    }

    // ---------- 网络 ----------

    /** 所有网络调用统一切到 IO，避免主线程 NetworkOnMainThreadException。 */
    suspend fun login(conn: FnosConnection, password: String): String = withContext(Dispatchers.IO) {
        connection = conn
        primaryBase = conn.baseUrl
        backupBase = conn.backupBaseUrl
        val lines = listOfNotNull(primaryBase.takeIf { it.isNotBlank() }, backupBase).distinct()
        var last: Throwable? = null
        for ((i, line) in lines.withIndex()) {
            try {
                return@withContext loginOn(conn, line, password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                CrashLogger.info("FnOS", "线路 $line 登录失败：${e.message}")
                if (i < lines.lastIndex) {
                    CrashLogger.info("FnOS", "主线路不可用，切换备用线路 ${lines[i + 1]}")
                }
            }
        }
        throw (last as? Exception) ?: Exception("飞牛登录失败：所有线路均不可用")
    }

    private fun loginOn(conn: FnosConnection, base: String, password: String): String {
        baseUrl = base
        CrashLogger.info("FnOS", "开始登录 $base，用户=${conn.username}")
        val body = buildJsonObject {
            put("username", conn.username)
            put("password", sha256Hex(password))
            put("deviceId", randomDeviceId())
        }
        val req = Request.Builder()
            .url("$baseUrl/user/password-login")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = runCatching { okHttp.newCall(req).execute() }.getOrElse { t ->
            when (t) {
                is SSLHandshakeException ->
                    throw Exception(
                        "HTTPS 握手失败（${t.message}）。自签/反代证书已自动放行；" +
                            "请确认该端口确实是 TLS 端口——飞牛音乐网关默认 5666 是明文 HTTP（需关闭「使用 HTTPS」）。" +
                            "若服务器在域名后做了反代，请确保反代开启了正确的 TLS。详情见日志 FnOS。",
                        t
                    )
                is EOFException ->
                    throw Exception(
                        "连接被服务器中断（无响应）。请确认端口填对：HTTPS 用 TLS 端口、HTTP 用明文端口；" +
                            "飞牛音乐网关默认 5666 为明文。详情见日志 FnOS。",
                        t
                    )
                else -> throw t
            }
        }
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw Exception("HTTP ${resp.code}：${raw.take(160).ifBlank { "无响应内容" }}。请确认端口为 5666、HTTPS 开关与服务端一致。")
        }
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw Exception("服务器返回的不是 JSON（HTTP ${resp.code}）：${raw.take(160)}。请确认地址指向飞牛音乐网关（默认端口 5666）。")
        }
        val code = root.pickInt("code") ?: 0
        val msg = root.pickStr("msg", "message").orEmpty()
        if (code != 0 && code != 200) {
            throw Exception("飞牛返回 code=$code：$msg（HTTP ${resp.code}）")
        }
        val tok = findToken(root) ?: tokenFromCookies(resp)
            ?: throw Exception("未取到登录令牌。服务器消息：$msg；响应片段：${raw.take(200)}")
        token = tok
        // 两条线路都登记 cookie：任一线路可用时都能直接带上鉴权
        registry.put(baseUrl, HostAuth(AuthType.COOKIE, cookie = "music-token=$tok"))
        backupBase?.let { registry.put(it, HostAuth(AuthType.COOKIE, cookie = "music-token=$tok")) }
        CrashLogger.info("FnOS", "登录成功（线路 $baseUrl），token 长度=${tok.length}")
        return tok
    }

    private fun findToken(root: JsonObject): String? {
        val names = arrayOf("token", "userToken", "musicToken", "accessToken", "music-token")
        val containers = listOfNotNull(root, root.pickObj("data"))
        for (obj in containers) {
            obj.pickStr(*names)?.let { return it }
            obj.pickObj("data")?.pickStr(*names)?.let { return it }
        }
        return null
    }

    private fun tokenFromCookies(resp: okhttp3.Response): String? {
        val re = Regex("music-token=([^;,\\s]+)")
        return resp.headers("Set-Cookie").firstNotNullOfOrNull { re.find(it)?.groupValues?.get(1) }
    }

    fun setSession(conn: FnosConnection, token: String) {
        connection = conn
        val previous = baseUrl
        primaryBase = conn.baseUrl
        backupBase = conn.backupBaseUrl
        // 保留刚登录成功的线路（可能是备用线路），否则回落到主线路
        baseUrl = if (previous == primaryBase || (backupBase != null && previous == backupBase))
            previous else primaryBase
        this.token = token
        registry.put(baseUrl, HostAuth(AuthType.COOKIE, cookie = "music-token=$token"))
        backupBase?.let { registry.put(it, HostAuth(AuthType.COOKIE, cookie = "music-token=$token")) }
    }

    fun logout() {
        token = ""
        if (baseUrl.isNotEmpty()) registry.remove(baseUrl)
        backupBase?.let { registry.remove(it) }
        connection = null
    }

    private suspend fun getJson(path: String, params: List<Pair<String, String>> = emptyList()): JsonObject =
        withContext(Dispatchers.IO) {
            val primary = baseUrl.takeIf { it.isNotBlank() } ?: primaryBase
            val alt = if (primary == primaryBase) backupBase
            else primaryBase.takeIf { it.isNotBlank() && it != primary }
            val lines = listOfNotNull(primary, alt).distinct()
            var last: Throwable? = null
            for ((i, line) in lines.withIndex()) {
                try {
                    val r = getJsonOn(line, path, params)
                    if (line != baseUrl) {
                        baseUrl = line
                        CrashLogger.info("FnOS", "线路已切换为 $line")
                    }
                    return@withContext r
                } catch (e: SessionExpiredException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                    if (i < lines.lastIndex) {
                        CrashLogger.info("FnOS", "线路 $line 请求 $path 失败：${e.message}；改试备用线路 ${lines[i + 1]}")
                    }
                }
            }
            throw (last as? Exception) ?: Exception("请求失败：$path")
        }

    private fun getJsonOn(base: String, path: String, params: List<Pair<String, String>>): JsonObject {
            CrashLogger.info("FnOS", "GET $path（线路 $base）")
            val url = buildString {
                append(base); append(path)
                if (params.isNotEmpty()) {
                    append("?"); append(params.joinToString("&") {
                        "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}"
                    })
                }
            }
            val req = Request.Builder().url(url).get().header("X-Music-API", "v1").build()
            val resp = okHttp.newCall(req).execute()
            val raw = resp.body?.string().orEmpty()
            val root = try {
                json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                throw Exception("响应解析失败（HTTP ${resp.code}）：${raw.take(160)}")
            }
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}：${root.pickStr("msg") ?: raw.take(120)}")
            }
            // 记录响应顶层结构，便于按具体固件返回定位「专辑页报错」一类问题。
            // 解析全部走宽松 JsonObject 提取，任何字段类型不符都只会退化为 null，不会抛异常。
            CrashLogger.info(
                "FnOS",
                "$path 响应顶层键=${root.keys.joinToString(",")}；" +
                    "data 类型=${root["data"]?.let { it::class.java.simpleName } ?: "null"}"
            )
            val code = root.pickInt("code") ?: 0
            val msg = root.pickStr("msg", "message").orEmpty()
            if (code == 401 || code == 120001 || msg.contains("invalid token", ignoreCase = true)) {
                throw SessionExpiredException()
            }
            if (code != 0 && code != 200) throw Exception("接口错误 code=$code：$msg")
            return root
    }

    // ---------- 列表接口 ----------

    suspend fun trackList(page: Int, size: Int = 50, sort: String? = null): Pair<List<FnosTrack>, Int> {
        val params = mutableListOf("page" to page.toString(), "size" to size.toString())
        sort?.let { params.add("sort" to it) }
        val r = pageOf(getJson("/track/list", params)) { parseTrack(it) }
        CrashLogger.info("FnOS", "trackList page=$page 返回 ${r.first.size} 首 / total=${r.second}")
        return r
    }

    suspend fun albumList(page: Int, size: Int = 50): Pair<List<FnosAlbum>, Int> {
        val r = pageOf(getJson("/album/list", listOf("page" to page.toString(), "size" to size.toString()))) { parseAlbum(it) }
        CrashLogger.info("FnOS", "albumList page=$page 返回 ${r.first.size} 张 / total=${r.second}")
        return r
    }

    suspend fun artistList(page: Int, size: Int = 50): Pair<List<FnosArtist>, Int> {
        val r = pageOf(getJson("/artist/list", listOf("page" to page.toString(), "size" to size.toString()))) { parseArtist(it) }
        CrashLogger.info("FnOS", "artistList page=$page 返回 ${r.first.size} 位 / total=${r.second}")
        return r
    }

    suspend fun albumTracks(albumGUID: String, page: Int, size: Int = 200): Pair<List<FnosTrack>, Int> =
        pageOf(getJson("/track/album-detail/list", listOf("albumGUID" to albumGUID, "page" to page.toString(), "size" to size.toString()))) { parseTrack(it) }

    suspend fun artistTracks(artistGUID: String, page: Int, size: Int = 200): Pair<List<FnosTrack>, Int> =
        pageOf(getJson("/track/artist-detail/list", listOf("artistGUID" to artistGUID, "page" to page.toString(), "size" to size.toString()))) { parseTrack(it) }

    suspend fun playlistList(page: Int, size: Int = 50): Pair<List<FnosPlaylist>, Int> =
        pageOf(getJson("/playlist/list", listOf("page" to page.toString(), "size" to size.toString()))) { parsePlaylist(it) }

    suspend fun playlistTracks(playlistGUID: String, page: Int, size: Int = 200): Pair<List<FnosTrack>, Int> =
        pageOf(getJson("/track/playlist-detail/list", listOf("playlistGUID" to playlistGUID, "page" to page.toString(), "size" to size.toString()))) { parseTrack(it) }

    suspend fun searchTrack(q: String, page: Int, size: Int = 50): Pair<List<FnosTrack>, Int> =
        pageOf(getJson("/search/track", listOf("q" to q, "page" to page.toString(), "size" to size.toString()))) { parseTrack(it) }

    suspend fun searchAlbum(q: String, page: Int, size: Int = 50): Pair<List<FnosAlbum>, Int> =
        pageOf(getJson("/search/album", listOf("q" to q, "page" to page.toString(), "size" to size.toString()))) { parseAlbum(it) }

    suspend fun searchArtist(q: String, page: Int, size: Int = 50): Pair<List<FnosArtist>, Int> =
        pageOf(getJson("/search/artist", listOf("q" to q, "page" to page.toString(), "size" to size.toString()))) { parseArtist(it) }

    /** 服务端「风格 / 流派」分类。飞牛原生接口：GET /genre/list。 */
    suspend fun genreList(page: Int = 1, size: Int = 200): Pair<List<FnosGenre>, Int> {
        val r = pageOf(
            getJson("/genre/list", listOf("page" to page.toString(), "size" to size.toString()))
        ) { o ->
            FnosGenre(
                guid = o.pickStr("guid", "GUID", "id", "genreGUID", "genreId", "name") ?: "",
                name = o.pickStr("name", "genre", "genreName", "title") ?: "",
                trackCount = o.pickInt("trackCount", "trackNum", "count", "songCount")
            )
        }
        CrashLogger.info("FnOS", "genreList 返回 ${r.first.size} 个风格 / total=${r.second}")
        return r
    }

    /** 某个风格下的曲目。guid 查不到时用风格名再试一次（部分服务端只认 name）。 */
    suspend fun genreTracks(
        genreGUID: String,
        genreName: String = "",
        page: Int = 1,
        size: Int = 300
    ): Pair<List<FnosTrack>, Int> {
        suspend fun query(key: String, value: String) =
            pageOf(
                getJson(
                    "/track/genre-detail/list",
                    listOf(key to value, "page" to page.toString(), "size" to size.toString())
                )
            ) { parseTrack(it) }

        var r = query("genreGUID", genreGUID)
        if (r.first.isEmpty() && genreName.isNotBlank()) {
            // 部分服务端只认名称；guid 缺失时 parseGenre 会把 name 当作 guid，
            // 此时仍要用 genreName 参数再试一次，不能因为两者相等就跳过。
            r = query("genreName", genreName)
        }
        CrashLogger.info("FnOS", "genreTracks($genreGUID) 返回 ${r.first.size} 首 / total=${r.second}")
        return r
    }

    /** 拉取全量曲目：用于本地构建文件夹树与随机漫游。结果按进程缓存，见 FnosCatalog。 */
    suspend fun allTracks(maxPages: Int = 20, pageSize: Int = 500): List<FnosTrack> {
        val out = ArrayList<FnosTrack>(pageSize)
        var page = 1
        while (page <= maxPages) {
            val (list, total) = trackList(page, pageSize)
            out.addAll(list)
            if (list.isEmpty() || (total > 0 && out.size >= total)) break
            page++
        }
        CrashLogger.info("FnOS", "allTracks 完成：${out.size} 首 / $page 页")
        return out
    }

    /**
     * 接口自检：逐个探测新版飞牛音乐可能提供的分类接口。
     *
     * 飞牛官方没有公开 API 文档，不同固件版本接口差异较大；这个按钮让用户
     * 在自己的 NAS 上跑一次就能看到哪些路径真实存在，便于后续精确对接。
     */
    suspend fun probeEndpoints(): List<String> {
        data class Probe(val label: String, val path: String, val params: List<Pair<String, String>>)
        val one = listOf("page" to "1", "size" to "1")
        val probes = listOf(
            Probe("风格分类", "/genre/list", one),
            Probe("风格曲目", "/track/genre-detail/list", listOf("genreGUID" to "", "page" to "1", "size" to "1")),
            Probe("文件夹视图", "/folder/list", listOf("path" to "/", "page" to "1", "size" to "1")),
            Probe("随机曲目", "/track/random", one),
            Probe("漫游", "/roam/list", one),
            Probe("电台", "/radio/list", one),
            Probe("推荐", "/recommend/list", one),
            Probe("发现", "/discover/list", one)
        )
        return probes.map { p ->
            val result = runCatching { getJson(p.path, p.params) }.fold(
                onSuccess = { root ->
                    val code = root.pickInt("code") ?: 0
                    val data = root["data"]
                    val shape = when (data) {
                        is JsonObject -> data.keys.joinToString(",")
                        is kotlinx.serialization.json.JsonArray -> "数组(${data.size})"
                        else -> data?.let { it::class.java.simpleName } ?: "null"
                    }
                    if (code == 0) "可用 · data=$shape"
                    else "返回 code=$code · ${root.pickStr("msg").orEmpty().take(40)}"
                },
                onFailure = { "不可用 · ${it.message?.take(70)}" }
            )
            "${p.label}  ${p.path}  →  $result"
        }
    }

    suspend fun lyricText(trackGUID: String): String? {
        val root = getJson("/lyric/list", listOf("trackGUID" to trackGUID))
        val data = root["data"]
        val obj = data as? JsonObject
        val arr: JsonArray? = (data as? JsonArray) ?: obj?.pickArr("list", "items", "lyrics", "data")
        if (arr != null) {
            return arr.mapNotNull { el ->
                (el as? JsonObject)?.pickStr("content", "lyric", "text", "lrc")
                    ?: (el as? JsonPrimitive)?.contentOrNull
            }.firstOrNull { it.isNotBlank() }
        }
        return obj?.pickStr("content", "lyric", "text", "lrc")
            ?: root.pickStr("content", "lyric")
    }

    fun streamUrl(guid: String) = "$baseUrl/track/stream?guid=$guid"

    fun coverUrl(coverId: String?) =
        if (coverId.isNullOrBlank()) null else "$baseUrl/static/cover?coverId=$coverId"

    /**
     * 把曲目的文件路径变成「库内目录路径」，供文件夹 Tab 逐层浏览。
     * 飞牛返回的 path 可能是 /vol1/1000/Music/... 这类内部绝对路径，
     * 这里剥掉卷前缀、去掉文件名，只保留目录层级。
     */
    private fun folderOf(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val raw = path.replace('\\', '/')
        val dir = raw.substringBeforeLast('/', "")
        if (dir.isBlank()) return ""
        // /vol1/1000/…、/volume1/… 等 NAS 卷前缀对浏览没有意义
        val cleaned = Regex("^/(?:vol|volume)\\d+/\\d+").replace(dir, "")
        val result = if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        return if (result == "/") "" else result
    }

    fun toTrack(t: FnosTrack): Track {
        val artist = t.artists.joinToString(" / ") { it.name.ifBlank { "未知歌手" } }.ifBlank { "未知歌手" }
        // 时长可能是秒或毫秒，统一换算成毫秒
        val raw = t.duration ?: t.audioSpec?.duration ?: 0L
        val durMs = if (raw > 100_000L) raw else raw * 1000L
        return Track(
            id = "fnos:${t.guid}",
            title = t.title.ifBlank { "未知标题" },
            artist = artist,
            album = t.album.name.ifBlank { "未知专辑" },
            durationMs = durMs,
            coverUrl = coverUrl(t.coverId) ?: coverUrl(t.album.coverId),
            streamUrl = streamUrl(t.guid),
            source = SourceType.FNOS,
            sampleRateHz = t.audioSpec?.sampleRate ?: 0,
            bitDepth = t.audioSpec?.bitDepth ?: 0,
            codec = t.audioSpec?.codec?.uppercase()
                ?: t.audioSpec?.format?.uppercase() ?: "",
            folder = folderOf(t.audioSpec?.path)
        )
    }
}
