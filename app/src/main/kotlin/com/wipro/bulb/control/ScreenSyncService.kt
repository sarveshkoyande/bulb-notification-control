package com.wipro.bulb.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that captures this phone's own screen (via MediaProjection),
 * samples the centre pixel a few times a second, and drives the bulb's colour to
 * match — a phone-side "ambient light" mode.
 *
 * Downscales the virtual display to a small buffer (the system does the scaling),
 * so the sampled "centre pixel" is really an average of the screen's centre region,
 * not a single raw pixel — desirable for a smoother, less flickery result.
 */
class ScreenSyncService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private lateinit var sdkControl: BulbSdkController
    private var lastSentHsv: FloatArray? = null
    private var lastSentAt = 0L

    override fun onCreate() {
        super.onCreate()
        sdkControl = BulbSdkController(applicationContext) { Log.d(TAG, it) }
        bgThread = HandlerThread("ScreenSyncCapture").apply { start() }
        bgHandler = Handler(bgThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        startForeground(NOTIF_ID, buildNotification())

        if (data == null || resultCode == 0) {
            Log.e(TAG, "Missing projection permission result — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)
        mediaProjection = projection

        // Required on API 34+ before createVirtualDisplay(), harmless on older versions.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopSelf()
            }
        }, bgHandler)

        startCapture(projection)
        return START_STICKY
    }

    private fun startCapture(projection: MediaProjection) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getMetrics(metrics)

        val reader = ImageReader.newInstance(
            CAPTURE_SIZE, CAPTURE_SIZE, PixelFormat.RGBA_8888, 2
        )
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "BulbScreenSync",
            CAPTURE_SIZE, CAPTURE_SIZE, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler
        )

        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (e: Exception) { null }
            image?.use { img -> onFrame(img) }
        }, bgHandler)
    }

    private fun onFrame(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val cx = CAPTURE_SIZE / 2
        val cy = CAPTURE_SIZE / 2
        val offset = cy * rowStride + cx * pixelStride
        if (offset + 3 >= buffer.capacity()) return

        val r = buffer.get(offset).toInt() and 0xFF
        val g = buffer.get(offset + 1).toInt() and 0xFF
        val b = buffer.get(offset + 2).toInt() and 0xFF

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)

        maybeSend(hsv)
    }

    private fun maybeSend(hsv: FloatArray) {
        val now = System.currentTimeMillis()
        val last = lastSentHsv
        val changedEnough = last == null ||
            kotlin.math.abs(last[0] - hsv[0]) > 8f ||
            kotlin.math.abs(last[1] - hsv[1]) > 0.06f ||
            kotlin.math.abs(last[2] - hsv[2]) > 0.06f

        if (now - lastSentAt < MIN_INTERVAL_MS || !changedEnough) return
        lastSentAt = now
        lastSentHsv = hsv

        val hue = hsv[0].toInt().coerceIn(0, 360)
        val sat = (hsv[1] * 1000).toInt().coerceIn(0, 1000)
        val value = (hsv[2] * 1000).toInt().coerceIn(0, 1000)
        sdkControl.setColor(hue, sat, value)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen colour sync", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ScreenSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen colour sync active")
            .setContentText("Bulb is matching your screen")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        bgThread.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenSyncService"
        private const val CHANNEL_ID = "screen_sync"
        private const val NOTIF_ID = 42
        private const val CAPTURE_SIZE = 64
        private const val MIN_INTERVAL_MS = 1200L
        const val ACTION_STOP = "com.wipro.bulb.control.STOP_SCREEN_SYNC"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }
}

private inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        this?.close()
    }
}
