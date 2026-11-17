package cn.weiguang.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** A service-owned overlay: it goes away when the user revokes accessibility access. */
@android.annotation.SuppressLint("ClickableViewAccessibility") // The drag handler explicitly calls performClick on a tap.
internal class CaptureOverlay(
    private val service: AccessibilityService,
    private val onPause: () -> Unit,
    private val onStop: () -> Unit
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val root = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(Color.rgb(18, 45, 39)); cornerRadius = dp(18).toFloat() }
        elevation = dp(8).toFloat()
    }
    private val header = label(14f).apply { typeface = Typeface.DEFAULT_BOLD; text = "微光 · 拖动移动 / 轻点收起" }
    private val body = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val title = label(14f).apply { maxLines = 2 }
    private val status = label(12f).apply { maxLines = 3; setPadding(0, dp(6), 0, dp(4)) }
    private val queue = label(11f)
    private val pause = Button(service).apply { textSize = 12f; contentDescription = "暂停或继续屏幕采集"; setOnClickListener { onPause() } }
    private val stop = Button(service).apply { text = "结束"; textSize = 12f; contentDescription = "结束屏幕采集"; setOnClickListener { onStop() } }
    private val params = WindowManager.LayoutParams(dp(238), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(210); y = dp(100) }
    private var attached = false
    private var collapsed = false

    init {
        root.addView(header)
        body.addView(title); body.addView(status); body.addView(queue)
        val buttons = LinearLayout(service)
        buttons.addView(pause, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addView(buttons); root.addView(body)
        header.contentDescription = "移动或收起采集悬浮窗"
        header.setOnClickListener {
            collapsed = !collapsed
            body.visibility = if (collapsed) View.GONE else View.VISIBLE
            header.text = if (collapsed) "微光 · 轻点展开" else "微光 · 拖动移动 / 轻点收起"
        }
        var downX = 0f; var downY = 0f; var originalX = 0; var originalY = 0; var moved = false
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        header.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; originalX = params.x; originalY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved && attached) {
                        val screen = service.resources.displayMetrics
                        params.x = (originalX + dx.toInt()).coerceIn(0, (screen.widthPixels - root.width).coerceAtLeast(0))
                        params.y = (originalY + dy.toInt()).coerceIn(0, (screen.heightPixels - root.height - dp(32)).coerceAtLeast(0))
                        manager.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) view.performClick()
            }
            true
        }
    }

    fun render(grant: CaptureGrant, detail: String, summary: String, changing: Boolean) {
        title.text = service.getString(cn.weiguang.app.R.string.capture_overlay_work, grant.title)
        status.text = if (grant.paused) "已暂停 · 已保存材料仍可同步" else detail
        queue.text = summary
        pause.text = if (grant.paused) "继续" else "暂停"
        pause.isEnabled = !changing; stop.isEnabled = !changing
        if (!attached) {
            params.x = params.x.coerceAtMost((service.resources.displayMetrics.widthPixels - dp(238)).coerceAtLeast(0))
            manager.addView(root, params); attached = true
        }
    }

    fun hideForScreenshot() { root.visibility = View.INVISIBLE }
    fun restore() { root.visibility = View.VISIBLE }
    fun close() { if (attached) manager.removeView(root); attached = false }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun label(size: Float) = TextView(service).apply { textSize = size; setTextColor(Color.rgb(230, 244, 238)) }
}
