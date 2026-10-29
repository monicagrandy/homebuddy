package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.network.DocumentDto
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val queueMutex = Mutex()
    override fun documents(householdId: Int): Flow<List<DocumentEntity>> = local.documents(householdId)

    override fun uploads(householdId: Int): Flow<List<UploadEntity>> = local.uploads(householdId)

    override suspend fun reconcileUploads() = queueMutex.withLock {
        val uploads = local.allUploads()
        uploads.filter { it.requiresRecovery }.forEach { scheduler.ensureScheduled(it.id) }
        // Copies are only written under this lock, so a file no upload refers to is left over.
        withContext(io) { files.removeUnreferenced(uploads.mapNotNullTo(mutableSetOf()) { it.filePath }) }
    }

    override suspend fun refresh(householdId: Int) {
        local.replaceDocuments(householdId, remote.documents(householdId).map { it.toEntity(householdId) })
    }

    override suspend fun delete(householdId: Int, entryId: String) {
        remote.delete(householdId, entryId)
        local.deleteDocument(householdId, entryId)
    }

    override suspend fun addFile(householdId: Int, displayName: String, uri: String, docType: String): String = queueMutex.withLock {
        val id = newId()
        var added = false
        try {
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
            added = true
        } finally {
            if (!added) withContext(NonCancellable) { afterCutShortAdd(id) }
        }
        scheduler.schedule(id)
        id
    }

    /**
     * Adding failed or was cancelled after the copy may have been written. An upload that was saved
     * all the same still goes ahead; otherwise nothing would ever send or remove the copy.
     */
    private suspend fun afterCutShortAdd(id: String) {
        if (local.upload(id) != null) {
            scheduler.schedule(id)
            return
        }
        try {
            withContext(io) { files.discard(id) }
        } catch (_: IOException) {
            // Removed with the other leftovers when uploads are next reconciled.
        }
    }

    override suspend fun fileName(uri: String) = picked.displayName(uri)

    override suspend fun addLink(householdId: Int, displayName: String, url: String, docType: String): String = queueMutex.withLock {
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
        id
    }

    override suspend fun retry(uploadId: String) = queueMutex.withLock {
        val upload = local.upload(uploadId) ?: return@withLock
        val action = upload.retryAction
        if (action == UploadRetry.NONE) return@withLock
        if ((action == UploadRetry.NEW_ATTEMPT || upload.jobId == null) && upload.url == null &&
            !withContext(io) { upload.filePath?.let { File(it).isFile } ?: false }
        ) {
            local.saveUpload(upload.copy(errorCode = "input_missing", errorMessage = "The local PDF is no longer available. Add it again to retry."))
            return@withLock
        }
        local.saveUpload(upload.copy(
            state = UploadState.QUEUED,
            errorCode = null,
            errorMessage = null,
            jobId = if (action == UploadRetry.NEW_ATTEMPT) null else upload.jobId,
            entryId = if (action == UploadRetry.NEW_ATTEMPT) null else upload.entryId,
            attemptId = if (action == UploadRetry.NEW_ATTEMPT) newId() else upload.attemptId,
        ))
        scheduler.schedule(uploadId)
    }

    override suspend fun dismiss(uploadId: String) = queueMutex.withLock {
        val upload = local.upload(uploadId) ?: return@withLock
        scheduler.cancel(uploadId)
        withContext(io) { files.delete(upload.filePath) }
        local.deleteUpload(uploadId)
    }

    override suspend fun wipe() = queueMutex.withLock {
        scheduler.cancelAll()
        local.clear()
        withContext(io) { files.clear() }
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
