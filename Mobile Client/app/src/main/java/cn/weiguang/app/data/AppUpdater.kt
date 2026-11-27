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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
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
    private val transfer = ApkTransfer(client)
    private val root get() = File(context.filesDir, "updates").apply { mkdirs() }
    private var operation: Job? = null
    private val busy get() = operation?.isCompleted == false
    private var selectedEndpoint: String? = null
    private val checkedAt = mutableMapOf<String, Long>()

    fun check(rawEndpoint: String, manual: Boolean = true) {
        if (!BuildConfig.ENABLE_SELF_UPDATE) return
        val updateEndpoint = CloudEndpoint.selected(rawEndpoint)
        val changed = selectedEndpoint != updateEndpoint
        val recentlyChecked = System.currentTimeMillis() - (checkedAt[updateEndpoint] ?: 0) < TimeUnit.HOURS.toMillis(4)
        if (!changed && (busy || !manual && recentlyChecked)) return
        if (changed) {
            operation?.cancel()
            selectedEndpoint = updateEndpoint
            mutable.value = UpdateState()
        }
        val previous = mutable.value
        operation = scope.launch {
            mutable.value = previous.copy(phase = "checking", message = "正在检查更新…")
            try {
                val endpoint = ApiClient.validateEndpoint(updateEndpoint)
                val release = withContext(Dispatchers.IO) {
                    val call = client.newCall(Request.Builder().url("$endpoint/v1/app/update?channel=${BuildConfig.BUILD_TYPE}&version_code=${BuildConfig.VERSION_CODE}").build())
                    call.readCancellable { response ->
                        if (response.code == 204) return@readCancellable null
                        check(response.isSuccessful) { "更新服务暂时无法使用，请稍后重试。" }
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
                        AppRelease.parse(JSONObject(String(bytes, Charsets.UTF_8)), endpoint, BuildConfig.BUILD_TYPE, BuildConfig.ALLOW_LOCAL_HTTP).also {
                            require(CloudEndpoint.acceptsDownload(it.url)) { "安装包地址与 ScreenMind 云端不一致" }
                        }
                    }
                }
                val available = release?.takeIf { it.code > BuildConfig.VERSION_CODE }
                if (available != null) require(Build.VERSION.SDK_INT >= available.minSdk) { "新版需要更高版本的 Android 系统" }
                val result = withContext(Dispatchers.IO) {
                    cacheLock.withLock {
                        prune(available)
                        if (available == null) UpdateState(phase = "current", message = "暂无可用更新。")
                        else cachedState(available)
                    }
                }
                checkedAt[updateEndpoint] = System.currentTimeMillis()
                mutable.value = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                mutable.value = previous.copy(message = if (error is IOException) "暂时无法连接更新服务，请检查网络后重试。" else error.message ?: "这次没有查到更新，请稍后重试。")
            }
        }
    }

    private fun cachedState(release: AppRelease): UpdateState {
        val file = File(root, release.sha256 + ".apk")
        if (file.isFile) {
            try {
                verify(file, release)
                return UpdateState("ready", release, 100, "新版已下载，可以安装。")
            } catch (_: IllegalArgumentException) {
                file.delete()
            }
        }
        val partial = File(root, release.sha256 + ".part")
        if (partial.length() > release.size) partial.delete()
        val bytes = partial.length()
        return pending(release, bytes, if (bytes > 0) "已保留上次的下载进度。" else "发现新版本 ${release.name}。")
    }

    private fun pending(release: AppRelease, bytes: Long, message: String) =
        UpdateState(if (bytes > 0) "paused" else "available", release, (bytes.coerceIn(0, release.size) * 100 / release.size).toInt(), message)

    private fun prune(release: AppRelease?) {
        // A development build can alternate between servers with different releases.
        val expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
        root.listFiles()?.filter { file ->
            file.isFile && cacheName.matches(file.name) && file.nameWithoutExtension != release?.sha256 &&
                (BuildConfig.LOCK_CLOUD_ENDPOINT || file.lastModified() < expiry)
        }?.forEach { it.delete() }
    }

    fun download() {
        if (!BuildConfig.ENABLE_SELF_UPDATE || busy || mutable.value.phase !in setOf("available", "paused")) return
        val release = mutable.value.release ?: return
        operation = scope.launch {
            mutable.value = mutable.value.copy(phase = "downloading", message = "正在下载，暂停后可以接着下载。")
            val partial = File(root, release.sha256 + ".part")
            try {
                withContext(Dispatchers.IO) {
                    cacheLock.withLock {
                        var lastPercent = -1
                        transfer.receive(release.url, release.size, partial) { bytes ->
                            val percent = (bytes * 100 / release.size).toInt()
                            if (percent != lastPercent) {
                                ensureActive()
                                mutable.update { state ->
                                    if (state.release == release && state.phase == "downloading") state.copy(progress = percent) else state
                                }
                                lastPercent = percent
                            }
                        }
                        ensureActive()
                        mutable.update { state ->
                            if (state.release == release && state.phase == "downloading") {
                                UpdateState("verifying", release, 100, "下载完成，正在核对安装包。")
                            } else state
                        }
                        try {
                            verify(partial, release)
                        } catch (invalid: IllegalArgumentException) {
                            partial.delete()
                            throw invalid
                        }
                        ensureActive()
                        check(partial.renameTo(File(root, release.sha256 + ".apk"))) { "无法保存安装包，请检查手机的剩余空间。" }
                    }
                }
                mutable.value = UpdateState("ready", release, 100, "新版已下载，可以安装。")
            } catch (cancelled: CancellationException) {
                if (cancelled is DownloadPaused && selectedEndpoint == release.endpoint) {
                    mutable.value = pending(release, partial.length(), "下载已暂停。")
                }
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                val bytes = partial.length()
                val message = when {
                    error !is IOException -> error.message ?: "安装包校验失败，请重新下载。"
                    bytes > 0 -> "下载中断，已保留进度。恢复网络后可以继续。"
                    else -> "下载没有开始，请检查网络和剩余空间后重试。"
                }
                mutable.value = pending(release, bytes, message)
            }
        }
    }

    fun pause() {
        if (mutable.value.phase == "downloading") operation?.cancel(DownloadPaused())
    }

    private class DownloadPaused : CancellationException()

    private companion object {
        val cacheLock = Mutex()
        val cacheName = Regex("""[a-f0-9]{64}\.(apk|part)""")
    }

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
        if (!BuildConfig.ENABLE_SELF_UPDATE || busy || mutable.value.phase != "ready") return
        val release = mutable.value.release ?: return
        operation = scope.launch {
            try {
                val file = File(root, release.sha256 + ".apk")
                val valid = withContext(Dispatchers.IO) {
                    cacheLock.withLock {
                        if (!file.isFile) return@withLock false
                        try {
                            verify(file, release)
                            true
                        } catch (_: IllegalArgumentException) {
                            file.delete()
                            false
                        }
                    }
                }
                if (!valid) {
                    mutable.value = pending(release, 0, "安装包已失效，请重新下载。")
                    return@launch
                }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mutable.value = mutable.value.copy(message = "请允许 ScreenMind 安装更新，返回后再次点击安装。")
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
