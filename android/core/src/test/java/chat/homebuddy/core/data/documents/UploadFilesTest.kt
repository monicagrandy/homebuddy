package chat.homebuddy.core.data.documents

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun File.names() = list().orEmpty().sorted()

    /** 64 KB chunks of a PDF, then [failure] instead of the [failAfter]th chunk. */
    private fun pdf(chunks: Int, failAfter: Int? = null, failure: IOException = IOException("Read failed"), onRead: (Int) -> Unit = {}) =
        object : InputStream() {
            var reads = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                onRead(++reads)
                if (reads == failAfter) throw failure
                if (reads > chunks) return -1
                buffer.fill('%'.code.toByte(), offset, offset + length)
                return length
            }
        }

    @Test
    fun `a complete copy is moved into place`() = runTest {
        val dir = folder.newFolder("uploads")

        val copy = UploadFiles(dir).save("upload-1", ByteArrayInputStream("%PDF-1.4 manual".toByteArray()))

        assertEquals(File(dir, "upload-1.pdf"), copy)
        assertEquals("%PDF-1.4 manual", copy.readText())
        assertEquals(listOf("upload-1.pdf"), dir.names())
    }

    @Test
    fun `a read that fails partway leaves nothing behind`() = runTest {
        val dir = folder.newFolder("uploads")

        val error = runCatching { UploadFiles(dir).save("upload-1", pdf(chunks = 4, failAfter = 3)) }.exceptionOrNull()

        assertEquals("Read failed", error?.message)
        assertTrue(dir.names().isEmpty())
    }

    @Test
    fun `running out of space partway leaves nothing behind and says so`() = runTest {
        val dir = folder.newFolder("uploads")
        // Room for one chunk, as a nearly full phone would have.
        val nearlyFull: (File) -> OutputStream = { file ->
            object : FileOutputStream(file) {
                var written = 0
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (written + length > 64 * 1024) throw IOException("write failed: ENOSPC (No space left on device)")
                    super.write(bytes, offset, length)
                    written += length
                }
            }
        }

        val error = runCatching { UploadFiles(dir, nearlyFull).save("upload-1", pdf(chunks = 4)) }.exceptionOrNull()

        assertTrue(error is UploadFiles.CopyFailed)
        assertTrue(dir.names().isEmpty())
    }

    @Test
    fun `cancelling partway stops copying and leaves nothing behind`() = runTest {
        val dir = folder.newFolder("uploads")
        lateinit var copying: Deferred<File>
        var reads = 0
        val source = pdf(chunks = 100) { read ->
            reads = read
            if (read == 2) copying.cancel()
        }

        copying = async { UploadFiles(dir).save("upload-1", source) }
        val error = runCatching { copying.await() }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(2, reads)
        assertTrue(dir.names().isEmpty())
    }

    @Test
    fun `copies no upload refers to are removed, and referenced ones kept`() = runTest {
        val dir = folder.newFolder("uploads")
        val files = UploadFiles(dir)
        val kept = files.save("queued", "%PDF-1.4 kept".byteInputStream())
        files.save("unrecorded", "%PDF-1.4 orphan".byteInputStream())
        File(dir, "killed.pdf.partial").writeText("%PDF-1.4 half")

        files.removeUnreferenced(setOf(kept.absolutePath))
        files.removeUnreferenced(setOf(kept.absolutePath))

        assertEquals(listOf("queued.pdf"), dir.names())
        UploadFiles(folder.root.resolve("never-created")).removeUnreferenced(emptySet())
    }

    @Test
    fun `discarding removes a complete or partial copy`() = runTest {
        val dir = folder.newFolder("uploads")
        val files = UploadFiles(dir)
        files.save("done", "%PDF-1.4 manual".byteInputStream())
        File(dir, "cut-short.pdf.partial").writeText("%PDF-1.4 half")

        files.discard("done")
        files.discard("cut-short")
        files.discard("never-saved")

        assertTrue(dir.names().isEmpty())
    }

    @Test
    fun `clearing private files is safe to repeat`() {
        val dir = folder.newFolder("uploads")
        File(dir, "old.pdf").writeText("private bytes")
        val files = UploadFiles(dir)

        files.clear()
        files.clear()

        assertFalse(dir.exists())
    }

    @Test
    fun `failed file deletion is reported so session cleanup can retry`() {
        val dir = folder.newFolder("uploads")
        val undeletable = object : File(dir.absolutePath) {
            override fun delete(): Boolean = false
        }

        assertTrue(runCatching { UploadFiles(undeletable).clear() }.exceptionOrNull() is IOException)
        assertTrue(dir.exists())

        UploadFiles(dir).clear()
        assertFalse(dir.exists())
    }
}
