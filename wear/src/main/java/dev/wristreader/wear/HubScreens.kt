package dev.wristreader.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import dev.wristreader.shared.Document
import dev.wristreader.shared.FileTypes
import kotlinx.coroutines.*

enum class HubFilter(val label: String) {
    ALL("Все"), READING("Чтение"), MUSIC("Музыка"), APPS("APK"), OTHER("Другое");
    fun accepts(doc: Document) = when (this) {
        ALL -> true
        READING -> FileTypes.isReadable(doc.header.mime)
        MUSIC -> doc.header.mime.startsWith("audio/")
        APPS -> doc.header.mime == FileTypes.APK
        OTHER -> !FileTypes.isReadable(doc.header.mime) && !doc.header.mime.startsWith("audio/") && doc.header.mime != FileTypes.APK
    }
}

@Composable fun MusicScreen(doc: Document, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by MusicPlayback.state.collectAsState()
    var error by remember { mutableStateOf("") }
    fun command(action: String, position: Long? = null) {
        runCatching { MusicPlayback.command(context,action,position = position) }
            .onFailure { error = it.message ?: "Не удалось запустить плеер" }
    }
    LaunchedEffect(doc.header.id) {
        if (state.id != doc.header.id) runCatching { MusicPlayback.command(context,"PLAY",doc.header.id) }
            .onFailure { error = it.message ?: "Не удалось запустить плеер" }
    }
    BackHandler(onBack = onClose)
    HubList {
        item { Text("Музыка",style = MaterialTheme.typography.title2) }
        item { Text(state.name.ifBlank { doc.header.name },maxLines = 3) }
        item { Text(if (state.loading) "Загрузка…" else "${trackTime(state.position)} / ${trackTime(state.duration)}",Modifier.testTag("music-time")) }
        if (state.message.isNotEmpty()) item { Text(state.message) }
        if (error.isNotEmpty()) item { Text(error) }
        item { Chip(onClick = {
            if (state.id.isEmpty()) runCatching { MusicPlayback.command(context,"PLAY",doc.header.id) }.onFailure { error = it.message ?: "Ошибка" }
            else command(if (state.playing || state.loading) "PAUSE" else "PLAY")
        },label = { Text(if (state.playing || state.loading) "Пауза" else "Играть") }) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactChip(onClick = { command("SEEK",state.position - 15000) },label = { Text("−15 с") },modifier = Modifier.weight(1f))
            CompactChip(onClick = { command("SEEK",state.position + 15000) },label = { Text("+15 с") },modifier = Modifier.weight(1f))
        } }
        item { CompactChip(onClick = { command("PREVIOUS") },label = { Text("Предыдущий трек") }) }
        item { CompactChip(onClick = { command("NEXT") },label = { Text("Следующий трек") }) }
        item { CompactChip(onClick = { command("STOP") },label = { Text("Остановить") }) }
        item { Text("Музыка продолжает играть в фоне",style = MaterialTheme.typography.caption2) }
        item { CompactChip(onClick = onClose,label = { Text("К файлам") }) }
    }
}

private fun trackTime(ms: Long): String = "%d:%02d".format(ms / 60000,(ms / 1000) % 60)

@Suppress("DEPRECATION")
@Composable fun FileScreen(doc: Document, onClose: () -> Unit) {
    val context = LocalContext.current
    val apk = doc.header.mime == FileTypes.APK
    var message by remember(doc.header.id) { mutableStateOf("") }
    val packageLabel by produceState<String?>(null,doc.header.id) {
        value = if (apk) withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getPackageArchiveInfo(doc.original.path,0) }
                .getOrNull()?.let { "${it.packageName}\n${it.versionName ?: ""}" } ?: "Некорректный APK"
        } else ""
    }
    BackHandler(onBack = onClose)
    fun launch() {
        runCatching {
            check(!apk || packageLabel != "Некорректный APK") { "Файл не является одиночным APK" }
            if (apk && !context.packageManager.canRequestPackageInstalls()) {
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}"))
                check(settings.resolveActivity(context.packageManager) != null) { "Прошивка не разрешает установку из приложения; используйте ADB" }
                context.startActivity(settings)
                message = "Разрешите WristHub установку приложений, затем вернитесь и нажмите «Установить APK» ещё раз"
            } else {
                val intent = fileIntent(context,doc)
                check(intent.resolveActivity(context.packageManager) != null) {
                    if (apk) "Системный установщик недоступен; APK сохранён, установите его через ADB"
                    else "На часах нет приложения для этого типа файла. Файл сохранён в WristHub"
                }
                context.startActivity(intent)
            }
        }.onFailure { message = it.message ?: "Не удалось открыть файл" }
    }
    HubList {
        item { Text(FileTypes.label(doc.header.mime),style = MaterialTheme.typography.title2) }
        item { Text(doc.header.name,maxLines = 4) }
        item { Text("${"%.1f".format(doc.header.size / 1048576.0)} МиБ") }
        if (apk) {
            item { Text(packageLabel ?: "Проверка APK…",style = MaterialTheme.typography.caption2) }
            item { Text("Установка откроет системное подтверждение. Нужен APK, совместимый с часами.",style = MaterialTheme.typography.caption2) }
        }
        item { Chip(onClick = { launch() },enabled = !apk || (packageLabel != null && packageLabel != "Некорректный APK"),
            label = { Text(if (apk) "Установить APK" else "Открыть в приложении") }) }
        if (message.isNotEmpty()) item { Text(message) }
        item { CompactChip(onClick = onClose,label = { Text("К файлам") }) }
    }
}

@Composable private fun HubList(content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    ScalingLazyColumn(state = state,modifier = Modifier.fillMaxSize().rotary { delta -> scope.launch { state.scrollBy(delta) } },
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 28.dp,vertical = 36.dp),content = content)
}
