package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.FakeScheduler
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.data.upload
import chat.homebuddy.core.testing.httpError
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException

class CachedDocumentsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val remote = TestDocumentsRemoteDataSource()
    private val local = TestDocumentsLocalDataSource()
    private val scheduler = FakeScheduler()
    private lateinit var uploadsDir: File
    private lateinit var repository: CachedDocumentsRepository

    @Before
    fun setUp() {
        uploadsDir = folder.newFolder("uploads")
        repository = CachedDocumentsRepository(remote, local, UploadFiles(uploadsDir), scheduler, clock = { 42L }, newId = { "upload-1" })
    }

    private suspend fun cached() = repository.documents(7).first().map { it.entryId }

    private suspend fun cache(vararg entryIds: String) =
        local.replaceDocuments(7, entryIds.map { DocumentEntity(7, it, it, "manual", "ready", null, null) })

    @Test
    fun `a refresh replaces the cache, so documents deleted elsewhere disappear`() = runTest {
        cache("deleted-on-the-web")
        remote.documents[7] = listOf(documentDto("bosch-manual"), documentDto("ge-washer", status = "indexing"))

        repository.refresh(7)

        assertEquals(listOf("bosch-manual", "ge-washer"), cached())
        assertEquals("indexing", repository.documents(7).first()[1].status)
    }

    @Test
    fun `deleting removes the document on the server and here`() = runTest {
        cache("bosch-manual")

        repository.delete(7, "bosch-manual")

        assertEquals(listOf("bosch-manual"), remote.deleted)
        assertTrue(cached().isEmpty())
    }

    @Test
    fun `a failed delete keeps the document`() = runTest {
        cache("bosch-manual")
        remote.deleteFailure = httpError(500)

        val error = runCatching { repository.delete(7, "bosch-manual") }.exceptionOrNull()

        assertTrue(error is HttpException)
        assertEquals(listOf("bosch-manual"), cached())
    }

    @Test
    fun `adding a file copies it, records it, and schedules the upload`() = runTest {
        val id = repository.addFile(7, "Bosch manual", "%PDF-1.4".byteInputStream())

        val upload = repository.uploads(7).first().single()
        assertEquals(UploadState.QUEUED, upload.state)
        assertEquals(42L, upload.createdAt)
        assertEquals("%PDF-1.4", File(upload.filePath!!).readText())
        assertEquals(listOf(id), scheduler.scheduled)
    }

    @Test
    fun `files over the server's limit are refused before copying`() = runTest {
        val files = UploadFiles(uploadsDir)

        val error = runCatching { files.save("big", ByteArray(11).inputStream(), maxBytes = 10) }.exceptionOrNull()

        assertTrue(error is UploadFiles.FileTooLarge)
        assertFalse(File(uploadsDir, "big.pdf").exists())
    }

    @Test
    fun `adding a link records the URL`() = runTest {
        repository.addLink(7, "Bosch manual", "https://example.com/manual.pdf")

        assertEquals("https://example.com/manual.pdf", repository.uploads(7).first().single().url)
        assertEquals(listOf("upload-1"), scheduler.scheduled)
    }

    @Test
    fun `retrying clears the error and schedules again`() = runTest {
        local.saveUpload(upload().copy(state = UploadState.FAILED, errorCode = "network", errorMessage = "Offline"))

        repository.retry("upload-1")

        val upload = local.upload("upload-1")!!
        assertEquals(UploadState.QUEUED, upload.state)
        assertNull(upload.errorCode)
        assertEquals(listOf("upload-1"), scheduler.scheduled)
    }

    @Test
    fun `dismissing cancels the work and deletes the copy`() = runTest {
        repository.addFile(7, "Bosch manual", "%PDF-1.4".byteInputStream())
        val copy = File(local.upload("upload-1")!!.filePath!!)

        repository.dismiss("upload-1")

        assertNull(local.upload("upload-1"))
        assertFalse(copy.exists())
        assertEquals(listOf("upload-1"), scheduler.cancelled)
    }

    @Test
    fun `wiping removes everything this account left on the phone`() = runTest {
        cache("bosch-manual")
        repository.addFile(7, "Bosch manual", "%PDF-1.4".byteInputStream())

        repository.wipe()

        assertTrue(cached().isEmpty())
        assertTrue(repository.uploads(7).first().isEmpty())
        assertFalse(uploadsDir.exists())
        assertTrue(scheduler.cancelledAll)
    }
}
