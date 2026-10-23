package dev.wristreader.shared

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class Document(val directory: File, val header: Protocol.Header) {
    val original get() = File(directory, "original")
    val text get() = File(directory, "text.txt")
}

/** Имена с удалённой стороны никогда не становятся путями файлов. */
class DocumentStore(context: Context, outgoing: Boolean = false) {
    val root = File(context.filesDir, if (outgoing) "outgoing" else "library").apply { mkdirs() }
    fun list(): List<Document> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { runCatching { read(it) }.getOrNull() }.sortedByDescending { it.directory.lastModified() }

    fun read(dir: File): Document {
        val j = JSONObject(File(dir, "meta.json").readText())
        fun bytes(key: String): ByteArray = j.getString(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val h = Protocol.Header(j.getString("id"), j.getString("name"), j.getString("mime"),
            j.getLong("size"), j.getLong("textSize"), bytes("hash"), bytes("textHash"))
        h.validate()
        require(File(dir, "original").length() == h.size && File(dir, "text.txt").length() == h.textSize)
        return Document(dir, h)
    }

    fun metadata(dir: File, h: Protocol.Header) {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        val data = JSONObject().put("id", h.id).put("name", h.name)
            .put("mime", h.mime).put("size", h.size).put("textSize", h.textSize)
            .put("hash", hex(h.hash)).put("textHash", hex(h.textHash)).toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(File(dir,"meta.json")).use { it.write(data); it.fd.sync() }
    }

    fun cleanup() { root.listFiles().orEmpty().filter { it.name.startsWith(".") }.forEach { it.deleteRecursively() } }
}
