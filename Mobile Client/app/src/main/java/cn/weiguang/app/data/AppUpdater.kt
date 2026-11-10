package cn.weiguang.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import cn.weiguang.app.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppRelease(val code: Long, val name: String, val sha256: String, val size: Long,
    val minSdk: Int, val notes: String, val url: String, val endpoint: String) {
    companion object {
        const val MAX_SIZE = 200L * 1024 * 1024
        fun parse(json: JSONObject, endpoint: String, channel: String, allowHttp: Boolean): AppRelease {
            require(json.getInt("schema_version") == 1) { "暂不支持此更新格式" }
            require(json.getString("package_name") == "cn.weiguang.app" && json.getString("channel") == channel) { "更新包不适用于当前应用" }
            val code = json.getLong("version_code"); val name = json.getString("version_name")
            val hash = json.getString("sha256"); val size = json.getLong("size_bytes")
            val minSdk = json.getInt("min_sdk"); val notes = json.optString("notes")
            require(code > 0 && name.length in 1..40 && minSdk >= 29 && notes.length <= 4000) { "更新信息不完整" }
            require(Regex("^[a-f0-9]{64}$").matches(hash) && size in 1..MAX_SIZE) { "更新包校验信息无效" }
            val supplied = json.optString("download_url")
            val url = supplied.ifBlank { "$endpoint/v1/app/releases/$hash.apk" }
            val uri = URI(url); val source = URI(endpoint)
            require(uri.host != null && uri.userInfo == null && uri.fragment == null) { "更新下载地址无效" }
            require(uri.scheme == "https" || (allowHttp && supplied.isBlank() && uri.scheme == "http" && uri.authority == source.authority)) { "更新下载需要安全连接" }
            return AppRelease(code, name, hash, size, minSdk, notes, url, endpoint)
        }
    }
}

data class UpdateState(val phase: String = "idle", val release: AppRelease? = null,
    val progress: Int = 0, val message: String = "启动时自动检查，也可以手动检查更新。")

/** Internal distribution only. Downloads never carry account or model credentials. */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val root get() = File(context.filesDir, "updates").apply { mkdirs() }
    private var operation: Job? = null
    @Volatile private var activeCall: Call? = null
    private val checkedAt = mutableMapOf<String, Long>()

    fun check(rawEndpoint: String, manual: Boolean = true) {
        if (!BuildConfig.ENABLE_SELF_UPDATE || operation?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && now - (checkedAt[rawEndpoint] ?: 0) < 4 * 60 * 60 * 1000L) return
        checkedAt[rawEndpoint] = now
        val prior = mutable.value
        operation = scope.launch {
            mutable.value = prior.copy(phase = "checking", message = "正在检查更新…")
            try {
                val endpoint = ApiClient.validateEndpoint(rawEndpoint)
                val release = withContext(Dispatchers.IO) {
                    val call = client.newCall(Request.Builder().url("$endpoint/v1/app/update?channel=${BuildConfig.BUILD_TYPE}&version_code=${BuildConfig.VERSION_CODE}").build())
                    activeCall = call
                    call.execute().use { response ->
                        if (response.code == 204) return@withContext null
                        check(response.isSuccessful) { "更新服务暂不可用（${response.code}）" }
                        val body = requireNotNull(response.body)
                        require(body.contentLength() <= 20000) { "更新信息过大" }
                        val bytes = body.byteStream().use { input ->
                            val buffer = ByteArray(20001)
                            var total = 0
                            while (total < buffer.size) {
                                val count = input.read(buffer, total, buffer.size - total)
                                if (count < 0) break
                                total += count
                            }
                            buffer.copyOf(total)
                        }
                        require(bytes.size <= 20000) { "更新信息过大" }
                        AppRelease.parse(JSONObject(String(bytes, Charsets.UTF_8)), endpoint, BuildConfig.BUILD_TYPE, BuildConfig.ALLOW_LOCAL_HTTP)
                    }
                }
                if (release == null || release.code <= BuildConfig.VERSION_CODE) {
                    mutable.value = UpdateState(phase = "current", message = "当前服务器没有可用的新版本。")
                } else {
                    require(Build.VERSION.SDK_INT >= release.minSdk) { "新版需要更高版本的 Android 系统" }
                    val ready = withContext(Dispatchers.IO) {
                        val file = File(root, release.sha256 + ".apk")
                        file.exists() && runCatching { verify(file, release); true }.getOrDefault(false)
                    }
                    mutable.value = UpdateState(if (ready) "ready" else "available", release,
                        message = if (ready) "新版已下载并校验，可以安装。" else "发现新版本 ${release.name}。")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = prior.copy(message = if (error is java.io.IOException) "暂时无法连接更新服务，请检查服务地址或稍后重试。" else error.message ?: "检查更新失败") }
            finally { activeCall = null }
        }
    }

    fun download() {
        if (operation?.isActive == true) return
        val release = mutable.value.release ?: return
        operation = scope.launch {
            mutable.value = UpdateState("downloading", release, message = "正在下载安装包，请保持应用开启。")
            try {
                withContext(Dispatchers.IO) {
                    val destination = File(root, release.sha256 + ".apk")
                    val partial = File(root, release.sha256 + ".part")
                    try {
                        val call = client.newCall(Request.Builder().url(release.url).build())
                        activeCall = call
                        call.execute().use { response ->
                            check(response.isSuccessful) { "下载未完成（${response.code}），请重试" }
                            val body = requireNotNull(response.body)
                            require(body.contentLength() == -1L || body.contentLength() == release.size) { "安装包大小与版本信息不符" }
                            var total = 0L; var lastPercent = -1
                            body.byteStream().use { input -> partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= release.size) { "安装包超过预期大小" }
                                    output.write(buffer, 0, count)
                                    val percent = (total * 100 / release.size).toInt()
                                    if (percent != lastPercent) { mutable.value = mutable.value.copy(progress = percent); lastPercent = percent }
                                }
                            } }
                            require(total == release.size) { "下载不完整，请重试" }
                        }
                        ensureActive()
                        verify(partial, release)
                        check(partial.renameTo(destination)) { "无法保存安装包，请重试" }
                    } finally { partial.delete() }
                }
                mutable.value = UpdateState("ready", release, 100, "下载和签名校验完成，点击安装后由系统确认。")
            } catch (cancelled: CancellationException) {
                mutable.value = UpdateState("available", release, message = "下载已取消，可以重新下载。")
                throw cancelled
            } catch (error: Exception) {
                mutable.value = UpdateState("available", release, message = if (error is java.io.IOException) "下载中断，请重新下载。" else error.message ?: "下载校验失败")
            } finally { activeCall = null }
        }
    }

    fun cancel() { activeCall?.cancel(); operation?.cancel() }

    @Suppress("DEPRECATION")
    private fun verify(file: File, release: AppRelease) {
        require(file.length() == release.size) { "安装包大小校验失败" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包内容校验失败，请重新下载" }
        val pm = context.packageManager
        val archive = requireNotNull(pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)) { "无法识别此安装包" }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName == context.packageName && archive.longVersionCode == release.code && archive.longVersionCode > installed.longVersionCode) { "安装包名称或版本不匹配" }
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        require(signers(installed).isNotEmpty() && signers(archive) == signers(installed)) { "新版签名与当前应用不同，已停止安装，请联系维护者" }
    }

    fun install() {
        if (!BuildConfig.ENABLE_SELF_UPDATE || operation?.isActive == true || mutable.value.phase != "ready") return
        val release = mutable.value.release ?: return
        operation = scope.launch {
            try {
                val file = File(root, release.sha256 + ".apk")
                withContext(Dispatchers.IO) { verify(file, release) }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mutable.value = mutable.value.copy(message = "请允许微光安装更新，返回后再次点击安装。")
                } else {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    mutable.value = mutable.value.copy(message = "请在系统安装界面确认；取消后可再次点击安装。")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(message = error.message ?: "无法打开安装界面，请稍后再试") }
        }
    }
}
