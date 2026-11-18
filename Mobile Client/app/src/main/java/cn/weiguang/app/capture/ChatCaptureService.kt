package cn.weiguang.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import cn.weiguang.app.BuildConfig
import cn.weiguang.app.WeiguangApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Event-driven OCR, with one frame in flight and a durable account/work authorization. */
@androidx.annotation.RequiresApi(30)
class ChatCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = (applicationContext as WeiguangApp).repository
    private val handler = Handler(Looper.getMainLooper())
    private val schedule = CaptureSchedule()
    private var grant: CaptureGrant? = null
    private var pending: ChatWindow? = null
    private var busy = false
    private var changing = false
    private var generation = 0L
    private val blockedWindows = linkedSetOf<Int>()
    private var settleRetries = 0
    private var status = CaptureStatus(connected = true, detail = "等待微信或QQ聊天页")
    private var queueSummary = ""
    private lateinit var overlay: CaptureOverlay
    private val runPending = Runnable { capturePending() }
    private data class ChatWindow(val id: Int, val pkg: String, val name: String)

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = CaptureOverlay(this, { changeControl(stop = false) }, { changeControl(stop = true) })
        publish("等待微信或QQ聊天页")
        scope.launch {
            combine(repository.captureGrants, repository.events) { target, events -> target to events }.collect { (target, events) ->
                val changed = target != grant
                if (changed) {
                    grant = target; generation++; clearPending(); changing = false
                    status = status.copy(detail = if (target?.paused == true) "已暂停" else "等待微信或QQ聊天页")
                }
                val relevant = events.filter { target != null && it.agentWorkId == target.workId && !it.deleted }
                val waiting = relevant.count { it.syncState in listOf("pending", "failed") }
                val failed = relevant.count { it.syncState == "conflict" || it.agentError != null }
                queueSummary = "待同步 $waiting · 已交给Agent ${relevant.count { it.agentTaskId != null }}" + if (failed > 0) " · 需检查 $failed" else ""
                render()
                if (changed && target != null && !target.paused) requestActive()
            }
        }
        repository.session.value?.let { repository.scheduleSync(it.accountKey) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::overlay.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        val name = appName(pkg) ?: return
        val target = grant ?: return
        if (repository.session.value?.accountKey != target.account) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                generation++
                val className = event.className?.toString().orEmpty()
                if (isBlocked(className)) {
                    blockedWindows.add(event.windowId)
                    if (blockedWindows.size > 32) blockedWindows.remove(blockedWindows.first())
                } else if (className.startsWith("$pkg.") && (className.endsWith("Activity") || className.endsWith("UI"))) {
                    // A real application page transition may clear the classification. Generic
                    // FrameLayout/TextView events must not turn a known browser into a chat.
                    blockedWindows.remove(event.windowId)
                }
                if (event.windowId in blockedWindows) { clearPending(); publish("当前页面不采集"); return }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> Unit
            else -> return
        }
        // Keep page classification current while paused, so resume cannot capture a browser opened meanwhile.
        if (target.paused || changing) return
        if (event.windowId < 0 || event.windowId in blockedWindows) return
        request(ChatWindow(event.windowId, pkg, name))
    }

    private fun appName(pkg: String): String? = when (pkg) {
        "com.tencent.mm" -> "微信"
        "com.tencent.mobileqq" -> "QQ"
        "cn.weiguang.capturefixture" -> if (BuildConfig.DEBUG) "合成聊天测试" else null
        else -> null
    }

    private fun isBlocked(name: String) = listOf("webview", "gallery", "winkhome", "qbasealbum", "dialog",
        "imageviewer", "browser", "brandservice", "flutter", "plugin.").any { name.lowercase().contains(it) }

    /** Check the real foreground window, not a remembered package from an old accessibility event. */
    private fun activeChat(): ChatWindow? {
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val visible = windows
        if (visible.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return null
        val active = visible.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: visible.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive } ?: return null
        if (active.id in blockedWindows) return null
        val root = active.root ?: return null
        val pkg = root.packageName?.toString() ?: return null
        return appName(pkg)?.let { ChatWindow(active.id, pkg, it) }
    }

    private fun requestActive() { activeChat()?.let(::request) }
    private fun request(window: ChatWindow) {
        if (grant == null || grant?.paused == true || changing) return
        settleRetries = 0
        pending = window
        schedule.request(SystemClock.elapsedRealtime())
        enqueue()
    }
    private fun enqueue() {
        handler.removeCallbacks(runPending)
        if (busy) return // Keep the latest pending frame; completion schedules it.
        schedule.delay(SystemClock.elapsedRealtime())?.let { delay -> handler.postDelayed(runPending, delay) }
    }
    private fun clearPending() { pending = null; schedule.cancel(); handler.removeCallbacks(runPending) }

    private fun current(expected: CaptureGrant, epoch: Long) = !changing && grant == expected && !expected.paused &&
        generation == epoch && repository.session.value?.accountKey == expected.account

    private fun capturePending() {
        val target = grant?.takeUnless { it.paused || changing } ?: return
        val frame = pending ?: return
        if (busy) return
        val wait = schedule.delay(SystemClock.elapsedRealtime()) ?: return
        if (wait > 0) { enqueue(); return }
        val active = activeChat()
        if (active != frame) {
            // Old windows can emit a final content event after a new Activity is visible.
            // Resolve the actual foreground again instead of losing the new page's only trigger.
            if (active != null) { clearPending(); request(active) }
            else if (settleRetries++ < 2) {
                // Window roots may lag the transition event. Retry only this settling request.
                schedule.cancel(); schedule.request(SystemClock.elapsedRealtime()); enqueue()
            } else { clearPending(); publish("等待可采集的聊天页，键盘和锁屏不采集") }
            return
        }
        schedule.start(SystemClock.elapsedRealtime()); pending = null; busy = true
        val epoch = generation
        scope.launch {
            var bitmap: Bitmap? = null
            try {
                publish("正在识别屏幕文字")
                // API34+ captures the chat window only. Earlier versions briefly hide our overlay.
                if (Build.VERSION.SDK_INT < 34) { overlay.hideForScreenshot(); delay(100) }
                if (!current(target, epoch) || activeChat() != frame) return@launch
                bitmap = withTimeout(10_000) { screenshot(frame.id) }
                status = status.copy(screenshots = status.screenshots + 1)
                if (!current(target, epoch) || activeChat() != frame) return@launch
                val payload = ChronoCapture.recognize(applicationContext, bitmap, frame.pkg, frame.name)
                if (!current(target, epoch) || activeChat() != frame) return@launch
                val saved = ChronoCapture.save(applicationContext, payload, target.account, target.workId,
                    automatic = true, expectedCaptureToken = target.token)
                status = if (saved == null) status.copy(duplicates = status.duplicates + 1) else status.copy(saved = status.saved + 1)
                publish(if (saved == null) "没有新增消息 · 已去重" else "已存到本机「线索」，请核对后上传")
            } catch (_: TimeoutCancellationException) { if (current(target, epoch)) publish("截屏超时，请检查系统聊天采集权限") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(target, epoch)) publish("本次未采集成功，可检查页面、权限或同步状态") }
            finally {
                bitmap?.recycle()
                overlay.restore(); busy = false; enqueue()
            }
        }
    }

    private suspend fun screenshot(windowId: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        status = status.copy(requests = status.requests + 1)
        CaptureRuntime.publish(status)
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                var wrapped: Bitmap? = null
                val bitmap = try {
                    runCatching {
                        wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                } finally { wrapped?.recycle(); buffer.close() }
                if (bitmap == null) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("截图转换失败"))
                } else if (continuation.isActive) continuation.resume(bitmap) { _, image, _ -> image.recycle() }
                else bitmap.recycle()
            }
            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("截图失败：$errorCode"))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(windowId, mainExecutor, callback)
        else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
    }

    private fun changeControl(stop: Boolean) {
        val target = grant ?: return
        if (changing) return
        changing = true; generation++; clearPending(); render()
        scope.launch {
            try {
                if (stop) repository.setCaptureTarget(null, false, target.token)
                else repository.setCapturePaused(!target.paused, target.token)
                // Stay blocked until the grant collector observes the new authorization.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { changing = false; publish("控制未完成，请在微光中检查事项") }
        }
    }

    private fun publish(detail: String) { status = status.copy(detail = detail); render() }
    private fun render() {
        CaptureRuntime.publish(status)
        if (!::overlay.isInitialized) return
        val target = grant
        if (target == null) overlay.close() else overlay.render(target, status.detail, queueSummary, changing)
    }

    override fun onInterrupt() { generation++; clearPending(); publish("系统中断采集，请检查无障碍权限") }
    override fun onDestroy() {
        generation++; clearPending(); scope.cancel()
        if (::overlay.isInitialized) overlay.close()
        CaptureRuntime.publish(CaptureStatus())
        super.onDestroy()
    }
}
