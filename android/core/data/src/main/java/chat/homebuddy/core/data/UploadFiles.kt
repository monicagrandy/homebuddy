package chat.homebuddy.core.data

import java.io.File
import java.io.InputStream

/**
 * Private copies of picked PDFs. A picked file's content:// permission can lapse before the
 * upload runs, so the bytes are copied in while the app still has access.
 */
class UploadFiles(private val dir: File) {
    fun save(id: String, source: InputStream, maxBytes: Long = MAX_BYTES): File {
        dir.mkdirs()
        val target = File(dir, "$id.pdf")
        source.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        output.close()
                        target.delete()
                        throw FileTooLarge()
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        return target
    }

    fun delete(path: String?) {
        if (path != null) File(path).delete()
    }

    fun clear() {
        dir.deleteRecursively()
    }

    class FileTooLarge : Exception("The PDF is larger than 50 MB.")

    companion object {
        /** The server's limit (MAX_UPLOAD_MB). */
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
