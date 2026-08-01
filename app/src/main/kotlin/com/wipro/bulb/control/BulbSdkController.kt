package com.wipro.bulb.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.sdk.api.IResultCallback

/**
 * Real device control via the Thing SDK, using the devId obtained from pairing.
 *
 * DP map — confirmed directly from this device's actual schema (queryDeviceInfo()),
 * NOT the assumed "_v2" convention that turned out wrong:
 *   1  switch_led    bool
 *   2  work_mode     enum: white | colour | scene | music
 *   3  bright_value  int 10-1000
 *   4  temp_value    int 0-1000
 *   7  countdown     int 0-86400 (seconds)
 *   ?  colour_data_raw  type "raw" (base64), maxlen 128 — id discovered at runtime,
 *      NOT 5 as first assumed. DP5 doesn't exist on this device at all; every
 *      publishDps({"5":...}) call failed outright with "no dps or dps is invalid".
 *      Colour appeared to "work" for red only because switching work_mode to
 *      "colour" shows the bulb's last-cached colour, coincidentally red — the
 *      actual colour writes were silently erroring the whole time.
 *
 * Also confirmed empirically: switching work_mode and setting the colour DP in
 * the SAME publishDps call is unreliable; send them as two SEPARATE sequential
 * calls with a short gap (see ensureMode).
 */
class BulbSdkController(context: Context, private val onLog: (String) -> Unit) {

    private val prefs = context.getSharedPreferences("bulb_sdk", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    var devId: String?
        get() = prefs.getString(KEY_DEV_ID, null)
        set(value) { prefs.edit().putString(KEY_DEV_ID, value).apply() }

    /** Tracks last mode sent so we only switch (and pay the settle delay) when needed. */
    private var lastMode: String? = null

    /** Discovered from the real device schema via queryDeviceInfo() — DP5 does not exist
     *  on this device; the real colour DP has a different id and is type "raw", which
     *  requires base64-encoded bytes rather than a plain hex string. */
    private var colourDpId: Int?
        get() = prefs.getInt(KEY_COLOUR_DP, -1).takeIf { it > 0 }
        set(value) { prefs.edit().putInt(KEY_COLOUR_DP, value ?: -1).apply() }
    private var colourDpIsRaw: Boolean
        get() = prefs.getBoolean(KEY_COLOUR_DP_RAW, true)
        set(value) { prefs.edit().putBoolean(KEY_COLOUR_DP_RAW, value).apply() }

    fun turnOn() = publish(mapOf("1" to true))
    fun turnOff() = publish(mapOf("1" to false))

    /** hue 0-360, sat 0-1000, value(brightness) 0-1000 */
    fun setColor(hue: Int, sat: Int, value: Int) {
        val dpId = colourDpId
        if (dpId == null) {
            onLog("✗ Colour DP unknown — tap 'Query device schema' once first")
            return
        }
        val hex = "%04x%04x%04x".format(
            hue.coerceIn(0, 360), sat.coerceIn(0, 1000), value.coerceIn(0, 1000)
        )
        // RAW-type Tuya colour DPs commonly carry the same ASCII hex string, just
        // base64-wrapped for transport (confirmed pattern across Tuya raw DPs).
        val dpValue: Any = if (colourDpIsRaw) {
            android.util.Base64.encodeToString(hex.toByteArray(Charsets.US_ASCII), android.util.Base64.NO_WRAP)
        } else {
            hex
        }
        ensureMode("colour") {
            publish(mapOf(dpId.toString() to dpValue))
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

    /**
     * Logs the device's real DP schema + current dp values from the SDK's local cache.
     * Ground truth for which DP id is actually colour_data_v2 on this specific device —
     * DP5 is Tuya's usual convention but was never directly confirmed for this product.
     */
    fun queryDeviceInfo() {
        val id = devId
        if (id == null) { onLog("✗ No paired devId yet"); return }
        runCatching {
            val bean = ThingHomeSdk.getDataInstance().getDeviceBean(id)
            if (bean == null) {
                onLog("✗ getDeviceBean returned null for $id")
                return
            }
            onLog("current dps: ${bean.dps}")

            val schema = org.json.JSONArray(bean.schema)
            for (i in 0 until schema.length()) {
                val dp = schema.getJSONObject(i)
                val code = dp.optString("code")
                if (code.contains("colour", true) || code.contains("color", true)) {
                    val prop = dp.optJSONObject("property")
                    val dpId = dp.optInt("id")
                    val type = prop?.optString("type")
                    onLog("★ COLOUR DP FOUND: code=$code id=$dpId type=$type property=$prop")
                    colourDpId = dpId
                    colourDpIsRaw = (type == "raw")
                }
            }
            if (colourDpId == null) onLog("✗ No dp with 'colour'/'color' in its code found in schema")
        }.onFailure {
            onLog("✗ queryDeviceInfo failed: ${it.javaClass.simpleName}: ${it.message}")
        }
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
        private const val KEY_COLOUR_DP = "colourDpId"
        private const val KEY_COLOUR_DP_RAW = "colourDpIsRaw"
        private const val MODE_SWITCH_DELAY_MS = 400L
    }
}
