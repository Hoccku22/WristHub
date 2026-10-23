package dev.wristreader.wear

import android.bluetooth.BluetoothManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.wristreader.shared.Protocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun secureRfcommServerCanOpenOnThisFirmware() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BLUETOOTH_CONNECT")
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            assertNotNull(adapter); assertTrue("Включите Bluetooth для аппаратного теста", adapter.isEnabled)
            adapter.listenUsingRfcommWithServiceRecord("WristReader-test", Protocol.SERVICE_UUID).use {
                assertNotNull(it)
            }
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    @Test fun multiPagePdfRapidRenderAndClose() {
        val file = File(context.cacheDir, "render-test.pdf")
        try {
            val pdf = PdfDocument()
            try {
                repeat(12) { i ->
                    val p = pdf.startPage(PdfDocument.PageInfo.Builder(240,320,i + 1).create())
                    p.canvas.drawColor(Color.WHITE)
                    p.canvas.drawText("Page ${i + 1}", 20f, 80f, Paint().apply { color = Color.BLACK; textSize = 24f })
                    pdf.finishPage(p)
                }
                file.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            PdfSession(file).use { session ->
                assertEquals(12, session.count)
                assertTrue(session.aspectRatios.all { kotlin.math.abs(it - 0.75f) < 0.001f })
                repeat(48) { i -> assertTrue(session.page(i % 12).width > 0) }
            }
            // Повторное открытие проверяет освобождение PdfRenderer и дескриптора.
            PdfSession(file).use { assertEquals(12, it.count) }
        } finally { file.delete() }
    }

    @Test fun damagedPdfReportsError() {
        val file = File(context.cacheDir, "broken.pdf").apply { writeText("broken") }
        try { assertThrows(Exception::class.java) { PdfSession(file).close() } } finally { file.delete() }
    }

    @Test fun largeImageDecodeIsSampled() {
        val file = File(context.cacheDir, "large.png")
        try {
            val bitmap = Bitmap.createBitmap(2048,2048,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            val decoded = decodeImage(file)
            assertTrue(decoded.width <= 1536); decoded.recycle()
        } finally { file.delete() }
    }
}
