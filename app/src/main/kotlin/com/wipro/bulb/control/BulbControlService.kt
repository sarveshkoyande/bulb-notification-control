package com.wipro.bulb.control

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class BulbControlService : Service() {

    private lateinit var broadcaster: TuyaBeaconBroadcaster

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BulbNotificationListener.ACTION_NOTIFICATION_RECEIVED) {
                Log.d("BulbControlService", "Notification -> blink bulb")
                broadcaster.blink()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        broadcaster = TuyaBeaconBroadcaster(this) { Log.d("BulbControlService", it) }

        val filter = IntentFilter(BulbNotificationListener.ACTION_NOTIFICATION_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(notificationReceiver, filter)
        }
        Log.d("BulbControlService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(notificationReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
