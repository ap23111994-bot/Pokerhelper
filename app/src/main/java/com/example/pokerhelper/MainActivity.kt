package com.example.pokerhelper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Felt = Color(0xFF101A18)
private val Panel = Color(0xFF1B2925)
private val Gold = Color(0xFFE7C16D)
private val Muted = Color(0xFFA7B6AF)
private val RedCard = Color(0xFFC94E52)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PokerHelperApp() }
    }
}

@Composable
private fun PokerHelperApp() {
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(context, LiveScreenCaptureService::class.java).apply {
                putExtra(LiveScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(LiveScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(serviceIntent)
        }
    }
    var page by remember { mutableIntStateOf(0) }
    val hero = remember { mutableStateListOf<PlayingCard?>(null, null) }
    val board = remember { mutableStateListOf<PlayingCard?>(null, null, null, null, null) }
    var picker by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var result by remember { mutableStateOf<HandAnalysis?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pot by remember { mutableStateOf("100") }
    var call by remember { mutableStateOf("25") }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, secondary = Color(0xFF8DC8A8), background = Felt, surface = Panel, onPrimary = Felt)) {
        Surface(Modifier.fillMaxSize(), color = Felt) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ПОКЕРНЫЙ ПОМОЩНИК", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Gold)
                Text("Анализ раздачи · Таймер турнира", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (!Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } else {
                                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Начать распознавание") }
                    OutlinedButton(
                        onClick = {
                            context.startService(
                                Intent(context, LiveScreenCaptureService::class.java)
                                    .setAction(LiveScreenCaptureService.ACTION_STOP)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Остановить") }
                }
                Text(
                    "Приложение считывает текст с экрана. Автоматическое распознавание карт и позиций игроков пока находится в разработке.",
                    color = Muted,
                    fontSize = 11.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { page = 0 }, modifier = Modifier.weight(1f)) { Text("Раздача") }
                    OutlinedButton(onClick = { page = 1 }, modifier = Modifier.weight(1f)) { Text("Турнир") }
                }
                if (page == 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("ВАШИ КАРТЫ", color = Gold, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            hero.forEachIndexed { i, card -> CardSlot(card?.label ?: "Выбрать", card != null, Modifier.weight(1f)) { picker = "hero" to i } }
                        }
                        Text("ОБЩИЕ КАРТЫ НА СТОЛЕ", color = Gold, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            board.forEachIndexed { i, card -> CardSlot(card?.label ?: "—", card != null, Modifier.weight(1f)) { picker = "board" to i } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Банк", pot, { pot = it }, Modifier.weight(1f))
                            NumberField("Сколько нужно добавить", call, { call = it }, Modifier.weight(1f))
                        }
                        Button(onClick = {
                            val h = hero.filterNotNull(); val b = board.filterNotNull()
                            val p = pot.replace(',', '.').toDoubleOrNull() ?: -1.0; val c = call.replace(',', '.').toDoubleOrNull() ?: -1.0
                            if (h.size == 2 && b.size >= 3 && p >= 0 && c >= 0) {
                                busy = true
                                scope.launch {
                                    result = withContext(Dispatchers.Default) { PokerEngine.analyze(h, b, p, c, iterations = 1500) }
                                    busy = false
                                }
                            } else result = null
                        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Считаю…" else "Рассчитать шансы") }
                        result?.let { AnalysisCard(it) }
                        if (result == null) Text("Выберите 2 свои карты и минимум 3 общие карты на столе.", color = Muted, fontSize = 12.sp)
                    }
                } else TournamentPanel()
            }
        }
    }

    picker?.let { target ->
        CardPickerDialog(
            used = (hero.filterNotNull() + board.filterNotNull()).toSet(),
            onDismiss = { picker = null },
            onSelect = { card ->
                if (target.first == "hero") hero[target.second] = card else board[target.second] = card
                result = null; picker = null
            },
            onClear = {
                if (target.first == "hero") hero[target.second] = null else board[target.second] = null
                result = null; picker = null
            }
        )
    }
}

@Composable
private fun CardSlot(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.height(54.dp).clickable(onClick = onClick), color = if (selected) Color(0xFFF4F1E8) else Panel,
        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (selected) Gold else Color(0xFF40534A))) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = if (selected) Felt else Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d{0,9}([.,]\\d{0,2})?$"))) onChange(it) },
        label = { Text(label) }, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
}

@Composable
private fun AnalysisCard(r: HandAnalysis) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ЧТО ДЕЛАТЬ", color = Gold, fontWeight = FontWeight.Bold)
            Text(
                r.recommendation.russianLabel,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = when (r.recommendation) {
                    PokerAction.FOLD -> Color(0xFFFF7777)
                    PokerAction.CALL -> Color(0xFFFFD166)
                    PokerAction.RAISE, PokerAction.BET -> Color(0xFF83D6A2)
                    PokerAction.CHECK -> Color(0xFF9CCBFF)
                }
            )
            Text(r.recommendationReason, color = Muted, fontSize = 13.sp)
            Text("РЕЗУЛЬТАТ РАСЧЁТА", color = Gold, fontWeight = FontWeight.Bold)
            Text("Шанс на победу: ${"%.1f".format(Locale.US, r.equity * 100)}%", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            r.handName?.let { Text("Ваша комбинация: ${translateHandName(it)}", color = Muted) }
            Text("Необходимая доля банка для колла: ${"%.1f".format(Locale.US, r.potOdds * 100)}%")
            Text("Ожидаемый результат колла: ${if (r.expectedValue >= 0) "+" else ""}${"%.2f".format(Locale.US, r.expectedValue)}")
            Text("Карты для улучшения комбинации: ${r.outs}", color = Muted)
            Text("Оценка по ${r.iterations} симуляциям против случайных карт", color = Muted, fontSize = 11.sp)
            Text("Совет приблизительный: не учитывает диапазон соперника, позицию и действия других игроков.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CardPickerDialog(used: Set<PlayingCard>, onDismiss: () -> Unit, onSelect: (PlayingCard) -> Unit, onClear: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Выберите карту") },
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(340.dp)) {
                items(PlayingCard.deck) { card ->
                    val unavailable = card in used
                    Surface(Modifier.padding(3.dp).size(58.dp).clickable(enabled = !unavailable) { onSelect(card) },
                        color = if (unavailable) Color(0xFF303A36) else Color(0xFFF4F1E8), shape = RoundedCornerShape(8.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(card.label, color = if (unavailable) Muted else if (card.suit == 'h' || card.suit == 'd') RedCard else Felt, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClear) { Text("Убрать карту") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun TournamentPanel() {
    var sb by remember { mutableStateOf("10") }
    var bb by remember { mutableStateOf("20") }
    var minutes by remember { mutableStateOf("10") }
    var seconds by remember { mutableIntStateOf(600) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(running, seconds) {
        if (running) {
            if (seconds > 0) { delay(1000); seconds -= 1 }
            else {
                sb = formatBlind((sb.toDoubleOrNull() ?: 10.0) * 2)
                bb = formatBlind((bb.toDoubleOrNull() ?: 20.0) * 2)
                seconds = (minutes.toIntOrNull()?.coerceIn(1, 180) ?: 10) * 60
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("УРОВЕНЬ БЛАЙНДОВ", color = Gold, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Малый блайнд", sb, { sb = it }, Modifier.weight(1f))
            NumberField("Большой блайнд", bb, { bb = it }, Modifier.weight(1f))
        }
        NumberField("Длительность уровня (мин.)", minutes, { minutes = it; if (!running) seconds = (it.toIntOrNull()?.coerceIn(1, 180) ?: 10) * 60 }, Modifier.fillMaxWidth())
        Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ТЕКУЩИЕ БЛАЙНДЫ", color = Muted, fontSize = 12.sp)
                Text("${sb.ifBlank { "10" }} / ${bb.ifBlank { "20" }}", color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text("%02d:%02d".format(seconds / 60, seconds % 60), fontSize = 42.sp, fontWeight = FontWeight.Bold)
                Text(if (running) "Таймер запущен" else "Таймер на паузе", color = Muted, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { running = !running }, modifier = Modifier.weight(1f)) { Text(if (running) "Пауза" else "Старт") }
            OutlinedButton(onClick = { running = false; seconds = (minutes.toIntOrNull()?.coerceIn(1, 180) ?: 10) * 60 }, modifier = Modifier.weight(1f)) { Text("Сбросить уровень") }
        }
        Text("Когда таймер дойдёт до нуля, малый и большой блайнды автоматически удвоятся.", color = Muted, fontSize = 12.sp)
    }
}

private fun translateHandName(name: String): String = when (name) {
    "High card" -> "старшая карта"
    "One pair" -> "одна пара"
    "Two pair" -> "две пары"
    "Three of a kind" -> "сет / тройка"
    "Straight" -> "стрит"
    "Flush" -> "флеш"
    "Full house" -> "фулл-хаус"
    "Four of a kind" -> "каре"
    "Straight flush" -> "стрит-флеш"
    else -> name
}

private fun formatBlind(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.US, value)
