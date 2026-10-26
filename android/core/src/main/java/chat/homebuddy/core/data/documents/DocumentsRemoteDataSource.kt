package chat.homebuddy.core.data.documents

import chat.homebuddy.core.network.DocumentDto
import chat.homebuddy.core.network.DocumentJobDto
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.toApiError
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/** What the server said to a new upload. */
sealed interface JobSubmission {
    /** Queued for indexing, or (status "duplicate") already in this home. */
    data class Accepted(val job: DocumentJobDto) : JobSubmission

    /** The server is busy (429) or failing (5xx): send it again later. */
    data object TryLater : JobSubmission

    /** Refused for good, such as a file that isn't a PDF. */
    data class Rejected(val code: String?, val message: String) : JobSubmission
}

/** A home's documents and upload jobs on the server. Failures throw [HttpException], or [IOException] offline. */
interface DocumentsRemoteDataSource {
    suspend fun documents(householdId: Int): List<DocumentDto>

    /** Deleting something already deleted elsewhere (404) counts as done. */
    suspend fun delete(householdId: Int, entryId: String)

    /** Sends a PDF ([file]) or a link ([url]). Resending with the same [idempotencyKey] returns the same job. */
    suspend fun submit(idempotencyKey: String, householdId: Int, displayName: String, docType: String, file: File?, url: String?): JobSubmission

    /** Null when the job is gone (404): its document was deleted before it finished. */
    suspend fun job(jobId: String): DocumentJobDto?
}

class RetrofitDocumentsRemoteDataSource(private val api: HomeBuddyApi) : DocumentsRemoteDataSource {
    override suspend fun documents(householdId: Int) = api.documents(householdId)

    override suspend fun delete(householdId: Int, entryId: String) {
        val response = api.deleteDocument(entryId, householdId)
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
    }

    override suspend fun submit(
        idempotencyKey: String,
        householdId: Int,
        displayName: String,
        docType: String,
        file: File?,
        url: String?,
    ): JobSubmission = try {
        val job = api.createDocumentJob(
            idempotencyKey = idempotencyKey,
            householdId = householdId.toString().toPlain(),
            displayName = displayName.toPlain(),
            docType = docType.toPlain(),
            file = file?.let { MultipartBody.Part.createFormData("file", "$idempotencyKey.pdf", it.asRequestBody(PDF)) },
            url = url?.toPlain(),
        )
        JobSubmission.Accepted(job)
    } catch (error: HttpException) {
        if (error.code() >= 500 || error.code() == 429) {
            JobSubmission.TryLater
        } else {
            val apiError = error.toApiError()
            JobSubmission.Rejected(apiError.code, apiError.message)
        }
    }

    override suspend fun job(jobId: String): DocumentJobDto? = try {
        api.documentJob(jobId)
    } catch (error: HttpException) {
        if (error.code() == 404) null else throw error
    }

    private companion object {
        val PDF = "application/pdf".toMediaType()
        val TEXT = "text/plain".toMediaType()

        fun String.toPlain() = toRequestBody(TEXT)
    }
}
