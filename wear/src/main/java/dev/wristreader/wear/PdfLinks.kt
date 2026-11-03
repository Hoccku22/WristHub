package dev.wristreader.wear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import java.io.File

/** Normalized coordinates in the rendered (cropped and rotated) page. */
data class PdfLink(val bounds: Rect, val page: Int)

fun readPdfLinks(file: File): List<List<PdfLink>> =
    PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
        pdf.pages.map { source ->
            val crop = source.cropBox
            fun point(x: Float, y: Float): Offset {
                val u = (x - crop.lowerLeftX) / crop.width
                val v = (y - crop.lowerLeftY) / crop.height
                return when ((source.rotation % 360 + 360) % 360) {
                    90 -> Offset(v, u)
                    180 -> Offset(1 - u, v)
                    270 -> Offset(1 - v, 1 - u)
                    else -> Offset(u, 1 - v)
                }
            }
            // A broken annotation must not disable the other links or page rendering.
            runCatching { source.annotations }.getOrDefault(emptyList()).mapNotNull { annotation ->
                runCatching {
                    val link = annotation as? PDAnnotationLink ?: return@runCatching null
                    val destination = link.destination ?: (link.action as? PDActionGoTo)?.destination
                    val target = when (destination) {
                        is PDNamedDestination -> pdf.documentCatalog.findNamedDestinationPage(destination)
                        is PDPageDestination -> destination
                        else -> null
                    } ?: return@runCatching null
                    val index = target.retrievePageNumber()
                    if (index !in 0 until pdf.numberOfPages) return@runCatching null
                    val rect = link.rectangle ?: return@runCatching null
                    val a = point(rect.lowerLeftX, rect.lowerLeftY)
                    val b = point(rect.upperRightX, rect.upperRightY)
                    PdfLink(Rect(minOf(a.x,b.x), minOf(a.y,b.y), maxOf(a.x,b.x), maxOf(a.y,b.y)), index)
                }.getOrNull()
            }
        }
    }

fun List<PdfLink>.targetAt(point: Offset): Int? = firstOrNull { it.bounds.contains(point) }?.page
