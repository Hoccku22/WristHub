package dev.wristreader.shared

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Transfer(val id: String = "", val name: String = "", val done: Long = 0, val total: Long = 0,
                    val message: String = "Приём выключен", val running: Boolean = false)
object TransferBus {
    val current = MutableStateFlow(Transfer())
    val results = MutableStateFlow<Map<String, String>>(emptyMap())
    val revision = MutableStateFlow(0)
    fun result(id: String, value: String) { results.value = results.value + (id to value) }
}

object BluetoothAccess {
    fun adapter(c: Context): BluetoothAdapter? = c.getSystemService(BluetoothManager::class.java)?.adapter
    fun granted(c: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission")
    fun status(c: Context): String = when {
        !granted(c) -> "Разрешите доступ к Bluetooth"
        adapter(c) == null -> "Bluetooth Classic недоступен"
        adapter(c)?.isEnabled != true -> "Bluetooth выключен"
        else -> "Bluetooth включён • RFCOMM"
    }
}

/** Только BluetoothSocket: сетевого транспорта и автоматического резерва нет. */
@SuppressLint("MissingPermission")
class BluetoothService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var lastActivity = 0L
    private var job: Job? = null
    private var cancelled = false
    private val timer = Executors.newSingleThreadScheduledExecutor()

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("transfer", "Передача Bluetooth", NotificationManager.IMPORTANCE_LOW))
        timer.scheduleAtFixedRate({
            if (socket != null && SystemClock.elapsedRealtime() - lastActivity > 90_000) runCatching { socket?.close() }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun report(t: Transfer) {
        TransferBus.current.value = t
        val stop = PendingIntent.getService(this, 1, Intent(this, BluetoothService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "transfer").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("WristHub • Bluetooth").setContentText(t.message)
            .setOngoing(true).addAction(0, "Остановить", stop)
        if (t.total > 0) n.setProgress(100, (100 * t.done / t.total).toInt(), false)
        startForeground(17, n.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            cancelled = true
            runCatching { socket?.close() }; runCatching { server?.close() }
            job?.cancel(); stopSelf(); return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        cancelled = false
        // Вызов до первой приостановки обязателен для startForegroundService.
        report(Transfer(message = "Подготовка Bluetooth…", running = true))
        job = scope.launch {
            try {
                check(BluetoothAccess.granted(this@BluetoothService)) { "Нет разрешения Bluetooth" }
                val adapter = BluetoothAccess.adapter(this@BluetoothService)
                check(adapter?.isEnabled == true) { "Bluetooth выключен или недоступен" }
                if (intent?.action == "RECEIVE") receive(adapter!!) else {
                    val address = intent?.getStringExtra("address") ?: error("Не выбраны часы")
                    val ids = intent.getStringArrayListExtra("ids") ?: error("Нет файлов")
                    val store = DocumentStore(this@BluetoothService, true)
                    for (id in ids) {
                        ensureActive()
                        val doc = store.list().firstOrNull { it.header.id == id } ?: continue
                        try { send(adapter!!, address, doc); TransferBus.result(id, "Доставлен ✓") }
                        catch (e: Exception) {
                            TransferBus.result(id, if (cancelled) "Отменено — можно повторить" else "Ошибка: ${e.message}")
                            throw e
                        }
                    }
                }
                TransferBus.current.value = Transfer(message = "Передача завершена")
            } catch (e: Exception) {
                TransferBus.current.value = Transfer(message = if (cancelled) "Передача отменена" else "Ошибка: ${e.message}")
            } finally {
                runCatching { socket?.close() }; socket = null
                runCatching { server?.close() }; server = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun touch() { lastActivity = SystemClock.elapsedRealtime() }
    private fun connected(s: BluetoothSocket) { touch(); socket = s }
    private fun response(out: DataOutputStream, code: Int, message: String = "") {
        out.writeInt(code); out.writeUTF(message.take(500)); out.flush(); touch()
    }
    private fun answer(input: DataInputStream): Int {
        val code = input.readInt(); val message = input.readUTF(); touch()
        check(code != Protocol.REJECTED) { message }; return code
    }
    private inline fun <T> awake(block: () -> T): T {
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristReader:transfer")
        lock.acquire(60 * 60 * 1000L)
        return try { block() } finally { if (lock.isHeld) lock.release() }
    }

    private fun send(adapter: BluetoothAdapter, address: String, doc: Document) = awake {
        val h = doc.header
        report(Transfer(h.id, h.name, total = h.size + h.textSize, message = "Подключение к часам…", running = true))
        adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID).use { s ->
            connected(s)
            val timeout = timer.schedule({ runCatching { s.close() } }, 30, TimeUnit.SECONDS)
            try { s.connect() } finally { timeout.cancel(false) }
            touch()
            val input = DataInputStream(BufferedInputStream(s.inputStream))
            val output = DataOutputStream(BufferedOutputStream(s.outputStream))
            Protocol.writeHeader(output, h)
            val code = answer(input)
            if (code == Protocol.COMMITTED) return@use
            check(code == Protocol.READY) { "Некорректный ответ часов" }
            fun progress(n: Long) {
                touch()
                val old = TransferBus.current.value
                if (n == h.size + h.textSize || n - old.done >= 128 * 1024)
                    report(Transfer(h.id, h.name, n, h.size + h.textSize, "Отправка: ${h.name}", true))
            }
            doc.original.inputStream().use { Protocol.verify(Protocol.copy(it, output, h.size) { n -> progress(n) }, h.hash) }
            doc.text.inputStream().use { Protocol.verify(Protocol.copy(it, output, h.textSize) { n -> progress(h.size + n) }, h.textHash) }
            output.flush()
            check(answer(input) == Protocol.COMMITTED) { "Часы не подтвердили сохранение" }
        }
        socket = null
    }

    private fun receive(adapter: BluetoothAdapter) {
        val store = DocumentStore(this)
        store.cleanup()
        server = adapter.listenUsingRfcommWithServiceRecord("WristHub", Protocol.SERVICE_UUID)
        while (!cancelled) {
            report(Transfer(message = "Приём включён • ожидаю телефон", running = true))
            val s = server!!.accept()
            connected(s)
            s.use {
                try {
                    check(s.remoteDevice.bondState == BluetoothDevice.BOND_BONDED) { "Сначала выполните сопряжение" }
                    awake { receiveOne(store, s) }
                } catch (e: Exception) {
                    TransferBus.current.value = Transfer(message = "Приём: ${e.message}", running = true)
                    // Ошибка отдельного соединения не выключает сервер.
                    Thread.sleep(1500)
                } finally { socket = null }
            }
        }
    }

    private fun receiveOne(store: DocumentStore, s: BluetoothSocket) {
        val input = DataInputStream(BufferedInputStream(s.inputStream))
        val out = DataOutputStream(BufferedOutputStream(s.outputStream))
        var temp: File? = null
        try {
            val h = Protocol.readHeader(input); touch()
            val target = File(store.root, h.id)
            if (target.exists()) {
                val existing = store.read(target).header
                check(existing.size == h.size && existing.textSize == h.textSize &&
                    existing.hash.contentEquals(h.hash) && existing.textHash.contentEquals(h.textHash)) { "Конфликт идентификатора" }
                response(out, Protocol.COMMITTED); return
            }
            Protocol.requireSpace(store.root.usableSpace, h)
            temp = File(store.root, ".${h.id}").apply { if (exists()) deleteRecursively(); check(mkdirs()) }
            response(out, Protocol.READY)
            fun progress(n: Long) {
                touch()
                if (n == h.size + h.textSize || n - TransferBus.current.value.done >= 128 * 1024)
                    report(Transfer(h.id, h.name, n, h.size + h.textSize, "Приём: ${h.name}", true))
            }
            report(Transfer(h.id, h.name, total = h.size + h.textSize, message = "Приём: ${h.name}", running = true))
            VerifiedPayload.receive(input, temp, h, ::progress)
            store.metadata(temp, h)
            check(temp.renameTo(target)) { "Не удалось сохранить документ" }
            TransferBus.revision.value++
            response(out, Protocol.COMMITTED)
        } catch (e: Exception) {
            runCatching { response(out, Protocol.REJECTED, e.message ?: "Ошибка приёма") }
            throw e
        } finally { temp?.takeIf { it.exists() }?.deleteRecursively() }
    }

    override fun onDestroy() {
        cancelled = true
        runCatching { socket?.close() }; runCatching { server?.close() }
        scope.cancel(); timer.shutdownNow()
        if (TransferBus.current.value.running) TransferBus.current.value = Transfer(message = "Служба Bluetooth остановлена")
        super.onDestroy()
    }
}
