package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.network.DocumentDto
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** [DocumentsRepository] as a cache of the server's list, with uploads queued in the background. */
class CachedDocumentsRepository(
    private val remote: DocumentsRemoteDataSource,
    private val local: DocumentsLocalDataSource,
    private val picked: PickedFileDataSource,
    private val files: UploadFiles,
    private val scheduler: UploadScheduler,
    private val io: CoroutineDispatcher = Dispatchers.IO,
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

    override suspend fun addFile(householdId: Int, displayName: String, uri: String, docType: String): String {
        val id = newId()
        // Copied now, while the app still has permission to read it; the upload may run much later.
        val copy = withContext(io) { files.save(id, picked.open(uri)) }
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

    override suspend fun fileName(uri: String) = picked.displayName(uri)

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
