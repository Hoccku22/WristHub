package dev.wristreader.shared

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.MessageDigest
import java.util.UUID

class ProtocolTest {
    @get:Rule val folder = TemporaryFolder()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun header(b: ByteArray, text: ByteArray = byteArrayOf(), mime: String = "application/pdf") =
        Protocol.Header(UUID.randomUUID().toString(), "Учебник.pdf", mime, b.size.toLong(), text.size.toLong(), sha(b), sha(text))

    @Test fun cyrillicHeaderRoundTrip() {
        val h = header(byteArrayOf(1,2,3))
        val bytes = ByteArrayOutputStream()
        Protocol.writeHeader(DataOutputStream(bytes), h)
        val parsed = Protocol.readHeader(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(h.name, parsed.name); assertEquals(h.size, parsed.size); assertArrayEquals(h.hash, parsed.hash)
    }
    @Test fun twentyMiBStreamsWithBoundedReads() {
        val size = 20L * 1024 * 1024
        var remaining = size
        val input = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 7 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertTrue(len <= 32768)
                if (remaining == 0L) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                b.fill(7, off, off + n); remaining -= n; return n
            }
        }
        var done = 0L
        val actual = Protocol.copy(input, OutputStream.nullOutputStream(), size) { done = it }
        val md = MessageDigest.getInstance("SHA-256")
        repeat(640) { md.update(ByteArray(32768) { 7 }) }
        assertEquals(size, done); assertArrayEquals(md.digest(), actual)
    }
    @Test fun pdfAndTextCommittedTogether() {
        val bytes = "%PDF-original".toByteArray(); val text = "Привет\n\nWorld".toByteArray()
        val temp = folder.newFolder()
        VerifiedPayload.receive(ByteArrayInputStream(bytes + text), temp, header(bytes, text))
        assertArrayEquals(bytes, File(temp,"original").readBytes())
        assertArrayEquals(text, File(temp,"text.txt").readBytes())
    }
    @Test fun disconnectDeletesPartialPacket() {
        val bytes = ByteArray(4096) { 1 }; val temp = folder.newFolder()
        assertThrows(EOFException::class.java) { VerifiedPayload.receive(ByteArrayInputStream(bytes.copyOf(1000)), temp, header(bytes)) }
        assertFalse(temp.exists())
    }
    @Test fun corruptionDeletesPacket() {
        val temp = folder.newFolder()
        assertThrows(IllegalArgumentException::class.java) { VerifiedPayload.receive(ByteArrayInputStream(byteArrayOf(2)), temp, header(byteArrayOf(1))) }
        assertFalse(temp.exists())
    }
    @Test fun failedTextAlsoDeletesOriginal() {
        val temp = folder.newFolder(); val h = header(byteArrayOf(1), byteArrayOf(3))
        assertThrows(IllegalArgumentException::class.java) { VerifiedPayload.receive(ByteArrayInputStream(byteArrayOf(1,2)), temp, h) }
        assertFalse(temp.exists())
    }
    @Test fun cancelledReadDeletesPacketAndCanRetry() {
        val h = header(byteArrayOf(1,2,3)); val temp = folder.newFolder()
        val cancelled = object : InputStream() { override fun read(): Int = throw IOException("socket closed") }
        assertThrows(IOException::class.java) { VerifiedPayload.receive(cancelled, temp, h) }
        assertFalse(temp.exists()); assertTrue(temp.mkdir())
        VerifiedPayload.receive(ByteArrayInputStream(byteArrayOf(1,2,3)), temp, h)
        assertEquals(3L, File(temp,"original").length())
    }
    @Test fun insufficientSpaceRejectedBeforePayload() {
        val h = header(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { Protocol.requireSpace(Protocol.RESERVE, h) }
        Protocol.requireSpace(Protocol.RESERVE + 1, h)
    }
    @Test fun maliciousHeadersRejected() {
        val h = header(byteArrayOf(1))
        for (bad in listOf(h.copy(id = "../../x"), h.copy(size = -1), h.copy(size = Protocol.MAX_FILE + 1),
            h.copy(textSize = Long.MAX_VALUE), h.copy(name = "x\nx"), h.copy(mime = "application/\nzip"), h.copy(mime = "*/*"))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        }
    }
    @Test fun multipleImagesUseIndependentFrames() {
        val wire = ByteArrayOutputStream(); val out = DataOutputStream(wire)
        val bytes = listOf(byteArrayOf(1), byteArrayOf(2,3), byteArrayOf(4,5,6))
        bytes.forEach { Protocol.writeHeader(out, header(it, mime = "image/png")); out.write(it) }
        val input = DataInputStream(ByteArrayInputStream(wire.toByteArray()))
        bytes.forEach { expected ->
            val h = Protocol.readHeader(input); val temp = folder.newFolder()
            VerifiedPayload.receive(input, temp, h)
            assertArrayEquals(expected, File(temp,"original").readBytes())
        }
        assertEquals(-1, input.read())
    }

    @Test fun arbitraryFilesPreserveBytesAndMime() {
        for (mime in listOf(FileTypes.APK,"audio/mpeg","application/zip",FileTypes.BINARY,"text/plain")) {
            val bytes = ByteArray(8192) { (it % 251).toByte() }
            val h = header(bytes,mime = mime)
            val wire = ByteArrayOutputStream()
            Protocol.writeHeader(DataOutputStream(wire),h)
            val decoded = Protocol.readHeader(DataInputStream(ByteArrayInputStream(wire.toByteArray())))
            assertEquals(mime,decoded.mime)
            val temp = folder.newFolder()
            VerifiedPayload.receive(ByteArrayInputStream(bytes),temp,decoded)
            assertArrayEquals(bytes,File(temp,"original").readBytes())
            assertEquals(0L,File(temp,"text.txt").length())
        }
    }

    @Test fun fileDetectionKeepsKnownReadersAndNormalizesOtherTypes() {
        assertEquals("application/pdf",FileTypes.detect("file.bin",null,"%PDF-test".toByteArray()))
        assertEquals(FileTypes.APK,FileTypes.detect("App.APK","application/zip",byteArrayOf(80,75)))
        assertEquals("audio/mpeg",FileTypes.detect("Song.MP3",FileTypes.BINARY,byteArrayOf()))
        assertEquals(FileTypes.BINARY,FileTypes.detect("file.unknown","*/*",byteArrayOf()))
        assertEquals(FileTypes.BINARY,FileTypes.detect("file.unknown","text/plain\nInjected",byteArrayOf()))
        assertEquals("application/zip",FileTypes.detect("backup.zip",null,byteArrayOf()))
        assertFalse(FileTypes.isReadable("audio/mpeg"))
        assertTrue(FileTypes.isReadable("image/png"))
    }

    @Test fun emptyGenericFileIsCommittedAsRealFiles() {
        val temp = folder.newFolder()
        VerifiedPayload.receive(ByteArrayInputStream(byteArrayOf()),temp,header(byteArrayOf(),mime = FileTypes.BINARY))
        assertTrue(File(temp,"original").isFile)
        assertTrue(File(temp,"text.txt").isFile)
        assertEquals(0L,File(temp,"original").length())
    }
}
