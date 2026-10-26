package dev.wristreader.wear

import android.content.res.ColorStateList
import android.widget.SeekBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.*
import dev.wristreader.shared.Document
import kotlinx.coroutines.launch

@Composable fun MusicScreen(doc: Document, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by MusicPlayback.state.collectAsState()
    var error by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var lastId by remember(doc.header.id) { mutableStateOf(doc.header.id) }
    var lastName by remember(doc.header.id) { mutableStateOf(doc.header.name) }
    LaunchedEffect(state.id,state.name) {
        if (state.id.isNotEmpty()) { lastId = state.id; lastName = state.name.ifBlank { lastName } }
    }
    fun command(action: String, position: Long? = null) {
        error = ""
        runCatching { MusicPlayback.command(context,action,position = position) }
            .onFailure { error = it.message ?: "Не удалось запустить плеер" }
    }
    fun playPause() {
        if (state.id.isEmpty()) runCatching { MusicPlayback.command(context,"PLAY",lastId) }.onFailure { error = it.message ?: "Ошибка" }
        else command(if (state.playing || state.loading) "PAUSE" else "PLAY")
    }
    LaunchedEffect(doc.header.id) {
        if (state.id != doc.header.id) runCatching { MusicPlayback.command(context,"PLAY",doc.header.id) }
            .onFailure { error = it.message ?: "Не удалось запустить плеер" }
    }
    BackHandler(onBack = onClose)
    val duration by rememberUpdatedState(state.duration)
    val onSeek by rememberUpdatedState<(Long) -> Unit>({ command("SEEK",it) })
    val status = error.ifBlank { state.message }
    BoxWithConstraints(Modifier.fillMaxSize().background(WatchBackground).testTag("hub-player"),contentAlignment = Alignment.Center) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val needsScroll = maxHeight < 215.dp || LocalDensity.current.fontScale > 1.15f || status.isNotBlank()
        val controlsWidth = minOf(maxWidth * 0.82f,164.dp)
        val layout = if (needsScroll) Modifier.width(maxWidth * 0.72f).heightIn(max = maxHeight * 0.78f)
            .verticalScroll(scroll).rotary { delta -> scope.launch { scroll.scrollBy(delta) } }.padding(vertical = 5.dp)
            else Modifier.width(maxWidth * 0.72f)
        Column(layout,horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (status.isBlank()) WatchIcon("music",Modifier.size(24.dp),WatchAccent)
            Text(state.name.ifBlank { lastName }.substringBeforeLast('.',state.name.ifBlank { lastName }),
                style = MaterialTheme.typography.title3,textAlign = TextAlign.Center,maxLines = 2,overflow = TextOverflow.Ellipsis)
            if (status.isNotBlank()) Text(status,style = MaterialTheme.typography.caption3,textAlign = TextAlign.Center,maxLines = 3,overflow = TextOverflow.Ellipsis)
            AndroidView(factory = { c -> SeekBar(c).apply {
                max = 1000
                thumbTintList = ColorStateList.valueOf(WatchAccent.toArgb())
                progressTintList = ColorStateList.valueOf(WatchAccent.toArgb())
                progressBackgroundTintList = ColorStateList.valueOf(WatchPanel.toArgb())
                contentDescription = "Позиция трека"
                setPadding(8,0,8,0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) { onSeek(bar.progress.toLong() * duration / 1000) }
                })
            } },update = { bar ->
                bar.isEnabled = state.duration > 0 && !state.loading
                if (!bar.isPressed) bar.progress = if (state.duration > 0) (1000L * state.position / state.duration).toInt() else 0
            },modifier = Modifier.fillMaxWidth().height(30.dp))
            Text(if (state.loading) "Загрузка…" else "${trackTime(state.position)} / ${trackTime(state.duration)}",
                Modifier.testTag("music-time"),style = MaterialTheme.typography.caption3,color = WatchMuted)
            Row(Modifier.requiredWidth(controlsWidth),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { command("PREVIOUS") },enabled = state.id.isNotEmpty() && !state.loading,
                    modifier = Modifier.size(44.dp),colors = ButtonDefaults.buttonColors(backgroundColor = WatchPanel)) {
                    WatchIcon("previous",Modifier.size(21.dp),description = "Предыдущий трек")
                }
                Button(onClick = { playPause() },modifier = Modifier.size(52.dp).testTag("music-play-pause"),
                    colors = ButtonDefaults.buttonColors(backgroundColor = WatchAccent,contentColor = WatchInk)) {
                    WatchIcon(if (state.playing || state.loading) "pause" else "play",Modifier.size(24.dp),
                        description = if (state.playing || state.loading) "Пауза" else "Играть")
                }
                Button(onClick = { command("NEXT") },enabled = state.id.isNotEmpty() && !state.loading,
                    modifier = Modifier.size(44.dp),colors = ButtonDefaults.buttonColors(backgroundColor = WatchPanel)) {
                    WatchIcon("next",Modifier.size(21.dp),description = "Следующий трек")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                WatchSmallAction("К файлам","back",onClose)
                WatchSmallAction("Ещё","settings") { more = true }
            }
        }
    }
    if (more) Dialog(onDismissRequest = { more = false },properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(WatchBackground),contentAlignment = Alignment.Center) {
            Column(Modifier.width(maxWidth * 0.70f),horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Управление",style = MaterialTheme.typography.title3)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CompactChip(onClick = { command("SEEK",state.position - 15000) },label = { Text("−15 с") },modifier = Modifier.weight(1f),colors = ChipDefaults.chipColors(backgroundColor = WatchPanel))
                    CompactChip(onClick = { command("SEEK",state.position + 15000) },label = { Text("+15 с") },modifier = Modifier.weight(1f),colors = ChipDefaults.chipColors(backgroundColor = WatchPanel))
                }
                CompactChip(onClick = { command("STOP"); more = false },label = { Text("Остановить") },colors = ChipDefaults.chipColors(backgroundColor = WatchPanel))
                CompactChip(onClick = { more = false },label = { Text("Готово") },colors = ChipDefaults.chipColors(backgroundColor = WatchPanel))
            }
        }
    }
}

private fun trackTime(ms: Long): String = "%d:%02d".format(ms / 60000,(ms / 1000) % 60)
