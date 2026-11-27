package cn.weiguang.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.weiguang.app.capture.ScreenshotImportException
import cn.weiguang.app.capture.decodeScreenshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScreenshotDecoderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aRotatedJpegIsUprightForTheTextDetector() {
        withImage(240, 400) { file ->
            val metadata = ExifInterface(file.absolutePath)
            metadata.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            metadata.saveAttributes()
            val decoded = decodeScreenshot(context, Uri.fromFile(file))
            try {
                assertEquals(400, decoded.width)
                assertEquals(240, decoded.height)
                assertNotEquals(Bitmap.Config.HARDWARE, decoded.config)
                // The red top half of the camera original is on the right after a clockwise turn.
                assertTrue(Color.red(decoded.getPixel(350, 120)) > 240)
                assertTrue(Color.red(decoded.getPixel(50, 120)) < 20)
            } finally { decoded.recycle() }
        }
    }

    @Test fun largePhotosKeepTheirShapeAndLongUnreadableStripsGiveCropGuidance() {
        withImage(3200, 2400) { file ->
            val decoded = decodeScreenshot(context, Uri.fromFile(file))
            try {
                assertTrue(decoded.width.toLong() * decoded.height <= 5_000_000)
                assertTrue(decoded.width > 2400)
                assertEquals(4.0 / 3, decoded.width.toDouble() / decoded.height, 0.002)
            } finally { decoded.recycle() }
        }
        withImage(300, 12000) { file ->
            val error = runCatching { decodeScreenshot(context, Uri.fromFile(file)).recycle() }.exceptionOrNull()
            assertTrue(error.toString(), error is ScreenshotImportException && error.message.orEmpty().contains("裁成几段"))
        }
    }

    private fun withImage(width: Int, height: Int, block: (File) -> Unit) {
        val file = File.createTempFile("screenmind-photo-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.BLACK)
                    drawRect(0f, 0f, width.toFloat(), height / 2f, Paint().apply { color = Color.RED })
                }
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally { bitmap.recycle() }
            block(file)
        } finally { file.delete() }
    }
}
