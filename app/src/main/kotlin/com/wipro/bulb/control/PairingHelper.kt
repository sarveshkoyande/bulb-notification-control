package com.wipro.bulb.control

import android.app.Activity
import com.thingclips.smart.activator.core.kit.ThingActivatorCoreKit
import com.thingclips.smart.activator.core.kit.bean.ThingActivatorScanDeviceBean
import com.thingclips.smart.activator.core.kit.bean.ThingActivatorScanFailureBean
import com.thingclips.smart.activator.core.kit.bean.ThingActivatorScanKey
import com.thingclips.smart.activator.core.kit.bean.ThingDeviceActiveErrorBean
import com.thingclips.smart.activator.core.kit.bean.ThingDeviceActiveLimitBean
import com.thingclips.smart.activator.core.kit.builder.ThingDeviceActiveBuilder
import com.thingclips.smart.activator.core.kit.callback.ThingActivatorScanCallback
import com.thingclips.smart.activator.core.kit.listener.IThingMeshDeviceActiveListener
import com.thingclips.smart.android.ble.api.ScanType
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import com.thingclips.smart.sdk.bean.DeviceBean

/**
 * Wraps the Thing Activator Core Kit for pairing the beacon bulb.
 *
 * Every class/method here was verified against the real 7.8.0 AAR class files with
 * javap and cross-checked against Tuya's official BizSdkSample
 * (DeviceConfigBleActivity.kt) — not guessed from the older com.tuya.smart docs.
 */
class PairingHelper(private val activity: Activity, private val onLog: (String) -> Unit) {

    private var scanKey: ThingActivatorScanKey? = null
    var currentHomeId: Long = 0L
        private set

    fun createHome(name: String) {
        onLog("Creating home '$name' …")
        runCatching {
            ThingHomeSdk.getHomeManagerInstance().createHome(
                name, 0.0, 0.0, "",
                mutableListOf(),
                object : IThingHomeResultCallback {
                    override fun onSuccess(bean: HomeBean?) {
                        currentHomeId = bean?.homeId ?: 0L
                        onLog("✓ Home created, homeId=$currentHomeId")
                    }
                    override fun onError(code: String?, error: String?) {
                        onLog("✗ createHome failed [$code] $error")
                    }
                }
            )
        }.onFailure { logCrash("createHome", it) }
    }

    /**
     * Scans for any nearby unpaired BLE/beacon device (60s) and pairs the first one found
     * into currentHomeId. For a single known bulb this "pair the first thing we see" logic
     * is fine — call after standing next to the bulb and power-cycling it.
     */
    fun searchAndPairBulb() {
        if (currentHomeId == 0L) {
            onLog("✗ No home yet — tap 'Create Home' first")
            return
        }
        if (!hasBlePermissions()) {
            onLog("✗ Missing BLE/location permission(s): ${missingPermissions().joinToString()}")
            onLog("→ Grant them (re-open app, allow prompts) and retry")
            return
        }
        onLog("Scanning for BLE/beacon devices (60s) — power-cycle the bulb now…")

        runCatching {
            scanKey = ThingActivatorCoreKit.getScanDeviceManager().startBlueToothDeviceSearch(
                60_000L,
                arrayListOf(ScanType.SINGLE, ScanType.THING_BEACON),
                object : ThingActivatorScanCallback {
                    override fun deviceFound(deviceBean: ThingActivatorScanDeviceBean) {
                        onLog("✓ Found device: uniqueId=${deviceBean.uniqueId} pid=${deviceBean.pid}")
                        stopScan()
                        pair(deviceBean)
                    }
                    override fun deviceRepeat(deviceBean: ThingActivatorScanDeviceBean) {}
                    override fun deviceUpdate(deviceBean: ThingActivatorScanDeviceBean) {}
                    override fun scanFailure(failureBean: ThingActivatorScanFailureBean) {
                        onLog("✗ scan failure: $failureBean")
                    }
                    override fun scanFinish() {
                        onLog("Scan window finished (no more devices)")
                    }
                }
            )
        }.onFailure { logCrash("startBlueToothDeviceSearch", it) }
    }

    fun stopScan() {
        runCatching {
            scanKey?.let { ThingActivatorCoreKit.getScanDeviceManager().stopScan(it) }
        }.onFailure { logCrash("stopScan", it) }
        scanKey = null
    }

    private fun pair(deviceBean: ThingActivatorScanDeviceBean) {
        val mode = deviceBean.supprotActivatorTypeList.firstOrNull()
        if (mode == null) {
            onLog("✗ Device offers no supported activation mode")
            return
        }
        onLog("Pairing via mode=$mode …")

        runCatching {
            val activeManager = ThingActivatorCoreKit.getActiveManager().newThingActiveManager()
            activeManager.startActive(ThingDeviceActiveBuilder().apply {
                activeModel = mode
                setActivatorScanDeviceBean(deviceBean)
                timeOut = 60
                relationId = currentHomeId
                // BEACON devices are activated through the same batch/"mesh" code path
                // internally (confirmed by the listener needing IThingMeshDeviceActiveListener,
                // and IThingBeaconManager's group-oriented API). Leaving this list empty made
                // the batch loop iterate zero devices and fire onFinish() with nothing done —
                // the bulb never actually got processed. Put the device IN the batch.
                setMeshSearchBeans(mutableListOf(deviceBean))
                setLightningSearchBeans(mutableListOf())
                setDevList(mutableListOf())
                // The SDK internally casts the listener to IThingMeshDeviceActiveListener
                // even for a single BEACON device — it extends IThingDeviceActiveListener
                // and adds onFinish(). A plain IThingDeviceActiveListener CCEs at runtime.
                listener = object : IThingMeshDeviceActiveListener {
                    var succeeded = false

                    override fun onFind(devId: String) {
                        onLog("… found devId=$devId")
                    }
                    override fun onBind(devId: String) {
                        onLog("… bound devId=$devId")
                    }
                    override fun onActiveSuccess(deviceBean: DeviceBean) {
                        succeeded = true
                        onLog("✓ PAIRED — devId=${deviceBean.devId} name=${deviceBean.name}")
                        onLog("Save this devId to control the bulb via publishDps.")
                    }
                    override fun onActiveError(errorBean: ThingDeviceActiveErrorBean) {
                        onLog("✗ Pairing error: $errorBean")
                    }
                    override fun onActiveLimited(limitBean: ThingDeviceActiveLimitBean) {
                        onLog("✗ Pairing limited: $limitBean")
                    }
                    override fun onFinish() {
                        onLog(
                            if (succeeded) "… pairing sequence finished (after success)"
                            else "✗ pairing sequence finished WITHOUT any success/error — device was not processed"
                        )
                    }
                }
            })
        }.onFailure { logCrash("startActive", it) }
    }

    private fun hasBlePermissions(): Boolean = missingPermissions().isEmpty()

    private fun missingPermissions(): List<String> {
        val needed = mutableListOf<String>()
        val perms = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms += android.Manifest.permission.BLUETOOTH_SCAN
            perms += android.Manifest.permission.BLUETOOTH_CONNECT
        }
        for (p in perms) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(activity, p)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) needed += p.substringAfterLast('.')
        }
        return needed
    }

    private fun logCrash(where: String, t: Throwable) {
        onLog("✗ CRASH in $where: ${t.javaClass.simpleName}: ${t.message}")
        android.util.Log.e("PairingHelper", "crash in $where", t)
    }
}
