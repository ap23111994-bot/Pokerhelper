package com.example.pokerhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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
                val fullTextTask = recognizer.process(InputImage.fromBitmap(bitmap, 0))
                val cardSlots = PokerCardImageReader.slots(bitmap.width, bitmap.height)
                val playerSlots = PokerPlayerImageReader.slots(bitmap.width, bitmap.height)
                val cardTasks = cardSlots.map { slot ->
                    val crop = Bitmap.createBitmap(bitmap, slot.left, slot.top, slot.width, slot.height)
                    val enlarged = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
                    val task = recognizer.process(InputImage.fromBitmap(enlarged, 0))
                    task.addOnCompleteListener {
                        enlarged.recycle()
                        crop.recycle()
                    }
                    task
                }
                val playerTasks = playerSlots.map { slot ->
                    val crop = Bitmap.createBitmap(bitmap, slot.left, slot.top, slot.width, slot.height)
                    val enlarged = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
                    val task = recognizer.process(InputImage.fromBitmap(enlarged, 0))
                    task.addOnCompleteListener {
                        enlarged.recycle()
                        crop.recycle()
                    }
                    task
                }
                val allTasks = mutableListOf<Task<*>>(fullTextTask)
                allTasks.addAll(cardTasks)
                allTasks.addAll(playerTasks)
                Tasks.whenAllComplete(allTasks).addOnCompleteListener {
                    try {
                        val fullText = if (fullTextTask.isSuccessful) fullTextTask.result?.text.orEmpty() else ""
                        val recognizedCards = cardSlots.indices.mapNotNull { index ->
                            val slot = cardSlots[index]
                            val task = cardTasks[index]
                            if (task.isSuccessful && PokerCardImageReader.hasCard(bitmap, slot)) {
                                PokerCardImageReader.parseCard(task.result?.text.orEmpty(), bitmap, slot)
                                    ?.let { slot.label to it }
                            } else null
                        }.toMap()
                        val recognizedPlayers = playerSlots.indices.mapNotNull { index ->
                            val task = playerTasks[index]
                            if (task.isSuccessful) {
                                PokerPlayerImageReader.parse(playerSlots[index], task.result?.text.orEmpty())
                                    ?.let { playerSlots[index].label to it }
                            } else null
                        }.toMap()
                        updateOverlay(PokerTextParser.summarize(fullText, recognizedCards, recognizedPlayers))
                    } catch (_: Exception) {
                        updateOverlay("Не удалось распознать карты. Проверьте положение стола.")
                    } finally {
                        bitmap.recycle()
                        processing.set(false)
                    }
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
        val density = resources.displayMetrics.density
        val text = TextView(this).apply {
            text = "ПОКЕРНЫЙ ПОМОЩНИК\nПодключаюсь к экрану…"
            textSize = 12f
            setTextColor(0xFFF5F7F6.toInt())
            setBackgroundColor(0xEE14211D.toInt())
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            maxLines = 8
            setLineSpacing(2f, 1.0f)
            elevation = 12f * density
        }
        val params = LayoutParams(
            (resources.displayMetrics.widthPixels * 0.58f).toInt(),
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * density).toInt()
            y = (64 * density).toInt()
        }
        text.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX + event.rawX - touchX).toInt().coerceAtLeast(0)
                        params.y = (startY + event.rawY - touchY).toInt().coerceAtLeast(0)
                        try { wm.updateViewLayout(text, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })
        try {
            wm.addView(text, params)
            overlay = text
            overlayParams = params
        } catch (_: Exception) {
            // OCR can still run with the persistent notification if overlay permission was revoked.
        }
    }

    private fun updateOverlay(message: String) {
        val safe = message.take(520)
        android.os.Handler(mainLooper).post {
            overlay?.text = "ПОКЕРНЫЙ ПОМОЩНИК\n$safe"
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
            .setContentTitle("Распознавание стола — Покерный помощник")
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

private object PokerCardImageReader {
    data class Slot(val label: String, val left: Int, val top: Int, val width: Int, val height: Int)

    fun slots(screenWidth: Int, screenHeight: Int): List<Slot> {
        fun slot(label: String, x: Double, y: Double, w: Double, h: Double) = Slot(
            label,
            (screenWidth * x).toInt().coerceIn(0, screenWidth - 1),
            (screenHeight * y).toInt().coerceIn(0, screenHeight - 1),
            (screenWidth * w).toInt().coerceAtLeast(1).coerceAtMost(screenWidth - (screenWidth * x).toInt().coerceIn(0, screenWidth - 1)),
            (screenHeight * h).toInt().coerceAtLeast(1).coerceAtMost(screenHeight - (screenHeight * y).toInt().coerceIn(0, screenHeight - 1))
        )
        val board = (0 until 5).map { i -> slot("Стол ${i + 1}", 0.195 + i * 0.12, 0.425, 0.116, 0.09) }
        val hero = listOf(slot("Ваша карта 1", 0.56, 0.76, 0.095, 0.08), slot("Ваша карта 2", 0.615, 0.76, 0.105, 0.08))
        return board + hero
    }

    fun hasCard(bitmap: Bitmap, slot: Slot): Boolean {
        var bright = 0
        var total = 0
        val x0 = slot.left + slot.width / 8
        val x1 = slot.left + slot.width * 7 / 8
        val y0 = slot.top + slot.height / 10
        val y1 = slot.top + slot.height * 9 / 10
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val pixel = bitmap.getPixel(x, y)
            val r = AndroidColor.red(pixel); val g = AndroidColor.green(pixel); val b = AndroidColor.blue(pixel)
            if (r > 190 && g > 190 && b > 180 && kotlin.math.abs(r - g) < 45 && kotlin.math.abs(g - b) < 55) bright++
            total++
        }
        return total > 0 && bright.toDouble() / total > 0.24
    }

    fun parseCard(text: String, bitmap: Bitmap, slot: Slot): PlayingCard? {
        val rankMatch = Regex("(?i)(10|[2-9]|[AKQJT])").find(text.replace("O", "0")) ?: return null
        val rank = when (rankMatch.value.uppercase(Locale.ROOT)) {
            "A" -> 14; "K" -> 13; "Q" -> 12; "J" -> 11; "T", "10" -> 10
            else -> rankMatch.value.toIntOrNull() ?: return null
        }
        val suit = detectSuit(bitmap, slot) ?: return null
        return runCatching { PlayingCard(rank, suit) }.getOrNull()
    }

    private fun detectSuit(bitmap: Bitmap, slot: Slot): Char? {
        var red = 0; var green = 0; var blue = 0; var dark = 0
        val x0 = slot.left + slot.width / 7
        val x1 = slot.left + slot.width * 6 / 7
        val y0 = slot.top + slot.height * 38 / 100
        val y1 = slot.top + slot.height * 92 / 100
        for (y in y0 until y1 step 1) for (x in x0 until x1 step 1) {
            val pixel = bitmap.getPixel(x, y)
            val r = AndroidColor.red(pixel); val g = AndroidColor.green(pixel); val b = AndroidColor.blue(pixel)
            when {
                r > 125 && r > g * 1.35 && r > b * 1.25 -> red++
                g > 75 && g > r * 1.22 && g > b * 1.15 -> green++
                b > 100 && b > r * 1.18 && b > g * 1.10 -> blue++
                r < 65 && g < 65 && b < 65 -> dark++
            }
        }
        val maxColor = maxOf(red, green, blue)
        if (maxColor < 8 && dark < 20) return null
        return when {
            red >= green && red >= blue && red >= 8 -> 'h'
            blue >= red && blue >= green && blue >= 8 -> 'd'
            green >= red && green >= blue && green >= 8 -> 'c'
            dark >= 20 -> 's'
            else -> null
        }
    }
}

private object PokerPlayerImageReader {
    data class Slot(val label: String, val left: Int, val top: Int, val width: Int, val height: Int)

    data class PlayerInfo(val name: String, val stack: String?, val status: String)

    fun slots(screenWidth: Int, screenHeight: Int): List<Slot> {
        fun slot(label: String, x: Double, y: Double, w: Double, h: Double) = Slot(
            label,
            (screenWidth * x).toInt().coerceIn(0, screenWidth - 1),
            (screenHeight * y).toInt().coerceIn(0, screenHeight - 1),
            (screenWidth * w).toInt().coerceAtLeast(1).coerceAtMost(screenWidth - (screenWidth * x).toInt().coerceIn(0, screenWidth - 1)),
            (screenHeight * h).toInt().coerceAtLeast(1).coerceAtMost(screenHeight - (screenHeight * y).toInt().coerceIn(0, screenHeight - 1))
        )
        // Calibrated for the portrait PPPoker layout shown in the user's screenshots.
        return listOf(
            slot("Сверху слева", 0.015, 0.392, 0.19, 0.048),
            slot("Сверху справа", 0.80, 0.392, 0.19, 0.048),
            slot("Слева", 0.015, 0.632, 0.19, 0.048),
            slot("Справа", 0.81, 0.595, 0.17, 0.045),
            slot("Вы (снизу)", 0.405, 0.823, 0.19, 0.047)
        )
    }

    fun parse(slot: Slot, text: String): PlayerInfo? {
        val cleanLines = text.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanLines.isEmpty()) return null
        val all = cleanLines.joinToString(" ").lowercase(Locale.ROOT)
        if (all.contains("empty") || all.contains("пусто")) return PlayerInfo("Пустое место", null, "ПУСТО")
        if (all.contains("вне игры") || all.contains("fold")) {
            val name = cleanLines.firstOrNull { it.any(Char::isLetter) && !it.contains("Fold", true) && !it.contains("Фолд", true) }
                ?: slot.label
            val stack = findStack(cleanLines)
            return PlayerInfo(name.take(22), stack, "ПАС")
        }
        val stack = findStack(cleanLines)
        val name = cleanLines.firstOrNull { line ->
            line.any(Char::isLetter) && findStack(listOf(line)) == null &&
                !line.contains("NLH", true) && !line.contains("bank", true) &&
                !line.contains("блайнд", true)
        }?.replace(Regex("\\s+"), " ")?.take(22) ?: return null
        return PlayerInfo(name, stack, "В ИГРЕ")
    }

    private fun findStack(lines: List<String>): String? {
        val pattern = Regex("(?<![A-Za-z])\\d[\\d, .]{1,12}\\d(?![A-Za-z])")
        return lines.asSequence().flatMap { pattern.findAll(it).map { match -> match.value.trim() } }
            .map { it.replace(" ", "").replace(",", "") }
            .firstOrNull { value -> value.filter(Char::isDigit).length >= 2 }
            ?.let { value ->
                val digits = value.filter(Char::isDigit)
                digits.reversed().chunked(3).joinToString(" ").reversed()
            }
    }
}

private object PokerTextParser {
    private val numberPattern = Regex("[0-9][0-9, .]*")

    fun summarize(
        text: String,
        cards: Map<String, PlayingCard> = emptyMap(),
        players: Map<String, PokerPlayerImageReader.PlayerInfo> = emptyMap()
    ): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val result = mutableListOf<String>()
        val normalized = lines.map { line -> line.lowercase(Locale.ROOT) }

        if (normalized.any { Regex("\\bnlh\\b").containsMatchIn(it) }) {
            result += "Игра: NLH (техасский холдем)"
        }
        val potLineIndex = normalized.indexOfFirst { line ->
            line.contains("pot") || line.contains("bank") || line.contains("банк") || line.contains("bahk")
        }
        if (potLineIndex >= 0) {
            val number = numberPattern.find(lines[potLineIndex])?.value?.replace(" ", "")?.replace(",", "")
            if (!number.isNullOrBlank()) result += "Банк: ${formatNumber(number)}"
        }
        val blindLineIndex = lines.indexOfFirst { raw ->
            val line = raw.lowercase(Locale.ROOT)
            line.contains("blind") || line.contains("блайнд") || line.contains("ante") || line.contains("анте")
        }
        if (blindLineIndex >= 0) {
            val nums = numberPattern.findAll(lines[blindLineIndex]).map { it.value.trim().replace(" ", "") }.toList()
            if (nums.size >= 2) result += "Блайнды: ${formatNumber(nums[0])} / ${formatNumber(nums[1])}"
        }

        val heroCards = listOfNotNull(cards["Ваша карта 1"], cards["Ваша карта 2"])
        val boardCards = (1..5).mapNotNull { cards["Стол $it"] }
        result += if (heroCards.size == 2) "Ваши карты: ${heroCards.joinToString(" ") { it.label }}" else
            "Ваши карты: ${heroCards.joinToString(" ") { it.label }} (распознано ${heroCards.size}/2)"
        result += if (boardCards.isNotEmpty()) "Карты на столе: ${boardCards.joinToString(" ") { it.label }}" else
            "Карты на столе: не распознаны (проверьте масштаб/ракурс)"

        if (players.isNotEmpty()) {
            result += "ИГРОКИ:"
            players.entries.forEach { (seat, player) ->
                val stackText = player.stack?.let { " · стек $it" } ?: ""
                result += "$seat: ${player.name}$stackText · ${player.status}"
            }
        } else {
            result += "Игроки: распознавание не удалось"
        }
        result += "Позиция: нижнее место — вы"
        result += "Проверьте имена и стеки: OCR может ошибаться."
        return result.take(9).joinToString("\\n")
    }

    private fun formatNumber(value: String): String {
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty()) return value
        return digits.reversed().chunked(3).joinToString(" ").reversed()
    }
}
