package com.wipro.bulb.control

import android.app.Application
import android.util.Log

/**
 * Initialises the Thing (Tuya) Smart Life App SDK.
 *
 * Credentials come from BuildConfig, which is fed by CI secrets (or
 * _secrets/signing.properties locally) — never hardcoded in the repo.
 *
 * Auth requires ALL of these to match the iot.tuya.com configuration:
 *   - applicationId (com.wipro.bulb.control)
 *   - AppKey / AppSecret
 *   - signing certificate SHA256
 *   - the app-specific security-algorithm AAR in app/libs
 * A mismatch surfaces as "illegal client".
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
            Log.w(TAG, "Thing SDK credentials absent — SDK not initialised (replay mode still works)")
            return
        }
        try {
            // Reflection keeps the build working even if the SDK artifact is unavailable,
            // so the existing broadcaster app never gets blocked by SDK issues.
            val sdk = Class.forName("com.thingclips.smart.home.sdk.ThingHomeSdk")
            val init = sdk.getMethod(
                "init", Application::class.java, String::class.java, String::class.java
            )
            init.invoke(null, this, key, secret)
            Log.i(TAG, "Thing SDK initialised (key=${key.take(6)}…)")
        } catch (t: Throwable) {
            Log.e(TAG, "Thing SDK init failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "BulbApplication"
    }
}
