package com.wipro.bulb.control

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit

class BulbController(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter
) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnecting = false

    fun connectAndBlink() {
        if (isConnecting || bluetoothGatt != null) {
            Log.d("BulbController", "Already connecting or connected")
            return
        }

        isConnecting = true
        val macAddress = BULB_MAC_ADDRESS

        Log.d("BulbController", "Attempting to connect to bulb at $macAddress")

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

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BulbController", "Connected to GATT server, discovering services")
                    gatt?.discoverServices()
                }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BulbController", "Disconnected from GATT server")
                    isConnecting = false
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BulbController", "Services discovered")
                gatt?.let { tryBlink(it) }
            } else {
                Log.e("BulbController", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.d("BulbController", "Characteristic written, status: $status")
            // Disconnect after sending command
            gatt?.disconnect()
        }
    }

    private fun tryBlink(gatt: BluetoothGatt) {
        val services = gatt.services
        Log.d("BulbController", "Found ${services.size} services")

        // Try the custom service UUIDs we discovered
        val targetUUIDs = listOf(
            "0000e8e0-0000-1000-8000-00805f9b34fb", // Extended for 0x9BE8
            "00005622-0000-1000-8000-00805f9b34fb", // Extended for 0x5622
            "00006c00-0000-1000-8000-00805f9b34fb"  // Extended for 0x6C00
        )

        for (service in services) {
            val serviceUUID = service.uuid.toString().lowercase()
            Log.d("BulbController", "Checking service: $serviceUUID")

            if (targetUUIDs.any { serviceUUID.contains(it.take(8)) }) {
                Log.d("BulbController", "Found target service: $serviceUUID")

                val characteristics = service.characteristics
                for (char in characteristics) {
                    Log.d("BulbController", "  Characteristic: ${char.uuid}")

                    // Try to write blink command
                    // Experimenting with different byte patterns
                    val blinkCommand = byteArrayOf(0x01, 0x01, 0xFF) // Basic blink attempt
                    char.value = blinkCommand

                    val canWrite = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    val canWriteNoResponse = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

                    if (canWrite || canWriteNoResponse) {
                        Log.d("BulbController", "Writing command to ${char.uuid}")
                        gatt.writeCharacteristic(char)
                        return
                    }
                }
            }
        }

        Log.w("BulbController", "Could not find writable characteristic")
        gatt.disconnect()
    }

    companion object {
        private const val BULB_MAC_ADDRESS = "DC:23:51:0C:BD:F4"
    }
}
