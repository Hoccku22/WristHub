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
        item { CompactChip(onClick = onClose,label = { Text("К файлам") },colors = ChipDefaults.chipColors(backgroundColor = WatchPanel)) }
    }
}

@Composable private fun HubList(content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    ScalingLazyColumn(state = state,modifier = Modifier.fillMaxSize().rotary { delta -> scope.launch { state.scrollBy(delta) } },
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 28.dp,vertical = 36.dp),content = content)
}
