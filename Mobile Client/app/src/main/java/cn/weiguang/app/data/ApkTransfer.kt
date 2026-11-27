package cn.weiguang.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Called on an IO dispatcher so cancellation also interrupts a stalled socket read. */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T =
    suspendCancellableCoroutine { result ->
        result.invokeOnCancellation { cancel() }
        try {
            result.resume(execute().use(read))
        } catch (error: Exception) {
            result.resumeWithException(error)
        }
    }

internal class ApkTransfer(private val calls: Call.Factory) {
    suspend fun receive(url: String, size: Long, partial: File, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            require(size > 0)
            var offset = partial.length()
            var restarted = false
            if (offset > size) {
                partial.outputStream().close()
                offset = 0
            }
            onProgress(offset)
            while (offset < size) {
                ensureActive()
                val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                if (offset > 0) request.header("Range", "bytes=$offset-")
                val received = calls.newCall(request.build()).readCancellable { response ->
                    // A stale or unusable range may be retried once from zero.
                    if (offset > 0 && response.code == 416) return@readCancellable null
                    if (response.code != 200 && response.code != 206) throw IOException("更新服务暂时无法提供下载。")
                    val ranged = response.code == 206
                    val start = if (ranged) offset else 0L
                    val end = if (ranged) rangeEnd(response.header("Content-Range"), start, size) else size
                    val body = requireNotNull(response.body) { "没有收到安装包，请稍后重试。" }
                    if (end == null || body.contentLength().let { it >= 0 && it != end - start }) {
                        if (ranged && offset > 0) return@readCancellable null
                        throw IllegalArgumentException("安装包大小与更新说明不符，请稍后重新检查更新。")
                    }
                    require(response.header("Content-Encoding", "identity").equals("identity", ignoreCase = true)) {
                        "安装包格式有误，请稍后重新检查更新。"
                    }
                    var written = start
                    body.byteStream().use { input ->
                        FileOutputStream(partial, start > 0).use { output ->
                            onProgress(start)
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (written + count > end) {
                                    output.channel.truncate(start)
                                    throw IllegalArgumentException("收到的安装包过大，请重新检查更新。")
                                }
                                output.write(buffer, 0, count)
                                written += count
                                onProgress(written)
                            }
                        }
                    }
                    if (written < end) throw EOFException("下载中断。")
                    written
                }
                if (received == null) {
                    require(!restarted) { "安装包暂时无法继续下载，请稍后重试。" }
                    restarted = true
                    partial.outputStream().close()
                    offset = 0
                    onProgress(0)
                } else {
                    offset = received
                }
            }
        }

    private fun rangeEnd(header: String?, start: Long, size: Long): Long? {
        val match = contentRange.matchEntire(header.orEmpty()) ?: return null
        val first = match.groupValues[1].toLongOrNull()
        val last = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull()
        return if (first == start && total == size && last in start until size) last + 1 else null
    }

    private companion object {
        val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
    }
}
