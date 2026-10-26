package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.network.DocumentDto
import chat.homebuddy.core.network.DocumentJobDto
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

fun documentDto(entryId: String, status: String = "ready") =
    DocumentDto(id = 1, entryId = entryId, displayName = entryId, docType = "manual", status = status)

fun jobDto(status: String, jobId: String? = "job-1", errorCode: String? = null, error: String? = null, duplicateOf: String? = null) =
    DocumentJobDto(jobId = jobId, entryId = "bosch-manual", status = status, duplicateOf = duplicateOf, errorCode = errorCode, error = error)

/** The server's side of documents and upload jobs, in memory. */
class TestDocumentsRemoteDataSource : DocumentsRemoteDataSource {
    /** What the server lists for each home. */
    val documents = mutableMapOf<Int, List<DocumentDto>>()

    /** Every call fails as it would without a connection. */
    var offline = false

    /** Thrown by listing documents instead of answering. */
    var listFailure: Exception? = null

    /** Thrown by deleting a document instead of answering. */
    var deleteFailure: Exception? = null

    /** How the server answers an upload. */
    var submission: JobSubmission = JobSubmission.Accepted(jobDto("queued"))

    /** The server's answer to each poll of a job, in turn; null means the job is gone. */
    val jobs = ArrayDeque<DocumentJobDto?>()

    val submitted = mutableListOf<Submitted>()
    val deleted = mutableListOf<String>()
    var polls = 0
        private set

    data class Submitted(val idempotencyKey: String, val householdId: Int, val displayName: String, val fileContent: String?, val url: String?)

    override suspend fun documents(householdId: Int): List<DocumentDto> {
        checkConnection()
        listFailure?.let { throw it }
        return documents[householdId].orEmpty()
    }

    override suspend fun delete(householdId: Int, entryId: String) {
        checkConnection()
        deleteFailure?.let { throw it }
        deleted += entryId
        documents[householdId] = documents[householdId].orEmpty().filterNot { it.entryId == entryId }
    }

    override suspend fun submit(idempotencyKey: String, householdId: Int, displayName: String, docType: String, file: File?, url: String?): JobSubmission {
        checkConnection()
        submitted += Submitted(idempotencyKey, householdId, displayName, file?.readText(), url)
        return submission
    }

    override suspend fun job(jobId: String): DocumentJobDto? {
        checkConnection()
        polls++
        return jobs.removeFirst()
    }

    private fun checkConnection() {
        if (offline) throw IOException("No connection")
    }
}

/** The cached documents and uploads, in memory. */
class TestDocumentsLocalDataSource : DocumentsLocalDataSource {
    private val documents = MutableStateFlow<List<DocumentEntity>>(emptyList())
    private val uploads = MutableStateFlow<Map<String, UploadEntity>>(emptyMap())

    override fun documents(householdId: Int): Flow<List<DocumentEntity>> =
        documents.map { all -> all.filter { it.householdId == householdId }.sortedBy { it.displayName.lowercase() } }

    override suspend fun replaceDocuments(householdId: Int, documents: List<DocumentEntity>) =
        this.documents.update { all -> all.filterNot { it.householdId == householdId } + documents }

    override suspend fun deleteDocument(householdId: Int, entryId: String) =
        documents.update { all -> all.filterNot { it.householdId == householdId && it.entryId == entryId } }

    override fun uploads(householdId: Int): Flow<List<UploadEntity>> =
        uploads.map { all -> all.values.filter { it.householdId == householdId }.sortedByDescending { it.createdAt } }

    override suspend fun upload(id: String) = uploads.value[id]

    override suspend fun saveUpload(upload: UploadEntity) = uploads.update { it + (upload.id to upload) }

    override suspend fun deleteUpload(id: String) = uploads.update { it - id }

    override suspend fun clear() {
        documents.value = emptyList()
        uploads.value = emptyMap()
    }
}
