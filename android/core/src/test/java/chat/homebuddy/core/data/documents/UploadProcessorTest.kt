package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.data.upload
import chat.homebuddy.core.testing.httpError
import java.io.IOException
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

class UploadProcessorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val remote = TestDocumentsRemoteDataSource()
    private val local = TestDocumentsLocalDataSource()
    private lateinit var files: UploadFiles
    private lateinit var processor: UploadProcessor

    @Before
    fun setUp() {
        files = UploadFiles(folder.newFolder("uploads"))
        processor = UploadProcessor(remote, local, files, pollIntervalMillis = 0, maxPolls = 3, wait = {})
    }

    private suspend fun queuedFile(id: String = "upload-1") =
        files.save(id, "%PDF-1.4 manual".byteInputStream()).also { local.saveUpload(upload(id, file = it)) }

    private suspend fun indexing() = local.saveUpload(upload(state = UploadState.INDEXING, jobId = "job-1"))

    private suspend fun stored(id: String = "upload-1") = local.upload(id)

    @Test
    fun `an upload is sent once, polled until indexed, then becomes a document`() = runTest {
        val copy = queuedFile()
        remote.jobs += listOf(jobDto("running"), jobDto("succeeded"))
        remote.documents[7] = listOf(documentDto("bosch-manual"))

        val outcome = processor.process("upload-1")

        assertEquals(UploadOutcome.FINISHED, outcome)
        assertEquals(
            listOf(TestDocumentsRemoteDataSource.Submitted("upload-1", 7, "Bosch manual", "%PDF-1.4 manual", url = null)),
            remote.submitted,
        )
        assertNull(stored())
        assertFalse("the local copy is deleted once the server has it", copy.exists())
        assertEquals(listOf("bosch-manual"), local.documents(7).first().map { it.entryId })
    }

    @Test
    fun `the same file from another device is reported as a duplicate`() = runTest {
        val copy = queuedFile()
        remote.submission = JobSubmission.Accepted(jobDto("duplicate", jobId = null, duplicateOf = "web-manual"))

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals(UploadState.DUPLICATE, stored()?.state)
        assertEquals("web-manual", stored()?.entryId)
        assertFalse(copy.exists())
    }

    @Test
    fun `a file the server rejects fails without retrying`() = runTest {
        queuedFile()
        remote.submission = JobSubmission.Rejected("not_pdf", "The uploaded file is not a PDF.")

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals(UploadState.FAILED, stored()?.state)
        assertEquals("not_pdf", stored()?.errorCode)
        assertEquals("The uploaded file is not a PDF.", stored()?.errorMessage)
    }

    @Test
    fun `a busy server means try again later, keeping the file`() = runTest {
        val copy = queuedFile()
        remote.submission = JobSubmission.TryLater

        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))

        assertEquals(UploadState.QUEUED, stored()?.state)
        assertTrue(copy.exists())
    }

    @Test
    fun `being offline means try again later`() = runTest {
        queuedFile()
        remote.offline = true

        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))
        assertEquals(UploadState.QUEUED, stored()?.state)
    }

    @Test
    fun `an interrupted upload resumes by polling its job, without sending again`() = runTest {
        indexing()
        remote.jobs += jobDto("succeeded")

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertTrue(remote.submitted.isEmpty())
        assertNull(stored())
    }

    @Test
    fun `a finished upload stays finished when the documents list can't be refreshed`() = runTest {
        listOf(IOException("No connection"), httpError(503)).forEach { failure ->
            indexing()
            remote.jobs += jobDto("succeeded")
            remote.listFailure = failure

            assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

            assertNull(stored())
        }
    }

    @Test
    fun `a link that turns out to be a web page fails with not_pdf`() = runTest {
        local.saveUpload(upload(url = "https://example.com/support"))
        remote.jobs += jobDto("failed", errorCode = "not_pdf", error = "The URL did not return a PDF.")

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals("https://example.com/support", remote.submitted.single().url)
        assertEquals(UploadState.FAILED, stored()?.state)
        assertEquals("not_pdf", stored()?.errorCode)
    }

    @Test
    fun `a job that disappeared means the document was deleted elsewhere`() = runTest {
        indexing()
        remote.jobs += null

        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))

        assertEquals("deleted", stored()?.errorCode)
    }

    @Test
    fun `a long index keeps the upload and checks again later`() = runTest {
        indexing()
        repeat(3) { remote.jobs += jobDto("running") }

        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))

        assertEquals(UploadState.INDEXING, stored()?.state)
        assertEquals(3, remote.polls)
    }

    @Test
    fun `a dismissed upload does nothing`() = runTest {
        assertEquals(UploadOutcome.FINISHED, processor.process("never-queued"))

        assertTrue(remote.submitted.isEmpty())
        assertEquals(0, remote.polls)
    }

    @Test
    fun `giving up after the last attempt shows a retryable failure`() = runTest {
        queuedFile()

        processor.giveUp("upload-1")

        assertEquals(UploadState.FAILED, stored()?.state)
        assertEquals("network", stored()?.errorCode)
    }

    @Test
    fun `failed cleanup preserves the completed result and retries without contacting the job`() = runTest {
        // A nonempty directory makes deletion fail without relying on host filesystem permissions.
        val blocked = folder.newFolder("blocked-input")
        val child = java.io.File(blocked, "open-data").apply { writeText("data") }
        local.saveUpload(upload(file = blocked, state = UploadState.COMPLETED, jobId = "job-1"))

        assertEquals(UploadOutcome.RETRY, processor.process("upload-1"))
        assertEquals(UploadState.COMPLETED, stored()?.state)
        assertEquals(blocked.absolutePath, stored()?.filePath)
        assertTrue(remote.submitted.isEmpty())
        assertEquals(0, remote.polls)

        child.delete()
        assertEquals(UploadOutcome.FINISHED, processor.process("upload-1"))
        assertNull(stored())
        assertFalse(blocked.exists())
        assertEquals(0, remote.polls)
    }
}
