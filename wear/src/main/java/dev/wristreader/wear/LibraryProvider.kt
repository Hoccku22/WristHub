package dev.wristreader.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import dev.wristreader.shared.Document
import dev.wristreader.shared.DocumentStore
import dev.wristreader.shared.FileTypes
import java.io.File
import java.util.UUID

/** Original payloads only, read-only, through a temporary URI grant. */
class LibraryProvider : FileProvider() {
    private fun document(uri: Uri): Document {
        val parts = uri.pathSegments
        require(parts.size == 3 && parts[0] == "library" && parts[2] == "original") { "Файл недоступен" }
        val id = UUID.fromString(parts[1]).toString()
        require(id == parts[1])
        val store = DocumentStore(requireNotNull(context))
        return store.read(File(store.root,id))
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Доступ только для чтения" }
        document(uri)
        return requireNotNull(super.openFile(uri,mode))
    }
    override fun getType(uri: Uri): String = document(uri).header.mime
}

@Suppress("DEPRECATION")
fun fileIntent(context: Context, doc: Document): Intent {
    val uri = FileProvider.getUriForFile(context,"${context.packageName}.files",doc.original,doc.header.name)
    return Intent(if (doc.header.mime == FileTypes.APK) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW)
        .setDataAndType(uri,doc.header.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = android.content.ClipData.newRawUri(doc.header.name,uri) }
}
