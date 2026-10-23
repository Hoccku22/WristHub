package dev.wristreader.shared

import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Протокол WR01: заголовок → READY → оригинал → UTF-8 → COMMITTED. */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("1826c0e3-39dc-483b-a833-70dccd7ecf21")
    const val MAX_FILE = 512L * 1024 * 1024
    const val MAX_TEXT = 32L * 1024 * 1024
    const val RESERVE = 8L * 1024 * 1024
    const val READY = 1
    const val COMMITTED = 2
    const val REJECTED = 3
    val types = setOf("application/pdf", "image/jpeg", "image/png", "image/webp")

    data class Header(val id: String, val name: String, val mime: String, val size: Long,
                      val textSize: Long, val hash: ByteArray, val textHash: ByteArray) {
        fun validate() {
            require(UUID.fromString(id).toString() == id) { "Неверный идентификатор" }
            require(name.isNotBlank() && name.length <= 240 && name.none { it.isISOControl() }) { "Неверное имя" }
            require(mime in types) { "Формат не поддерживается" }
            require(size in 1..MAX_FILE && textSize in 0..MAX_TEXT) { "Размер вне допустимых пределов" }
            require(hash.size == 32 && textHash.size == 32) { "Неверный SHA-256" }
            require(mime == "application/pdf" || textSize == 0L) { "Текст допустим только для PDF" }
        }
    }

    fun writeHeader(out: DataOutputStream, h: Header) {
        h.validate()
        out.writeInt(0x57523031)
        out.writeUTF(h.id); out.writeUTF(h.name); out.writeUTF(h.mime)
        out.writeLong(h.size); out.writeLong(h.textSize)
        out.write(h.hash); out.write(h.textHash); out.flush()
    }

    fun readHeader(input: DataInputStream): Header {
        require(input.readInt() == 0x57523031) { "Несовместимая версия WristReader" }
        return Header(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(), input.readLong(),
            ByteArray(32).also { input.readFully(it) }, ByteArray(32).also { input.readFully(it) }).also { it.validate() }
    }

    /** Копирует ровно объявленное число байт; EOF и несовпадение хеша — ошибки. */
    fun copy(input: InputStream, out: OutputStream, size: Long, progress: (Long) -> Unit = {}): ByteArray {
        require(size >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var count = 0L
        while (count < size) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - count).toInt())
            if (n < 0) throw EOFException("Соединение прервано: $count / $size байт")
            if (n == 0) continue
            out.write(buffer, 0, n); digest.update(buffer, 0, n)
            count += n; progress(count)
        }
        return digest.digest()
    }

    fun verify(actual: ByteArray, expected: ByteArray) {
        require(MessageDigest.isEqual(actual, expected)) { "Ошибка целостности SHA-256" }
    }

    fun requireSpace(free: Long, h: Header) {
        require(free >= h.size + h.textSize + RESERVE) { "Недостаточно свободного места на часах" }
    }

    fun hash(file: File): ByteArray = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32768)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest()
    }
}
