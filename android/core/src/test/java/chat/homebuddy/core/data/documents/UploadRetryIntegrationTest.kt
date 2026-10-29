package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.FakeScheduler
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.data.apiFor
import chat.homebuddy.core.data.job
import chat.homebuddy.core.data.json
import chat.homebuddy.core.data.upload
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real repository, processor and Retrofit requests: retry must get past the failed server job. */
class UploadRetryIntegrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()
    private val local = TestDocumentsLocalDataSource()
    private val scheduler = FakeScheduler()
    private lateinit var files: UploadFiles
    private lateinit var remote: DocumentsRemoteDataSource
    private lateinit var repository: CachedDocumentsRepository
    private lateinit var processor: UploadProcessor

    @Before
    fun setUp() {
        server.start()
        files = UploadFiles(folder.newFolder("uploads"))
        remote = RetrofitDocumentsRemoteDataSource(apiFor(server))
        var attempt = 0
        repository = CachedDocumentsRepository(remote, local, TestPickedFileDataSource(), files, scheduler, newId = { "retry-${++attempt}" })
        processor = processor()
    }

    private fun processor() = UploadProcessor(remote, local, files, maxPolls = 1, wait = {})

    @After fun tearDown() = server.shutdown()

    private suspend fun queuedFile(): File = files.save("upload-1", "%PDF-1.4 manual".byteInputStream())
        .also { local.saveUpload(upload(file = it)) }

    private fun succeeded(jobId: String = "job-2") {
        server.enqueue(json(job("succeeded", jobId = jobId)))
        server.enqueue(json("[]")) // refreshed documents
    }

    @Test
    fun `terminal indexing failure retries the retained PDF with a fresh durable key`() = runTest {
        val copy = queuedFile()
        server.enqueue(json(job("queued"), 202))
        server.enqueue(json(job("failed", errorCode = "index_failed", error = "Try later")))
        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))
        assertTrue(copy.exists())
        assertEquals(UploadRetry.NEW_ATTEMPT, local.upload("upload-1")!!.retryAction)

        repository.retry("upload-1")

        val retried = local.upload("upload-1")!!
        assertEquals("retry-1", retried.attemptId)
        assertNull(retried.jobId)
        assertNull(retried.entryId)
        server.enqueue(json("{}", 503))
        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))
        assertTrue(copy.exists())
        server.enqueue(json(job("queued", jobId = "job-2"), 202))
        succeeded()
        // A replacement worker reloads the durable attempt rather than generating another key.
        assertEquals(UploadOutcome.FINISHED, processor().process("upload-1"))

        val requests = List(6) { server.takeRequest() }
        val submissions = requests.filter { it.method == "POST" }
        assertEquals(listOf("upload-1", "retry-1", "retry-1"), submissions.map { it.getHeader("Idempotency-Key") })
        assertTrue(submissions.all { it.body.readUtf8().contains("%PDF-1.4 manual") })
        assertEquals(listOf("/documents/jobs/job-1", "/documents/jobs/job-2"), requests.filter { it.path!!.startsWith("/documents/jobs/") }.map { it.path })
        assertNull(local.upload("upload-1"))
        assertFalse(copy.exists())
    }

    @Test
    fun `terminal link failure creates a new job using the original URL`() = runTest {
        val url = "https://example.com/manual.pdf"
        local.saveUpload(upload(url = url))
        server.enqueue(json(job("queued"), 202))
        server.enqueue(json(job("failed", errorCode = "download_failed")))
        processor.process("upload-1")

        repository.retry("upload-1")
        server.enqueue(json(job("queued", jobId = "job-2"), 202))
        succeeded()
        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        val submissions = List(5) { server.takeRequest() }.filter { it.method == "POST" }
        assertEquals(listOf("upload-1", "retry-1"), submissions.map { it.getHeader("Idempotency-Key") })
        assertTrue(submissions.all { it.body.readUtf8().contains(url) })
        assertNull(local.upload("upload-1"))
    }

    @Test
    fun `exhausted submission retries keep the same idempotency key and input`() = runTest {
        val copy = queuedFile()
        server.enqueue(json("{}", 503))
        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))
        processor.giveUp("upload-1")

        repository.retry("upload-1")
        server.enqueue(json(job("queued"), 202))
        succeeded("job-1")
        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        val submissions = List(4) { server.takeRequest() }.filter { it.method == "POST" }
        assertEquals(listOf("upload-1", "upload-1"), submissions.map { it.getHeader("Idempotency-Key") })
        assertFalse(copy.exists())
    }

    @Test
    fun `exhausted polling resumes the accepted job without another submission`() = runTest {
        val copy = queuedFile()
        server.enqueue(json(job("queued"), 202))
        server.enqueue(json("{}", 503))
        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))
        processor.giveUp("upload-1")

        repository.retry("upload-1")
        assertEquals("job-1", local.upload("upload-1")!!.jobId)
        succeeded("job-1")
        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        val requests = List(4) { server.takeRequest() }
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(2, requests.count { it.path == "/documents/jobs/job-1" })
        assertFalse(copy.exists())
    }

    @Test
    fun `permanent rejection does not retry even if stale work runs again`() = runTest {
        val copy = queuedFile()
        server.enqueue(json(job("queued"), 202))
        server.enqueue(json(job("failed", errorCode = "no_text", error = "No readable text")))
        processor.process("upload-1")

        repository.retry("upload-1")
        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals(UploadRetry.NONE, local.upload("upload-1")!!.retryAction)
        assertEquals("no_text", local.upload("upload-1")!!.errorCode)
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(2, server.requestCount)
        assertFalse(copy.exists())
    }

    @Test
    fun `a legacy terminal job without input cannot be retried`() = runTest {
        local.saveUpload(upload(state = UploadState.FAILED, jobId = "old-job").copy(errorCode = "index_failed"))
        repository.retry("upload-1")
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(UploadRetry.NONE, local.upload("upload-1")!!.retryAction)
    }

    @Test
    fun `a missing PDF asks the user to add it again instead of scheduling invalid work`() = runTest {
        val copy = queuedFile()
        local.saveUpload(local.upload("upload-1")!!.copy(state = UploadState.FAILED, errorCode = "index_failed", jobId = "job-1"))
        copy.delete()
        repository.retry("upload-1")
        assertEquals("input_missing", local.upload("upload-1")!!.errorCode)
        assertEquals(UploadRetry.NONE, local.upload("upload-1")!!.retryAction)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `repeated retry taps create only one new attempt`() = runTest {
        local.saveUpload(upload(url = "https://example.com/manual.pdf", state = UploadState.FAILED, jobId = "job-1").copy(errorCode = "index_failed"))
        repository.retry("upload-1")
        repository.retry("upload-1")
        assertEquals("retry-1", local.upload("upload-1")!!.attemptId)
        assertEquals(listOf("upload-1"), scheduler.scheduled)
    }
}
