package cn.weiguang.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import cn.weiguang.app.WeiguangApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/** Chat-only capture adapted from ChronoCapsule; account/work upload opt-in is checked before saving. */
class ChatCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowGeneration = 0L

    private val repository get() = (applicationContext as WeiguangApp).repository

    @Volatile
    private var lastCaptureAt = 0L

    /** 一张图还没处理完就不再抓新的 */
    @Volatile
    private var busy = false

    /** 每次开始抓图的时刻，看门狗用来判断 busy 卡了多久 */
    @Volatile
    private var busySince = 0L

    /** 最近一分钟内的抓图时刻，用来限制总频率 */
    private val recentCaptures = ArrayDeque<Long>()

    private val handler = Handler(Looper.getMainLooper())

    /** 等待界面稳定后再截的那一屏，只保留最新一次 */
    private var pendingPkg: String? = null
    private var pendingAppName: String? = null

    private val pendingCapture = Runnable { capturePending() }

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository.session.value?.let { repository.scheduleSync(it.accountKey) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (Build.VERSION.SDK_INT < 30) return
        val e = event ?: return
        val rawPkg = e.packageName?.toString() ?: return
        val className = e.className?.toString().orEmpty()
        // 真机上 logcat 常常读不到，先落盘再判断，才能看到事件到底有没有来
        Log.d(TAG, "事件 type=${e.eventType} $rawPkg / $className")

        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val generation = ++windowGeneration

        // 只认聊天 App 自己的窗口事件。输入法弹出时事件包名是输入法，
        // 拿"最近一次聊天 App"去猜键盘底下是谁，实测会把键盘按键当成聊天内容
        // 传上云（整屏 36 条里 35 条是 Gboard 的键），比漏一屏更糟。
        val appName = CHAT_PACKAGES[rawPkg] ?: run { clearPending(); return }
        if (!isChatWindow(className)) {
            Log.d(TAG, "非聊天页面，跳过：$className")
            clearPending()
            return
        }
        Log.d(TAG, "命中聊天窗口：$appName / $className")

        // 不立刻抓：等动画结束、页面渲染完再抓，否则经常截到空白会话页
        scope.launch {
            val account = repository.session.value?.accountKey ?: return@launch
            if (repository.captureTarget(account) == null || windowGeneration != generation) return@launch
            pendingPkg = rawPkg
            pendingAppName = appName
            handler.removeCallbacks(pendingCapture)
            handler.postDelayed(pendingCapture, SETTLE_MS)
        }
    }

    /**
     * 同一个 App 里不全是聊天：微信的网页、图片查看器，QQ 的相册/空间/支付页
     * 都会发同样的窗口事件。这些页面抓下来会把网页内容当成聊天消息上传，
     * 真机实测一屏相册能出 60 个垃圾块。按类名挡掉，宁可少抓一屏。
     */
    private fun isChatWindow(className: String): Boolean {
        if (className.isEmpty()) return true
        val lower = className.lowercase()
        return BLOCKED_WINDOW_CLASSES.none { lower.contains(it) }
    }

    /** 界面已稳定，真正执行截图前的最后一轮检查 */
    private fun capturePending() {
        val pkg = pendingPkg ?: return
        val appName = pendingAppName ?: return
        val now = SystemClock.elapsedRealtime()

        if (busy) {
            // 上一张还在流水线里。不要丢：pending 还留着，process 结束时会补抓
            if (now - busySince < BUSY_TIMEOUT_MS) {
                Log.d(TAG, "上一张还在处理，稍后补抓")
                return
            }
            Log.d(TAG, "上一张处理超过 ${BUSY_TIMEOUT_MS / 1000} 秒未结束，强制复位")
            busy = false
        }
        if (!screenReady()) {
            Log.d(TAG, "息屏或锁屏，跳过")
            clearPending()
            return
        }
        if (now - lastCaptureAt < MIN_INTERVAL_MS) {
            // 还没冷却完，按剩余时间再排一次（期间有新事件会覆盖成更新的那一屏）
            handler.removeCallbacks(pendingCapture)
            handler.postDelayed(pendingCapture, MIN_INTERVAL_MS - (now - lastCaptureAt))
            return
        }
        if (!allowByRateLimit(now)) {
            clearPending()
            return
        }

        clearPending()
        lastCaptureAt = now
        Log.d(TAG, "开始截图：$appName")
        capture(pkg, appName)
    }

    private fun clearPending() {
        pendingPkg = null
        pendingAppName = null
        handler.removeCallbacks(pendingCapture)
    }

    /** 息屏、锁屏时不抓：拿到的是黑屏或锁屏页，没有结构化价值，白耗电 */
    private fun screenReady(): Boolean {
        if (powerManager?.isInteractive != true) return false
        return keyguard?.isKeyguardLocked != true
    }

    /** 滑动窗口限流：一分钟最多 MAX_CAPTURES_PER_MINUTE 张 */
    private fun allowByRateLimit(now: Long): Boolean {
        while (recentCaptures.isNotEmpty() && now - recentCaptures.first() >= RATE_WINDOW_MS) {
            recentCaptures.removeFirst()
        }
        if (recentCaptures.size >= MAX_CAPTURES_PER_MINUTE) {
            Log.w(TAG, "一分钟内已抓 ${recentCaptures.size} 张，丢弃本次事件")
            return false
        }
        recentCaptures.addLast(now)
        return true
    }

    @android.annotation.TargetApi(30)
    private fun capture(pkg: String, appName: String) {
        val account = repository.session.value?.accountKey ?: return
        val generation = windowGeneration
        busy = true
        busySince = SystemClock.elapsedRealtime()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    // wrapHardwareBuffer 得到的是 HARDWARE 位图，必须 copy 成软件位图才能读像素
                    val software = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    buffer.close()

                    if (software == null) {
                        Log.d(TAG, "截图转换失败")
                        busy = false
                        return
                    }
                    if (windowGeneration != generation || repository.session.value?.accountKey != account) {
                        software.recycle()
                        busy = false
                        return
                    }
                    Log.d(TAG, "截图成功 ${software.width}×${software.height}")
                    Thread { process(software, pkg, appName, account) }.start()
                }

                override fun onFailure(errorCode: Int) {
                    // 1 = 服务没被授予截图能力；2 = 被系统限流
                    Log.d(TAG, "截图失败 code=$errorCode")
                    busy = false
                }
            }
        )
    }

    private fun process(bitmap: Bitmap, pkg: String, appName: String, account: String) {
        try {
            runBlocking {
                val target = repository.captureTarget(account) ?: return@runBlocking
                val payload = ChronoCapture.recognize(applicationContext, bitmap, pkg, appName)
                ChronoCapture.save(applicationContext, payload, account, target, automatic = true)
            }
        } catch (_: Exception) {
            // No raw chat text in logs. Persisted evidence is retried by the authenticated outbox.
            Log.w(TAG, "聊天采集未完成，请在事项页面检查授权和同步状态")
        } finally {
            bitmap.recycle()
            busy = false
            handler.post { if (pendingPkg != null) handler.post(pendingCapture) }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        Log.w(TAG, "无障碍服务已断开")
        clearPending()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChatCapture"

        /** 只在这些应用里自动记录。加新应用只需要往这张表里加一行 */
        private val CHAT_PACKAGES = mapOf(
            "com.tencent.mm" to "微信",
            "com.tencent.mobileqq" to "QQ"
        )

        /** 窗口事件后等这么久再抓，避开切换动画，保证拿到渲染完的页面 */
        private const val SETTLE_MS = 350L

        /**
         * 类名里带这些片段的窗口不是聊天页：
         * webview 网页、gallery 图片查看、相册、系统对话框、QQ 空间/插件页。
         * 小写比较，微信/QQ 的类名大小写不统一。
         */
        private val BLOCKED_WINDOW_CLASSES = listOf(
            "webview", "gallery", "winkhome", "qbasealbum",
            "dialog", "imageviewer", "browser",
            // 公众号文章是 Flutter 页面，类名里没有 webview
            "brandservice", "flutter", "plugin."
        )

        /**
         * 两次采集的最小间隔。系统建屏硬限 1 秒 1 张，这里留 1.5 倍余量；
         * 单次流水线本身就要 0.3~1 秒，再密也处理不过来。
         */
        private const val MIN_INTERVAL_MS = 1_500L

        /** 一分钟内最多抓几张，防止微信高频弹窗把 CPU 吃满、发热耗电 */
        private const val MAX_CAPTURES_PER_MINUTE = 20
        private const val RATE_WINDOW_MS = 60_000L

        /** busy 超过这个时长仍未复位，判定为卡死，强制放行下一次抓图 */
        private const val BUSY_TIMEOUT_MS = 20_000L
    }
}
