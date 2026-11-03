package dev.wristreader.wear

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import dev.wristreader.shared.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfNavigationTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(root: File): File {
        PDFBoxResourceLoader.init(context)
        val file = File(root,"original")
        PDDocument().use { pdf ->
            repeat(3) { pdf.addPage(PDPage(PDRectangle(240f,640f))) }
            val target = PDPageFitDestination().apply { page = pdf.getPage(2) }
            pdf.getPage(0).annotations.add(PDAnnotationLink().apply {
                rectangle = PDRectangle(48f,128f,144f,384f)
                destination = target
            })
            val tree = PDDestinationNameTreeNode().apply { names = mapOf("chapter" to target) }
            pdf.documentCatalog.names = PDDocumentNameDictionary(pdf.documentCatalog).apply { dests = tree }
            pdf.getPage(1).apply {
                cropBox = PDRectangle(10f,20f,200f,400f)
                rotation = 90
                annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(30f,60f,40f,80f)
                    action = PDActionGoTo().apply { destination = PDNamedDestination("chapter") }
                })
            }
            pdf.save(file)
        }
        return file
    }

    @Test fun directAndNamedDestinationsRespectCropAndRotation() {
        val root = File(context.cacheDir,"pdf-links-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val links = readPdfLinks(fixture(root))
            assertEquals(2,links[0].targetAt(Offset(.5f,.5f)))
            assertNull(links[0].targetAt(Offset(.05f,.05f)))
            val rotated = links[1].single()
            assertEquals(2,rotated.page)
            assertEquals(.1f,rotated.bounds.left,.001f)
            assertEquals(.1f,rotated.bounds.top,.001f)
            assertEquals(.3f,rotated.bounds.right,.001f)
            assertEquals(.3f,rotated.bounds.bottom,.001f)
            assertTrue(links[2].isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun doubleTapZoomDoesNotFollowLinkAndSingleTapNavigatesInBothModes() {
        val root = File(context.cacheDir,"pdf-taps-${UUID.randomUUID()}").apply { mkdirs() }
        val prefs = ReaderPreferences(context)
        val id = UUID.randomUUID().toString()
        try {
            val file = fixture(root)
            val doc = Document(root,Protocol.Header(id,"Links.pdf","application/pdf",file.length(),0,Protocol.hash(file),ByteArray(32)))
            var settings by mutableStateOf(ReaderSettings(remember = false))
            rule.setContent { WatchTheme { Reader(doc,emptyList(),prefs,settings,{},{}) } }
            rule.waitUntil(15000) { rule.onAllNodesWithContentDescription("Страница документа").fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(15000) { rule.onAllNodesWithTag("pdf-links-ready").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("zoom-picture").performTouchInput { doubleClick() }
            rule.onNodeWithTag("zoom-picture").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"zoom:2.0"))
            rule.onNodeWithText("1 / 3").assertExists()
            rule.onNodeWithTag("zoom-picture").performTouchInput { doubleClick() }
            rule.onNodeWithTag("zoom-picture").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"zoom:1.0"))
            rule.onNodeWithTag("zoom-picture").performTouchInput { click() }
            rule.waitUntil(10000) { rule.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty() }
            rule.runOnIdle { settings = settings.copy(mode = "feed") }
            rule.waitUntil(15000) { rule.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("pdf-page-0").performTouchInput { click() }
            rule.waitUntil(10000) { rule.onAllNodesWithTag("pdf-page-2").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            rule.runOnIdle { }
            root.deleteRecursively()
        }
    }
}
