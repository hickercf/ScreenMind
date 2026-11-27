package cn.weiguang.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor

internal fun keyboardOnScreen(device: UiAutomation): Boolean? {
    val dump = ParcelFileDescriptor.AutoCloseInputStream(device.executeShellCommand("dumpsys window windows"))
    val keyboard = dump.bufferedReader().use { it.readText() }.lineSequence().map { it.trim() }
        .dropWhile { !it.startsWith("Window #") || !it.endsWith(" InputMethod}:") }
        .drop(1).takeWhile { !it.startsWith("Window #") }
    return keyboard.firstOrNull { it.startsWith("isOnScreen=") }?.substringAfter('=')?.toBooleanStrictOrNull()
}
