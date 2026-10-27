package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.FakeScheduler
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.data.upload
import chat.homebuddy.core.testing.httpError
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
    private val picked = TestPickedFileDataSource().apply { contents[MANUAL] = "%PDF-1.4" }
    private val scheduler = FakeScheduler()
    private lateinit var uploadsDir: File
    private lateinit var repository: CachedDocumentsRepository

    @Before
    fun setUp() {
        uploadsDir = folder.newFolder("uploads")
        repository = CachedDocumentsRepository(remote, local, picked, UploadFiles(uploadsDir), scheduler, clock = { 42L }, newId = { "upload-1" })
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
        val id = repository.addFile(7, "Bosch manual", MANUAL)

        val upload = repository.uploads(7).first().single()
        assertEquals(UploadState.QUEUED, upload.state)
        assertEquals(42L, upload.createdAt)
        assertEquals("%PDF-1.4", File(upload.filePath!!).readText())
        assertEquals(listOf(id), scheduler.scheduled)
    }

    @Test
    fun `a file that can't be read isn't added`() = runTest {
        val error = runCatching { repository.addFile(7, "Bosch manual", "content://gone/1") }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(repository.uploads(7).first().isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `a picked file's name comes from the app that shared it`() = runTest {
        picked.names[MANUAL] = "Bosch_SHX3AR75UC.pdf"

        assertEquals("Bosch_SHX3AR75UC.pdf", repository.fileName(MANUAL))
        assertNull(repository.fileName("content://unnamed/1"))
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
        repository.addFile(7, "Bosch manual", MANUAL)
        val copy = File(local.upload("upload-1")!!.filePath!!)

        repository.dismiss("upload-1")

        assertNull(local.upload("upload-1"))
        assertFalse(copy.exists())
        assertEquals(listOf("upload-1"), scheduler.cancelled)
    }

    @Test
    fun `wiping removes everything this account left on the phone`() = runTest {
        cache("bosch-manual")
        repository.addFile(7, "Bosch manual", MANUAL)

        repository.wipe()

        assertTrue(cached().isEmpty())
        assertTrue(repository.uploads(7).first().isEmpty())
        assertFalse(uploadsDir.exists())
        assertTrue(scheduler.cancelledAll)
    }

    @Test
    fun `wiping deletes the copies on the IO dispatcher, not on the caller's thread`() = runTest {
        val onIo = CachedDocumentsRepository(
            remote, local, picked, UploadFiles(uploadsDir), scheduler,
            io = StandardTestDispatcher(testScheduler), clock = { 42L }, newId = { "upload-1" },
        )
        onIo.addFile(7, "Bosch manual", MANUAL)

        // The caller runs wipe at once, as the main thread would, up to where it hands the files to IO.
        launch(UnconfinedTestDispatcher(testScheduler)) { onIo.wipe() }
        assertTrue("the copies are still there until IO runs", uploadsDir.exists())

        runCurrent()
        assertFalse(uploadsDir.exists())
    }

    private companion object {
        const val MANUAL = "content://downloads/bosch-manual"
    }
}
