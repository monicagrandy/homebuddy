package chat.homebuddy.core.data.documents

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Private copies of picked PDFs. A picked file's content:// permission can lapse before the
 * upload runs, so the bytes are copied in while the app still has access.
 *
 * A copy is written to a partial file and renamed into place only once it's complete, so a copy
 * that fails, fills the storage or is cancelled leaves nothing behind. Anything left over anyway,
 * for example when the app was killed mid-copy, is removed by [removeUnreferenced].
 */
class UploadFiles(
    private val dir: File,
    /** Where a copy's bytes are written; tests use it to run out of space. */
    private val openOutput: (File) -> OutputStream = ::FileOutputStream,
) {
    /** Blocks on [source]; call it on an IO dispatcher. Cancellation is checked between chunks. */
    suspend fun save(id: String, source: InputStream, maxBytes: Long = MAX_BYTES): File {
        val target = file(id)
        val partial = partialFile(id)
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw CopyFailed(IOException("Couldn't create $dir."))
            source.use { input -> openOutput(partial).use { output -> copy(input, output, maxBytes) } }
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(target)) throw CopyFailed(IOException("Couldn't move the copy into place."))
            return target
        } finally {
            // Gone already once it's been moved into place; otherwise the copy failed or was cancelled.
            partial.delete()
        }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw FileTooLarge()
            try {
                output.write(buffer, 0, read)
            } catch (failure: IOException) {
                throw CopyFailed(failure)
            }
        }
    }

    fun delete(path: String?) {
        if (path == null) return
        val file = File(path)
        if (file.exists() && !file.delete()) throw IOException("Couldn't remove the cached upload.")
    }

    /** Removes [id]'s copy, complete or not, for an upload that was never recorded. */
    fun discard(id: String) {
        delete(partialFile(id).path)
        delete(file(id).path)
    }

    /**
     * Removes every file whose absolute path isn't in [referenced]: copies cut short by the
     * app being killed, and copies whose upload is gone. Only safe while no copy is being written.
     * Best effort: a file that can't be removed now is tried again next time.
     */
    fun removeUnreferenced(referenced: Set<String>) {
        dir.listFiles()?.forEach { file -> if (file.absolutePath !in referenced) file.delete() }
    }

    fun clear() {
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Couldn't remove cached uploads.")
    }

    private fun file(id: String) = File(dir, "$id.pdf")

    private fun partialFile(id: String) = File(dir, "$id.pdf.partial")

    class FileTooLarge : Exception("The PDF is larger than 50 MB.")

    /** Writing the copy failed, almost always because the phone is out of space. */
    class CopyFailed(cause: IOException) : IOException("Couldn't save a copy of the PDF.", cause)

    companion object {
        /** The server's limit (MAX_UPLOAD_MB). */
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
