package com.wipro.bulb.control

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.thingclips.smart.android.user.api.ILoginCallback
import com.thingclips.smart.android.user.api.IRegisterCallback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.sdk.api.IResultCallback

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var broadcaster: TuyaBeaconBroadcaster
    private val logMessages = mutableListOf<String>()

    private lateinit var emailField: EditText
    private lateinit var codeField: EditText
    private lateinit var passwordField: EditText
    private lateinit var pairing: PairingHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logText)
        val enableListenerBtn = findViewById<Button>(R.id.enableListenerBtn)
        val buttons = findViewById<LinearLayout>(R.id.testButtonsContainer)

        broadcaster = TuyaBeaconBroadcaster(this) { logMessage(it) }
        pairing = PairingHelper(this) { logMessage(it) }

        enableListenerBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // ---- Existing replay control (works today) ----
        addLabel(buttons, "— Replay mode (captured packets) —")
        addButton(buttons, "Turn ON  (preamble + cmd)") { broadcaster.turnOn() }
        addButton(buttons, "Turn OFF (preamble + cmd)") { broadcaster.turnOff() }
        addButton(buttons, "Set RED") { broadcaster.setRed() }
        addButton(buttons, "Blink (ON/OFF/ON)") { broadcaster.blink() }
        addButton(buttons, "SWEEP ON (seq brute-force ~24s)") { broadcaster.sweepOn() }

        // ---- Thing SDK: account setup (unlocks arbitrary colour) ----
        addLabel(buttons, "— Thing SDK account —")
        emailField = addField(buttons, "email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        codeField = addField(buttons, "verification code", InputType.TYPE_CLASS_NUMBER)
        passwordField = addField(buttons, "password (min 6 chars)", InputType.TYPE_TEXT_VARIATION_PASSWORD)

        addButton(buttons, "1. Send verify code") { sendVerifyCode() }
        addButton(buttons, "2. Register account") { registerAccount() }
        addButton(buttons, "3. Login") { login() }

        // ---- Pairing (after login) ----
        addLabel(buttons, "— Pair the bulb —")
        addButton(buttons, "4. Create Home") { if (sdkReady()) pairing.createHome("Bulb Home") }
        addButton(buttons, "5. Search & Pair Bulb (60s)") { if (sdkReady()) pairing.searchAndPairBulb() }

        requestPermissions()
        startService(Intent(this, BulbControlService::class.java))
        logMessage("Ready. Broadcaster mode (Tuya beacon).")
        logMessage(BulbApplication.sdkStatus)
    }

    // ---------- Thing SDK ----------

    /**
     * First authenticated server call — also proves the AppKey/Secret/SHA256/AAR
     * combination is accepted. An "illegal client" error means one of them mismatches.
     */
    private fun sendVerifyCode() {
        val email = emailField.text.toString().trim()
        if (email.isEmpty()) { logMessage("Enter an email first"); return }
        if (!sdkReady()) return

        logMessage("Sending verify code to $email …")
        // (userName, region, countryCode, type=1 register, callback)
        ThingHomeSdk.getUserInstance().sendVerifyCodeWithUserName(
            email, "", COUNTRY_CODE, 1,
            object : IResultCallback {
                override fun onSuccess() {
                    logMessage("✓ SDK AUTH OK — code sent, check your email")
                }
                override fun onError(code: String?, error: String?) {
                    logMessage("✗ [$code] $error")
                    if (code?.contains("ILLEGAL_CLIENT", true) == true ||
                        error?.contains("illegal client", true) == true
                    ) {
                        logMessage("→ AppKey/Secret, package name, SHA256 or security AAR mismatch")
                    }
                }
            }
        )
    }

    private fun registerAccount() {
        val email = emailField.text.toString().trim()
        val code = codeField.text.toString().trim()
        val pwd = passwordField.text.toString()
        if (email.isEmpty() || code.isEmpty() || pwd.isEmpty()) {
            logMessage("Need email, code and password"); return
        }
        if (!sdkReady()) return

        logMessage("Registering $email …")
        ThingHomeSdk.getUserInstance().registerAccountWithEmail(
            COUNTRY_CODE, email, pwd, code,
            object : IRegisterCallback {
                override fun onSuccess(user: User?) {
                    logMessage("✓ Registered as ${user?.username ?: email}")
                }
                override fun onError(code: String?, error: String?) {
                    logMessage("✗ register failed [$code] $error")
                }
            }
        )
    }

    private fun login() {
        val email = emailField.text.toString().trim()
        val pwd = passwordField.text.toString()
        if (email.isEmpty() || pwd.isEmpty()) { logMessage("Need email and password"); return }
        if (!sdkReady()) return

        logMessage("Logging in as $email …")
        ThingHomeSdk.getUserInstance().loginWithEmail(
            COUNTRY_CODE, email, pwd,
            object : ILoginCallback {
                override fun onSuccess(user: User?) {
                    logMessage("✓ Logged in — uid=${user?.uid}")
                    logMessage("Next: create a home and pair the bulb")
                }
                override fun onError(code: String?, error: String?) {
                    logMessage("✗ login failed [$code] $error")
                }
            }
        )
    }

    private fun sdkReady(): Boolean {
        if (!BulbApplication.sdkInitialised) {
            logMessage("✗ SDK not initialised — ${BulbApplication.sdkStatus}")
            return false
        }
        return true
    }

    // ---------- UI helpers ----------

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        parent.addView(b)
    }

    private fun addLabel(parent: LinearLayout, text: String) {
        val t = TextView(this)
        t.text = text
        t.setPadding(0, 24, 0, 8)
        parent.addView(t)
    }

    private fun addField(parent: LinearLayout, hint: String, inputType: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = InputType.TYPE_CLASS_TEXT or inputType
        e.setSingleLine()
        parent.addView(e)
        return e
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
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
    }

    companion object {
        /** India. Change if your Tuya account region differs. */
        private const val COUNTRY_CODE = "91"
    }
}
