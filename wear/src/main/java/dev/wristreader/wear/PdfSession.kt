package dev.wristreader.wear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.File

/** PdfRenderer не потокобезопасен: открытие страницы, кэш и закрытие сериализованы. */
class PdfSession(file: File) : Closeable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
    val count = renderer.pageCount
    // Геометрия загружается вместе с PDF вне UI-потока: лента сразу знает точную высоту.
    val aspectRatios: FloatArray = try {
        FloatArray(count) { index -> renderer.openPage(index).use { it.width.toFloat() / it.height } }
    } catch (e: Exception) { renderer.close(); fd.close(); throw e }
    private var closed = false
    private val cache = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
        // Bitmap может ещё отображаться Compose, поэтому recycle при вытеснении не вызывается.
    }
    @Synchronized fun page(index: Int): Bitmap {
        check(!closed) { "PDF закрыт" }
        cache[index]?.let { return it }
        return renderer.openPage(index).use { page ->
            val scale = minOf(640f / page.width, 2048f / page.height)
            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(index, bitmap)
            bitmap
        }
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; cache.evictAll(); renderer.close(); fd.close() }
    }
}

fun decodeImage(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Изображение повреждено" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Не удалось открыть изображение")
}
