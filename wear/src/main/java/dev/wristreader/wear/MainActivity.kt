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
import androidx.compose.runtime.saveable.rememberSaveable
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
    private var musicRequested by mutableStateOf(false)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++; receive() }
    private val enable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++; receive() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        musicRequested = intent.getBooleanExtra("music",false)
        setContent { WatchTheme { App() } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("music",false)) musicRequested = true
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
        var filter by rememberSaveable { mutableStateOf(HubFilter.ALL) }
        var inLibrary by rememberSaveable { mutableStateOf(false) }
        val music by MusicPlayback.state.collectAsState()
        LaunchedEffect(musicRequested,documents) {
            if (musicRequested && documents.isNotEmpty()) {
                showSettings = false
                opened = documents.firstOrNull { it.header.id == music.id }
                inLibrary = true
                filter = HubFilter.MUSIC
                musicRequested = false
            }
        }
        var deleting by remember { mutableStateOf<Document?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(revision, refresh) { withContext(Dispatchers.IO) { documents = store.list(); free = store.root.usableSpace } }
        if (showSettings) {
            SettingsScreen(settings, onChange = { value -> prefs.scope.launch { prefs.save(value) } }) { showSettings = false }
            return
        }
        if (opened != null) {
            val doc = opened!!
            when {
                FileTypes.isReadable(doc.header.mime) -> Reader(doc, documents.filter { FileTypes.isReadable(it.header.mime) && it.header.mime.startsWith("image/") }, prefs, settings,
                    onSettings = { showSettings = true }, onClose = { opened = null })
                doc.header.mime.startsWith("audio/") -> MusicScreen(doc) { opened = null }
                else -> FileScreen(doc) { opened = null }
            }
            return
        }
        if (inLibrary) {
            WatchLibrary(documents,filter,free,settings.sensitivity,onClose = { inLibrary = false },
                onOpen = { opened = it },onDelete = { deleting = it })
        } else {
            WatchHome(transfer.running,transfer,error,music.playing,music.id.isNotEmpty(),onReceive = {
                if (transfer.running) {
                    receiveEnabled = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    stopService(Intent(this@MainActivity,BluetoothService::class.java))
                } else { receiveEnabled = true; receive(manual = true) }
            },onSection = { filter = it; inLibrary = true },onSettings = { showSettings = true },onMusic = {
                filter = HubFilter.MUSIC; inLibrary = true
                opened = documents.firstOrNull { it.header.id == music.id }
            })
        }
        deleting?.let { target ->
            DeleteFileDialog(target.header.name,onDismiss = { deleting = null },onConfirm = {
                deleting = null
                if (music.id == target.header.id) MusicPlayback.command(this@MainActivity,"STOP")
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { target.directory.deleteRecursively() }
                    if (!removed) android.widget.Toast.makeText(this@MainActivity,"Не удалось удалить файл",android.widget.Toast.LENGTH_LONG).show()
                    refresh++
                }
            })
        }
    }
}
