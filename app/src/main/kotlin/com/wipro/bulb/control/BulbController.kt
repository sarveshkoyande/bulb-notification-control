package com.wipro.bulb.control

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

class BulbController(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isBusy = false
    private var pendingCommand: ByteArray? = null
    private var scanning = false

    private val handler = Handler(Looper.getMainLooper())
    private val writeQueue = ArrayDeque<BluetoothGattCharacteristic>()

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter.bluetoothLeScanner
    }

    fun testCommand(command: ByteArray) {
        if (isBusy) {
            onLog("Busy. Wait a few seconds and retry.")
            return
        }
        isBusy = true
        pendingCommand = command
        writeQueue.clear()
        startScan()
    }

    fun connectAndBlink() {
        testCommand(byteArrayOf(0x01, 0x01, 0xFF.toByte()))
    }

    // ---- Step 1: scan so we connect only when the bulb is actually present ----

    private fun startScan() {
        val s = scanner
        if (s == null) {
            onLog("✗ No BLE scanner (is Bluetooth on?)")
            cleanup()
            return
        }
        val filter = ScanFilter.Builder().setDeviceAddress(BULB_MAC_ADDRESS).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        onLog("Scanning 25s for $BULB_MAC_ADDRESS ... POWER-CYCLE the bulb now (switch off, then on).")
        sawNonConnectable = false
        scanning = true
        s.startScan(listOf(filter), settings, scanCallback)

        handler.postDelayed({
            if (scanning) {
                if (sawNonConnectable) {
                    onLog("✗ Timeout: bulb only ever advertised NON-connectable. It never opened a connect window while scanning.")
                } else {
                    onLog("✗ Timeout: bulb not seen at all in 25s. Check the MAC address and move phone closer.")
                }
                stopScan()
                cleanup()
            }
        }, 25000)
    }

    private fun stopScan() {
        if (scanning) {
            scanning = false
            try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        }
    }

    private var sawNonConnectable = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null || !scanning) return
            val connectable = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || result.isConnectable

            // Even if this packet is non-connectable, try a PATIENT autoConnect: the
            // controller will latch onto any connectable window the scan can't catch.
            onLog("Seen bulb rssi=${result.rssi}dBm connectable=$connectable. Trying patient autoConnect (up to 40s)...")
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("✗ Scan failed (error=$errorCode)")
            scanning = false
            cleanup()
        }
    }

    // ---- Step 2: connect ----

    private fun connect(device: BluetoothDevice) {
        // autoConnect = true: patient background connect that survives brief/directed windows.
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, true, gattCallback)
        }
        // Give the patient connect up to 40s before giving up.
        handler.postDelayed({
            if (isBusy && bluetoothGatt != null) {
                onLog("✗ autoConnect gave up after 40s — bulb never accepted a connection.")
                bluetoothGatt?.disconnect()
                cleanup()
            }
        }, 40000)
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
                    onLog("   CHR ${short(c.uuid)} [${propsToString(c.properties)}]")
                    if (isWritable(c)) writeQueue.add(c)
                }
            }

            onLog("── ${writeQueue.size} writable characteristic(s). Writing... ──")
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
            onLog("── Done. If the bulb reacted, note the CHR whose write said OK just before. ──")
            gatt.disconnect()
            return
        }
        val cmd = pendingCommand ?: byteArrayOf(0x01)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType =
                if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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

    private fun short(uuid: UUID?): String {
        if (uuid == null) return "null"
        val s = uuid.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb"))
            "0x" + s.substring(4, 8).uppercase()
        else s
    }

    private fun cleanup() {
        isBusy = false
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeQueue.clear()
    }

    companion object {
        private const val BULB_MAC_ADDRESS = "DC:23:51:0C:BD:F4"
    }
}
