package cn.weiguang.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.IOException
import kotlin.math.sqrt

internal class ScreenshotImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

// Cap the decoded bitmap and the long side passed to the OCR detector on memory-limited phones.
private const val MaxPixels = 5_000_000
private const val MaxSide = 4096
private const val ReadableShortSide = 480

internal fun decodeScreenshot(context: Context, uri: Uri): Bitmap {
    try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1.0, MaxSide.toDouble() / maxOf(width, height),
                sqrt(MaxPixels.toDouble() / (width.toDouble() * height)))
            if (scale < 1.0) {
                val targetWidth = (width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (height * scale).toInt().coerceAtLeast(1)
                val readable = minOf(ReadableShortSide, (minOf(width, height) * 0.65).toInt().coerceAtLeast(1))
                if (minOf(targetWidth, targetHeight) < readable) {
                    throw ScreenshotImportException("这张图片太长，缩小后文字会看不清。请裁成几段后分别导入。")
                }
                decoder.setTargetSize(targetWidth, targetHeight)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    } catch (error: ImageDecoder.DecodeException) {
        throw ScreenshotImportException("这张图片不完整或无法读取，请从相册重新选择。", error)
    } catch (error: IOException) {
        throw ScreenshotImportException("暂时读不到这张图片。可以重试，或回到相册重新分享。", error)
    } catch (error: SecurityException) {
        throw ScreenshotImportException("这张图片的读取权限已失效，请从相册重新分享。", error)
    }
}
