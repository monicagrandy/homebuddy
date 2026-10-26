package chat.homebuddy.core.data

import chat.homebuddy.core.network.DocumentDto
import chat.homebuddy.core.network.HomeBuddyApi
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

/** [DocumentsRepository] as a Room cache of the server's list, with uploads queued in WorkManager. */
class CachedDocumentsRepository(
    private val api: HomeBuddyApi,
    private val documents: DocumentDao,
    private val uploads: UploadDao,
    private val files: UploadFiles,
    private val scheduler: UploadScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DocumentsRepository {
    override fun documents(householdId: Int): Flow<List<DocumentEntity>> = documents.observe(householdId)

    override fun uploads(householdId: Int): Flow<List<UploadEntity>> = uploads.observe(householdId)

    override suspend fun refresh(householdId: Int) {
        documents.replaceAll(householdId, api.documents(householdId).map { it.toEntity(householdId) })
    }

    override suspend fun delete(householdId: Int, entryId: String) {
        val response = api.deleteDocument(entryId, householdId)
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
        documents.delete(householdId, entryId)
    }

    override suspend fun addFile(householdId: Int, displayName: String, content: InputStream, docType: String): String {
        val id = newId()
        val copy = files.save(id, content)
        uploads.upsert(
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
        uploads.upsert(
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
        val upload = uploads.get(uploadId) ?: return
        uploads.upsert(upload.copy(state = UploadState.QUEUED, errorCode = null, errorMessage = null))
        scheduler.schedule(uploadId)
    }

    override suspend fun dismiss(uploadId: String) {
        val upload = uploads.get(uploadId) ?: return
        scheduler.cancel(uploadId)
        files.delete(upload.filePath)
        uploads.delete(uploadId)
    }

    override suspend fun wipe() {
        scheduler.cancelAll()
        uploads.clear()
        documents.clear()
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
