package dev.wristreader.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wristreader.shared.*

data class PhoneDevice(val name: String, val address: String)
private val Mint = Color(0xFF68E0CE)
private val Ink = Color(0xFF082F2B)
private val Background = Color(0xFF0B121B)
private val Panel = Color(0xFF15212E)
private val Soft = Color(0xFF173E3D)

@Composable fun PhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint,onPrimary = Ink,background = Background,
        surface = Background,surfaceContainer = Panel,surfaceContainerHigh = Color(0xFF1D2D3C),
        onSurface = Color(0xFFEFF5FA),onSurfaceVariant = Color(0xFF9CB1C3),outlineVariant = Color(0xFF283B4B)),content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PhoneScreen(documents: List<Document>, devices: List<PhoneDevice>, address: String,
    preparing: Boolean, message: String, transfer: Transfer, results: Map<String,String>,
    bluetoothGranted: Boolean, bluetoothEnabled: Boolean, notificationsGranted: Boolean,
    onDevice: (String) -> Unit, onPermissions: () -> Unit, onBluetooth: () -> Unit,
    onAdd: () -> Unit, onCancelImport: () -> Unit, onSend: (List<String>) -> Unit,
    onStop: () -> Unit, onRemove: (Document) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("files") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var queue by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var knownIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var deleting by remember { mutableStateOf<Document?>(null) }
    val selectedDocs = documents.filter { it.header.id in selected }
    val device = devices.firstOrNull { it.address == address }
    val busy = preparing || transfer.running
    LaunchedEffect(documents.map { it.header.id }) {
        // Newly imported files are selected; removals never leave stale selected IDs.
        val added = documents.map { it.header.id }.filter { it !in knownIds }
        selected = ArrayList(documents.map { it.header.id }.filter { it in selected || it in added })
        knownIds = ArrayList(documents.map { it.header.id })
    }
    fun toggle(doc: Document) { selected = ArrayList(selected).apply { if (!remove(doc.header.id)) add(doc.header.id) } }
    fun send() { queue = ArrayList(selectedDocs.map { it.header.id }); tab = "transfers"; onSend(queue) }
    Scaffold(containerColor = Background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp,vertical = 12.dp),verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Mint,shape = RoundedCornerShape(11.dp)) { PhoneIcon("hub",Modifier.padding(8.dp).size(20.dp),Ink) }
                Text("WristHub",style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.SemiBold,modifier = Modifier.padding(start = 10.dp).weight(1f))
                IconButton(onClick = { tab = "settings" }) { PhoneIcon("settings",description = "Настройки") }
            }
        },bottomBar = {
            Column {
                if (tab == "files") Surface(color = Background) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp,vertical = 12.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.widthIn(min = 80.dp)) {
                            Text(fileCount(selectedDocs.size),style = MaterialTheme.typography.labelLarge)
                            Text(fileSize(selectedDocs.sumOf { it.header.size }),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { send() },enabled = !busy && selectedDocs.isNotEmpty() && device != null && bluetoothEnabled && bluetoothGranted,
                            shape = RoundedCornerShape(17.dp),modifier = Modifier.weight(1f).heightIn(min = 50.dp)) {
                            PhoneIcon("send",Modifier.size(19.dp),MaterialTheme.colorScheme.onPrimary)
                            Text("Отправить",Modifier.padding(start = 9.dp))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = Background,tonalElevation = 0.dp) {
                    listOf(Triple("files","Файлы","folder"),Triple("transfers","Передачи","transfer"),Triple("watch","Часы","watch"),Triple("settings","Настройки","settings")).forEach { (key,label,icon) ->
                        NavigationBarItem(selected = tab == key,onClick = { tab = key },icon = { PhoneIcon(icon,Modifier.size(21.dp)) },label = { Text(label,maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Mint,selectedTextColor = Mint,indicatorColor = Soft,unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding = PaddingValues(horizontal = 20.dp,vertical = 8.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message.isNotBlank()) item {
                Surface(color = Panel,shape = RoundedCornerShape(16.dp)) { Text(message,Modifier.padding(14.dp),style = MaterialTheme.typography.bodySmall) }
            }
            when (tab) {
                "files" -> {
                    item { DeviceCard(device,bluetoothGranted,bluetoothEnabled) { tab = "watch" } }
                    item { Row(Modifier.fillMaxWidth().padding(top = 8.dp),verticalAlignment = Alignment.CenterVertically) {
                        Text("Мои файлы",style = MaterialTheme.typography.headlineMedium,fontWeight = FontWeight.SemiBold,modifier = Modifier.weight(1f))
                        Text(fileCount(documents.size),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    item { FilledTonalButton(onClick = onAdd,enabled = !busy,shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Soft,contentColor = Mint),modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        PhoneIcon("add",Modifier.size(20.dp),Mint); Text("Добавить файлы",Modifier.padding(start = 8.dp))
                    } }
                    if (preparing) item { Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Подготовка файлов…",Modifier.padding(top = 8.dp))
                        TextButton(onClick = onCancelImport) { Text("Отменить подготовку") }
                    } }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("all" to "Все","pdf" to "PDF","audio" to "Музыка","apk" to "APK","image" to "Фото","other" to "Другое").forEach { (key,label) ->
                            FilterChip(selected = filter == key,onClick = { filter = key },label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF1D2D3C),selectedLabelColor = MaterialTheme.colorScheme.onSurface))
                        }
                    } }
                    val visible = documents.filter { matches(it.header.mime,filter) }
                    if (visible.isEmpty()) item {
                        Text(if (documents.isEmpty()) "Добавь файлы с телефона, выбери их и отправь на часы." else "В этом разделе пока нет файлов.",
                            Modifier.padding(vertical = 24.dp),color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(visible,key = { it.header.id }) { doc -> FileRow(doc,doc.header.id in selected,!busy,{ toggle(doc) },{ deleting = doc }) }
                    if (documents.isNotEmpty()) item { Text("Удерживай файл, чтобы убрать его из списка отправки.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                "transfers" -> {
                    item { PageTitle("Передачи") }
                    val entries = documents.filter { it.header.id in queue || (queue.isEmpty() && it.header.id in results) }
                    if (entries.isEmpty() && !transfer.running) {
                        item { EmptyPanel("Выбери файлы и отправь их на часы.","Выбрать файлы") { tab = "files" } }
                    } else {
                        item { Surface(color = Panel,shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val delivered = entries.isNotEmpty() && entries.all { results[it.header.id]?.contains("Доставлен") == true }
                            val totalBytes = entries.sumOf { it.header.size + it.header.textSize }
                            val deliveredBytes = entries.filter { results[it.header.id]?.contains("Доставлен") == true }.sumOf { it.header.size + it.header.textSize }
                            val inFlightBytes = if (transfer.running && results[transfer.id]?.contains("Доставлен") != true) transfer.done else 0
                            val completedBytes = (deliveredBytes + inFlightBytes).coerceAtMost(totalBytes)
                            val fraction = if (totalBytes > 0) (completedBytes.toFloat() / totalBytes).coerceIn(0f,1f) else if (delivered && !transfer.running) 1f else 0f
                            Text(if (transfer.running && transfer.total == 0L) "Подключение…" else "${(fraction*100).toInt()}%",style = MaterialTheme.typography.displaySmall,fontWeight = FontWeight.Medium)
                            Text(if (delivered && !transfer.running) "Всё на часах" else if (transfer.message == "Приём выключен") "Очередь готова к отправке" else transfer.message)
                            Text(device?.name ?: "Часы",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (transfer.running && transfer.total == 0L) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { fraction },modifier = Modifier.fillMaxWidth())
                            if (totalBytes > 0) Text("${fileSize(completedBytes)} из ${fileSize(totalBytes)}",style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { if (transfer.running) onStop() else { selected = ArrayList(entries.map { it.header.id }); tab = "files" } },modifier = Modifier.fillMaxWidth()) {
                                Text(if (transfer.running) "Отменить передачу" else "К файлам")
                            }
                        } } }
                        items(entries,key = { it.header.id }) { doc ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FileSymbol(doc.header.mime)
                                Column(Modifier.weight(1f)) {
                                    Text(doc.header.name,maxLines = 2,overflow = TextOverflow.Ellipsis)
                                    Text(if (transfer.running && transfer.id == doc.header.id) "Отправка" else results[doc.header.id] ?: "В очереди",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item { Text("Оставь WristHub открытым на часах до завершения отправки. При обрыве файл отправляется заново.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                "watch" -> {
                    item { PageTitle("Мои часы") }
                    if (!bluetoothGranted) item { EmptyPanel("Разреши Bluetooth, чтобы увидеть сопряжённые устройства.","Разрешить Bluetooth",onPermissions) }
                    else {
                        item { Text(if (bluetoothEnabled) "Bluetooth включён" else "Bluetooth выключен",color = Mint) }
                        if (!bluetoothEnabled) item { Button(onClick = onBluetooth) { Text("Включить Bluetooth") } }
                        if (devices.isEmpty()) item { Text("Сначала выполни сопряжение часов и телефона в системных настройках Bluetooth.") }
                        items(devices,key = { it.address }) { target ->
                            Card(onClick = { onDevice(target.address) },enabled = !transfer.running,colors = CardDefaults.cardColors(containerColor = if (target.address == address) Soft else Panel),shape = RoundedCornerShape(20.dp)) {
                                Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    PhoneIcon("watch",Modifier.size(27.dp),Mint)
                                    Column(Modifier.weight(1f)) { Text(target.name,fontWeight = FontWeight.Medium); Text(target.address,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    if (target.address == address) PhoneIcon("check",color = Mint)
                                }
                            }
                        }
                        item { Text("Сопряжение не означает, что часы сейчас готовы принять файл. Перед отправкой открой WristHub на часах.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                else -> {
                    item { PageTitle("Настройки") }
                    item { SectionText("Разрешения") }
                    item { PermissionRow("Bluetooth",if (bluetoothGranted) "Разрешён" else "Нужен доступ","watch",onPermissions) }
                    item { PermissionRow("Уведомления",if (notificationsGranted) "Разрешены" else "Нужен доступ","info",onPermissions) }
                    item { SectionText("О приложении") }
                    item { Text("WristHub 2.1",fontWeight = FontWeight.Medium); Text("Раньше WristReader",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Text("Файлы передаются напрямую по Bluetooth. Без аккаунта, рекламы и облачного хранилища.",color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Text("Для APK и музыки на обоих устройствах нужна версия 2.0 или новее.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    deleting?.let { doc -> AlertDialog(onDismissRequest = { deleting = null },title = { Text("Убрать файл из списка?") },
        text = { Text("${doc.header.name}\n\nОригинал в памяти телефона и копия на часах останутся.") },
        confirmButton = { TextButton(onClick = { deleting = null; onRemove(doc) }) { Text("Убрать") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }) }
}

@Composable private fun DeviceCard(device: PhoneDevice?, granted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick,colors = CardDefaults.cardColors(containerColor = Panel),shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Surface(color = Soft,shape = RoundedCornerShape(15.dp)) { PhoneIcon("watch",Modifier.padding(10.dp).size(27.dp),Mint) }
            Column(Modifier.weight(1f)) {
                Text(device?.name ?: "Выбрать часы",fontWeight = FontWeight.Medium)
                Text(when { !granted -> "Нужен доступ к Bluetooth"; !enabled -> "Bluetooth выключен"; device == null -> "Сопряжённые устройства"; else -> "Сопряжены · Bluetooth" },style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PhoneIcon("arrow",color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun FileRow(doc: Document, selected: Boolean, enabled: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled,onClick = onClick,onLongClick = onRemove).padding(vertical = 14.dp,horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FileSymbol(doc.header.mime)
            Column(Modifier.weight(1f)) {
                Text(doc.header.name,maxLines = 1,overflow = TextOverflow.Ellipsis,style = MaterialTheme.typography.bodyMedium,fontWeight = FontWeight.Medium)
                Text("${FileTypes.label(doc.header.mime)} · ${fileSize(doc.header.size)}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = selected,onCheckedChange = null,enabled = enabled)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
@Composable private fun FileSymbol(mime: String) {
    Surface(color = Color(0xFF1D2D3C),shape = RoundedCornerShape(12.dp)) {
        PhoneIcon(when { mime == FileTypes.APK -> "package"; mime.startsWith("audio/") -> "music"; mime.startsWith("image/") -> "image"; else -> "file" },Modifier.padding(11.dp).size(22.dp),Mint)
    }
}
@Composable private fun PageTitle(label: String) { Text(label,Modifier.padding(vertical = 8.dp),style = MaterialTheme.typography.headlineMedium,fontWeight = FontWeight.SemiBold) }
@Composable private fun SectionText(label: String) { Text(label,Modifier.padding(top = 10.dp),style = MaterialTheme.typography.titleMedium) }
@Composable private fun PermissionRow(title: String, value: String, icon: String, onClick: () -> Unit) {
    Card(onClick = onClick,colors = CardDefaults.cardColors(containerColor = Panel)) { Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhoneIcon(icon,color = Mint); Text(title,Modifier.weight(1f)); Text(value,style = MaterialTheme.typography.bodySmall,color = Mint)
    } }
}
@Composable private fun EmptyPanel(label: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) { Text(label,color = MaterialTheme.colorScheme.onSurfaceVariant); FilledTonalButton(onClick = onClick) { Text(action) } }
}
private fun fileCount(n: Int) = "$n " + when { n % 100 in 11..14 -> "файлов"; n % 10 == 1 -> "файл"; n % 10 in 2..4 -> "файла"; else -> "файлов" }
private fun fileSize(n: Long) = "%.1f МиБ".format(n / 1048576.0)
internal fun matches(mime: String, filter: String) = when (filter) {
    "all" -> true; "pdf" -> mime == "application/pdf"; "audio" -> mime.startsWith("audio/")
    "apk" -> mime == FileTypes.APK; "image" -> mime.startsWith("image/")
    else -> mime != "application/pdf" && mime != FileTypes.APK && !mime.startsWith("audio/") && !mime.startsWith("image/")
}

@Composable private fun PhoneIcon(kind: String, modifier: Modifier = Modifier.size(24.dp),color: Color = LocalContentColor.current,description: String? = null) {
    val vector = remember(kind) { ImageVector.Builder(kind,24.dp,24.dp,24f,24f).apply {
        path(fill = null,stroke = SolidColor(Color.Black),strokeLineWidth = 1.7f,strokeLineCap = StrokeCap.Round,strokeLineJoin = StrokeJoin.Round) {
            when(kind) {
                "add" -> { moveTo(12f,5f);lineTo(12f,19f);moveTo(5f,12f);lineTo(19f,12f) }
                "check" -> { moveTo(5f,12f);lineTo(10f,17f);lineTo(19f,7f) }
                "arrow" -> { moveTo(9f,5f);lineTo(16f,12f);lineTo(9f,19f) }
                "send" -> { moveTo(3f,10f);lineTo(21f,3f);lineTo(14f,21f);lineTo(10f,14f);close();moveTo(10f,14f);lineTo(21f,3f) }
                "transfer" -> { moveTo(7f,4f);lineTo(7f,20f);moveTo(3f,8f);lineTo(7f,4f);lineTo(11f,8f);moveTo(17f,20f);lineTo(17f,4f);moveTo(13f,16f);lineTo(17f,20f);lineTo(21f,16f) }
                "watch" -> { moveTo(8f,6f);lineTo(16f,6f);lineTo(18f,9f);lineTo(18f,15f);lineTo(16f,18f);lineTo(8f,18f);lineTo(6f,15f);lineTo(6f,9f);close();moveTo(9f,6f);lineTo(10f,2f);lineTo(14f,2f);lineTo(15f,6f);moveTo(9f,18f);lineTo(10f,22f);lineTo(14f,22f);lineTo(15f,18f);moveTo(12f,9f);lineTo(12f,12f);lineTo(14f,13f) }
                "folder" -> { moveTo(3f,7f);lineTo(3f,19f);lineTo(21f,19f);lineTo(21f,7f);lineTo(11f,7f);lineTo(9f,4f);lineTo(3f,4f);close() }
                "settings" -> { moveTo(4f,6f);lineTo(20f,6f);moveTo(4f,12f);lineTo(20f,12f);moveTo(4f,18f);lineTo(20f,18f);moveTo(8f,3f);lineTo(8f,9f);moveTo(16f,9f);lineTo(16f,15f);moveTo(10f,15f);lineTo(10f,21f) }
                "hub" -> { for(x in listOf(4f,14f)) for(y in listOf(4f,14f)) { moveTo(x,y);lineTo(x+6,y);lineTo(x+6,y+6);lineTo(x,y+6);close() } }
                "music" -> { moveTo(9f,17f);lineTo(9f,5f);lineTo(19f,3f);lineTo(19f,15f);moveTo(9f,8f);lineTo(19f,6f);moveTo(9f,17f);curveTo(2f,13f,2f,23f,9f,19f);close();moveTo(19f,15f);curveTo(12f,11f,12f,21f,19f,17f);close() }
                "package" -> { moveTo(12f,2f);lineTo(21f,7f);lineTo(21f,17f);lineTo(12f,22f);lineTo(3f,17f);lineTo(3f,7f);close();moveTo(3f,7f);lineTo(12f,12f);lineTo(21f,7f);moveTo(12f,12f);lineTo(12f,22f);moveTo(7f,5f);lineTo(17f,10f) }
                "image" -> { moveTo(3f,3f);lineTo(21f,3f);lineTo(21f,21f);lineTo(3f,21f);close();moveTo(3f,17f);lineTo(9f,11f);lineTo(13f,15f);lineTo(16f,12f);lineTo(21f,17f);moveTo(7f,7f);lineTo(8f,7f) }
                "info" -> { moveTo(4f,4f);lineTo(20f,4f);lineTo(20f,20f);lineTo(4f,20f);close();moveTo(12f,11f);lineTo(12f,16f);moveTo(12f,8f);lineTo(12.1f,8f) }
                else -> { moveTo(5f,2f);lineTo(14f,2f);lineTo(19f,7f);lineTo(19f,22f);lineTo(5f,22f);close();moveTo(14f,2f);lineTo(14f,7f);lineTo(19f,7f);moveTo(8f,12f);lineTo(16f,12f);moveTo(8f,16f);lineTo(14f,16f) }
            }
        }
    }.build() }
    Icon(vector,description,modifier,tint = color)
}
