package com.wipro.bulb.control

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import java.util.UUID

class BulbController(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnecting = false
    private var pendingCommand: ByteArray? = null

    // Queue of writable characteristics to try one after another.
    private val writeQueue = ArrayDeque<BluetoothGattCharacteristic>()

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothAdapter = manager.adapter
    }

    fun testCommand(command: ByteArray) {
        if (isConnecting || bluetoothGatt != null) {
            onLog("Busy (already connecting/connected). Wait a few seconds and retry.")
            return
        }

        isConnecting = true
        pendingCommand = command
        writeQueue.clear()

        onLog("Connecting to $BULB_MAC_ADDRESS ...")
        val device = bluetoothAdapter.getRemoteDevice(BULB_MAC_ADDRESS)

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun connectAndBlink() {
        testCommand(byteArrayOf(0x01, 0x01, 0xFF.toByte()))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    onLog("✓ Connected (status=$status). Discovering services...")
                    gatt?.discoverServices()
                }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    onLog("✗ Disconnected (status=$status)")
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt == null) { cleanup(); return }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("✗ Service discovery failed (status=$status)")
                gatt.disconnect()
                return
            }

            onLog("── GATT MAP (${gatt.services.size} services) ──")
            for (service in gatt.services) {
                onLog("SVC ${short(service.uuid)}")
                for (c in service.characteristics) {
                    val props = propsToString(c.properties)
                    onLog("   CHR ${short(c.uuid)} [$props]")
                    if (isWritable(c)) writeQueue.add(c)
                }
            }

            onLog("── ${writeQueue.size} writable characteristic(s). Writing command... ──")
            if (writeQueue.isEmpty()) {
                onLog("✗ Nothing writable. Bulb likely rejects non-Wipro writes.")
                gatt.disconnect()
            } else {
                writeNext(gatt)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            onLog("   → write ${short(characteristic?.uuid)} : ${if (ok) "OK ✓ (watch the bulb!)" else "fail(status=$status)"}")
            if (gatt != null) writeNext(gatt)
        }
    }

    private fun writeNext(gatt: BluetoothGatt) {
        val c = writeQueue.removeFirstOrNull()
        if (c == null) {
            onLog("── Done. If bulb reacted, note which CHR write said OK just before. ──")
            gatt.disconnect()
            return
        }
        val cmd = pendingCommand ?: byteArrayOf(0x01)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType =
                if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(c, cmd, writeType)
        } else {
            @Suppress("DEPRECATION")
            run {
                c.value = cmd
                gatt.writeCharacteristic(c)
            }
        }
    }

    private fun isWritable(c: BluetoothGattCharacteristic): Boolean {
        val w = BluetoothGattCharacteristic.PROPERTY_WRITE
        val wnr = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        return (c.properties and (w or wnr)) != 0
    }

    private fun propsToString(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts.add("R")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts.add("W")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts.add("Wnr")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts.add("N")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts.add("I")
        return if (parts.isEmpty()) "-" else parts.joinToString(",")
    }

    // Shorten 128-bit standard-base UUIDs to their 16-bit form for readability.
    private fun short(uuid: UUID?): String {
        if (uuid == null) return "null"
        val s = uuid.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb"))
            "0x" + s.substring(4, 8).uppercase()
        else s
    }

    private fun cleanup() {
        isConnecting = false
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeQueue.clear()
    }

    companion object {
        private const val BULB_MAC_ADDRESS = "DC:23:51:0C:BD:F4"
    }
}
