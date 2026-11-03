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
    private const val REMOTE_NAME = "NVIDIA SHIELD Remote"
    private val SERVICE = UUID.fromString("00001000-b89b-11e5-9912-ba0be0483c18")
    private val OUT = UUID.fromString("00002001-b89b-11e5-9912-ba0be0483c18")
    private val RSP = UUID.fromString("00002000-b89b-11e5-9912-ba0be0483c18")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val CMD_EMIT: Byte = 44
    private const val TIMEOUT_MS = 5000L

    const val SLOT_POWER_TOGGLE = 1

    private val main = Handler(Looper.getMainLooper())
    private var tid: Byte = 0

    /** Paired Shield remotes, sorted by address so the order is stable. */
    fun pairedRemotes(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.filter { it.name == REMOTE_NAME }.sortedBy { it.address }
    }

    /** Short label for a remote, e.g. "remote …C0:B9". */
    fun label(device: BluetoothDevice) = "remote …" + device.address.takeLast(5)

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Fires [slot] on one paired Shield remote: [preferred] (a Bluetooth address) if it's paired,
     * otherwise the first connected one. Only ever one remote, because firing a toggle from two
     * would cancel out. [onResult] gets a short status on the main thread.
     */
    fun emit(context: Context, slot: Int, preferred: String? = null, onResult: (String) -> Unit = {}) {
        val app = context.applicationContext
        if (!hasPermission(app)) return onResult("no Bluetooth permission")
        val remotes = pairedRemotes(app)
        if (remotes.isEmpty()) return onResult("Shield remote not paired")
        val connected = app.getSystemService(BluetoothManager::class.java)
            .getConnectedDevices(BluetoothProfile.GATT).map { it.address }.toSet()
        val remote = remotes.firstOrNull { it.address == preferred }
            ?: remotes.firstOrNull { it.address in connected }
            ?: remotes.first()

        // Keep the CPU up long enough to finish, as this also runs when the Shield is going to sleep.
        val wake = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ButtonHooks:remoteIr")
            .apply { acquire(TIMEOUT_MS + 1000) }

        var gatt: BluetoothGatt? = null
        var finished = false
        fun finish(result: String) {
            main.post {
                if (finished) return@post
                finished = true
                gatt?.close()
                if (wake.isHeld) wake.release()
                Log.i(TAG, "IR emit slot $slot via ${label(remote)}: $result")
                onResult(result)
            }
        }
        main.postDelayed({ finish("timed out") }, TIMEOUT_MS)

        val frame = ByteArray(18).also {
            it[0] = CMD_EMIT
            tid = (tid + 1).toByte()
            it[1] = tid
            it[4] = slot.toByte()
        }

        gatt = remote.connectGatt(app, false, object : BluetoothGattCallback() {
            private var out: BluetoothGattCharacteristic? = null

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> finish("disconnected ($status)")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(SERVICE)
                out = service?.getCharacteristic(OUT)
                val rsp = service?.getCharacteristic(RSP)
                if (out == null || rsp == null) return finish("remote has no IR service")
                g.setCharacteristicNotification(rsp, true)
                val cccd = rsp.getDescriptor(CCCD)
                if (cccd != null) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) else send(g)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = send(g)

            private fun send(g: BluetoothGatt) {
                g.writeCharacteristic(out!!, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (value.size >= 2 && value[0] == CMD_EMIT && value[1] == frame[1]) {
                    val status = if (value.size >= 4) (value[2].toInt() and 0xff) or ((value[3].toInt() and 0xff) shl 8) else -1
                    finish(if (status == 0) "ok" else "remote error $status")
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }
}
