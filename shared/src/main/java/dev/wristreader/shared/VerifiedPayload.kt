package dev.wristreader.shared

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Сбой или отмена закрытием сокета всегда удаляют незавершённый пакет. */
object VerifiedPayload {
    fun receive(input: InputStream, temp: File, h: Protocol.Header, progress: (Long) -> Unit = {}) {
        try {
            FileOutputStream(File(temp, "original")).use {
                Protocol.verify(Protocol.copy(input, it, h.size, progress), h.hash); it.fd.sync()
            }
            FileOutputStream(File(temp, "text.txt")).use {
                Protocol.verify(Protocol.copy(input, it, h.textSize) { n -> progress(h.size + n) }, h.textHash); it.fd.sync()
            }
        } catch (e: Exception) { temp.deleteRecursively(); throw e }
    }
}
