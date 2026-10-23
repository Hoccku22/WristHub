package dev.wristreader.wear

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import androidx.wear.compose.material.*
import dev.wristreader.shared.Document
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PdfLoad(val session: PdfSession? = null, val error: String = "")
data class BitmapLoad(val bitmap: Bitmap? = null, val error: String = "")

@Composable private fun pdf(doc: Document, prefs: ReaderPreferences): PdfLoad {
    val load by produceState(PdfLoad(), doc.header.id) {
        val result = withContext(Dispatchers.IO) { runCatching { PdfSession(doc.original) } }
        val session = result.getOrNull()
        value = PdfLoad(session, result.exceptionOrNull()?.message ?: "")
        awaitDispose { prefs.scope.launch { session?.close() } }
    }
    return load
}

@Composable private fun pageBitmap(session: PdfSession, page: Int, prefetch: Boolean = true): BitmapLoad {
    val load by produceState(BitmapLoad(), session, page, prefetch) {
        value = BitmapLoad()
        value = withContext(Dispatchers.IO) { runCatching { session.page(page) }
            .fold({ BitmapLoad(it) }, { BitmapLoad(error = it.message ?: "Ошибка страницы") }) }
        // Соседние страницы подготавливаются вне главного потока и остаются в LRU.
        if (prefetch) withContext(Dispatchers.IO) {
            for (next in listOf(page + 1, page - 1)) if (next in 0 until session.count) {
                ensureActive(); runCatching { session.page(next) }
            }
        }
    }
    return load
}

@Composable private fun Picture(load: BitmapLoad, modifier: Modifier, fitWidth: Boolean = false) {
    Box(modifier, contentAlignment = Alignment.Center) {
        load.bitmap?.let { Image(it.asImageBitmap(), "Страница документа", Modifier.fillMaxSize(),
            contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit) }
        if (load.error.isNotEmpty()) Text(load.error) else if (load.bitmap == null) CircularProgressIndicator()
    }
}

@Composable private fun ZoomPicture(load: BitmapLoad, key: Any, s: ReaderSettings, locked: Boolean,
    reset: Int, modifier: Modifier, step: (Int) -> Unit) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val bitmap = load.bitmap
        val initial = if (s.fitWidth && reset == 0 && bitmap != null)
            (maxWidth.value / maxHeight.value * bitmap.height / bitmap.width).coerceIn(1f,6f) else 1f
        Picture(load, Modifier.fillMaxSize().zoomable(key,locked,s.vertical,step,initial))
    }
}

@Composable fun Reader(
    doc: Document, images: List<Document>, prefs: ReaderPreferences, settings: ReaderSettings,
    onSettings: () -> Unit, onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val view = LocalView.current
    DisposableEffect(view) {
        val old = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = old }
    }
    var mode by remember(doc.header.id) { mutableStateOf(settings.mode) }
    var controls by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var reset by remember { mutableIntStateOf(0) }
    val progress = remember(doc.header.id,mode) { ReadingProgressState() }
    val isPdf = doc.header.mime == "application/pdf"
    val loaded = if (isPdf) pdf(doc, prefs) else PdfLoad()
    val bg = if (settings.dark) Color.Black else Color.White
    val fg = if (settings.dark) Color.White else Color.Black
    CompositionLocalProvider(LocalContentColor provides fg) {
        BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
            val safeX = maxWidth * 0.147f + settings.margin.dp
            val safeY = maxHeight * 0.147f
            // Элементы управления накладываются поверх, не сжимая страницу при открытии меню.
            Box(Modifier.fillMaxSize()) {
                when {
                    !isPdf -> ImageSlides(doc, images, prefs, settings, locked, reset, progress) { controls = !controls }
                    loaded.error.isNotEmpty() -> Text("PDF не открыт: ${loaded.error}", Modifier.padding(safeX,safeY))
                    loaded.session == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    mode == "feed" -> Box(Modifier.fillMaxSize().padding(horizontal = safeX, vertical = safeY)) {
                        PdfFeed(doc, loaded.session, prefs, settings, locked,progress)
                    }
                    mode == "text" -> Box(Modifier.fillMaxSize().padding(horizontal = safeX, vertical = safeY).padding(bottom = 8.dp)) {
                        TextReader(doc,prefs,settings,locked,progress) { mode = "slides" }
                    }
                    else -> PdfSlides(doc, loaded.session, prefs, settings, locked, reset, !controls,progress)
                }
                if (controls) {
                    Column(Modifier.align(Alignment.TopCenter).padding(horizontal = safeX).padding(top = safeY)
                        .background(bg.copy(alpha = 0.96f)), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(doc.header.name, maxLines = 1, style = MaterialTheme.typography.caption2)
                    Row(horizontalArrangement = Arrangement.Center) {
                        if (isPdf) Text("${when(mode) { "feed" -> "Лента"; "text" -> "Текст"; else -> "Слайды" }}",
                            Modifier.clickable {
                                mode = when(mode) { "slides" -> "feed"; "feed" -> "text"; else -> "slides" }
                                prefs.scope.launch { prefs.save(settings.copy(mode = mode)) }
                            }.padding(6.dp))
                        Text("⚙", Modifier.clickable(onClick = onSettings).padding(6.dp))
                    }
                    }
                }
                if (isPdf || controls) Row(Modifier.align(Alignment.BottomCenter).offset(x = if (!controls && (!isPdf || mode == "slides")) 24.dp else 0.dp)
                    .padding(bottom = 8.dp).background(bg),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (controls) {
                        Text("1:1", Modifier.clickable { reset++ }.padding(6.dp), style = MaterialTheme.typography.caption2)
                        Text(if (locked) "🔒" else "🔓", Modifier.clickable { locked = !locked }.padding(6.dp))
                    }
                    Text(if (controls) "Скрыть" else "Меню", Modifier.clickable { controls = !controls }.padding(6.dp), style = MaterialTheme.typography.caption2)
                }
                // Поверх подложек меню, чтобы нижняя часть окружности не прерывалась.
                if (isPdf && settings.progressRing) ReadingProgressRing(progress)
            }
        }
    }
}

/** Максимальный прямоугольник страницы внутри круга с местом под нижние кнопки. */
@Composable private fun RoundPageFrame(aspect: Float, margin: Int, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val radius = (minOf(maxWidth,maxHeight) / 2 - 4.dp - margin.dp).value.coerceAtLeast(1f)
        val ratio = aspect.coerceAtLeast(0.01f)
        val center = maxHeight.value / 2
        val bottom = (maxHeight - 34.dp).value.coerceAtLeast(1f)
        val centeredHeight = 2 * radius / sqrt(1 + ratio * ratio)
        val needsLift = center + centeredHeight / 2 > bottom
        val factor = 1 + ratio * ratio / 4
        val reach = bottom - center
        val height = if (needsLift)
            ((reach + sqrt(factor * radius * radius - ratio * ratio / 4 * reach * reach)) / factor).coerceAtLeast(1f)
            else centeredHeight
        val width = (height * ratio).coerceAtMost(maxWidth.value)
        val top = if (needsLift) bottom - height else center - height / 2
        Box(Modifier.align(Alignment.TopCenter).offset(y = top.dp).width(width.dp).height(height.dp).clipToBounds(), content = content)
    }
}

@Composable private fun PdfSlides(doc: Document, session: PdfSession, prefs: ReaderPreferences,
    s: ReaderSettings, locked: Boolean, reset: Int, showCounter: Boolean, progress: ReadingProgressState) {
    val position = remember(prefs,doc.header.id) { prefs.position(doc.header.id,"slides") }
    val restored by position.collectAsState(initial = null)
    if (restored == null) return
    var page by remember(doc.header.id) { mutableIntStateOf(if (s.remember) restored!!.index.coerceIn(0,session.count - 1) else 0) }
    SideEffect { progress.page(page,session.count) }
    SavePosition(prefs, doc.header.id, "slides", s.remember, ReadingPosition(page))
    val rotary = remember { RotaryPages() }
    val step: (Int) -> Unit = { page = (page + it).coerceIn(0, session.count - 1) }
    val load = pageBitmap(session,page)
    Box(Modifier.fillMaxSize().rotary { if (!locked) rotary.move(it,s.sensitivity,step) }) {
        RoundPageFrame(if (s.fitWidth && reset == 0) maxOf(1f,session.aspectRatios[page]) else session.aspectRatios[page],s.margin) {
            ZoomPicture(load, "$page/$reset", s,locked,reset,Modifier.fillMaxSize(),step)
        }
        if (showCounter) Text("${page + 1} / ${session.count}", Modifier.align(Alignment.BottomCenter).offset(x = (-34).dp).padding(bottom = 14.dp), style = MaterialTheme.typography.caption2)
    }
}

@Composable private fun PdfFeed(doc: Document, session: PdfSession, prefs: ReaderPreferences, s: ReaderSettings, locked: Boolean, progress: ReadingProgressState) {
    val position = remember(prefs,doc.header.id) { prefs.position(doc.header.id,"feed") }
    val restored by position.collectAsState(initial = null)
    if (restored == null) return
    val state = rememberLazyListState(if (s.remember) restored!!.index.coerceIn(0,session.count - 1) else 0,
        if (s.remember) restored!!.offset.coerceAtLeast(0) else 0)
    val scope = rememberCoroutineScope()
    SaveScrollPosition(prefs,doc.header.id,"feed",s.remember,state)
    TrackScrollProgress(state,progress)
    LazyColumn(state = state, userScrollEnabled = !locked, verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize().testTag("pdf-feed").rotary { if (!locked) scope.launch { state.scrollBy(it * s.sensitivity) } }) {
        items(session.count, key = { it }) { index ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Без фиксированной ячейки и её пустых полей; LazyColumn сам готовит соседние элементы.
                Picture(pageBitmap(session,index,prefetch = false), Modifier.fillMaxWidth().aspectRatio(session.aspectRatios[index]).testTag("pdf-page-$index"))
                Text("${index + 1} / ${session.count}", style = MaterialTheme.typography.caption3)
            }
        }
    }
}

@Composable private fun ImageSlides(doc: Document, images: List<Document>, prefs: ReaderPreferences,
    s: ReaderSettings, locked: Boolean, reset: Int, progress: ReadingProgressState, onTap: () -> Unit) {
    val slides = remember(images,doc) { images.ifEmpty { listOf(doc) } }
    val position = remember(prefs,doc.header.id) { prefs.position(doc.header.id,"images") }
    val restored by position.collectAsState(initial = null)
    if (restored == null) return
    var index by remember(doc.header.id) { mutableIntStateOf(if (s.remember && restored!!.offset == 1) restored!!.index.coerceIn(0,slides.lastIndex)
        else slides.indexOfFirst { it.header.id == doc.header.id }.coerceAtLeast(0)) }
    SavePosition(prefs,doc.header.id,"images",s.remember,ReadingPosition(index,1))
    SideEffect { progress.page(index,slides.size) }
    val selected = slides[index]
    val load by produceState(BitmapLoad(), selected.header.id) {
        value = BitmapLoad()
        value = withContext(Dispatchers.IO) { runCatching { decodeImage(selected.original) }
            .fold({ BitmapLoad(it) }, { BitmapLoad(error = it.message ?: "Ошибка изображения") }) }
    }
    val rotary = remember { RotaryPages() }
    val step: (Int) -> Unit = { index = (index + it).coerceIn(0,slides.lastIndex) }
    Box(Modifier.fillMaxSize().rotary { if (!locked) rotary.move(it,s.sensitivity,step) }) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("image-viewport")) {
            val aspect = load.bitmap?.let { it.width.toFloat() / it.height } ?: 1f
            val screenAspect = maxWidth.value / maxHeight.value
            // Заполняем весь экран без искажения пропорций. Края доступны перемещением.
            val fillScale = if (reset == 0) maxOf(aspect / screenAspect,screenAspect / aspect) else 1f
            Picture(load, Modifier.fillMaxSize().zoomable("$index/$reset",locked,s.vertical,step,fillScale,onTap,aspect))
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable fun SaveScrollPosition(prefs: ReaderPreferences, id: String, mode: String, enabled: Boolean, state: LazyListState) {
    LaunchedEffect(id,mode,enabled,state) {
        if (enabled) snapshotFlow { ReadingPosition(state.firstVisibleItemIndex,state.firstVisibleItemScrollOffset) }
            .distinctUntilChanged().debounce(250).collect { prefs.savePosition(id,mode,it) }
    }
    DisposableEffect(id,mode,enabled,state) { onDispose {
        if (enabled) {
            val position = ReadingPosition(state.firstVisibleItemIndex,state.firstVisibleItemScrollOffset)
            prefs.scope.launch { prefs.savePosition(id,mode,position) }
        }
    } }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable fun SavePosition(prefs: ReaderPreferences, id: String, mode: String, enabled: Boolean, value: ReadingPosition) {
    val latest by rememberUpdatedState(value)
    LaunchedEffect(id,mode,enabled) {
        if (enabled) snapshotFlow { latest }.distinctUntilChanged().debounce(250).collect { prefs.savePosition(id,mode,it) }
    }
    DisposableEffect(id,mode,enabled) { onDispose { if (enabled) prefs.scope.launch { prefs.savePosition(id,mode,latest) } } }
}
