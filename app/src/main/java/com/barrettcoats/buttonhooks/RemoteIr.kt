package com.barrettcoats.buttonhooks

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.UUID

/**
 * Makes the Shield Remote (2019) fire an IR code it already has stored.
 *
 * The remote has a vendor GATT service (`00001000-b89b-…`). Frames are 18 bytes,
 * `[command, transactionId, slot, offset, data…]`, written to `…2001`, and the remote replies by
 * notification on `…2000`. Command 44 is "emit": data[0] is the slot to fire. On NVIDIA's firmware
 * the Shield sends this when it sleeps or wakes; LineageOS has nothing that does.
 *
 * Emitting doesn't change what's stored on the remote.
 */
@SuppressLint("MissingPermission")
object RemoteIr {
    private const val TAG = "ButtonHooks"
    private val SERVICE = UUID.fromString("00001000-b89b-11e5-9912-ba0be0483c18")
    private val OUT = UUID.fromString("00002001-b89b-11e5-9912-ba0be0483c18")
    private val RSP = UUID.fromString("00002000-b89b-11e5-9912-ba0be0483c18")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val CMD_EMIT: Byte = 44
    private const val TIMEOUT_MS = 5000L

    /** Name fallback; Android usually doesn't cache vendor services in a device's UUID list. */
    private const val NAME_HINT = "SHIELD Remote"

    /** Addresses already confirmed to have the IR service. */
    private const val PREFS = "remote_ir"
    private const val VERIFIED = "verified"

    /** Most unknown LE devices to probe for the service when nothing matches by name. */
    private const val MAX_PROBES = 4

    const val SLOT_POWER_OFF = 0
    const val SLOT_POWER_TOGGLE = 1
    const val SLOT_INPUT_SCROLL = 4
    const val SLOT_POWER_ON = 5
    const val SLOT_MUTE = 11
    const val SLOT_INPUT_NEXT = 13
    const val SLOT_INPUT_SCROLL_UP = 14

    /** Gap between repeats; NVIDIA spaces its power emits by about 300 ms. */
    private const val REPEAT_GAP_MS = 300L

    private val main = Handler(Looper.getMainLooper())
    private var tid: Byte = 0

    /** Ends the emit in progress, so a quick sleep-then-wake doesn't interleave two sessions. */
    private var cancelCurrent: (() -> Unit)? = null

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun verified(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(VERIFIED, emptySet()) ?: emptySet()

    private fun markVerified(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = verified(context)
        if (address !in set) prefs.edit().putStringSet(VERIFIED, set + address).apply()
    }

    private fun bondedLe(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }.sortedBy { it.address }
    }

    private fun matchReason(context: Context, device: BluetoothDevice): String? = when {
        device.address in verified(context) -> "seen before"
        device.uuids?.any { it.uuid == SERVICE } == true -> "IR service"
        device.name?.contains(NAME_HINT, ignoreCase = true) == true -> "name"
        else -> null
    }

    /**
     * Paired remotes known or likely to have NVIDIA's IR service, sorted by address so the order
     * is stable: confirmed before, listed with the service by Android, or named like a Shield
     * remote. Connecting checks for the service anyway.
     */
    fun pairedRemotes(context: Context): List<BluetoothDevice> =
        bondedLe(context).filter { matchReason(context, it) != null }

    /** Short label for a remote, e.g. "remote …C0:B9". */
    fun label(device: BluetoothDevice) = "remote …" + device.address.takeLast(5)

    /**
     * Fires [slot] on one paired Shield remote: [preferred] (a Bluetooth address) if it's paired,
     * otherwise a connected one. Only ever one remote, because firing a toggle from two would
     * cancel out. If no paired device looks like a Shield remote, up to [MAX_PROBES] other paired
     * LE devices are checked for the IR service, one at a time, and the first one that has it is
     * used and remembered.
     *
     * [times] > 1 repeats it, as NVIDIA does for discrete on/off codes (harmless to repeat, and
     * it covers a missed IR flash). [onResult] gets a short status on the main thread.
     */
    fun emit(
        context: Context,
        slot: Int,
        preferred: String? = null,
        times: Int = 1,
        onResult: (String) -> Unit = {},
    ) {
        cancelCurrent?.invoke()
        val app = context.applicationContext
        if (!hasPermission(app)) return onResult("no Bluetooth permission")

        val connected = app.getSystemService(BluetoothManager::class.java)
            .getConnectedDevices(BluetoothProfile.GATT).map { it.address }.toSet()
        val remotes = pairedRemotes(app)
        val candidates = if (remotes.isNotEmpty()) {
            // A known remote: use exactly one, never fall through to a second.
            listOf(
                remotes.firstOrNull { it.address == preferred }
                    ?: remotes.firstOrNull { it.address in connected }
                    ?: remotes.first()
            )
        } else {
            bondedLe(app).sortedByDescending { it.address in connected }.take(MAX_PROBES)
        }
        if (candidates.isEmpty()) return onResult("Shield remote not paired")

        // Keep the CPU up long enough to finish, as this also runs when the Shield is going to sleep.
        val wake = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ButtonHooks:remoteIr")
            .apply { acquire(TIMEOUT_MS * candidates.size + 1000) }

        var cancelled = false
        cancelCurrent = { cancelled = true }

        fun tryNext(index: Int) {
            if (cancelled || index >= candidates.size) {
                if (wake.isHeld) wake.release()
                if (!cancelled) {
                    Log.i(TAG, "IR emit slot $slot: no paired device has the Shield remote's IR service")
                    onResult("no Shield remote found")
                }
                return
            }
            val device = candidates[index]
            Log.d(TAG, "Trying ${label(device)} (${matchReason(app, device) ?: "probing"})")
            val session = Session(app, device, slot, times) { result, hasService ->
                if (hasService) {
                    markVerified(app, device.address)
                    if (wake.isHeld) wake.release()
                    Log.i(TAG, "IR emit slot $slot x$times via ${label(device)}: $result")
                    onResult(result)
                } else {
                    tryNext(index + 1)
                }
            }
            cancelCurrent = { cancelled = true; session.finish("superseded", true) }
            session.start()
        }
        tryNext(0)
    }

    /** One GATT connection to one device. [done] gets the result and whether the IR service was there. */
    private class Session(
        val app: Context,
        val device: BluetoothDevice,
        val slot: Int,
        val times: Int,
        val done: (String, Boolean) -> Unit,
    ) : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private var out: BluetoothGattCharacteristic? = null
        private var finished = false
        private var sent = 0
        private val token = Any()
        private val frame = ByteArray(18).also {
            it[0] = CMD_EMIT
            it[4] = slot.toByte()
        }

        fun start() {
            main.postDelayed({ finish("timed out", true) }, token, TIMEOUT_MS)
            gatt = device.connectGatt(app, false, this, BluetoothDevice.TRANSPORT_LE)
        }

        fun finish(result: String, hasService: Boolean) {
            main.post {
                if (finished) return@post
                finished = true
                main.removeCallbacksAndMessages(token)
                gatt?.close()
                done(result, hasService)
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> finish("disconnected ($status)", out != null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            out = service?.getCharacteristic(OUT)
            val rsp = service?.getCharacteristic(RSP)
            if (out == null || rsp == null) return finish("no IR service", false)
            g.setCharacteristicNotification(rsp, true)
            val cccd = rsp.getDescriptor(CCCD)
            if (cccd != null) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) else send(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = send(g)

        private fun send(g: BluetoothGatt) {
            tid = (tid + 1).toByte()
            frame[1] = tid
            sent++
            g.writeCharacteristic(out!!, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.size < 2 || value[0] != CMD_EMIT || value[1] != frame[1]) return
            val status = if (value.size >= 4) (value[2].toInt() and 0xff) or ((value[3].toInt() and 0xff) shl 8) else -1
            when {
                status != 0 -> finish("remote error $status", true)
                sent < times -> main.postDelayed({ if (!finished) send(g) }, token, REPEAT_GAP_MS)
                else -> finish("ok", true)
            }
        }
    }
}
