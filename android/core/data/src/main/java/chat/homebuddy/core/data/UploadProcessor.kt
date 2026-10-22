package chat.homebuddy.core.data

import chat.homebuddy.core.network.DocumentJobDto
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.toApiError
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val api: HomeBuddyApi,
    private val uploads: UploadDao,
    private val documents: DocumentDao,
    private val files: UploadFiles,
    private val pollIntervalMillis: Long = 3_000,
    private val maxPolls: Int = 100,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun process(uploadId: String): UploadOutcome {
        var upload = uploads.get(uploadId) ?: return UploadOutcome.FINISHED
        if (upload.jobId == null) {
            upload = submit(upload) ?: return if (uploads.get(uploadId)?.state == UploadState.QUEUED) UploadOutcome.RETRY else UploadOutcome.FINISHED
        }
        return poll(upload)
    }

    /** After the last attempt WorkManager allows, show the upload as failed so the user can retry it. */
    suspend fun giveUp(uploadId: String) {
        val upload = uploads.get(uploadId) ?: return
        if (upload.state == UploadState.FAILED || upload.state == UploadState.DUPLICATE) return
        uploads.upsert(
            upload.copy(state = UploadState.FAILED, errorCode = "network", errorMessage = "Couldn't upload. Check your connection and try again."),
        )
    }

    /** Returns the upload with its job, or null when it ended (duplicate, rejected) or must wait. */
    private suspend fun submit(upload: UploadEntity): UploadEntity? {
        uploads.upsert(upload.copy(state = UploadState.UPLOADING))
        val job = try {
            api.createDocumentJob(
                idempotencyKey = upload.id,
                householdId = upload.householdId.toString().toPlain(),
                displayName = upload.displayName.toPlain(),
                docType = upload.docType.toPlain(),
                file = upload.filePath?.let { path ->
                    MultipartBody.Part.createFormData("file", "${upload.id}.pdf", File(path).asRequestBody(PDF))
                },
                url = upload.url?.toPlain(),
            )
        } catch (error: HttpException) {
            if (error.code() >= 500 || error.code() == 429) {
                uploads.upsert(upload.copy(state = UploadState.QUEUED))
                return null
            }
            val apiError = error.toApiError()
            fail(upload, apiError.code ?: "rejected", apiError.message)
            return null
        } catch (_: IOException) {
            uploads.upsert(upload.copy(state = UploadState.QUEUED))
            return null
        }

        // The server has the file now; the local copy isn't needed any more.
        files.delete(upload.filePath)
        if (job.status == "duplicate") {
            uploads.upsert(
                upload.copy(state = UploadState.DUPLICATE, filePath = null, entryId = job.duplicateOf ?: job.entryId),
            )
            return null
        }
        val indexing = upload.copy(state = UploadState.INDEXING, filePath = null, jobId = job.jobId, entryId = job.entryId)
        uploads.upsert(indexing)
        return indexing
    }

    private suspend fun poll(upload: UploadEntity): UploadOutcome {
        val jobId = checkNotNull(upload.jobId)
        repeat(maxPolls) {
            if (uploads.get(upload.id) == null) return UploadOutcome.FINISHED // dismissed meanwhile
            val job: DocumentJobDto = try {
                api.documentJob(jobId)
            } catch (_: IOException) {
                return UploadOutcome.RETRY
            } catch (error: HttpException) {
                if (error.code() == 404) {
                    fail(upload, "deleted", "This document was removed before it finished.")
                    return UploadOutcome.FINISHED
                }
                return UploadOutcome.RETRY
            }
            when (job.status) {
                "succeeded" -> {
                    uploads.delete(upload.id)
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
        uploads.upsert(upload.copy(state = UploadState.FAILED, filePath = null, errorCode = code, errorMessage = message))
    }

    private suspend fun refreshDocuments(householdId: Int) {
        runCatching { documents.replaceAll(householdId, api.documents(householdId).map { it.toEntity(householdId) }) }
    }

    private companion object {
        val PDF = "application/pdf".toMediaType()
        val TEXT = "text/plain".toMediaType()

        fun String.toPlain() = toRequestBody(TEXT)
    }
}
