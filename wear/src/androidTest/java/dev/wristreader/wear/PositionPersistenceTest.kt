package dev.wristreader.wear

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import dev.wristreader.shared.*
import java.io.File
import java.io.ByteArrayInputStream

/** Запускать методы двумя отдельными am instrument, чтобы между ними умер процесс. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PositionPersistenceTest {
    private val prefs get() = ReaderPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
    private val id = "00000000-0000-0000-0000-000000000001"
    @Test fun aWritePositions() = runBlocking {
        prefs.savePosition(id,"slides",ReadingPosition(7))
        prefs.savePosition(id,"textFeed",ReadingPosition(3,81))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DocumentStore(context)
        val dir = File(store.root,id).apply { mkdirs() }
        val bytes = "%PDF-fixture-for-storage".toByteArray()
        val original = File(context.cacheDir,"persistence-hash").apply { writeBytes(bytes) }
        val empty = File(context.cacheDir,"persistence-text").apply { writeBytes(byteArrayOf()) }
        val h = Protocol.Header(id,"Проверка хранения.pdf","application/pdf",bytes.size.toLong(),0,Protocol.hash(original),Protocol.hash(empty))
        try { VerifiedPayload.receive(ByteArrayInputStream(bytes),dir,h); store.metadata(dir,h) }
        finally { original.delete(); empty.delete() }
    }
    @Test fun bReadPositionsAfterProcessRestart() = runBlocking {
        assertEquals(ReadingPosition(7),prefs.position(id,"slides").first())
        assertEquals(ReadingPosition(3,81),prefs.position(id,"textFeed").first())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DocumentStore(context)
        val dir = File(store.root,id)
        val doc = store.read(dir)
        try { Protocol.verify(Protocol.hash(doc.original),doc.header.hash) }
        finally { dir.deleteRecursively() }
    }
}
