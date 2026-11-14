package cn.weiguang.app.capture

import android.content.Context
import android.graphics.Bitmap
import cn.weiguang.app.WeiguangApp
import cn.weiguang.app.data.LocalEvent
import com.chronocapsule.contract.ScreenContract
import com.chronocapsule.dedup.CrossScreenDeduper
import com.chronocapsule.pipeline.ScreenPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One shared OCR implementation; all networking belongs to the authenticated Repository. */
object ChronoCapture {
    private val lock = Mutex()

    suspend fun recognize(context: Context, bitmap: Bitmap, appPackage: String = "unknown", appName: String = "截图导入") =
        withContext(Dispatchers.IO) {
            val output = ScreenPipeline.process(context, bitmap, "sess-${UUID.randomUUID()}", "evt-${UUID.randomUUID()}", appPackage, appName)
            check(output.problems.isEmpty() && output.payload.blocks.isNotEmpty()) { "没有识别到有效聊天消息，请选择清晰的聊天截图" }
            output.payload
        }

    suspend fun save(context: Context, payload: ScreenContract.Payload, account: String, workId: String? = null,
                     upload: Boolean = false, automatic: Boolean = false, occurredAt: String? = null): LocalEvent? = lock.withLock {
        val repository = (context.applicationContext as WeiguangApp).repository
        if (!automatic) return@withLock repository.saveCapture(payload, account, workId, upload, occurredAt = occurredAt)
        check(workId != null && repository.captureTarget(account) == workId) { "请先为当前事项开启自动记录" }
        val scope = "$account\u0000$workId"
        val name = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        val history = File(context.filesDir, "capture_history/$name.txt")
        withContext(Dispatchers.IO) {
            CrossScreenDeduper.reset()
            CrossScreenDeduper.loadFrom(history)
            val result = CrossScreenDeduper.filter(payload.blocks, payload.appPackage, payload.pageTitle)
            if (result.blocks.isEmpty()) return@withContext null
            val filtered = payload.copy(blocks = ScreenContract.renumber(result.blocks))
            // Commit dedupe only after Room has durably accepted the upload/task outbox.
            val event = repository.saveCapture(filtered, account, workId, upload = true, requireTarget = true)
            history.parentFile?.mkdirs()
            CrossScreenDeduper.saveTo(history)
            event
        }
    }
}
