package dev.wristreader.wear

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material.MaterialTheme
import dev.wristreader.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.util.UUID

class ReaderUiTest {
    @get:Rule val rule = createComposeRule()
    private var screenLock: android.os.PowerManager.WakeLock? = null
    @Suppress("DEPRECATION")
    @Before fun wakeScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        screenLock = instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,"WristReader:UI-test")
            .apply { acquire(90_000) }
        val ui = instrumentation.uiAutomation
        ui.executeShellCommand("input keyevent KEYCODE_WAKEUP").use { fd -> java.io.FileInputStream(fd.fileDescriptor).readBytes() }
    }
    @After fun captureScreen() {
        screenLock?.takeIf { it.isHeld }?.release()
        val i = InstrumentationRegistry.getInstrumentation()
        val image = i.uiAutomation.takeScreenshot() ?: return
        File(i.targetContext.cacheDir,"last-test-screen.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }

    @Test fun readerModesTextAndPositionOnRoundWatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir,"ui-fixture").apply { mkdirs() }
        val prefs = ReaderPreferences(context)
        val previousSettings = runBlocking { prefs.settings.first() }
        runBlocking { prefs.save(ReaderSettings()) }
        var settingsVisible by mutableStateOf(false)
        val id = UUID.randomUUID().toString()
        try {
            val original = File(root,"original")
            val pdf = PdfDocument()
            try {
                repeat(3) { index ->
                    val p = pdf.startPage(PdfDocument.PageInfo.Builder(240,if (index == 1) 120 else 320,index + 1).create())
                    p.canvas.drawColor(Color.WHITE)
                    p.canvas.drawText("WristReader ${index + 1}",20f,80f,Paint().apply { color=Color.BLACK; textSize=20f })
                    pdf.finishPage(p)
                }
                original.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            val text = File(root,"text.txt").apply { writeText("Учебные материалы\n\nКириллица и Latin. Текст переносится по ширине экрана.\n\n".repeat(30)) }
            val h = Protocol.Header(id,"Тест чтения.pdf","application/pdf",original.length(),text.length(),Protocol.hash(original),Protocol.hash(text))
            val doc = Document(root,h)
            rule.setContent { MaterialTheme {
                val settings by prefs.settings.collectAsState(initial = ReaderSettings())
                if (settingsVisible) SettingsScreen(settings,{ value -> prefs.scope.launch { prefs.save(value) } }) { settingsVisible = false }
                else Reader(doc,emptyList(),prefs,settings,{ settingsVisible = true },{})
            } }
            rule.waitForIdle()
            rule.waitUntil(15000) { rule.onAllNodesWithContentDescription("Страница документа").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("1 / 3").assertExists()
            rule.onNodeWithTag("reading-progress").assertRangeInfoEquals(ProgressBarRangeInfo(1f / 3,0f..1f))
            val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()
            val portrait = rule.onNodeWithContentDescription("Страница документа").fetchSemanticsNode().boundsInRoot
            runBlocking { prefs.save(ReaderSettings(progressRing = false)) }
            rule.waitUntil(10000) { rule.onAllNodesWithTag("reading-progress").fetchSemanticsNodes().isEmpty() }
            assertEquals(portrait,rule.onNodeWithContentDescription("Страница документа").fetchSemanticsNode().boundsInRoot)
            runBlocking { prefs.save(ReaderSettings()) }
            rule.onNodeWithTag("reading-progress").assertRangeInfoEquals(ProgressBarRangeInfo(1f / 3,0f..1f))
            assertTrue("Страница должна занимать больше 60% высоты круглого экрана",portrait.height > screenHeight * 0.60f)
            assertTrue("Меню должно находиться в нижней части круга",rule.onNodeWithText("Меню").fetchSemanticsNode().boundsInRoot.bottom > screenHeight * 0.93f)
            screenshot("slides-layout-qa.png")
            val now = android.os.SystemClock.uptimeMillis()
            val properties = arrayOf(android.view.MotionEvent.PointerProperties().apply { this.id = 0 })
            val coordinates = arrayOf(android.view.MotionEvent.PointerCoords().apply { setAxisValue(android.view.MotionEvent.AXIS_SCROLL,-4f) })
            val rotary = android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_SCROLL,1,
                properties,coordinates,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_ROTARY_ENCODER,0)
            try { InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(rotary,true) }
            finally { rotary.recycle() }
            rule.waitUntil(10000) { rule.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("reading-progress").assertRangeInfoEquals(ProgressBarRangeInfo(2f / 3,0f..1f))
            val beforeMenu = rule.onNodeWithContentDescription("Страница документа").fetchSemanticsNode().boundsInRoot
            rule.onNodeWithText("Меню").performClick()
            assertEquals(beforeMenu,rule.onNodeWithContentDescription("Страница документа").fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithText("Слайды").performClick()
            rule.onNodeWithText("Лента").assertExists()
            rule.waitUntil(10000) { rule.onAllNodesWithTag("pdf-feed").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pdf-feed").performScrollToIndex(1)
            rule.waitUntil(15000) {
                rule.onAllNodes(hasContentDescription("Страница документа") and hasAnyAncestor(hasTestTag("pdf-page-1")))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()
            val landscape = rule.onNodeWithTag("pdf-page-1").fetchSemanticsNode().boundsInRoot
            assertEquals("Ячейка должна повторять пропорцию PDF",2f,landscape.width / landscape.height,0.03f)
            val next = rule.onNodeWithTag("pdf-page-2").fetchSemanticsNode().boundsInRoot
            val gap = next.top - landscape.bottom
            assertTrue("Между страницами только компактный номер и малый зазор",gap >= 0 && gap <= 20 * context.resources.displayMetrics.density)
            screenshot("feed-layout-qa.png")
            rule.onNodeWithText("Лента").performClick()
            rule.onNodeWithText("Текст").assertExists()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Учебные материалы",substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Скрыть").performClick()
            rule.onAllNodesWithText("Кириллица",substring = true)[0].assertExists()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.cacheDir,"reader-qa.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            screenshot.recycle()
            rule.onNodeWithText("Меню").performClick()
            rule.onNodeWithText("⚙").performClick()
            rule.onNodeWithText("Кольцо: включено").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("Кольцо: выключено").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(runBlocking { prefs.settings.first().progressRing })
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10000) { rule.onAllNodesWithText("Меню").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("reading-progress").assertDoesNotExist()
            runBlocking {
                prefs.savePosition(id,"slides",ReadingPosition(2))
                assertEquals(2, prefs.position(id,"slides").first().index)
                prefs.savePosition(id,"textFeed",ReadingPosition(1,45))
                assertEquals(ReadingPosition(1,45),prefs.position(id,"textFeed").first())
            }
        } finally { runBlocking { prefs.save(previousSettings) }; root.deleteRecursively() }
    }

    @Test fun imagesFillScreenWithoutReaderChrome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir,"image-fixture").apply { mkdirs() }
        val original = File(root,"original")
        val bitmap = android.graphics.Bitmap.createBitmap(480,240,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        original.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        val header = Protocol.Header(UUID.randomUUID().toString(),"Landscape.png","image/png",
            original.length(),0,Protocol.hash(original),ByteArray(32))
        val doc = Document(root,header)
        val prefs = ReaderPreferences(context)
        try {
            rule.setContent { MaterialTheme {
                Reader(doc,listOf(doc),prefs,ReaderSettings(margin = 24,progressRing = true),{},{})
            } }
            rule.waitUntil(15000) { rule.onAllNodesWithContentDescription("Страница документа").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("reading-progress").assertDoesNotExist()
            rule.onNodeWithText("Меню").assertDoesNotExist()
            rule.onNodeWithText("1 / 1").assertDoesNotExist()
            val viewport = rule.onNodeWithTag("image-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals(context.resources.displayMetrics.widthPixels.toFloat(),viewport.width,1f)
            assertEquals(context.resources.displayMetrics.heightPixels.toFloat(),viewport.height,1f)
            // Landscape image must cover the top and bottom of the circular screen too.
            val screen = rule.onRoot().captureToImage().toPixelMap()
            assertEquals(androidx.compose.ui.graphics.Color.Blue,screen[screen.width / 2,12])
            assertEquals(androidx.compose.ui.graphics.Color.Blue,screen[screen.width / 2,screen.height - 13])
            rule.onNodeWithTag("image-viewport").performTouchInput { click() }
            rule.onNodeWithText("Скрыть").assertExists()
            rule.onNodeWithText("1:1").assertExists()
            rule.onNodeWithTag("image-viewport").performTouchInput { click() }
            rule.onNodeWithText("Скрыть").assertDoesNotExist()
            rule.onNodeWithTag("reading-progress").assertDoesNotExist()
            screenshot("image-fullscreen-qa.png")
        } finally { root.deleteRecursively() }
    }

    private fun screenshot(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val image = i.uiAutomation.takeScreenshot()
        File(i.targetContext.cacheDir,name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }

    @Test fun deletionIsModalAndCancelPreservesTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir,"delete-fixture-${UUID.randomUUID()}").apply { writeText("keep on cancel") }
        var show by mutableStateOf(true)
        try {
            rule.setContent { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = 35)
                    LazyColumn(state = list,modifier = Modifier.fillMaxSize()) {
                        items(80) { androidx.wear.compose.material.Text("Файл $it") }
                    }
                    if (show) DeleteFileDialog("Очень длинное имя документа для проверки удаления.pdf",{ show = false },{ file.delete(); show = false })
                }
            } }
            rule.onNodeWithTag("delete-file-dialog").assertIsDisplayed()
            rule.onNodeWithTag("confirm-file-delete").assertIsDisplayed()
            rule.onNodeWithTag("cancel-file-delete").performClick()
            rule.onNodeWithTag("delete-file-dialog").assertDoesNotExist()
            assertTrue(file.exists())
            rule.runOnIdle { show = true }
            rule.onNodeWithTag("confirm-file-delete").performClick()
            rule.onNodeWithTag("delete-file-dialog").assertDoesNotExist()
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun measuredPaginationPreservesEveryCharacter() {
        val original = "Кириллица, Latin, абзацы.\n\n".repeat(20)
        var pages = emptyList<String>()
        rule.setContent {
            val measurer = rememberTextMeasurer()
            val measured = paginate(original,measurer,TextStyle(fontSize=16.sp,lineHeight=22.sp),220,200)
            SideEffect { pages = measured }
        }
        rule.waitUntil(10000) { pages.isNotEmpty() }
        assertTrue(pages.size > 1)
        assertEquals(original,pages.joinToString(""))
    }
}
