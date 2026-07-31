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
 * Controls a Tuya "beacon rgbcw" bulb by BROADCASTING BLE advertisements.
 *
 * Reconstructed from an HCI capture of the Smart Life app. Two frame types are
 * sent, both 26 bytes carried as AD type 0x03 (13 x uint16), preceded by Flags:
 *
 *   PREAMBLE: 13 7e1c 0004 [seq:2] 0102 [16-byte const body] [crc8]
 *   COMMAND : 0b 7e1c 0004 [seq:2] 05   [16-byte body + 1 tag] [crc8]
 *
 * The app always broadcasts the preamble before any command, so we do the same.
 * crc8: poly 0x07, init 0x7d, over all bytes except the crc itself.
 */
class TuyaBeaconBroadcaster(context: Context, private val onLog: (String) -> Unit) {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val advertiser: BluetoothLeAdvertiser? = adapter?.bluetoothLeAdvertiser
    private val handler = Handler(Looper.getMainLooper())

    /** Start above the highest sequence seen from the app in the capture (0x0039). */
    private var seq = 0x0040
    private var activeCb: AdvertiseCallback? = null
    private var generation = 0

    fun turnOn() = runSequence(ON, "ON")
    fun turnOff() = runSequence(OFF, "OFF")
    fun setRed() = runSequence(RED, "RED")

    /** Visible blink for notifications. */
    fun blink() {
        turnOn()
        handler.postDelayed({ turnOff() }, 6000)
        handler.postDelayed({ turnOn() }, 12000)
    }

    /** Preamble, then the command — mirroring what the Smart Life app does. */
    private fun runSequence(cmd17: ByteArray, label: String) {
        if (advertiser == null) {
            onLog("✗ No BLE advertiser — Bluetooth off, or this phone can't broadcast.")
            return
        }
        val pre = buildPreamble(seq++)
        onLog("TX preamble : ${pre.toHex()}")
        broadcast(pre, "preamble", PREAMBLE_MS)

        handler.postDelayed({
            val cmd = buildCommand(cmd17, seq++)
            onLog("TX $label : ${cmd.toHex()}")
            broadcast(cmd, label, COMMAND_MS)
        }, PREAMBLE_MS)
    }

    /**
     * Brute-force a rising sequence number in case the bulb enforces anti-replay.
     * Sends the preamble once, then many command frames.
     */
    fun sweepOn(count: Int = 120, stepMs: Long = 180) {
        if (advertiser == null) { onLog("✗ No BLE advertiser"); return }
        onLog("▶ SWEEP: preamble then seq ${"%04x".format(seq + 1)}.. (~${count * stepMs / 1000}s) — watch the bulb!")
        broadcast(buildPreamble(seq++), "preamble", PREAMBLE_MS)
        for (i in 0 until count) {
            handler.postDelayed({
                broadcast(buildCommand(ON, seq++), "ON", stepMs + 80, quiet = true)
            }, PREAMBLE_MS + i * stepMs)
        }
        handler.postDelayed({
            stop(); onLog("SWEEP done. Next seq=${"%04x".format(seq)}")
        }, PREAMBLE_MS + count * stepMs + 400)
    }

    private fun broadcast(
        payload: ByteArray,
        label: String,
        durationMs: Long,
        quiet: Boolean = false
    ) {
        val adv = advertiser ?: return
        val gen = ++generation

        val dataB = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
        for (i in 0 until 13) {
            val lo = payload[i * 2].toInt() and 0xFF
            val hi = payload[i * 2 + 1].toInt() and 0xFF
            dataB.addServiceUuid(uuid16((hi shl 8) or lo))
        }

        // connectable=true so Android includes the Flags AD (02 01 01), matching the app.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        stop()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (!quiet) onLog("▶ $label on air ${durationMs}ms")
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
        handler.postDelayed({ if (gen == generation) stop() }, durationMs)
    }

    fun stop() {
        val cb = activeCb ?: return
        activeCb = null
        try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
    }

    private fun buildPreamble(s: Int): ByteArray {
        val p = ByteArray(25)
        p[0] = 0x13; p[1] = 0x7e; p[2] = 0x1c
        p[3] = ((EPOCH shr 8) and 0xFF).toByte(); p[4] = (EPOCH and 0xFF).toByte()
        p[5] = ((s shr 8) and 0xFF).toByte(); p[6] = (s and 0xFF).toByte()
        p[7] = 0x01; p[8] = 0x02
        System.arraycopy(PREAMBLE_BODY, 0, p, 9, 16)
        return p + byteArrayOf(crc8(p).toByte())
    }

    private fun buildCommand(cmd17: ByteArray, s: Int): ByteArray {
        val p = ByteArray(25)
        p[0] = 0x0b; p[1] = 0x7e; p[2] = 0x1c
        p[3] = ((EPOCH shr 8) and 0xFF).toByte(); p[4] = (EPOCH and 0xFF).toByte()
        p[5] = ((s shr 8) and 0xFF).toByte(); p[6] = (s and 0xFF).toByte()
        p[7] = 0x05
        System.arraycopy(cmd17, 0, p, 8, 17)
        return p + byteArrayOf(crc8(p).toByte())
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
        val msb = ((v.toLong() and 0xFFFF) shl 32) or 0x1000L
        val lsb = -0x7FFFFF7FA064CB05L  // 0x800000805F9B34FB
        return ParcelUuid(UUID(msb, lsb))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val PREAMBLE_MS = 2000L
        private const val COMMAND_MS = 3000L

        /**
         * Captured 2026-07-31 after re-pairing the bulb. Re-binding regenerates the
         * key, so the 16-byte AES blocks change — byte[8] (the command id) does not.
         * If the bulb is ever removed/re-added again, these must be re-captured.
         */
        private const val EPOCH = 0x0008

        val PREAMBLE_BODY = hex("01a3995897a060bcba1ccf674de551a7")
        val ON = hex("33ce41efa6d7b9782770bb518e60132c10")
        val OFF = hex("d6b1bf63503f8f85a54347f805cce4d042")
        val RED = hex("d783f8bf0cd4dc8a70e977c7fb86546fc5")

        private fun hex(s: String) =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
