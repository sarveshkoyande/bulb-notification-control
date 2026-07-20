package com.wipro.bulb.control

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import android.util.Log

class BulbController(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnecting = false
    private var pendingCommand: ByteArray? = null

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothAdapter = manager.adapter
    }

    fun testCommand(command: ByteArray) {
        if (isConnecting || bluetoothGatt != null) {
            onLog("Already connecting or connected, waiting...")
            return
        }

        isConnecting = true
        pendingCommand = command
        val macAddress = BULB_MAC_ADDRESS

        onLog("Connecting to bulb at $macAddress...")

        val device = bluetoothAdapter.getRemoteDevice(macAddress)

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun connectAndBlink() {
        testCommand(byteArrayOf(0x01, 0x01, 0xFF.toByte()))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    onLog("✓ Connected to bulb, discovering services...")
                    gatt?.discoverServices()
                }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    onLog("✗ Disconnected from bulb")
                    isConnecting = false
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("✓ Services discovered")
                gatt?.let { sendCommand(it) }
            } else {
                onLog("✗ Failed to discover services: $status")
                gatt?.disconnect()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("✓ Command sent successfully!")
            } else {
                onLog("✗ Failed to send command: $status")
            }
            gatt?.disconnect()
        }
    }

    private fun sendCommand(gatt: BluetoothGatt) {
        val services = gatt.services
        onLog("Found ${services.size} services")

        val targetUUIDs = listOf(
            "0000e8e0-0000-1000-8000-00805f9b34fb",
            "00005622-0000-1000-8000-00805f9b34fb",
            "00006c00-0000-1000-8000-00805f9b34fb"
        )

        var sent = false
        for (service in services) {
            val serviceUUID = service.uuid.toString().lowercase()

            if (targetUUIDs.any { serviceUUID.contains(it.take(8)) }) {
                onLog("Found target service: $serviceUUID")

                val characteristics = service.characteristics
                for (char in characteristics) {
                    val canWrite = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    val canWriteNoResponse = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

                    if (canWrite || canWriteNoResponse) {
                        onLog("Characteristic ${char.uuid} - writable, sending...")
                        char.value = pendingCommand
                        gatt.writeCharacteristic(char)
                        sent = true
                        return
                    }
                }
            }
        }

        if (!sent) {
            onLog("✗ No writable characteristic found in target services")
            gatt.disconnect()
        }
    }

    companion object {
        private const val BULB_MAC_ADDRESS = "DC:23:51:0C:BD:F4"
    }
}
