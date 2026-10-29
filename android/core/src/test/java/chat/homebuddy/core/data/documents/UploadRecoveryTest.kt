package chat.homebuddy.core.data.documents

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import chat.homebuddy.core.data.FakeScheduler
import chat.homebuddy.core.data.HomeBuddyDatabase
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.data.upload
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Crash boundaries are tested against Room on disk, closing and reopening it before recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var database: HomeBuddyDatabase
    private lateinit var local: DocumentsLocalDataSource
    private lateinit var files: UploadFiles

    @Before fun setUp() {
        files = UploadFiles(folder.newFolder("uploads"))
        open()
    }

    private fun open() {
        database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), HomeBuddyDatabase::class.java,
            File(folder.root, "recovery.db").absolutePath,
        ).allowMainThreadQueries().build()
        local = RoomDocumentsLocalDataSource(database.documents(), database.uploads())
    }

    private fun restart() { database.close(); open() }
    @After fun tearDown() = database.close()

    private fun repository(remote: DocumentsRemoteDataSource, scheduler: FakeScheduler) = CachedDocumentsRepository(
        remote, local, TestPickedFileDataSource().apply { contents["content://manual"] = "%PDF-1.4 manual" },
        files, scheduler, newId = { "upload-1" },
    )

    private fun processor(remote: DocumentsRemoteDataSource, source: DocumentsLocalDataSource = local) =
        UploadProcessor(remote, source, files, maxPolls = 1, wait = {})

    private class SimulatedDeath : RuntimeException()

    @Test fun `a file row saved before work enqueue is recovered after restart`() = checkEnqueueGap(file = true)
    @Test fun `a link row saved before work enqueue is recovered after restart`() = checkEnqueueGap(file = false)

    private fun checkEnqueueGap(file: Boolean) = runTest {
        val remote = TestDocumentsRemoteDataSource()
        val scheduler = FakeScheduler().apply { whileScheduling = { throw SimulatedDeath() } }
        val failure = runCatching {
            if (file) repository(remote, scheduler).addFile(7, "Manual", "content://manual")
            else repository(remote, scheduler).addLink(7, "Manual", "https://example.com/manual.pdf")
        }.exceptionOrNull()
        assertTrue(failure is SimulatedDeath)
        assertEquals(UploadState.QUEUED, local.upload("upload-1")?.state)
        assertTrue(scheduler.scheduled.isEmpty())

        restart()
        val recovered = FakeScheduler()
        repository(remote, recovered).reconcileUploads()
        assertEquals(listOf("upload-1"), recovered.ensured)
        remote.jobs += jobDto("succeeded")
        assertEquals(UploadOutcome.FINISHED, processor(remote).process("upload-1"))
        assertNull(local.upload("upload-1"))
        if (file) assertEquals("%PDF-1.4 manual", remote.submitted.single().fileContent)
        else assertEquals("https://example.com/manual.pdf", remote.submitted.single().url)
    }

    @Test fun `death before accepted job persistence safely resends the same key and PDF`() = checkAcceptedGap(afterSave = false)
    @Test fun `death after accepted job persistence resumes polling without resubmission`() = checkAcceptedGap(afterSave = true)

    private fun checkAcceptedGap(afterSave: Boolean) = runTest {
        val copy = files.save("upload-1", "%PDF-1.4 manual".byteInputStream())
        local.saveUpload(upload(file = copy))
        val remote = TestDocumentsRemoteDataSource()
        val delegate = local
        val crashing = object : DocumentsLocalDataSource by delegate {
            override suspend fun saveUpload(upload: UploadEntity) {
                if (upload.state == UploadState.INDEXING) {
                    if (afterSave) delegate.saveUpload(upload)
                    throw SimulatedDeath()
                }
                delegate.saveUpload(upload)
            }
        }
        assertTrue(runCatching { processor(remote, crashing).process("upload-1") }.exceptionOrNull() is SimulatedDeath)
        assertTrue(copy.exists())
        restart()
        remote.jobs += jobDto("succeeded")
        assertEquals(UploadOutcome.FINISHED, processor(remote).process("upload-1"))
        assertEquals(if (afterSave) 1 else 2, remote.submitted.size)
        assertTrue(remote.submitted.all { it.idempotencyKey == "upload-1" && it.fileContent == "%PDF-1.4 manual" })
        assertFalse(copy.exists())
        assertNull(local.upload("upload-1"))
    }

    private enum class Boundary { BEFORE_SAVE, AFTER_SAVE, AFTER_DELETE }

    @Test fun `terminal persistence failure leaves the input available`() = checkTerminalGap(Boundary.BEFORE_SAVE)
    @Test fun `terminal results persisted before deletion recover as cleanup only`() = checkTerminalGap(Boundary.AFTER_SAVE)
    @Test fun `cleanup repeats safely when death follows file deletion`() = checkTerminalGap(Boundary.AFTER_DELETE)

    private fun checkTerminalGap(boundary: Boundary) = runTest {
        listOf(UploadState.COMPLETED, UploadState.DUPLICATE, UploadState.FAILED).forEach { terminal ->
            val id = "upload-$terminal"
            val copy = files.save(id, "%PDF-1.4 manual".byteInputStream())
            local.saveUpload(upload(id, file = copy))
            val remote = TestDocumentsRemoteDataSource()
            if (terminal == UploadState.DUPLICATE) remote.submission = JobSubmission.Accepted(jobDto("duplicate", jobId = null, duplicateOf = "existing-manual"))
            val result = if (terminal == UploadState.FAILED) jobDto("failed", errorCode = "no_text", error = "No readable text") else jobDto("succeeded")
            if (terminal != UploadState.DUPLICATE) remote.jobs += result
            val delegate = local
            val crashing = object : DocumentsLocalDataSource by delegate {
                override suspend fun saveUpload(upload: UploadEntity) {
                    if (upload.state == terminal) {
                        if (boundary == Boundary.BEFORE_SAVE) throw SimulatedDeath()
                        delegate.saveUpload(upload)
                        if (boundary == Boundary.AFTER_SAVE) throw SimulatedDeath()
                    } else delegate.saveUpload(upload)
                }
                override suspend fun deleteUpload(id: String) {
                    if (boundary == Boundary.AFTER_DELETE) throw SimulatedDeath()
                    delegate.deleteUpload(id)
                }
                override suspend fun clearUploadInput(upload: UploadEntity) {
                    if (boundary == Boundary.AFTER_DELETE) throw SimulatedDeath()
                    delegate.clearUploadInput(upload)
                }
            }
            assertTrue(runCatching { processor(remote, crashing).process(id) }.exceptionOrNull() is SimulatedDeath)
            assertEquals(boundary != Boundary.AFTER_DELETE, copy.exists())
            restart()
            if (boundary != Boundary.BEFORE_SAVE) {
                val stored = local.upload(id)!!
                assertEquals(terminal, stored.state)
                assertEquals(copy.absolutePath, stored.filePath)
                assertEquals(if (terminal == UploadState.DUPLICATE) "existing-manual" else "bosch-manual", stored.entryId)
            } else if (terminal != UploadState.DUPLICATE) remote.jobs += result
            val recovered = FakeScheduler()
            repository(remote, recovered).reconcileUploads()
            assertEquals(listOf(id), recovered.ensured)
            assertEquals(UploadOutcome.FINISHED, processor(remote).process(id))
            assertFalse(copy.exists())
            assertEquals(if (boundary == Boundary.BEFORE_SAVE && terminal == UploadState.DUPLICATE) 2 else 1, remote.submitted.size)
            if (terminal == UploadState.COMPLETED) assertNull(local.upload(id)) else {
                assertEquals(terminal, local.upload(id)?.state)
                assertNull(local.upload(id)?.filePath)
            }
        }
    }

    @Test fun `finishing cleanup cannot recreate an upload dismissed meanwhile`() = runTest {
        val copy = files.save("upload-1", "%PDF-1.4 manual".byteInputStream())
        local.saveUpload(upload(file = copy, state = UploadState.DUPLICATE))
        val delegate = local
        val dismissedDuringCleanup = object : DocumentsLocalDataSource by delegate {
            override suspend fun clearUploadInput(upload: UploadEntity) {
                delegate.deleteUpload(upload.id)
                delegate.clearUploadInput(upload)
            }
        }
        assertEquals(UploadOutcome.FINISHED, processor(TestDocumentsRemoteDataSource(), dismissedDuringCleanup).process("upload-1"))
        assertNull(local.upload("upload-1"))
        assertFalse(copy.exists())
    }
}
