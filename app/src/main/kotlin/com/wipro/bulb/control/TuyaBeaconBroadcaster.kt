package com.wipro.bulb.control

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Controls a Tuya "beacon rgbcw" bulb by BROADCASTING BLE advertisements,
 * replaying the on/off command bodies captured from the Smart Life app and
 * re-stamping a fresh sequence number + CRC-8 for each send.
 *
 * Packet (26-byte Tuya payload, carried as AD type 0x03 = 13x uint16):
 *   0b 7e 1c 00 04 [seq:2] 05 [16-byte cmd body][1-byte tag][CRC8]
 */
class TuyaBeaconBroadcaster(context: Context, private val onLog: (String) -> Unit) {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val advertiser: BluetoothLeAdvertiser? = adapter?.bluetoothLeAdvertiser
    private val handler = Handler(Looper.getMainLooper())

    // Start just above the last sequence the app used in the capture (0x000f).
    private var seq = 0x0010
    private var activeCb: AdvertiseCallback? = null

    fun turnOn() = send(ON, "ON")
    fun turnOff() = send(OFF, "OFF")

    /** Visible blink for notifications: ON, then OFF ~600ms later, then ON again. */
    fun blink() {
        turnOn()
        handler.postDelayed({ turnOff() }, 4000)
        handler.postDelayed({ turnOn() }, 8000)
    }

    /**
     * Sweep a rising sequence number. If the bulb rejects our packets because its
     * stored counter is ahead of ours (anti-replay), one of these will cross it.
     */
    fun sweepOn(count: Int = 140, stepMs: Long = 150) {
        onLog("▶ SWEEP ON: seq ${"%04x".format(seq)}..${"%04x".format(seq + count - 1)} " +
            "(~${count * stepMs / 1000}s) — watch the bulb!")
        for (i in 0 until count) {
            handler.postDelayed({ send(ON, "ON", durationMs = stepMs + 80, quiet = true) }, i * stepMs)
        }
        handler.postDelayed({
            stop(); onLog("SWEEP done. Next seq=${"%04x".format(seq)}")
        }, count * stepMs + 400)
    }

    private var generation = 0

    /** Same command, but connectable so Android prepends the Flags AD (like the app's packet). */
    fun turnOnWithFlags() = send(ON, "ON+flags", connectable = true)
    fun turnOffWithFlags() = send(OFF, "OFF+flags", connectable = true)

    private fun send(
        cmd17: ByteArray,
        label: String,
        durationMs: Long = 3500,
        quiet: Boolean = false,
        connectable: Boolean = false
    ) {
        val adv = advertiser
        if (adv == null) {
            onLog("✗ No BLE advertiser — this phone can't broadcast BLE, or Bluetooth is off.")
            return
        }
        val s = seq++
        val gen = ++generation
        val payload = buildPayload(cmd17, s)
        if (!quiet) onLog("TX $label seq=${"%04x".format(s)} adv=0201011b03${payload.toHex()}")

        val dataB = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
        for (i in 0 until 13) {
            val lo = payload[i * 2].toInt() and 0xFF
            val hi = payload[i * 2 + 1].toInt() and 0xFF
            dataB.addServiceUuid(uuid16((hi shl 8) or lo))
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(connectable)
            .setTimeout(0)
            .build()

        stop()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (!quiet) onLog("▶ broadcasting $label for ${durationMs}ms")
            }
            override fun onStartFailure(errorCode: Int) {
                onLog("✗ advertise failed err=$errorCode " +
                    "(1=DATA_TOO_LARGE 2=TOO_MANY_ADVERTISERS 3=ALREADY_STARTED 4=INTERNAL 5=UNSUPPORTED)")
            }
        }
        activeCb = cb
        try {
            adv.startAdvertising(settings, dataB.build(), cb)
        } catch (e: SecurityException) {
            onLog("✗ Missing BLUETOOTH_ADVERTISE permission")
            return
        }
        // Only stop if no newer send has superseded this one.
        handler.postDelayed({ if (gen == generation) stop() }, durationMs)
    }

    fun stop() {
        val cb = activeCb ?: return
        activeCb = null
        try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
    }

    private fun buildPayload(cmd17: ByteArray, s: Int): ByteArray {
        val p = ByteArray(25)
        p[0] = 0x0b; p[1] = 0x7e; p[2] = 0x1c; p[3] = 0x00; p[4] = 0x04
        p[5] = ((s shr 8) and 0xFF).toByte(); p[6] = (s and 0xFF).toByte(); p[7] = 0x05
        System.arraycopy(cmd17, 0, p, 8, 17)
        val mic = crc8(p)
        return p + byteArrayOf(mic.toByte())
    }

    private fun crc8(data: ByteArray, init: Int = 0x7d, poly: Int = 0x07): Int {
        var crc = init
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor poly) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc and 0xFF
    }

    private fun uuid16(v: Int): ParcelUuid {
        val msb = ((v.toLong() and 0xFFFF) shl 32) or 0x0000000000001000L
        val lsb = 0x800000805F9B34FBuL.toLong()
        return ParcelUuid(UUID(msb, lsb))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        // Captured constant command bodies (16-byte encrypted block + 1 tag byte).
        val ON = hex("33e8133e2195b5e01c66e4fdca6314d00b")
        val OFF = hex("d6b8d8714ece3182c6fb800f02770f3f79")
        private fun hex(s: String) =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
