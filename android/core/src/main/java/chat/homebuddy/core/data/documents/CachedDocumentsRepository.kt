package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.network.DocumentDto
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** [DocumentsRepository] as a cache of the server's list, with uploads queued in the background. */
class CachedDocumentsRepository(
    private val remote: DocumentsRemoteDataSource,
    private val local: DocumentsLocalDataSource,
    private val files: UploadFiles,
    private val scheduler: UploadScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DocumentsRepository {
    override fun documents(householdId: Int): Flow<List<DocumentEntity>> = local.documents(householdId)

    override fun uploads(householdId: Int): Flow<List<UploadEntity>> = local.uploads(householdId)

    override suspend fun refresh(householdId: Int) {
        local.replaceDocuments(householdId, remote.documents(householdId).map { it.toEntity(householdId) })
    }

    override suspend fun delete(householdId: Int, entryId: String) {
        remote.delete(householdId, entryId)
        local.deleteDocument(householdId, entryId)
    }

    override suspend fun addFile(householdId: Int, displayName: String, content: InputStream, docType: String): String {
        val id = newId()
        val copy = files.save(id, content)
        local.saveUpload(
            UploadEntity(
                id = id,
                householdId = householdId,
                displayName = displayName,
                docType = docType,
                filePath = copy.absolutePath,
                url = null,
                state = UploadState.QUEUED,
                createdAt = clock(),
            ),
        )
        scheduler.schedule(id)
        return id
    }

    override suspend fun addLink(householdId: Int, displayName: String, url: String, docType: String): String {
        val id = newId()
        local.saveUpload(
            UploadEntity(
                id = id,
                householdId = householdId,
                displayName = displayName,
                docType = docType,
                filePath = null,
                url = url,
                state = UploadState.QUEUED,
                createdAt = clock(),
            ),
        )
        scheduler.schedule(id)
        return id
    }

    override suspend fun retry(uploadId: String) {
        val upload = local.upload(uploadId) ?: return
        local.saveUpload(upload.copy(state = UploadState.QUEUED, errorCode = null, errorMessage = null))
        scheduler.schedule(uploadId)
    }

    override suspend fun dismiss(uploadId: String) {
        val upload = local.upload(uploadId) ?: return
        scheduler.cancel(uploadId)
        files.delete(upload.filePath)
        local.deleteUpload(uploadId)
    }

    override suspend fun wipe() {
        scheduler.cancelAll()
        local.clear()
        files.clear()
    }
}

internal fun DocumentDto.toEntity(householdId: Int) = DocumentEntity(
    householdId = householdId,
    entryId = entryId,
    displayName = displayName,
    docType = docType,
    status = status,
    downloadUrl = downloadUrl,
    uploadedAt = uploadedAt,
)
