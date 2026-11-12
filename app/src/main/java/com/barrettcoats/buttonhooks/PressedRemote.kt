package com.barrettcoats.buttonhooks

import android.content.Context
import android.util.Log
import android.view.InputDevice

/**
 * The Bluetooth address of the remote whose button was just pressed, so TV power can fire from
 * the remote in your hand rather than one lying elsewhere.
 *
 * The key that wakes the Shield never reaches apps (not even an accessibility service), and
 * Android hides which Bluetooth device an input device is (`InputDevice.getBluetoothAddress()`
 * is blocked for apps). Both show up in `dumpsys input`, though: the input dispatcher's
 * RecentQueue lists its last few events with their deviceId (the wake key included, even
 * though it was dropped), and the EventHub device list pairs each device's descriptor with its
 * `bluetoothAddress`. This needs the DUMP permission, like [SleepReason].
 */
object PressedRemote {
    private const val TAG = "ButtonHooks"

    /** Only a press this recent counts as the one that slept or woke the Shield. */
    private const val MAX_AGE_MS = 5000L

    private val KEY_EVENT = Regex("""KeyEvent\(deviceId=(-?\d+),.*action=DOWN,.*age=(\d+)ms""")
    private val DESCRIPTOR = Regex("""^\s*Descriptor: (\S+)""")
    private val ADDRESS = Regex("""bluetoothAddress=([0-9A-Fa-f:]{17})""")

    /** The pressing remote's address (upper case), or null if unknown. Blocks; call off the main thread. */
    fun address(context: Context): String? {
        if (!SleepReason.hasPermission(context)) return null
        return try {
            val proc = ProcessBuilder("dumpsys", "input").redirectErrorStream(true).start()
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.destroy()

            // Descriptor → Bluetooth address, from the EventHub device list.
            val addresses = HashMap<String, String>()
            var descriptor: String? = null
            for (line in lines) {
                DESCRIPTOR.find(line)?.let { descriptor = it.groupValues[1] }
                ADDRESS.find(line)?.let { m -> descriptor?.let { addresses[it] = m.groupValues[1].uppercase() } }
            }

            // RecentQueue runs oldest to newest: take the newest real key press that's still fresh.
            val queue = lines.dropWhile { !it.trim().startsWith("RecentQueue:") }.drop(1)
                .takeWhile { it.startsWith("    ") }
            val deviceId = queue.mapNotNull { KEY_EVENT.find(it) }
                .lastOrNull { it.groupValues[1].toInt() >= 0 && it.groupValues[2].toLong() <= MAX_AGE_MS }
                ?.groupValues?.get(1)?.toInt()
                ?: return null
            val address = InputDevice.getDevice(deviceId)?.descriptor?.let { addresses[it] }
            Log.d(TAG, "Pressed remote: input device $deviceId -> ${address ?: "no Bluetooth address"}")
            address
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read which remote was pressed", e)
            null
        }
    }
}
