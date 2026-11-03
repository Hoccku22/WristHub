package dev.wristreader.wear

import androidx.compose.ui.geometry.Offset
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfLinksTest {
    @Test fun parsesDirectNamedAndInvalidLinksFromSavedPdf() {
        val file = File.createTempFile("wristhub-links", ".pdf")
        try {
            PDDocument().use { pdf ->
                repeat(3) { pdf.addPage(PDPage(PDRectangle(200f,400f))) }
                val target = PDPageFitDestination().apply { page = pdf.getPage(2) }
                pdf.getPage(0).annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(20f,80f,100f,160f); destination = target
                })
                pdf.documentCatalog.names = PDDocumentNameDictionary(pdf.documentCatalog).apply {
                    dests = PDDestinationNameTreeNode().apply { names = mapOf("chapter" to target) }
                }
                pdf.getPage(1).annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(20f,80f,100f,160f)
                    action = PDActionGoTo().apply { destination = PDNamedDestination("chapter") }
                })
                pdf.getPage(1).annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(0f,0f,10f,10f)
                    destination = PDNamedDestination("missing")
                })
                pdf.save(file)
            }
            val links = readPdfLinks(file)
            assertEquals(2,links[0].targetAt(Offset(.3f,.5f)))
            assertEquals(2,links[1].targetAt(Offset(.3f,.5f)))
            assertNull(links[0].targetAt(Offset(.9f,.9f)))
            assertEquals(1,links[1].size)
            assertTrue(links[2].isEmpty())
        } finally { file.delete() }
    }

    @Test fun linkHitRegionsFollowAllPageRotationsAndCropOrigin() {
        val file = File.createTempFile("wristhub-rotations", ".pdf")
        try {
            PDDocument().use { pdf ->
                for (angle in listOf(0,90,180,270)) pdf.addPage(PDPage(PDRectangle(240f,640f)).apply {
                    cropBox = PDRectangle(10f,20f,200f,400f); rotation = angle
                })
                for (page in pdf.pages) page.annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(30f,60f,40f,80f)
                    destination = PDPageFitDestination().apply { this.page = pdf.getPage(3) }
                })
                pdf.save(file)
            }
            val links = readPdfLinks(file)
            for ((index,center) in listOf(Offset(.2f,.8f),Offset(.2f,.2f),Offset(.8f,.2f),Offset(.8f,.8f)).withIndex()) {
                assertEquals(3,links[index].targetAt(center))
                assertNull(links[index].targetAt(Offset(.5f,.5f)))
            }
        } finally { file.delete() }
    }
}
