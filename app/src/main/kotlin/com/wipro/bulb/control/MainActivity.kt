package com.wipro.bulb.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var enableListenerBtn: Button
    private lateinit var bulbController: BulbController
    private var logMessages = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusText)
        logTextView = findViewById(R.id.logText)
        enableListenerBtn = findViewById(R.id.enableListenerBtn)
        val testButtonsContainer = findViewById<LinearLayout>(R.id.testButtonsContainer)

        bulbController = BulbController(this, { logMessage(it) })

        enableListenerBtn.setOnClickListener {
            openNotificationListenerSettings()
        }

        // Test command buttons with different payloads
        val testCommands = listOf(
            "Blink (0x01,0x01,0xFF)" to byteArrayOf(0x01, 0x01, 0xFF.toByte()),
            "Blink (0x02,0x01)" to byteArrayOf(0x02, 0x01),
            "Color Red" to byteArrayOf(0xFF.toByte(), 0x00, 0x00),
            "Color Green" to byteArrayOf(0x00, 0xFF.toByte(), 0x00),
            "Color Blue" to byteArrayOf(0x00, 0x00, 0xFF.toByte()),
            "Flash Pattern" to byteArrayOf(0x04, 0x02, 0x03),
            "Pulse" to byteArrayOf(0x05, 0x01),
            "Turn On" to byteArrayOf(0x01),
            "Turn Off" to byteArrayOf(0x00),
            "Max Brightness" to byteArrayOf(0xFF.toByte()),
            "Command 1" to byteArrayOf(0x01, 0x01, 0x01),
            "Command 2" to byteArrayOf(0x02, 0x02, 0x02),
        )

        for ((label, command) in testCommands) {
            val btn = Button(this).apply {
                text = label
                setOnClickListener {
                    logMessage("Testing: $label")
                    bulbController.testCommand(command)
                }
            }
            testButtonsContainer.addView(btn)
        }

        requestPermissions()
        startService(Intent(this, BulbControlService::class.java))
        logMessage("App started")
    }

    private fun logMessage(msg: String) {
        logMessages.add("[${System.currentTimeMillis() % 10000}] $msg")
        if (logMessages.size > 50) logMessages.removeAt(0)

        runOnUiThread {
            logTextView.text = logMessages.joinToString("\n")
            // Scroll to bottom
            val scrollView = logTextView.parent as? ScrollView
            scrollView?.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        Log.d("BulbApp", msg)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.BLUETOOTH)
        permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}
