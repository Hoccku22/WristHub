package dev.wristreader.shared

import java.util.Locale

/** MIME describes a file; it never grants permission to execute or install it. */
object FileTypes {
    const val APK = "application/vnd.android.package-archive"
    const val BINARY = "application/octet-stream"
    private val mimePattern = Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")
    fun validMime(value: String) = value.length <= 127 && mimePattern.matches(value)
    private val extensions = mapOf(
        "apk" to APK, "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "wav" to "audio/wav", "flac" to "audio/flac", "ogg" to "audio/ogg", "opus" to "audio/ogg",
        "amr" to "audio/amr", "txt" to "text/plain", "zip" to "application/zip",
        "json" to "application/json", "mp4" to "video/mp4")
    fun detect(name: String, declared: String?, signature: ByteArray): String {
        val extension = name.substringAfterLast('.',"").lowercase(Locale.ROOT)
        if (extension == "apk") return APK
        fun ascii(start: Int, count: Int) = if (signature.size >= start + count)
            String(signature,start,count,Charsets.US_ASCII) else ""
        return when {
            ascii(0,5) == "%PDF-" -> "application/pdf"
            signature.size >= 2 && signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte() -> "image/jpeg"
            signature.size >= 8 && signature.copyOf(8).contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)) -> "image/png"
            ascii(0,4) == "RIFF" && ascii(8,4) == "WEBP" -> "image/webp"
            ascii(0,3) == "GIF" -> "image/gif"
            else -> extensions[extension] ?: declared?.substringBefore(';')?.lowercase(Locale.ROOT)
                ?.takeIf(::validMime) ?: BINARY
        }
    }
    fun isReadable(mime: String) = mime == "application/pdf" || mime in setOf("image/jpeg","image/png","image/webp","image/gif","image/bmp")
    fun label(mime: String) = when {
        mime == APK -> "Приложение APK"
        mime.startsWith("audio/") -> "Музыка"
        mime == "application/pdf" -> "PDF"
        mime.startsWith("image/") -> "Изображение"
        else -> "Файл • ${mime.substringAfter('/')}"
    }
}
