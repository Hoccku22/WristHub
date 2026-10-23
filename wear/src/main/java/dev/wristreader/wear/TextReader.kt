package dev.wristreader.wear

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.wear.compose.material.*
import dev.wristreader.shared.Document
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

/** Ограниченные блоки сохраняют переводы строк и не создают гигантский Text layout. */
fun textBlocks(file: File): List<String> {
    val blocks = ArrayList<String>()
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        val pending = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch < 0) break
            pending.append(ch.toChar())
            if (pending.length >= 1600 && (ch.toChar().isWhitespace() || pending.length >= 2000 && !ch.toChar().isHighSurrogate())) {
                blocks += pending.toString(); pending.setLength(0)
            }
        }
        if (pending.isNotEmpty()) blocks += pending.toString()
    }
    return blocks
}

/** Страница заканчивается по фактически измеренной строке, а не по числу символов. */
fun paginate(text: String, measurer: TextMeasurer, style: TextStyle, width: Int, height: Int): List<String> {
    if (text.isEmpty()) return listOf("")
    val pages = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        val rest = text.substring(start)
        val layout = measurer.measure(AnnotatedString(rest), style, constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
        var last = 0
        for (line in 0 until layout.lineCount) {
            if (layout.getLineBottom(line) <= height) last = line else break
        }
        val end = layout.getLineEnd(last, visibleEnd = false).coerceAtLeast(1).coerceAtMost(rest.length)
        pages += rest.substring(0,end)
        start += end
    }
    return pages
}

@Composable fun TextReader(doc: Document, prefs: ReaderPreferences, s: ReaderSettings, locked: Boolean,
    progress: ReadingProgressState, onPages: () -> Unit) {
    val blocks by produceState<List<String>?>(null, doc.header.id) {
        value = withContext(Dispatchers.IO) { runCatching { textBlocks(doc.text) }.getOrDefault(emptyList()) }
    }
    val loaded = blocks
    if (loaded == null) { CircularProgressIndicator(); return }
    if (loaded.isEmpty() || loaded.all { it.isBlank() }) {
        Column {
            Text("Нет извлекаемого текста. Возможно, это скан или PDF защищён. OCR пока не поддерживается.")
            CompactChip(onClick = onPages, label = { Text("Открыть страницы") })
        }
        return
    }
    val style = TextStyle(color = LocalContentColor.current, fontSize = s.font.sp, lineHeight = (s.font * s.line).sp)
    if (s.textPaged) TextPages(doc,prefs,s,locked,loaded,style,progress) else TextFeed(doc,prefs,s,locked,loaded,style,progress)
}

@Composable private fun TextFeed(doc: Document, prefs: ReaderPreferences, s: ReaderSettings, locked: Boolean,
    blocks: List<String>, style: TextStyle, progress: ReadingProgressState) {
    val position = remember(prefs,doc.header.id) { prefs.position(doc.header.id,"textFeed") }
    val restored by position.collectAsState(initial = null)
    if (restored == null) return
    val state = rememberLazyListState(if (s.remember) restored!!.index.coerceIn(0,blocks.lastIndex) else 0,
        if (s.remember) restored!!.offset.coerceAtLeast(0) else 0)
    val scope = rememberCoroutineScope()
    SaveScrollPosition(prefs,doc.header.id,"textFeed",s.remember,state)
    TrackScrollProgress(state,progress)
    LazyColumn(state = state, userScrollEnabled = !locked,
        modifier = Modifier.fillMaxSize().rotary { if (!locked) scope.launch { state.scrollBy(it * s.sensitivity) } }) {
        items(blocks.size, key = { it }) { BasicText(blocks[it], style = style, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable private fun TextPages(doc: Document, prefs: ReaderPreferences, s: ReaderSettings, locked: Boolean,
    blocks: List<String>, style: TextStyle, progress: ReadingProgressState) {
    val position = remember(prefs,doc.header.id) { prefs.position(doc.header.id,"textPages") }
    val restored by position.collectAsState(initial = null)
    if (restored == null) return
    var block by remember(doc.header.id) { mutableIntStateOf(if (s.remember) restored!!.index.coerceIn(0,blocks.lastIndex) else 0) }
    var subpage by remember(doc.header.id) { mutableIntStateOf(if (s.remember) restored!!.offset.coerceAtLeast(0) else 0) }
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val density = LocalDensity.current
    val rotary = remember { RotaryPages() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = with(density) { maxWidth.roundToPx() }
            val height = with(density) { maxHeight.roundToPx() }
            val pages = remember(block,style,width,height) { paginate(blocks[block],measurer,style,width,height) }
            val selected = subpage.coerceIn(0,pages.lastIndex)
            SideEffect { progress.update((block + (selected + 1f) / pages.size) / blocks.size) }
            var previousBlock by remember { mutableIntStateOf(block) }
            // Переход назад начинает с последней подстраницы предыдущего блока.
            LaunchedEffect(block,pages.size) {
                if (block < previousBlock) subpage = pages.lastIndex
                else subpage = subpage.coerceAtMost(pages.lastIndex)
                previousBlock = block
            }
            val step: (Int) -> Unit = { delta ->
                if (delta > 0) {
                    if (selected < pages.lastIndex) subpage = selected + 1
                    else if (block < blocks.lastIndex) { block++; subpage = 0 }
                } else {
                    if (selected > 0) subpage = selected - 1
                    else if (block > 0) { block--; subpage = Int.MAX_VALUE }
                }
            }
            SavePosition(prefs,doc.header.id,"textPages",s.remember,ReadingPosition(block,selected))
            var drag by remember { mutableFloatStateOf(0f) }
            val currentStep by rememberUpdatedState(step)
            Box(Modifier.fillMaxSize().rotary { if (!locked) rotary.move(it,s.sensitivity,currentStep) }
                .pointerInput(locked,s.vertical) {
                    detectDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                        if (!locked && abs(drag) > 30 * density.density) currentStep(if (drag < 0) 1 else -1)
                    }) { change, amount -> if (!locked) { drag += if (s.vertical) amount.y else amount.x; change.consume() } }
                }) { BasicText(pages[selected],style = style, modifier = Modifier.fillMaxWidth()) }
            Text("${block + 1}/${blocks.size} · ${selected + 1}/${pages.size}",
                modifier = Modifier.align(Alignment.BottomEnd).offset(y = 15.dp), style = MaterialTheme.typography.caption3)
        }
        Spacer(Modifier.height(16.dp))
    }
}
