package com.wipro.bulb.control

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
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
    private lateinit var sdkControl: BulbSdkController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logText)
        val enableListenerBtn = findViewById<Button>(R.id.enableListenerBtn)
        val buttons = findViewById<LinearLayout>(R.id.testButtonsContainer)
        val logScrollView = findViewById<ScrollView>(R.id.logScrollView)
        val toggleLogBtn = findViewById<Button>(R.id.toggleLogBtn)

        broadcaster = TuyaBeaconBroadcaster(this) { logMessage(it) }
        sdkControl = BulbSdkController(this) { logMessage(it) }
        pairing = PairingHelper(this, { logMessage(it) }) { devId ->
            sdkControl.devId = devId
            logMessage("Stored devId for control: $devId")
        }

        enableListenerBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        toggleLogBtn.setOnClickListener {
            val show = logScrollView.visibility != View.VISIBLE
            logScrollView.visibility = if (show) View.VISIBLE else View.GONE
            toggleLogBtn.text = if (show) "Minimize" else "Maximize"
        }

        setupControlPanel()

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

        addLabel(buttons, "— Debug —")
        addButton(buttons, "Query device schema (real DP map)") { sdkControl.queryDeviceInfo() }

        requestPermissions()
        startService(Intent(this, BulbControlService::class.java))
        logMessage("Ready. Broadcaster mode (Tuya beacon).")
        logMessage(BulbApplication.sdkStatus)
        sdkControl.devId?.let { logMessage("Previously paired devId: $it") }
    }

    // ---------- Bulb control panel (White / Colour, sliders) ----------

    private var isOn = true

    private fun setupControlPanel() {
        val modeWhiteBtn = findViewById<Button>(R.id.modeWhiteBtn)
        val modeColourBtn = findViewById<Button>(R.id.modeColourBtn)
        val powerToggleBtn = findViewById<Button>(R.id.powerToggleBtn)
        val colorPreview = findViewById<View>(R.id.colorPreview)

        val colourGroup = findViewById<LinearLayout>(R.id.colourControlsGroup)
        val whiteGroup = findViewById<LinearLayout>(R.id.whiteControlsGroup)

        val hueBar = findViewById<SeekBar>(R.id.hueSeekBar)
        val satBar = findViewById<SeekBar>(R.id.satSeekBar)
        val valBar = findViewById<SeekBar>(R.id.valSeekBar)
        val hueLabel = findViewById<TextView>(R.id.hueLabel)
        val satLabel = findViewById<TextView>(R.id.satLabel)
        val valLabel = findViewById<TextView>(R.id.valLabel)

        val brightBar = findViewById<SeekBar>(R.id.brightSeekBar)
        val tempBar = findViewById<SeekBar>(R.id.tempSeekBar)
        val brightLabel = findViewById<TextView>(R.id.brightLabel)
        val tempLabel = findViewById<TextView>(R.id.tempLabel)

        fun updatePreview() {
            val hsv = floatArrayOf(hueBar.progress.toFloat(), satBar.progress / 1000f, valBar.progress / 1000f)
            colorPreview.setBackgroundColor(Color.HSVToColor(hsv))
        }

        fun showColourMode(show: Boolean) {
            colourGroup.visibility = if (show) View.VISIBLE else View.GONE
            whiteGroup.visibility = if (show) View.GONE else View.VISIBLE
        }

        modeWhiteBtn.setOnClickListener {
            showColourMode(false)
            sdkControl.setColorTemp(tempBar.progress)
        }
        modeColourBtn.setOnClickListener {
            showColourMode(true)
            updatePreview()
            sdkControl.setColor(hueBar.progress, satBar.progress, valBar.progress)
        }
        powerToggleBtn.setOnClickListener {
            isOn = !isOn
            if (isOn) sdkControl.turnOn() else sdkControl.turnOff()
            powerToggleBtn.text = if (isOn) "Turn OFF" else "Turn ON"
        }

        val debouncer = SliderDebouncer(handlerDelayMs = 200)

        hueBar.setOnSeekBarChangeListener(seekListener { progress ->
            hueLabel.text = "Hue: $progress"
            updatePreview()
            debouncer.run { sdkControl.setColor(hueBar.progress, satBar.progress, valBar.progress) }
        })
        satBar.setOnSeekBarChangeListener(seekListener { progress ->
            satLabel.text = "Saturation: $progress"
            updatePreview()
            debouncer.run { sdkControl.setColor(hueBar.progress, satBar.progress, valBar.progress) }
        })
        valBar.setOnSeekBarChangeListener(seekListener { progress ->
            valLabel.text = "Brightness: $progress"
            updatePreview()
            debouncer.run { sdkControl.setColor(hueBar.progress, satBar.progress, valBar.progress) }
        })

        brightBar.setOnSeekBarChangeListener(seekListener { progress ->
            val v = progress + 10 // seekbar max=990 -> real range 10..1000
            brightLabel.text = "Brightness: $v"
            debouncer.run { sdkControl.setBrightness(v) }
        })
        tempBar.setOnSeekBarChangeListener(seekListener { progress ->
            tempLabel.text = "Warmth (0=warm, 1000=cool): $progress"
            debouncer.run { sdkControl.setColorTemp(progress) }
        })

        updatePreview()
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    /** Coalesces rapid slider drags into one publishDps ~200ms after the last move. */
    private inner class SliderDebouncer(private val handlerDelayMs: Long) {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pending: Runnable? = null
        fun run(action: () -> Unit) {
            pending?.let { handler.removeCallbacks(it) }
            val r = Runnable { action() }
            pending = r
            handler.postDelayed(r, handlerDelayMs)
        }
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
