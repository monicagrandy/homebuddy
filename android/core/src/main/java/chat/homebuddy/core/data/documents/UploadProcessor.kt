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
    suspend fun process(uploadId: String): UploadOutcome = try {
        processCurrent(uploadId)
    } catch (_: IOException) {
        // A failed cleanup must leave its durable terminal result available for the next run.
        UploadOutcome.RETRY
    }

    private suspend fun processCurrent(uploadId: String): UploadOutcome {
        var upload = local.upload(uploadId) ?: return UploadOutcome.FINISHED
        if (upload.requiresInputCleanup) {
            cleanupInput(upload)
            return UploadOutcome.FINISHED
        }
        if (upload.state == UploadState.FAILED) return UploadOutcome.FINISHED
        if (upload.jobId == null) {
            upload = submit(upload) ?: return if (local.upload(uploadId)?.state == UploadState.QUEUED) UploadOutcome.RETRY else UploadOutcome.FINISHED
        }
        return poll(upload)
    }

    /** After the last attempt WorkManager allows, show the upload as failed so the user can retry it. */
    suspend fun giveUp(uploadId: String) {
        val upload = local.upload(uploadId) ?: return
        if (upload.state == UploadState.FAILED || upload.state == UploadState.DUPLICATE || upload.state == UploadState.COMPLETED) return
        local.saveUpload(
            upload.copy(state = UploadState.FAILED, errorCode = "network", errorMessage = "Couldn't upload. Check your connection and try again."),
        )
    }

    /** Returns the upload with its job, or null when it ended (duplicate, rejected) or must wait. */
    private suspend fun submit(upload: UploadEntity): UploadEntity? {
        local.saveUpload(upload.copy(state = UploadState.UPLOADING))
        val submission = try {
            remote.submit(upload.attemptId ?: upload.id, upload.householdId, upload.displayName, upload.docType, upload.filePath?.let(::File), upload.url)
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

        if (job.status == "duplicate") {
            val duplicate = upload.copy(state = UploadState.DUPLICATE, jobId = job.jobId, entryId = job.duplicateOf ?: job.entryId)
            local.saveUpload(duplicate)
            cleanupInput(duplicate)
            return null
        }
        // Keep the input until indexing finishes, so a terminal server failure can start a new attempt.
        val indexing = upload.copy(state = UploadState.INDEXING, jobId = job.jobId, entryId = job.entryId)
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
                    val completed = upload.copy(state = UploadState.COMPLETED)
                    local.saveUpload(completed)
                    cleanupInput(completed)
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
        val failed = upload.copy(state = UploadState.FAILED, errorCode = code, errorMessage = message)
        local.saveUpload(failed)
        if (failed.requiresInputCleanup) cleanupInput(failed)
    }

    private suspend fun cleanupInput(upload: UploadEntity) {
        files.delete(upload.filePath)
        if (upload.state == UploadState.COMPLETED) {
            local.deleteUpload(upload.id)
            refreshDocuments(upload.householdId)
        } else if (upload.filePath != null) {
            local.clearUploadInput(upload)
        }
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
