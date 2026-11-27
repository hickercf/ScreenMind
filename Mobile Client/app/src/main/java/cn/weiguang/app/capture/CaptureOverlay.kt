package cn.weiguang.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import cn.weiguang.app.R
import kotlin.math.abs

/** A service-owned overlay: it goes away when the user revokes accessibility access. */
@android.annotation.SuppressLint("ClickableViewAccessibility") // The drag handler explicitly calls performClick on a tap.
internal class CaptureOverlay(
    private val service: AccessibilityService,
    private val onPause: () -> Unit,
    private val onStop: () -> Unit
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val rounded = ResourcesCompat.getFont(service, R.font.screenmind_rounded_regular)
    private val roundedBold = ResourcesCompat.getFont(service, R.font.screenmind_rounded_bold)
    private val root = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
        background = roundedBackground(Color.rgb(23, 36, 55), 20, strokeColor = Color.rgb(52, 71, 97))
        elevation = dp(10).toFloat()
    }
    private val header = label(14f).apply { typeface = roundedBold ?: Typeface.DEFAULT_BOLD; text = "ScreenMind · 拖动 / 点按收起" }
    private val body = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val title = label(14f).apply { maxLines = 2 }
    private val status = label(12f).apply { maxLines = 3; setPadding(0, dp(6), 0, dp(4)) }
    private val queue = label(11f)
    private val pause = action("暂停或继续屏幕采集", onPause)
    private val stop = action("结束屏幕采集", onStop).apply { text = "结束" }
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
        buttons.setPadding(0, dp(6), 0, 0)
        buttons.addView(pause, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        buttons.addView(stop, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(buttons); root.addView(body)
        header.contentDescription = "移动或收起采集悬浮窗"
        header.setOnClickListener {
            collapsed = !collapsed
            body.visibility = if (collapsed) View.GONE else View.VISIBLE
            header.text = if (collapsed) "ScreenMind · 点按展开" else "ScreenMind · 拖动 / 点按收起"
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
        listOf(pause, stop).forEach { button ->
            button.isEnabled = !changing
            button.alpha = if (changing) .6f else 1f
        }
        if (!attached) {
            params.x = params.x.coerceAtMost((service.resources.displayMetrics.widthPixels - dp(238)).coerceAtLeast(0))
            manager.addView(root, params); attached = true
        }
    }

    fun hideForScreenshot() { root.visibility = View.INVISIBLE }
    fun restore() { root.visibility = View.VISIBLE }
    fun close() { if (attached) manager.removeView(root); attached = false }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun roundedBackground(color: Int, radius: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }
    private fun label(size: Float) = TextView(service).apply {
        textSize = size
        typeface = rounded
        setTextColor(Color.rgb(232, 239, 251))
    }
    private fun action(description: String, onClick: () -> Unit) = Button(service).apply {
        textSize = 12f
        typeface = roundedBold
        setTextColor(Color.rgb(232, 239, 251))
        setPadding(0, 0, 0, 0)
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(Color.rgb(169, 198, 255)),
            roundedBackground(Color.rgb(33, 62, 112), 14), null)
        contentDescription = description
        setOnClickListener { onClick() }
    }
}
