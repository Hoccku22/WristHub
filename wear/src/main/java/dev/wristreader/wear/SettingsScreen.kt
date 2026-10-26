package dev.wristreader.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch

@Composable fun SettingsScreen(s: ReaderSettings, onChange: (ReaderSettings) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val state = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val scaling = remember { ScalingLazyColumnDefaults.scalingParams(edgeScale = 0.92f,edgeAlpha = 1f) }
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        ScalingLazyColumn(state = state, modifier = Modifier.fillMaxSize().rotary { delta -> scope.launch { state.scrollBy(delta * s.sensitivity) } },
            scalingParams = scaling,
            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 40.dp)) {
            item { Text("Настройки") }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(progressRing = !s.progressRing)) },
                label = { Text(if (s.progressRing) "Кольцо: включено" else "Кольцо: выключено") },
                secondaryLabel = { Text("Прогресс чтения") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(mode = when(s.mode) { "slides" -> "feed"; "feed" -> "text"; else -> "slides" })) },
                label = { Text("Режим: ${when(s.mode) { "feed" -> "лента"; "text" -> "текст"; else -> "слайды" }}") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(vertical = !s.vertical)) }, label = { Text(if (s.vertical) "Свайпы: вертикально" else "Свайпы: горизонтально") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(font = if (s.font >= 24) 12 else s.font + 2)) }, label = { Text("Шрифт: ${s.font} sp") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(line = if (s.line >= 1.9f) 1.1f else s.line + 0.2f)) }, label = { Text("Интервал: ${"%.1f".format(s.line)}") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(margin = if (s.margin >= 12) 0 else s.margin + 4)) }, label = { Text("Доп. поля: ${s.margin} dp") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(dark = !s.dark)) }, label = { Text(if (s.dark) "Тема: тёмная" else "Тема: светлая") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(sensitivity = if (s.sensitivity >= 2f) 0.5f else s.sensitivity + 0.5f)) }, label = { Text("Головка: ×${s.sensitivity}") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(remember = !s.remember)) }, label = { Text(if (s.remember) "Позиция: сохранять" else "Позиция: с начала") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(fitWidth = !s.fitWidth)) }, label = { Text(if (s.fitWidth) "PDF: по ширине" else "PDF: целиком") }) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = { onChange(s.copy(textPaged = !s.textPaged)) }, label = { Text(if (s.textPaged) "Текст: страницы" else "Текст: лента") }) }
            item { Text("WristHub 2.2",style = MaterialTheme.typography.caption3,color = WatchMuted) }
            item { Chip(colors = ChipDefaults.chipColors(backgroundColor = WatchPanel),onClick = onClose, label = { Text("Готово") }) }
        }
    }
}
