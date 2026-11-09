package cn.weiguang.app.data

import cn.weiguang.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int, message: String) : Exception(message)

class ApiClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun call(endpoint: String, path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint + path)
        if (token != null) request.header("Authorization", "Bearer $token")
        val requestBody = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        request.method(method, if (method in listOf("POST", "PUT")) requestBody ?: "{}".toRequestBody("application/json".toMediaType()) else null)
        client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful) {
                val detail = json.opt("detail")
                throw ApiFailure(response.code, if (detail is String) detail else "请求失败（${response.code}），请检查输入或服务端状态")
            }
            if (json.length() == 0) throw ApiFailure(502, "服务端返回了无法识别的内容")
            json
        }
    }

    companion object {
        fun validateEndpoint(input: String): String {
            val normalized = input.trim().trimEnd('/')
            val uri = try { URI(normalized) } catch (_: Exception) { throw IllegalArgumentException("请输入完整服务地址") }
            require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty()) { "地址格式示例 http://127.0.0.1:8765，不含路径和账号" }
            require(BuildConfig.ALLOW_LOCAL_HTTP || uri.scheme == "https") { "发布版需要 HTTPS 服务地址" }
            return normalized
        }
    }
}
