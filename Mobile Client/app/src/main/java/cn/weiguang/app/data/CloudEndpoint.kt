package cn.weiguang.app.data

import cn.weiguang.app.BuildConfig
import java.net.URI

internal object CloudEndpoint {
    const val production = "https://agent.qwertyuiopasdf.asia"
    private const val local = "http://127.0.0.1:8765"
    val default get() = if (BuildConfig.LOCK_CLOUD_ENDPOINT) production else local

    fun selected(stored: String?): String =
        if (BuildConfig.LOCK_CLOUD_ENDPOINT) production else stored ?: local

    fun acceptsSession(endpoint: String): Boolean {
        if (!BuildConfig.LOCK_CLOUD_ENDPOINT || endpoint == "offline") return true
        val uri = parse(endpoint) ?: return false
        return isProductionOrigin(uri) && uri.path.orEmpty().isEmpty() &&
            uri.rawQuery == null && uri.rawFragment == null
    }

    fun request(endpoint: String): String {
        if (!BuildConfig.LOCK_CLOUD_ENDPOINT) return endpoint
        require(acceptsSession(endpoint) && endpoint != "offline") { "请重新登录微光云端" }
        return production
    }

    fun acceptsDownload(url: String): Boolean =
        !BuildConfig.LOCK_CLOUD_ENDPOINT || parse(url)?.let(::isProductionOrigin) == true

    private fun parse(value: String): URI? = runCatching { URI(value) }.getOrNull()

    private fun isProductionOrigin(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("agent.qwertyuiopasdf.asia", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null
}
