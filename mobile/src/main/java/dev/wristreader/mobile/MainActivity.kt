package dev.wristreader.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.wristreader.shared.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class MobileModel(app: Application) : AndroidViewModel(app) {
    var documents by mutableStateOf<List<Document>>(emptyList())
    var preparing by mutableStateOf(false)
    var message by mutableStateOf("")
    private var importJob: Job? = null
    private val store = DocumentStore(app, true)
    private val initialLoad = viewModelScope.launch(Dispatchers.IO) { store.cleanup(); documents = store.list() }

    fun import(uris: List<Uri>) {
        if (preparing) { message = "Дождитесь завершения подготовки"; return }
        preparing = true
        message = ""
        importJob = viewModelScope.launch {
            try {
                initialLoad.join()
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    PDFBoxResourceLoader.init(context)
                    for (uri in uris.distinct()) {
                        ensureActive()
                        val id = UUID.randomUUID().toString()
                        val dir = File(store.root, ".$id").apply { mkdirs() }
                        try {
                            val resolver = context.contentResolver
                            var name = "Документ"
                            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) name = it.getString(0) ?: name
                            }
                            name = name.filterNot { it.isISOControl() }.take(240).ifBlank { "Документ" }
                            val original = File(dir, "original")
                            resolver.openInputStream(uri)?.use { input -> original.outputStream().use { out ->
                                val buffer = ByteArray(32768); var count = 0L
                                while (true) {
                                    ensureActive(); val n = input.read(buffer); if (n < 0) break
                                    count += n
                                    require(count <= Protocol.MAX_FILE) { "Файл больше 512 МиБ" }
                                    require(store.root.usableSpace > n + Protocol.RESERVE) { "Недостаточно памяти телефона" }
                                    out.write(buffer, 0, n)
                                }
                            } } ?: error("Нет доступа к файлу")
                            val signature = ByteArray(12)
                            original.inputStream().use { it.read(signature) }
                            val mime = when {
                                signature.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-" -> "application/pdf"
                                signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte() -> "image/jpeg"
                                signature.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,13,10,26,10)) -> "image/png"
                                String(signature, 0, 4, Charsets.US_ASCII) == "RIFF" && String(signature,8,4,Charsets.US_ASCII) == "WEBP" -> "image/webp"
                                else -> error("Поддерживаются PDF, JPEG, PNG, WebP")
                            }
                            val text = File(dir, "text.txt").apply { writeText("") }
                            if (mime == "application/pdf") {
                                try {
                                    PDDocument.load(original, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                                        text.bufferedWriter(Charsets.UTF_8).use { writer ->
                                            val stripper = PDFTextStripper().apply { sortByPosition = true; paragraphEnd = "\n\n" }
                                            for (page in 1..pdf.numberOfPages) {
                                                ensureActive(); stripper.startPage = page; stripper.endPage = page
                                                stripper.writeText(pdf, writer); writer.flush()
                                                require(text.length() <= Protocol.MAX_TEXT) { "Извлечённый текст больше 32 МиБ" }
                                            }
                                        }
                                    }
                                    if (text.readText().isBlank()) { text.writeText(""); message = "В PDF нет текста: на часах доступен просмотр страниц" }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { text.writeText(""); message = "Текст не извлечён (${e.message}); оригинал доступен" }
                            }
                            val h = Protocol.Header(id, name, mime, original.length(), text.length(), Protocol.hash(original), Protocol.hash(text))
                            h.validate(); store.metadata(dir, h)
                            check(dir.renameTo(File(store.root, id))) { "Ошибка сохранения" }
                            documents = store.list()
                        } finally { if (dir.exists()) dir.deleteRecursively() }
                    }
                }
            } catch (e: CancellationException) { message = "Подготовка отменена" }
            catch (e: Exception) { message = "Ошибка: ${e.message}" }
            finally { preparing = false }
        }
    }
    fun cancelImport() { importJob?.cancel() }
    fun remove(doc: Document) { viewModelScope.launch(Dispatchers.IO) { doc.directory.deleteRecursively(); documents = store.list() } }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: MobileModel
    private var refresh by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    private val enable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.import(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[MobileModel::class.java]
        if (savedInstanceState == null) sharedIntent(intent)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }
    override fun onResume() { super.onResume(); refresh++ }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); sharedIntent(intent) }
    @Suppress("DEPRECATION")
    private fun sharedIntent(i: Intent) {
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) model.import(uris)
    }
    private fun authorize() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(list.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    @Composable private fun Screen() {
        val current by TransferBus.current.collectAsState()
        val results by TransferBus.results.collectAsState()
        var address by remember { mutableStateOf("") }
        val devices = remember(refresh) {
            if (BluetoothAccess.granted(this)) runCatching { BluetoothAccess.adapter(this)?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            else emptyList()
        }
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("WristReader", style = MaterialTheme.typography.headlineLarge) }
                item { Text("Документы на запястье", style = MaterialTheme.typography.titleMedium) }
                item { Text(remember(refresh) { BluetoothAccess.status(this@MainActivity) }) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { authorize() }) { Text("Разрешения") }
                    OutlinedButton(onClick = {
                        if (BluetoothAccess.granted(this@MainActivity)) runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            .onFailure { model.message = it.message ?: "Откройте настройки Bluetooth" }
                        else authorize()
                    }) { Text("Включить BT") }
                } }
                item { Text("1. Откройте WristReader на часах и включите приём.\n2. Выберите сопряжённые часы.") }
                items(devices, key = { it.address }) { device ->
                    FilterChip(selected = address == device.address, onClick = { address = device.address },
                        label = { Text("${device.name ?: "Bluetooth"} • ${device.address}") })
                }
                if (devices.isEmpty()) item { Text("Нет сопряжённых устройств. Выполните сопряжение в системных настройках Bluetooth, затем вернитесь сюда.") }
                item { Button(enabled = !model.preparing && !current.running,
                    onClick = { picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp")) }) { Text("Выбрать файлы") } }
                if (model.preparing) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Подготовка и извлечение текста…"); TextButton(onClick = { model.cancelImport() }) { Text("Отменить") } }
                if (model.message.isNotEmpty()) item { Text(model.message, color = MaterialTheme.colorScheme.tertiary) }
                item {
                    Text(current.message)
                    if (current.total > 0) {
                        LinearProgressIndicator(progress = { current.done.toFloat() / current.total }, modifier = Modifier.fillMaxWidth())
                        Text("${size(current.done)} / ${size(current.total)}")
                    }
                    if (current.running) TextButton(onClick = { startService(Intent(this@MainActivity, BluetoothService::class.java).setAction("STOP")) }) { Text("Отменить передачу") }
                }
                items(model.documents, key = { it.header.id }) { doc ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text(doc.header.name, style = MaterialTheme.typography.titleMedium)
                        Text("${size(doc.header.size)} • ${doc.header.mime.substringAfter('/')}")
                        Text(results[doc.header.id] ?: "Готов к отправке")
                        Row {
                            TextButton(enabled = !current.running && address.isNotEmpty() && !model.preparing,
                                onClick = { send(address, listOf(doc.header.id)) }) { Text("Отправить / повторить") }
                            TextButton(enabled = !current.running, onClick = { model.remove(doc) }) { Text("Убрать") }
                        }
                    } }
                }
                if (model.documents.isNotEmpty()) item { Button(enabled = !current.running && !model.preparing && address.isNotEmpty(),
                    onClick = { send(address, model.documents.map { it.header.id }) }) { Text("Отправить все") } }
                item { Text("Только Bluetooth Classic. При обрыве отправка повторяется с начала. Файлы остаются в очереди до удаления.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    private fun send(address: String, ids: List<String>) {
        if (!BluetoothAccess.granted(this)) { authorize(); return }
        if (BluetoothAccess.adapter(this)?.isEnabled != true) { model.message = "Включите Bluetooth"; return }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, BluetoothService::class.java).setAction("SEND")
            .putExtra("address", address).putStringArrayListExtra("ids", ArrayList(ids))) }
            .onFailure { model.message = "Не удалось начать передачу: ${it.message}" }
    }
}
private fun size(n: Long) = "%.1f МиБ".format(n / 1048576.0)
