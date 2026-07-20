package com.wipro.bulb.control

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var broadcaster: TuyaBeaconBroadcaster
    private val logMessages = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logText)
        val enableListenerBtn = findViewById<Button>(R.id.enableListenerBtn)
        val buttons = findViewById<LinearLayout>(R.id.testButtonsContainer)

        broadcaster = TuyaBeaconBroadcaster(this) { logMessage(it) }

        enableListenerBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        addButton(buttons, "Turn ON") { broadcaster.turnOn() }
        addButton(buttons, "Turn OFF") { broadcaster.turnOff() }
        addButton(buttons, "Blink (ON/OFF/ON)") { broadcaster.blink() }

        requestPermissions()
        startService(Intent(this, BulbControlService::class.java))
        logMessage("Ready. Broadcaster mode (Tuya beacon).")
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        parent.addView(b)
    }

    private fun logMessage(msg: String) {
        logMessages.add(msg)
        if (logMessages.size > 60) logMessages.removeAt(0)
        runOnUiThread {
            logTextView.text = logMessages.joinToString("\n")
            (logTextView.parent as? ScrollView)?.post {
                (logTextView.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
        Log.d("BulbApp", msg)
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
    }
}
