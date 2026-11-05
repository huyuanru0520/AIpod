package com.musicbox.nas.data

import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class AuthType { BASIC, COOKIE, NONE }

/** Per-host auth attached to requests by [AuthInterceptor]. */
data class HostAuth(
    val type: AuthType,
    val basicUser: String? = null,
    val basicPass: String? = null,
    val cookie: String? = null
)

/**
 * 规范化用户在「地址」栏里粘贴的内容：去掉 http:// 前缀、路径，
 * 以及误填在主机里的端口（端口由单独字段控制）。
 */
fun normalizeHost(raw: String): String {
    var h = raw.trim()
    val scheme = h.indexOf("://")
    if (scheme >= 0) h = h.substring(scheme + 3)
    h = h.substringBefore('/').substringBefore('?').substringBefore('#')
    // 单个冒号说明是 "host:port"；IPv6（多个冒号）原样保留
    if (h.count { it == ':' } == 1) h = h.substringBefore(':')
    return h
}

/**
 * 清洗 WebDAV「根路径」：去掉粘贴时混入的换行 / 制表符（之前日志里出现过路径被换行切断、
 * 导致 PROPFIND 一直 404 的情况）；若用户把整段 URL 粘进了路径框，只取路径部分；
 * 空格（如「Apple 无损」）正常保留。最终保证以 / 开头。
 */
fun sanitizeWebDavPath(raw: String): String {
    var p = raw.replace(Regex("[\\r\\n\\t]"), "")
    val schemeIdx = p.indexOf("://")
    if (schemeIdx >= 0) {
        val after = p.substring(schemeIdx + 3)
        val slash = after.indexOf('/')
        p = if (slash >= 0) after.substring(slash) else "/"
    }
    p = p.trim()
    if (p.isBlank()) return "/"
    if (!p.startsWith("/")) p = "/$p"
    return p
}

/** Maps a request host:port to its auth. Thread-safe. */
class AuthRegistry {
    private val map = ConcurrentHashMap<String, HostAuth>()

    fun put(baseUrl: String, auth: HostAuth) { map[hostKey(baseUrl)] = auth }
    fun get(url: String): HostAuth? = map[hostKey(url)]
    fun remove(baseUrl: String) { map.remove(hostKey(baseUrl)) }
    fun clear() { map.clear() }

    companion object {
        /**
         * 纯字符串解析，不能用 java.net.URI：带中文或空格的路径会让 URI 构造抛异常，
         * 于是「注册时的 key」退化成整串 URL，与「请求时的 key」不一致，认证头丢失
         * （表现为 WebDAV 目录能列出来、点播放却 401）。
         */
        fun hostKey(url: String): String {
            val noScheme = url.substringAfter("://", url)
            val authority = noScheme
                .substringBefore('/').substringBefore('?').substringBefore('#')
            return authority.substringAfter('@').lowercase()
        }
    }
}

/** Injects Basic / Cookie auth based on the request host. */
class AuthInterceptor(private val registry: AuthRegistry) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val auth = registry.get(request.url.toString())
        if (auth == null) return chain.proceed(request)
        val builder = request.newBuilder()
        when (auth.type) {
            AuthType.BASIC ->
                // 账号密码都留空 = 匿名访问（AList 开启访客时可用）。
                // 此时若仍发一个空的 Basic 头，服务端反而会返回 401。
                if (auth.basicUser.isNullOrBlank() && auth.basicPass.isNullOrBlank()) {
                    Unit
                } else {
                    builder.header("Authorization", Credentials.basic(auth.basicUser ?: "", auth.basicPass ?: ""))
                }
            AuthType.COOKIE ->
                if (!auth.cookie.isNullOrBlank()) builder.header("Cookie", auth.cookie)
            AuthType.NONE -> Unit
        }
        return chain.proceed(builder.build())
    }
}

fun createOkHttpClient(registry: AuthRegistry): OkHttpClient =
    OkHttpClient.Builder()
        // 默认 Dispatcher 对「同一主机」只放行 5 个并发请求，会把扫描的并行度死死卡在 5。
        // 扫描（大量并行 PROPFIND / STRM 标签下载）需要更高并发，这里放宽到 24 路/主机、总 160。
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 160
                maxRequestsPerHost = 24
            }
        )
        .addInterceptor(AuthInterceptor(registry))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

/**
 * 宽松 HTTPS 客户端（专供飞牛音乐等自托管服务使用）。
 *
 * 默认 OkHttp 走 MODERN_TLS：只认 TLS 1.2/1.3 + 受信任证书，
 * 自签名证书 / 老固件 TLS 会直接握手失败（日志里表现为
 * SSLHandshakeException: connection closed / EOFException）。
 * 这里：
 *  - 安装「信任所有证书」的 TrustManager（自签名证书直接放行）；
 *  - hostnameVerifier 放行（证书主机名与填写域名不一致时也连接）；
 *  - 使用 COMPATIBLE_TLS（覆盖 TLS 1.0~1.3 与常见密码套件）+ 明文回退，
 *    兼容各种自托管 / 反代 / 老固件。
 * 仅对连接失败做兜底，不绕过用户凭据或传输内容。
 */
fun createLenientOkHttpClient(registry: AuthRegistry): OkHttpClient {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }
    return OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(registry))
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
