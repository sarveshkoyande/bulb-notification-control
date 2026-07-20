package com.wipro.bulb.control

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.Intent

class NotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            Log.d("NotificationListener", "Notification received from ${it.packageName}")
            // Send broadcast to BulbControlService to make bulb blink
            sendBroadcast(Intent(ACTION_NOTIFICATION_RECEIVED))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    companion object {
        const val ACTION_NOTIFICATION_RECEIVED = "com.wipro.bulb.action.NOTIFICATION_RECEIVED"
    }
}
