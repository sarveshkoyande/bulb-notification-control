package com.wipro.bulb.control

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class BulbControlService : Service() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private lateinit var bulbController: BulbController

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationListenerService.ACTION_NOTIFICATION_RECEIVED) {
                Log.d("BulbControlService", "Received notification broadcast, making bulb blink")
                bulbController.connectAndBlink()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        bulbController = BulbController(this, bluetoothAdapter)

        val filter = IntentFilter(NotificationListenerService.ACTION_NOTIFICATION_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                notificationReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(notificationReceiver, filter)
        }

        Log.d("BulbControlService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BulbControlService", "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(notificationReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
