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
import androidx.compose.runtime.*
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
                            val mime = FileTypes.detect(name,resolver.getType(uri),signature)
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
        setContent { PhoneTheme { Screen() } }
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
        var address by remember { mutableStateOf(getSharedPreferences("phone",MODE_PRIVATE).getString("device","").orEmpty()) }
        val devices = remember(refresh) {
            if (BluetoothAccess.granted(this)) runCatching { BluetoothAccess.adapter(this)?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            else emptyList()
        }
        LaunchedEffect(devices) {
            if (devices.none { it.address == address }) address = devices.singleOrNull()?.address.orEmpty()
        }
        LaunchedEffect(address) { getSharedPreferences("phone",MODE_PRIVATE).edit().putString("device",address).apply() }
        val granted = BluetoothAccess.granted(this)
        val enabled = granted && BluetoothAccess.adapter(this)?.isEnabled == true
        PhoneScreen(model.documents,devices.map { PhoneDevice(it.name ?: "Bluetooth",it.address) },address,
            model.preparing,model.message,current,results,granted,enabled,
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            onDevice = { address = it },onPermissions = { authorize() },onBluetooth = {
                if (granted) runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    .onFailure { model.message = it.message ?: "Откройте настройки Bluetooth" }
                else authorize()
            },onAdd = { picker.launch(arrayOf("*/*")) },onCancelImport = { model.cancelImport() },
            onSend = { ids -> send(address,ids) },onStop = { startService(Intent(this,BluetoothService::class.java).setAction("STOP")) },
            onRemove = { model.remove(it) })
    }
    private fun send(address: String, ids: List<String>) {
        if (!BluetoothAccess.granted(this)) { authorize(); return }
        if (BluetoothAccess.adapter(this)?.isEnabled != true) { model.message = "Включите Bluetooth"; return }
        ids.forEach { TransferBus.result(it,"В очереди") }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, BluetoothService::class.java).setAction("SEND")
            .putExtra("address", address).putStringArrayListExtra("ids", ArrayList(ids))) }
            .onFailure { model.message = "Не удалось начать передачу: ${it.message}" }
    }
}
