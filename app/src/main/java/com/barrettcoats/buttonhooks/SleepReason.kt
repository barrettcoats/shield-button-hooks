package com.barrettcoats.buttonhooks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Why the Shield last went to sleep, as PowerManagerService records it (`mLastSleepReason` in
 * `dumpsys power`): "power_button" for the remote's power button, "timeout" for the sleep
 * timer, "hdmi" when HDMI-CEC put it to sleep (another device took over the TV, or the TV went
 * off). No public API exposes it, so this reads `dumpsys power`, which needs the DUMP
 * permission. An ordinary app can't get DUMP from a dialog, but adb can grant it:
 *
 *     adb shell pm grant com.barrettcoats.buttonhooks android.permission.DUMP
 */
object SleepReason {
    private const val TAG = "ButtonHooks"
    const val POWER_BUTTON = "power_button"

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** The last sleep reason, or null if it can't be read. Blocks briefly; call off the main thread. */
    fun last(context: Context): String? {
        if (!hasPermission(context)) return null
        return try {
            val proc = ProcessBuilder("dumpsys", "power").redirectErrorStream(true).start()
            val reason = proc.inputStream.bufferedReader().useLines { lines ->
                lines.take(200).firstNotNullOfOrNull { line ->
                    line.trim().takeIf { it.startsWith("mLastSleepReason=") }?.substringAfter('=')
                }
            }
            proc.destroy()
            reason
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the sleep reason", e)
            null
        }
    }
}
