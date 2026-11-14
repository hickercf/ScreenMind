package cn.weiguang.app.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.weiguang.app.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CalendarEntry(val title: String, val text: String, val startsAt: String)

class CaptureService(private val context: Context) {
    suspend fun structured(uri: Uri): com.chronocapsule.contract.ScreenContract.Payload = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(android.graphics.BitmapFactory.decodeStream(requireNotNull(input))) { "无法打开图片" }
        }
        try { ChronoCapture.recognize(context, bitmap) } finally { bitmap.recycle() }
    }
    suspend fun recognize(uri: Uri): String = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val image = InputImage.fromFilePath(context, uri)
            val text = recognizer.process(image).await().text
            check(text.isNotBlank()) { "没有识别到文字，请选择清晰的截图" }
            check(text.length <= 10000) { "截图文字过多，请裁剪后分次导入" }
            text
        } finally { recognizer.close() }
    }

    suspend fun calendar(): List<CalendarEntry> = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) { "需要日历读取权限" }
        val start = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, start + 7L * 86400000)
        val columns = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION)
        val result = mutableListOf<CalendarEntry>()
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        context.contentResolver.query(builder.build(), columns, null, null, "begin ASC")?.use { cursor ->
            while (cursor.moveToNext() && result.size < 30) {
                val title = cursor.getString(0) ?: "未命名日程"
                val from = Instant.ofEpochMilli(cursor.getLong(1))
                val to = Instant.ofEpochMilli(cursor.getLong(2))
                val location = cursor.getString(3).orEmpty()
                result += CalendarEntry(title, "$title\n${formatter.format(from)} — ${formatter.format(to)}\n$location".trim(), from.toString())
            }
        }
        result
    }

    @SuppressLint("MissingPermission")
    suspend fun location(): Location = withTimeout(20000) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "需要大致位置权限" }
        val manager = context.getSystemService(LocationManager::class.java)
        check(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { "请打开系统定位与网络定位，或手动添加位置" }
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = CancellationSignal()
                manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancel, context.mainExecutor) { location ->
                    if (continuation.isActive) {
                        if (location != null) continuation.resume(location)
                        else continuation.resumeWithException(IllegalStateException("暂时未取得位置，请稍后再试"))
                    }
                }
                continuation.invokeOnCancellation { cancel.cancel() }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }
                    @Deprecated("Legacy platform callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("定位已关闭"))
                    }
                }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            }
        }
    }

    fun notifyTest() {
        if (Build.VERSION.SDK_INT >= 33) check(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "请先允许通知" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("weiguang-test", "设备联调提醒", NotificationManager.IMPORTANCE_DEFAULT))
        check(manager.areNotificationsEnabled()) { "系统通知已关闭，请在系统设置中开启" }
        manager.notify(41, NotificationCompat.Builder(context, "weiguang-test")
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("微光 · 通知测试")
            .setContentText("如已开启穿戴应用的通知转发，请检查腕上是否收到这条提醒。")
            .setStyle(NotificationCompat.BigTextStyle().bigText("如已开启穿戴应用的通知转发，请检查腕上是否收到这条提醒。此测试不表示已接入健康数据。"))
            .setAutoCancel(true).build())
    }
}
