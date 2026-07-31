package com.wipro.bulb.control

import android.app.Application
import android.util.Log
import com.thingclips.smart.home.sdk.ThingHomeSdk

/**
 * Initialises the Thing (Tuya) Smart Life App SDK.
 *
 * Credentials come from BuildConfig, fed by CI secrets (or
 * _secrets/signing.properties locally) — never hardcoded in the repo.
 *
 * SDK auth requires ALL of these to match the iot.tuya.com configuration:
 *   - applicationId (com.wipro.bulb.control)
 *   - AppKey / AppSecret
 *   - signing certificate SHA256
 *   - the app-specific security-algorithm AAR in app/libs
 * A mismatch surfaces as "illegal client" on the first API call.
 */
class BulbApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initThingSdk()
    }

    private fun initThingSdk() {
        val key = BuildConfig.TUYA_APP_KEY
        val secret = BuildConfig.TUYA_APP_SECRET

        if (key.isEmpty() || secret.isEmpty()) {
            sdkStatus = "SDK: no credentials in build (replay mode only)"
            Log.w(TAG, sdkStatus)
            return
        }

        try {
            ThingHomeSdk.setDebugMode(true)
            ThingHomeSdk.init(this, key, secret)
            sdkInitialised = true
            sdkStatus = "SDK: initialised (key ${key.take(6)}…)"
            Log.i(TAG, sdkStatus)
        } catch (t: Throwable) {
            sdkStatus = "SDK init FAILED: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, sdkStatus, t)
        }
    }

    companion object {
        private const val TAG = "BulbApplication"

        /** Surfaced in the UI — init happens before any Activity exists. */
        @Volatile var sdkStatus: String = "SDK: not initialised"
            private set

        @Volatile var sdkInitialised: Boolean = false
            private set
    }
}
