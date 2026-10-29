package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentDao
import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadDao
import chat.homebuddy.core.data.UploadEntity
import kotlinx.coroutines.flow.Flow

/** The cached documents, and the uploads this phone hasn't finished yet. */
interface DocumentsLocalDataSource {
    /** By name, ignoring case. */
    fun documents(householdId: Int): Flow<List<DocumentEntity>>

    /** Replaces one home's cached documents. */
    suspend fun replaceDocuments(householdId: Int, documents: List<DocumentEntity>)

    suspend fun deleteDocument(householdId: Int, entryId: String)

    /** Newest first. */
    fun uploads(householdId: Int): Flow<List<UploadEntity>>

    suspend fun upload(id: String): UploadEntity?

    suspend fun allUploads(): List<UploadEntity>

    suspend fun saveUpload(upload: UploadEntity)

    /** Clears a cleaned input only if its attempt still exists in the same terminal state. */
    suspend fun clearUploadInput(upload: UploadEntity)

    suspend fun deleteUpload(id: String)

    /** Every document and upload, for every home. */
    suspend fun clear()
}

class RoomDocumentsLocalDataSource(private val documents: DocumentDao, private val uploads: UploadDao) : DocumentsLocalDataSource {
    override fun documents(householdId: Int) = documents.observe(householdId)

    override suspend fun replaceDocuments(householdId: Int, documents: List<DocumentEntity>) = this.documents.replaceAll(householdId, documents)

    override suspend fun deleteDocument(householdId: Int, entryId: String) = documents.delete(householdId, entryId)

    override fun uploads(householdId: Int) = uploads.observe(householdId)

    override suspend fun upload(id: String) = uploads.get(id)

    override suspend fun allUploads() = uploads.all()

    override suspend fun saveUpload(upload: UploadEntity) = uploads.upsert(upload)

    override suspend fun clearUploadInput(upload: UploadEntity) {
        upload.filePath?.let { uploads.clearInput(upload.id, upload.attemptId, upload.state, it) }
    }

    override suspend fun deleteUpload(id: String) = uploads.delete(id)

    override suspend fun clear() {
        uploads.clear()
        documents.clear()
    }
}
