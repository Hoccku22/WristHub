package dev.wristreader.wear

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import dev.wristreader.shared.*
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val prefs by lazy { ReaderPreferences(this) }
    private var refresh by mutableIntStateOf(0)
    private var error by mutableStateOf("")
    private var foreground = false
    private var receiveEnabled = true
    private var permissionRequested = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++; receive() }
    private val enable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++; receive() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
    override fun onStart() {
        super.onStart()
        foreground = true
        receiveEnabled = true
    }
    override fun onResume() { super.onResume(); refresh++; receive() }
    override fun onStop() {
        foreground = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // При сворачивании закрывается только сокет WristReader, сопряжение Bluetooth остаётся.
        stopService(Intent(this,BluetoothService::class.java))
        super.onStop()
    }
    private fun receive(manual: Boolean = false) {
        if (!foreground || !receiveEnabled) return
        if (!BluetoothAccess.granted(this)) {
            error = "Разрешите Bluetooth для автоматического приёма"
            if (manual || !permissionRequested) {
                permissionRequested = true
                val p = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
                if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
                permissions.launch(p.toTypedArray())
            }
            return
        }
        if (BluetoothAccess.adapter(this)?.isEnabled != true) {
            error = "Включите Bluetooth для автоматического приёма"
            if (manual) runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { error = "Включите Bluetooth в настройках" }
            return
        }
        error = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching { ContextCompat.startForegroundService(this, Intent(this, BluetoothService::class.java).setAction("RECEIVE")) }
            .onFailure { error = it.message ?: "Ошибка Bluetooth" }
    }
    @Composable private fun App() {
        val settings by prefs.settings.collectAsState(initial = ReaderSettings())
        var showSettings by remember { mutableStateOf(false) }
        val store = remember { DocumentStore(this) }
        val revision by TransferBus.revision.collectAsState()
        val transfer by TransferBus.current.collectAsState()
        LaunchedEffect(transfer.running) {
            if (foreground && transfer.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        var documents by remember { mutableStateOf<List<Document>>(emptyList()) }
        var free by remember { mutableLongStateOf(0) }
        var opened by remember { mutableStateOf<Document?>(null) }
        var deleting by remember { mutableStateOf<Document?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(revision, refresh) { withContext(Dispatchers.IO) { documents = store.list(); free = store.root.usableSpace } }
        if (showSettings) {
            SettingsScreen(settings, onChange = { value -> prefs.scope.launch { prefs.save(value) } }) { showSettings = false }
            return
        }
        if (opened != null) {
            Reader(opened!!, documents.filter { it.header.mime.startsWith("image/") }, prefs, settings,
                onSettings = { showSettings = true }, onClose = { opened = null })
            return
        }
        val state = rememberScalingLazyListState()
        val scaling = remember { ScalingLazyColumnDefaults.scalingParams(edgeScale = 0.92f,edgeAlpha = 1f) }
        val freeLabel = remember(free) { "Свободно: ${"%.0f".format(free / 1048576.0)} МиБ" }
        Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
            ScalingLazyColumn(state = state, modifier = Modifier.fillMaxSize().rotary { delta -> scope.launch { state.scrollBy(delta * settings.sensitivity) } },
                scalingParams = scaling,
                contentPadding = PaddingValues(horizontal = 26.dp, vertical = 40.dp)) {
                item { Text("WristReader", style = MaterialTheme.typography.title2) }
                item { CompactChip(onClick = { showSettings = true }, label = { Text("Настройки") }) }
                item { Text(freeLabel) }
                item { Text(remember(refresh) { BluetoothAccess.status(this@MainActivity) }) }
                item { Chip(onClick = {
                    if (transfer.running) {
                        receiveEnabled = false
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        stopService(Intent(this@MainActivity,BluetoothService::class.java))
                    } else { receiveEnabled = true; receive(manual = true) }
                }, label = { Text(if (transfer.running) "Выключить приём" else "Включить приём") }) }
                item { Text(transfer.message) }
                if (transfer.total > 0) item { Text("${transfer.done * 100 / transfer.total}% • ${"%.1f".format(transfer.total / 1048576.0)} МиБ") }
                if (error.isNotEmpty()) item { Text(error) }
                if (documents.isEmpty()) item { Text("Пока приложение открыто, отправляйте PDF и фото с телефона") }
                items(documents.size, key = { documents[it].header.id }) { index ->
                    val doc = documents[index]
                    Column {
                        Chip(onClick = { opened = doc }, label = { Text(doc.header.name, maxLines = 2) },
                            secondaryLabel = { Text(doc.header.mime.substringAfter('/').uppercase()) })
                        CompactChip(onClick = { deleting = doc }, label = { Text("Удалить") })
                    }
                }
                if (deleting != null) {
                    item { Text("Удалить ${deleting!!.header.name}?") }
                    item { Chip(onClick = {
                        val target = deleting!!; deleting = null
                        scope.launch { withContext(Dispatchers.IO) { target.directory.deleteRecursively() }; refresh++ }
                    }, label = { Text("Да, удалить") }) }
                    item { CompactChip(onClick = { deleting = null }, label = { Text("Отмена") }) }
                }
            }
        }
    }
}
