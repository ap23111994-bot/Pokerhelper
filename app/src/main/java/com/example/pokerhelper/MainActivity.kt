package com.example.pokerhelper

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.roundToInt

data class Card(val rank: Int, val suit: Char) {
    override fun toString(): String {
        val rStr = when (rank) {
            14 -> "A"; 13 -> "K"; 12 -> "Q"; 11 -> "J"; 10 -> "10"
            else -> rank.toString()
        }
        val sStr = when (suit) {
            'h' -> "♥"; 'd' -> "♦"; 'c' -> "♣"; else -> "♠"
        }
        return "$rStr$sStr"
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var isTournamentMode = false
    private var startSB = 10.0
    private var startBB = 20.0
    private var blindMinutes = 10

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenScannerService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                putExtra("IS_TOURNAMENT", isTournamentMode)
                putExtra("SB", startSB)
                putExtra("BB", startBB)
                putExtra("BLIND_MINUTES", blindMinutes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish()
        } else {
            Toast.makeText(this, "Разрешение на запись экрана не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        showMainMenu()
    }

    private fun showMainMenu() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "Выберите режим покера"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }

        val btnCash = Button(this).apply {
            text = "💵 КЭШ-ИГРА"
            textSize = 18f
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                isTournamentMode = false
                checkPermissionsAndStart()
            }
        }

        val btnTournament = Button(this).apply {
            text = "🏆 ТУРНИР (с ростом блайндов)"
            textSize = 18f
            setPadding(24, 24, 24, 24)
            setOnClickListener { showTournamentSetup() }
        }

        layout.addView(title)
        layout.addView(btnCash)
        layout.addView(btnTournament)
        setContentView(layout)
    }

    private fun showTournamentSetup() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "Настройки турнира"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 32)
        }

        val etSB = EditText(this).apply {
            hint = "Начальный Малый блайнд (например, 10)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val etBB = EditText(this).apply {
            hint = "Начальный Большой блайнд (например, 20)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val etTimer = EditText(this).apply {
            hint = "Рост блайндов каждые (минут, например, 5)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val btnStart = Button(this).apply {
            text = "🚀 Запустить в реальном времени"
            textSize = 16f
            setOnClickListener {
                startSB = etSB.text.toString().toDoubleOrNull() ?: 10.0
                startBB = etBB.text.toString().toDoubleOrNull() ?: 20.0
                blindMinutes = etTimer.text.toString().toIntOrNull() ?: 10
                isTournamentMode = true
                checkPermissionsAndStart()
            }
        }

        layout.addView(title)
        layout.addView(etSB)
        layout.addView(etBB)
        layout.addView(etTimer)
        layout.addView(btnStart)
        setContentView(layout)
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Включите разрешение 'Поверх других окон'", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }
}

class ScreenScannerService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var tvMode: TextView
    private lateinit var tvCards: TextView
    private lateinit var tvAction: TextView

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels
    private val screenDensity = Resources.getSystem().displayMetrics.densityDpi

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var isTournament = false
    private var currentSB = 10.0
    private var currentBB = 20.0
    private var blindIntervalMs = 600_000L

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    // Автоматическое сканирование экрана каждые 2.5 секунды в реальном времени
    private val autoScanRunnable = object : Runnable {
        override fun run() {
            scanScreenAndCards()
            handler.postDelayed(this, 2500L)
        }
    }

    // Автоматический рост блайндов в турнире
    private val blindUpRunnable = object : Runnable {
        override fun run() {
            if (isTournament) {
                currentSB *= 2
                currentBB *= 2
                 updateModeHeader()
                handler.postDelayed(this, blindIntervalMs)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        isTournament = intent?.getBooleanExtra("IS_TOURNAMENT", false) ?: false
        currentSB = intent?.getDoubleExtra("SB", 10.0) ?: 10.0
        currentBB = intent?.getDoubleExtra("BB", 20.0) ?: 20.0
        val mins = intent?.getIntExtra("BLIND_MINUTES", 10) ?: 10
        blindIntervalMs = (mins.coerceAtLeast(1)) * 60_000L

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }

        setupFloatingWindow()

        // Запускаем циклы реального времени
        handler.postDelayed(autoScanRunnable, 1500L)
        if (isTournament) {
            handler.postDelayed(blindUpRunnable, blindIntervalMs)
        }

        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PokerLiveScanner",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6141414"))
            setPadding(20, 14, 20, 14)
        }

        // Верхняя строка: режим + кнопка [+Блайнд] + кнопка [X]
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        tvMode = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#90CAF9"))
            setPadding(0, 0, 16, 0)
        }
        updateModeHeader()

        val btnBlindUp = TextView(this).apply {
            text = if (isTournament) "[+Блайнд] " else ""
            textSize = 10f
            setTextColor(Color.YELLOW)
            setOnClickListener {
                if (isTournament) {
                    currentSB *= 2
                    currentBB *= 2
                    updateModeHeader()
                }
            }
        }

        val btnClose = TextView(this).apply {
            text = "✖"
            textSize = 13f
            setTextColor(Color.parseColor("#FF5252"))
            setPadding(12, 0, 0, 0)
            setOnClickListener { stopSelf() }
        }

        topRow.addView(tvMode)
        if (isTournament) topRow.addView(btnBlindUp)
        topRow.addView(btnClose)

        tvCards = TextView(this).apply {
            text = "Авто-скан..."
            setTextColor(Color.LTGRAY)
            textSize = 11f
        }

        tvAction = TextView(this).apply {
            text = "Ожидание раздачи..."
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 4, 0, 0)
        }

        container.addView(topRow)
        container.addView(tvCards)
        container.addView(tvAction)
        floatingView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 100
        }

        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
    }

    private fun updateModeHeader() {
        tvMode.text = if (isTournament) {
            "🏆 Турнир (${currentSB.toInt()}/${currentBB.toInt()})"
        } else {
            "💵 Кэш LIVE"
        }
    }

    private fun scanScreenAndCards() {
        if (isScanning) return
        val image = imageReader?.acquireLatestImage() ?: return
        isScanning = true

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                var detectedPot = 0.0
                var detectedCall = 0.0
                val numberRegex = Regex("[0-9]+(\\.[0-9]+)?")

                val boardCards = mutableListOf<Pair<Int, Card>>()
                val heroCards = mutableListOf<Pair<Int, Card>>()

                for (block in visionText.textBlocks) {
                    val blockText = block.text.lowercase().replace(",", "")

                    if (blockText.contains("pot") || blockText.contains("банк") || blockText.contains("total")) {
                        val num = numberRegex.find(blockText)?.value?.toDoubleOrNull()
                        if (num != null && num > 0) detectedPot = num
                    }

                    if (blockText.contains("call") || blockText.contains("колл") || blockText.contains("уравн")) {
                        val num = numberRegex.find(blockText)?.value?.toDoubleOrNull()
                        if (num != null && num > 0) detectedCall = num
                    }

                    for (line in block.lines) {
                        for (element in line.elements) {
                            val elBox = element.boundingBox ?: continue
                            val rank = parseCardRank(element.text.trim()) ?: continue
                            val suit = detectSuitByColor(bitmap, elBox)
                            val card = Card(rank, suit)
                            val yRatio = elBox.centerY().toFloat() / screenHeight

                            if (yRatio in 0.33f..0.58f) {
                                if (boardCards.none { abs(it.first - elBox.centerX()) < screenWidth * 0.04 }) {
                                    boardCards.add(elBox.centerX() to card)
                                }
                            } else if (yRatio in 0.59f..0.85f) {
                                if (heroCards.none { abs(it.first - elBox.centerX()) < screenWidth * 0.04 }) {
                                    heroCards.add(elBox.centerX() to card)
                                }
                            }
                        }
                    }
                }

                val sortedBoard = boardCards.sortedBy { it.first }.map { it.second }.take(5)
                val sortedHero = heroCards.sortedBy { it.first }.map { it.second }.take(2)

                evaluateAndShow(detectedPot, detectedCall, sortedHero, sortedBoard)
                isScanning = false
            }
            .addOnFailureListener {
                isScanning = false
            }
    }

    private fun parseCardRank(raw: String): Int? {
        val clean = raw.uppercase().replace("0", "Q").trim()
        return when (clean) {
            "A", "А" -> 14; "K", "К" -> 13; "Q" -> 12; "J" -> 11
            "10", "1O", "T" -> 10
            "9" -> 9; "8" -> 8; "7" -> 7; "6" -> 6
            "5" -> 5; "4" -> 4; "3" -> 3; "2" -> 2
            else -> null
        }
    }

    private fun detectSuitByColor(bitmap: Bitmap, box: Rect): Char {
        var redCount = 0; var greenCount = 0; var blueCount = 0; var darkCount = 0
        val startX = box.left.coerceAtLeast(0)
        val endX = box.right.coerceAtMost(bitmap.width - 1)
        val startY = box.top.coerceAtLeast(0)
        val endY = (box.bottom + box.height()).coerceAtMost(bitmap.height - 1)

        for (y in startY..endY step 2) {
            for (x in startX..endX step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                if (r > 200 && g > 200 && b > 200) continue
                if (r > 150 && r > g + 45 && r > b + 45) redCount++
                else if (b > 130 && b > r + 35 && b > g + 20) blueCount++
                else if (g > 120 && g > r + 30 && g > b + 25) greenCount++
                else if (r < 85 && g < 85 && b < 85) darkCount++
            }
        }
        val maxColor = maxOf(redCount, blueCount, greenCount, darkCount)
        return when (maxColor) {
            redCount -> 'h'; blueCount -> 'd'; greenCount -> 'c'; else -> 's'
        }
    }

    private fun evaluateAndShow(pot: Double, call: Double, hero: List<Card>, board: List<Card>) {
        val heroStr = if (hero.isEmpty()) "--" else hero.joinToString(" ")
        val boardStr = if (board.isEmpty()) "Префлоп" else board.joinToString(" ")
        tvCards.text = "Рука: $heroStr | Стол: $boardStr"

        val ( baseEquity, outsCount, handNote) = PokerOutsCalculator.calculateOutsAndEquity(hero, board)

        // В турнире при высоких блайндах учитываем давление блайндов (ICM/Push-Fold корректировка)
        val effectivePot = if (pot > 0) pot else if (isTournament) (currentSB + currentBB) else 0.0
        val effectiveCall = if (call > 0) call else 0.0

        if (effectivePot <= 0 || effectiveCall <= 0) {
            val preAction = if (baseEquity >= 55) "🚀 РЕЙЗ (RAISE)" else "✅ ЧЕК / ОЖИДАНИЕ"
            tvAction.text = "$handNote (Шанс: $baseEquity%)\n👉 $preAction"
            tvAction.setTextColor(if (baseEquity >= 55) Color.parseColor("#69F0AE") else Color.YELLOW)
            return
        }

        val potOdds = (effectiveCall / (effectivePot + effectiveCall)) * 100

        // В турнире требования к эквити чуть строже при коллах на стек
        val requiredEquity = if (isTournament && effectiveCall >= currentBB * 5) potOdds * 1.15 else potOdds

        val action: String
        val color: Int
        when {
            baseEquity < requiredEquity -> {
                action = "❌ ПАС / ФОЛД (FOLD)"
                color = Color.parseColor("#FF5252")
            }
            baseEquity >= requiredEquity * 1.55 || baseEquity >= 54 -> {
                action = "🚀 ПОВЫШАТЬ / РЕЙЗ (RAISE)"
                color = Color.parseColor("#69F0AE")
            }
            else -> {
                action = "✅ УРАВНЯТЬ / КОЛЛ (CALL)"
                color = Color.parseColor("#FFD700")
            }
        }

        tvAction.text = "$handNote (Аутов: $outsCount)\nБанк: ${potOdds.roundToInt()}% | Шанс: $baseEquity%\n👉 $action"
        tvAction.setTextColor(color)
    }

    private fun startForegroundNotification() {
        val channelId = "poker_live_channel"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Poker Live Helper", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Покер-анализатор работает")
            .setContentText("Режим реального времени активен")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
    }
}

object PokerOutsCalculator {
    fun calculateOutsAndEquity(hero: List<Card>, board: List<Card>): Triple<Int, Int, String> {
        val allCards = hero + board
        if (hero.size < 2) return Triple(25, 6, "Ожидание карт")

        if (board.size < 3) {
            val r1 = maxOf(hero[0].rank, hero[1].rank)
            val r2 = minOf(hero[0].rank, hero[1].rank)
            val suited = hero[0].suit == hero[1].suit

            return when {
                r1 == r2 && r1 >= 10 -> Triple(78, 2, "Премиум пара")
                r1 == r2 -> Triple(58, 2, "Карманная пара")
                r1 == 14 && r2 >= 11 -> Triple(65, 6, "Сильный туз (AK/AQ/AJ)")
                r1 >= 12 && r2 >= 10 && suited -> Triple(55, 6, "Одномастный бродвей")
                suited && (r1 - r2 == 1) -> Triple(44, 8, "Одномастные коннекторы")
                else -> Triple(32, 6, "Слабая стартовая")
            }
        }

        val multiplier = if (board.size == 3) 4 else 2
        val maxBoardRank = board.maxOf { it.rank }
        val rankCounts = allCards.groupingBy { it.rank }.eachCount()
        val suitCounts = allCards.groupingBy { it.suit }.eachCount()
        val maxSuitCount = suitCounts.values.maxOrNull() ?: 0

        if (maxSuitCount >= 5) return Triple(88, 0, "Готовый ФЛЕШ!")
        if (hasStraight(allCards.map { it.rank })) return Triple(82, 0, "Готовый СТРИТ!")
        if ((rankCounts.values.maxOrNull() ?: 0) >= 3) return Triple(78, 7, "СЕТ / ТРИПС!")

        val heroRanks = hero.map { it.rank }
        val hasTopPair = heroRanks.any { it == maxBoardRank && (rankCounts[it] ?: 0) >= 2 }
        val hasOverpair = hero[0].rank == hero[1].rank && hero[0].rank > maxBoardRank

        if (hasOverpair) return Triple(74, 2, "Оверпара!")
        if (hasTopPair) return Triple(62, 5, "Топ-пара")

        val suits = listOf('h', 'd', 'c', 's')
        val fullDeck = mutableListOf<Card>()
        for (r in 2..14) {
            for (s in suits) {
                if (allCards.none { it.rank == r && it.suit == s }) fullDeck.add(Card(r, s))
            }
        }

        val outCards = mutableSetOf<Card>()
        val notes = mutableListOf<String>()

        val flushDrawSuit = suitCounts.entries.find { it.value == 4 }?.key
        if (flushDrawSuit != null) {
            notes.add("Флеш-дро")
            fullDeck.filter { it.suit == flushDrawSuit }.forEach { outCards.add(it) }
        }

        var straightOutsCount = 0
        for (candidate in fullDeck) {
            if (hasStraight((allCards + candidate).map { it.rank })) {
                outCards.add(candidate)
                straightOutsCount++
            }
        }
        if (straightOutsCount >= 8) notes.add("Стрит-дро")
        else if (straightOutsCount > 0) notes.add("Гатшот")

        for (hCard in hero) {
            if (hCard.rank > maxBoardRank) {
                fullDeck.filter { it.rank == hCard.rank }.forEach { outCards.add(it) }
            }
        }
        if (hero.any { it.rank > maxBoardRank }) notes.add("Оверкарты")

        if (outCards.isEmpty()) {
            for (hCard in hero) {
                fullDeck.filter { it.rank == hCard.rank }.forEach { outCards.add(it) }
            }
            notes.add("Совпадений нет")
        }

        val totalOuts = outCards.size
        val equity = (totalOuts * multiplier).coerceIn(10, 95)
        return Triple(equity, totalOuts, notes.joinToString("+"))
    }

    private fun hasStraight(ranks: List<Int>): Boolean {
        val unique = ranks.toMutableSet()
        if (unique.contains(14)) unique.add(1)
        val sorted = unique.sorted()
        var consecutive = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1] + 1) {
                consecutive++
                if (consecutive >= 5) return true
            } else {
                consecutive = 1
            }
        }
        return false
    }
}
