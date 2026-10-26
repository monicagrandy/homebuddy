package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.apiFor
import chat.homebuddy.core.data.documentsJson
import chat.homebuddy.core.data.job
import chat.homebuddy.core.data.json
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException

class RetrofitDocumentsRemoteDataSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var remote: RetrofitDocumentsRemoteDataSource

    @Before
    fun setUp() {
        server.start()
        remote = RetrofitDocumentsRemoteDataSource(apiFor(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun submitLink() = remote.submit("upload-1", 7, "Bosch manual", "manual", file = null, url = "https://example.com/manual.pdf")

    @Test
    fun `a home's documents are listed`() = runTest {
        server.enqueue(json(documentsJson("bosch-manual" to "ready", "ge-washer" to "indexing")))

        val documents = remote.documents(7)

        assertEquals(listOf("bosch-manual", "ge-washer"), documents.map { it.entryId })
        assertEquals("indexing", documents[1].status)
        assertEquals("/documents?household_id=7", server.takeRequest().path)
    }

    @Test
    fun `deleting a document`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        remote.delete(7, "bosch-manual")

        val request = server.takeRequest()
        assertEquals("DELETE /documents/bosch-manual?household_id=7", "${request.method} ${request.path}")
    }

    @Test
    fun `deleting something already deleted on another device counts as done`() = runTest {
        server.enqueue(json("""{"detail": "Document not found."}""", code = 404))

        remote.delete(7, "bosch-manual")
    }

    @Test
    fun `any other failed delete throws`() = runTest {
        server.enqueue(json("""{"detail": "Something went wrong."}""", code = 500))

        try {
            remote.delete(7, "bosch-manual")
            fail("expected an HttpException")
        } catch (error: HttpException) {
            assertEquals(500, error.code())
        }
    }

    @Test
    fun `a file is sent with its details and the idempotency key`() = runTest {
        val file = folder.newFile("upload-1.pdf").apply { writeText("%PDF-1.4 manual") }
        server.enqueue(json(job("queued"), code = 202))

        val submission = remote.submit("upload-1", 7, "Bosch manual", "manual", file = file, url = null)

        assertEquals("job-1", (submission as JobSubmission.Accepted).job.jobId)
        val request = server.takeRequest()
        assertEquals("POST /documents/jobs", "${request.method} ${request.path}")
        assertEquals("upload-1", request.getHeader("Idempotency-Key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"household_id\"") && body.contains("Bosch manual") && body.contains("name=\"doc_type\""))
        assertTrue(body.contains("filename=\"upload-1.pdf\"") && body.contains("%PDF-1.4 manual"))
    }

    @Test
    fun `a link is sent without a file`() = runTest {
        server.enqueue(json(job("queued"), code = 202))

        submitLink()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("https://example.com/manual.pdf"))
        assertFalse(body.contains("filename="))
    }

    @Test
    fun `a duplicate is accepted with the document it duplicates`() = runTest {
        server.enqueue(json(job("duplicate", jobId = null, duplicateOf = "web-manual")))

        val job = (submitLink() as JobSubmission.Accepted).job

        assertEquals("duplicate", job.status)
        assertEquals("web-manual", job.duplicateOf)
    }

    @Test
    fun `a busy or failing server means try again later`() = runTest {
        server.enqueue(json("""{"detail": "Too many requests."}""", code = 429))
        server.enqueue(json("""{"detail": "Unavailable."}""", code = 503))

        assertEquals(JobSubmission.TryLater, submitLink())
        assertEquals(JobSubmission.TryLater, submitLink())
    }

    @Test
    fun `a refused upload comes back with the server's reason`() = runTest {
        server.enqueue(json("""{"detail": {"code": "not_pdf", "message": "The uploaded file is not a PDF."}}""", code = 422))

        assertEquals(JobSubmission.Rejected("not_pdf", "The uploaded file is not a PDF."), submitLink())
    }

    @Test
    fun `a job's progress`() = runTest {
        server.enqueue(json(job("running")))

        assertEquals("running", remote.job("job-1")?.status)
        assertEquals("/documents/jobs/job-1", server.takeRequest().path)
    }

    @Test
    fun `a job that's gone comes back as null`() = runTest {
        server.enqueue(json("""{"detail": "Job not found."}""", code = 404))

        assertNull(remote.job("job-1"))
    }

    @Test
    fun `any other job failure throws`() = runTest {
        server.enqueue(json("""{"detail": "Something went wrong."}""", code = 500))

        try {
            remote.job("job-1")
            fail("expected an HttpException")
        } catch (error: HttpException) {
            assertEquals(500, error.code())
        }
    }
}
