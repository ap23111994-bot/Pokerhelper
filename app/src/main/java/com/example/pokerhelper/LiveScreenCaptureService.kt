package com.example.pokerhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started screen capture for a visible training table.
 * Android always shows its system MediaProjection indicator while capture is active.
 */
class LiveScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var overlay: TextView? = null
    private var overlayParams: LayoutParams? = null
    private val processing = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            updateOverlay("Не получено разрешение на захват экрана.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_STICKY

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data).also { mediaProjection ->
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, Handler(mainLooper))
        }
        createCapturePipeline()
        return START_STICKY
    }

    private fun createCapturePipeline() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi
        workerThread = HandlerThread("PokerHelper-OCR").also { it.start() }
        worker = Handler(workerThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS || !processing.compareAndSet(false, true)) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage()
            if (image == null) {
                processing.set(false)
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            try {
                val bitmap = imageToBitmap(image)
                image.close()
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        val summary = PokerTextParser.summarize(result.text)
                        updateOverlay(summary)
                    }
                    .addOnFailureListener { updateOverlay("Не удалось распознать текст. Повторная попытка…") }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        processing.set(false)
                    }
            } catch (_: Exception) {
                image.close()
                processing.set(false)
            }
        }, worker)
        virtualDisplay = projection?.createVirtualDisplay(
            "PokerHelperTrainingCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            worker
        )
        if (overlay == null) createOverlay()
        updateOverlay("Захват экрана включён. Откройте тренировочный стол.\nКарты и позиции пока не распознаются автоматически.")
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = image.width + (rowStride - pixelStride * image.width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val text = TextView(this).apply {
            text = "Покерный помощник\nОжидание изображения…"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xDD14211D.toInt())
            setPadding(18, 14, 18, 14)
        }
        val params = LayoutParams(
            (resources.displayMetrics.widthPixels * 0.72f).toInt(),
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = (resources.displayMetrics.density * 90).toInt()
        }
        try {
            wm.addView(text, params)
            overlay = text
            overlayParams = params
        } catch (_: Exception) {
            // OCR can still run with the persistent notification if overlay permission was revoked.
        }
    }

    private fun updateOverlay(message: String) {
        val safe = message.take(650)
        android.os.Handler(mainLooper).post {
            overlay?.text = "ПОКЕРНЫЙ ПОМОЩНИК · ЭКРАН\n$safe"
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "poker_helper_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Распознавание экрана — Покерный помощник", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Poker Helper screen analysis")
            .setContentText("Экран записывается. Нажмите «Остановить» в приложении, чтобы завершить.")
            .setOngoing(true)
            .build()
    }

    private fun stopCapture() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            projection?.stop()
            projection = null
            workerThread?.quitSafely()
            workerThread = null
            worker = null
            if (overlay != null) {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay)
                overlay = null
                overlayParams = null
            }
            if (this::class.java != null) recognizer.close()
        } catch (_: Exception) {
            // Cleanup is best-effort because Android may already have revoked projection.
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.pokerhelper.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 4021
        private const val FRAME_INTERVAL_MS = 850L
    }
}

private object PokerTextParser {
    fun summarize(text: String): String {
        if (text.isBlank()) return "Экран получен, но текст не распознан."
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val patterns = listOf(
            Regex("(?i)\\b(pot|bank)\\b.*"),
            Regex("(?i)\\b(call|bet|raise|all.?in|blind|ante)\\b.*"),
            Regex("(?i)\\b(button|dealer|btn|small blind|big blind|sb|bb|utg|hijack|cut.?off|co)\\b.*")
        )
        val matches = lines.filter { line -> patterns.any { it.containsMatchIn(line) } }
        val heading = if (matches.isNotEmpty()) "Возможные данные стола:" else "Распознанный текст:"
        return (listOf(heading) + (if (matches.isNotEmpty()) matches else lines).take(8)).joinToString("\n")
    }
}
