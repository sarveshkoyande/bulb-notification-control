package com.wipro.bulb.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.sdk.api.IResultCallback

/**
 * Real device control via the Thing SDK, using the devId obtained from pairing.
 *
 * DP map (from the device's Tuya cloud spec — DP1/2/3/4/7/34 confirmed directly;
 * DP5=colour_data_v2 is Tuya's standard convention for rgbcw bulbs, not directly
 * confirmed from the (truncated) spec dump, so verify if colour control misbehaves):
 *   1  switch_led      bool
 *   2  work_mode        enum: white | colour | scene | music
 *   3  bright_value_v2  int 10-1000
 *   4  temp_value_v2    int 0-1000
 *   5  colour_data_v2   string "hhhhssssvvvv" (4 hex digits each: H 0-360, S 0-1000, V 0-1000)
 *
 * IMPORTANT quirk discovered empirically: switching work_mode and setting
 * colour_data_v2 in the SAME publishDps call is unreliable — only the first
 * colour after a mode switch (which happened to be red/H0) actually applied;
 * later hues were ignored. Sending the mode switch and the colour value as
 * two SEPARATE sequential calls (with a short gap) fixes it.
 */
class BulbSdkController(context: Context, private val onLog: (String) -> Unit) {

    private val prefs = context.getSharedPreferences("bulb_sdk", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    var devId: String?
        get() = prefs.getString(KEY_DEV_ID, null)
        set(value) { prefs.edit().putString(KEY_DEV_ID, value).apply() }

    /** Tracks last mode sent so we only switch (and pay the settle delay) when needed. */
    private var lastMode: String? = null

    fun turnOn() = publish(mapOf("1" to true))
    fun turnOff() = publish(mapOf("1" to false))

    /** hue 0-360, sat 0-1000, value(brightness) 0-1000 */
    fun setColor(hue: Int, sat: Int, value: Int) {
        val hex = "%04x%04x%04x".format(
            hue.coerceIn(0, 360), sat.coerceIn(0, 1000), value.coerceIn(0, 1000)
        )
        ensureMode("colour") {
            publish(mapOf("5" to hex))
        }
    }

    fun setBrightness(v: Int) {
        ensureMode("white") {
            publish(mapOf("3" to v.coerceIn(10, 1000)))
        }
    }

    fun setColorTemp(t: Int) {
        ensureMode("white") {
            publish(mapOf("4" to t.coerceIn(0, 1000)))
        }
    }

    /** Switch work_mode only if it actually changed, then run [after] once it's settled. */
    private fun ensureMode(mode: String, after: () -> Unit) {
        if (lastMode == mode) {
            after()
            return
        }
        lastMode = mode
        publish(mapOf("2" to mode))
        handler.postDelayed({ after() }, MODE_SWITCH_DELAY_MS)
    }

    private fun publish(dps: Map<String, Any>) {
        val id = devId
        if (id == null) {
            onLog("✗ No paired devId yet — pair the bulb first")
            return
        }
        val device = ThingHomeSdk.newDeviceInstance(id)
        val json = org.json.JSONObject(dps).toString()
        onLog("→ publishDps($json)")
        device.publishDps(json, object : IResultCallback {
            override fun onSuccess() {
                onLog("✓ command applied")
            }
            override fun onError(code: String?, error: String?) {
                onLog("✗ publishDps failed [$code] $error")
            }
        })
    }

    companion object {
        private const val KEY_DEV_ID = "devId"
        private const val MODE_SWITCH_DELAY_MS = 400L
    }
}
