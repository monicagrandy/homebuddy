package chat.homebuddy.core.data

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val scheduler = FakeScheduler()
    private lateinit var database: HomeBuddyDatabase
    private lateinit var uploadsDir: File
    private lateinit var repository: DocumentsRepository

    @Before
    fun setUp() {
        server.start()
        database = inMemoryDatabase()
        uploadsDir = folder.newFolder("uploads")
        repository = CachedDocumentsRepository(
            apiFor(server),
            database.documents(),
            database.uploads(),
            UploadFiles(uploadsDir),
            scheduler,
            clock = { 42L },
            newId = { "upload-1" },
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private suspend fun cached() = repository.documents(7).first().map { it.entryId }

    private suspend fun cache(vararg entryIds: String) =
        database.documents().upsertAll(entryIds.map { DocumentEntity(7, it, it, "manual", "ready", null, null) })

    @Test
    fun `a refresh replaces the cache, so documents deleted elsewhere disappear`() = runTest {
        cache("deleted-on-the-web")
        server.enqueue(json(documentsJson("bosch-manual" to "ready", "ge-washer" to "indexing")))

        repository.refresh(7)

        assertEquals(listOf("bosch-manual", "ge-washer"), cached())
        assertEquals("/documents?household_id=7", server.takeRequest().path)
    }

    @Test
    fun `deleting removes the document`() = runTest {
        cache("bosch-manual")
        server.enqueue(MockResponse().setResponseCode(204))

        repository.delete(7, "bosch-manual")

        assertTrue(cached().isEmpty())
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `deleting something already deleted on another device still removes it here`() = runTest {
        cache("bosch-manual")
        server.enqueue(json("""{"detail": "Document not found."}""", code = 404))

        repository.delete(7, "bosch-manual")

        assertTrue(cached().isEmpty())
    }

    @Test
    fun `a failed delete keeps the document`() = runTest {
        cache("bosch-manual")
        server.enqueue(json("""{"detail": "Something went wrong."}""", code = 500))

        val error = runCatching { repository.delete(7, "bosch-manual") }.exceptionOrNull()

        assertTrue(error is HttpException)
        assertEquals(listOf("bosch-manual"), cached())
    }

    @Test
    fun `adding a file copies it, records it, and schedules the upload`() = runTest {
        val id = repository.addFile(7, "Bosch manual", "%PDF-1.4".byteInputStream())

        val upload = repository.uploads(7).first().single()
        assertEquals(UploadState.QUEUED, upload.state)
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
    }

    @Test
    fun `retrying clears the error and schedules again`() = runTest {
        database.uploads().upsert(upload().copy(state = UploadState.FAILED, errorCode = "network", errorMessage = "Offline"))

        repository.retry("upload-1")

        val upload = database.uploads().get("upload-1")!!
        assertEquals(UploadState.QUEUED, upload.state)
        assertNull(upload.errorCode)
        assertEquals(listOf("upload-1"), scheduler.scheduled)
    }

    @Test
    fun `dismissing cancels the work and deletes the copy`() = runTest {
        repository.addFile(7, "Bosch manual", "%PDF-1.4".byteInputStream())
        val copy = File(database.uploads().get("upload-1")!!.filePath!!)

        repository.dismiss("upload-1")

        assertNull(database.uploads().get("upload-1"))
        assertFalse(copy.exists())
        assertEquals(listOf("upload-1"), scheduler.cancelled)
    }

    @Test
    fun `wiping removes everything this account left on the phone`() = runTest {
        cache("bosch-manual")
        repository.addFile(7, "Bosch manual", "%PDF-1.4".byteInputStream())

        repository.wipe()

        assertTrue(cached().isEmpty())
        assertTrue(database.uploads().all().isEmpty())
        assertFalse(uploadsDir.exists())
        assertTrue(scheduler.cancelledAll)
    }
}
