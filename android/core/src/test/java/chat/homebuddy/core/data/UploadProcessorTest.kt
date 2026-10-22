package chat.homebuddy.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadProcessorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var database: HomeBuddyDatabase
    private lateinit var files: UploadFiles
    private lateinit var processor: UploadProcessor

    @Before
    fun setUp() {
        server.start()
        database = inMemoryDatabase()
        files = UploadFiles(folder.newFolder("uploads"))
        processor = UploadProcessor(apiFor(server), database.uploads(), database.documents(), files, pollIntervalMillis = 0, maxPolls = 3, wait = {})
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private suspend fun queuedFile(id: String = "upload-1") =
        files.save(id, "%PDF-1.4 manual".byteInputStream()).also { database.uploads().upsert(upload(id, file = it)) }

    private suspend fun stored(id: String = "upload-1") = database.uploads().get(id)

    @Test
    fun `an upload is sent once, polled until indexed, then becomes a document`() = runTest {
        val copy = queuedFile()
        server.enqueue(json(job("queued"), code = 202))
        server.enqueue(json(job("running")))
        server.enqueue(json(job("succeeded")))
        server.enqueue(json(documentsJson("bosch-manual" to "ready")))

        val outcome = processor.process("upload-1")

        assertEquals(UploadOutcome.FINISHED, outcome)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/documents/jobs", post.path)
        assertEquals("upload-1", post.getHeader("Idempotency-Key"))
        val body = post.body.readUtf8()
        assertTrue(body.contains("name=\"household_id\"") && body.contains("Bosch manual") && body.contains("%PDF-1.4 manual"))
        assertEquals("/documents/jobs/job-1", server.takeRequest().path)
        assertNull(stored())
        assertFalse("the local copy is deleted once the server has it", copy.exists())
        assertEquals(listOf("bosch-manual"), database.documents().observe(7).first().map { it.entryId })
    }

    @Test
    fun `the same file from another device is reported as a duplicate`() = runTest {
        val copy = queuedFile()
        server.enqueue(json(job("duplicate", jobId = null, duplicateOf = "web-manual")))

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals(UploadState.DUPLICATE, stored()?.state)
        assertEquals("web-manual", stored()?.entryId)
        assertFalse(copy.exists())
    }

    @Test
    fun `a file the server rejects fails without retrying`() = runTest {
        queuedFile()
        server.enqueue(json("""{"detail": {"code": "not_pdf", "message": "The uploaded file is not a PDF."}}""", code = 422))

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals(UploadState.FAILED, stored()?.state)
        assertEquals("not_pdf", stored()?.errorCode)
        assertEquals("The uploaded file is not a PDF.", stored()?.errorMessage)
    }

    @Test
    fun `a busy server means try again later, keeping the file`() = runTest {
        val copy = queuedFile()
        server.enqueue(json("""{"detail": "Too many requests."}""", code = 429))

        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))

        assertEquals(UploadState.QUEUED, stored()?.state)
        assertTrue(copy.exists())
    }

    @Test
    fun `being offline means try again later`() = runTest {
        queuedFile()
        server.shutdown()

        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))
        assertEquals(UploadState.QUEUED, stored()?.state)
    }

    @Test
    fun `an interrupted upload resumes by polling its job, without sending again`() = runTest {
        database.uploads().upsert(upload(state = UploadState.INDEXING, jobId = "job-1"))
        server.enqueue(json(job("succeeded")))
        server.enqueue(json(documentsJson("bosch-manual" to "ready")))

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals("GET", server.takeRequest().method)
        assertNull(stored())
    }

    @Test
    fun `a link that turns out to be a web page fails with not_pdf`() = runTest {
        database.uploads().upsert(upload(url = "https://example.com/support"))
        server.enqueue(json(job("queued"), code = 202))
        server.enqueue(json(job("failed", errorCode = "not_pdf", error = "The URL did not return a PDF.")))

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertTrue(server.takeRequest().body.readUtf8().contains("https://example.com/support"))
        assertEquals(UploadState.FAILED, stored()?.state)
        assertEquals("not_pdf", stored()?.errorCode)
    }

    @Test
    fun `a job that disappeared means the document was deleted elsewhere`() = runTest {
        database.uploads().upsert(upload(state = UploadState.INDEXING, jobId = "job-1"))
        server.enqueue(json("""{"detail": "Job not found."}""", code = 404))

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals("deleted", stored()?.errorCode)
    }

    @Test
    fun `a long index keeps the upload and checks again later`() = runTest {
        database.uploads().upsert(upload(state = UploadState.INDEXING, jobId = "job-1"))
        repeat(3) { server.enqueue(json(job("running"))) }

        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))

        assertEquals(UploadState.INDEXING, stored()?.state)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a dismissed upload does nothing`() = runTest {
        assertEquals(UploadOutcome.FINISHED, processor.process("never-queued"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `giving up after the last attempt shows a retryable failure`() = runTest {
        queuedFile()

        processor.giveUp("upload-1")

        assertEquals(UploadState.FAILED, stored()?.state)
        assertEquals("network", stored()?.errorCode)
    }
}
