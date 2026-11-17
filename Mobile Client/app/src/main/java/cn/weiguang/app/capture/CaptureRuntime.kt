package cn.weiguang.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureGrant(val account: String, val workId: String, val title: String, val token: String, val paused: Boolean)

data class CaptureStatus(
    val connected: Boolean = false,
    val detail: String = "请开启系统聊天采集权限",
    val requests: Int = 0,
    val screenshots: Int = 0,
    val saved: Int = 0,
    val duplicates: Int = 0
)

/** Process-local service status; never contains chat text or login credentials. */
object CaptureRuntime {
    private val mutable = MutableStateFlow(CaptureStatus())
    val status = mutable.asStateFlow()
    internal fun publish(value: CaptureStatus) { mutable.value = value }
}
