package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import retrofit2.HttpException

enum class UploadOutcome {
    /** Done for good: indexed, a duplicate, failed for a reason retrying won't fix, or dismissed. */
    FINISHED,

    /** Offline, the server is busy, or still indexing: try again later. */
    RETRY,
}

/**
 * One run of an upload: send the file (once; the Idempotency-Key makes a resend return the same
 * job), then poll the job until the server has indexed it. Everything it learns is saved in the
 * uploads table, so a run that's interrupted picks up where it stopped.
 */
class UploadProcessor(
    private val remote: DocumentsRemoteDataSource,
    private val local: DocumentsLocalDataSource,
    private val files: UploadFiles,
    private val pollIntervalMillis: Long = 3_000,
    private val maxPolls: Int = 100,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun process(uploadId: String): UploadOutcome {
        var upload = local.upload(uploadId) ?: return UploadOutcome.FINISHED
        if (upload.jobId == null) {
            upload = submit(upload) ?: return if (local.upload(uploadId)?.state == UploadState.QUEUED) UploadOutcome.RETRY else UploadOutcome.FINISHED
        }
        return poll(upload)
    }

    /** After the last attempt WorkManager allows, show the upload as failed so the user can retry it. */
    suspend fun giveUp(uploadId: String) {
        val upload = local.upload(uploadId) ?: return
        if (upload.state == UploadState.FAILED || upload.state == UploadState.DUPLICATE) return
        local.saveUpload(
            upload.copy(state = UploadState.FAILED, errorCode = "network", errorMessage = "Couldn't upload. Check your connection and try again."),
        )
    }

    /** Returns the upload with its job, or null when it ended (duplicate, rejected) or must wait. */
    private suspend fun submit(upload: UploadEntity): UploadEntity? {
        local.saveUpload(upload.copy(state = UploadState.UPLOADING))
        val submission = try {
            remote.submit(upload.id, upload.householdId, upload.displayName, upload.docType, upload.filePath?.let(::File), upload.url)
        } catch (_: IOException) {
            JobSubmission.TryLater
        }
        val job = when (submission) {
            is JobSubmission.Accepted -> submission.job
            JobSubmission.TryLater -> {
                local.saveUpload(upload.copy(state = UploadState.QUEUED))
                return null
            }
            is JobSubmission.Rejected -> {
                fail(upload, submission.code ?: "rejected", submission.message)
                return null
            }
        }

        // The server has the file now; the local copy isn't needed any more.
        files.delete(upload.filePath)
        if (job.status == "duplicate") {
            local.saveUpload(
                upload.copy(state = UploadState.DUPLICATE, filePath = null, entryId = job.duplicateOf ?: job.entryId),
            )
            return null
        }
        val indexing = upload.copy(state = UploadState.INDEXING, filePath = null, jobId = job.jobId, entryId = job.entryId)
        local.saveUpload(indexing)
        return indexing
    }

    private suspend fun poll(upload: UploadEntity): UploadOutcome {
        val jobId = checkNotNull(upload.jobId)
        repeat(maxPolls) {
            if (local.upload(upload.id) == null) return UploadOutcome.FINISHED // dismissed meanwhile
            val job = try {
                remote.job(jobId)
            } catch (_: IOException) {
                return UploadOutcome.RETRY
            } catch (_: HttpException) {
                return UploadOutcome.RETRY
            }
            if (job == null) {
                fail(upload, "deleted", "This document was removed before it finished.")
                return UploadOutcome.FINISHED
            }
            when (job.status) {
                "succeeded" -> {
                    local.deleteUpload(upload.id)
                    refreshDocuments(upload.householdId)
                    return UploadOutcome.FINISHED
                }
                "failed" -> {
                    fail(upload, job.errorCode ?: "index_failed", job.error ?: "The document couldn't be added.")
                    return UploadOutcome.FINISHED
                }
                else -> wait(pollIntervalMillis) // queued, or running (the server retries its own hiccups)
            }
        }
        return UploadOutcome.RETRY
    }

    private suspend fun fail(upload: UploadEntity, code: String, message: String) {
        files.delete(upload.filePath)
        local.saveUpload(upload.copy(state = UploadState.FAILED, filePath = null, errorCode = code, errorMessage = message))
    }

    private suspend fun refreshDocuments(householdId: Int) {
        // The upload is done either way; the documents list refreshes again when it's next opened.
        try {
            local.replaceDocuments(householdId, remote.documents(householdId).map { it.toEntity(householdId) })
        } catch (_: IOException) {
        } catch (_: HttpException) {
        }
    }
}
