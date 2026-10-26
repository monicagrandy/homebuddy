package chat.homebuddy.core.testing

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.data.documents.DocumentsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * [DocumentsRepository] in memory, for ViewModel and use case tests on the JVM. [server] is what
 * refreshing brings into the cache. Set [failure] to make the calls to the server (refresh and
 * delete) throw instead, and [fileFailure] to make adding a picked file throw.
 */
class TestDocumentsRepository : DocumentsRepository {
    var server = listOf<DocumentEntity>()
    var failure: Exception? = null
    var fileFailure: Exception? = null

    /** The names other apps give their files, by address. */
    val fileNames = mutableMapOf<String, String>()

    /** The homes each refresh was for, in order. */
    val refreshed = mutableListOf<Int>()
    val retried = mutableListOf<String>()
    var wiped = false
        private set

    private val cached = MutableStateFlow<List<DocumentEntity>>(emptyList())
    private val queued = MutableStateFlow<List<UploadEntity>>(emptyList())

    override fun documents(householdId: Int): Flow<List<DocumentEntity>> = cached.map { all -> all.filter { it.householdId == householdId } }

    override fun uploads(householdId: Int): Flow<List<UploadEntity>> = queued.map { all -> all.filter { it.householdId == householdId } }

    override suspend fun refresh(householdId: Int) {
        failure?.let { throw it }
        refreshed += householdId
        cached.update { all -> all.filterNot { it.householdId == householdId } + server.filter { it.householdId == householdId } }
    }

    override suspend fun delete(householdId: Int, entryId: String) {
        failure?.let { throw it }
        cached.update { all -> all.filterNot { it.householdId == householdId && it.entryId == entryId } }
    }

    override suspend fun addFile(householdId: Int, displayName: String, uri: String, docType: String): String {
        fileFailure?.let { throw it }
        return queue(householdId, displayName, docType, filePath = uri, url = null)
    }

    override suspend fun fileName(uri: String) = fileNames[uri]

    override suspend fun addLink(householdId: Int, displayName: String, url: String, docType: String) =
        queue(householdId, displayName, docType, filePath = null, url = url)

    override suspend fun retry(uploadId: String) {
        retried += uploadId
    }

    override suspend fun dismiss(uploadId: String) = queued.update { all -> all.filterNot { it.id == uploadId } }

    override suspend fun wipe() {
        wiped = true
        cached.value = emptyList()
        queued.value = emptyList()
    }

    /** The file's address stands in for the copy a real upload makes. */
    private fun queue(householdId: Int, displayName: String, docType: String, filePath: String?, url: String?): String {
        val id = "upload-${queued.value.size + 1}"
        queued.update {
            it + UploadEntity(
                id = id,
                householdId = householdId,
                displayName = displayName,
                docType = docType,
                filePath = filePath,
                url = url,
                state = UploadState.QUEUED,
                createdAt = 0,
            )
        }
        return id
    }
}
