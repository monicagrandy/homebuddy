package chat.homebuddy.core.data.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/** Schedules the background work that uploads a document (WorkManager in the app, a fake in tests). */
interface UploadScheduler {
    fun schedule(uploadId: String)
    /** Restores missing work without replacing an unfinished worker; waits for durable enqueue. */
    suspend fun ensureScheduled(uploadId: String)
    fun cancel(uploadId: String)
    suspend fun cancelAll()
}

/** A home's documents, and the uploads still on their way to the server. */
interface DocumentsRepository {
    /** By name. */
    fun documents(householdId: Int): Flow<List<DocumentEntity>>

    fun uploads(householdId: Int): Flow<List<UploadEntity>>

    /** Restores unfinished durable uploads after startup session cleanup has completed. */
    suspend fun reconcileUploads()

    /** Replaces the cache with the server's list, so documents deleted on another device disappear. */
    suspend fun refresh(householdId: Int)

    /** Deleting something already deleted elsewhere (404) counts as done. */
    suspend fun delete(householdId: Int, entryId: String)

    /**
     * Copies the picked or shared file at [uri] (a content:// address) and queues its upload;
     * returns the upload's id. Throws [UploadFiles.FileTooLarge], [UploadFiles.CopyFailed] when
     * the copy can't be stored, or [IOException] when the file can't be read. Nothing is left
     * behind when it throws or is cancelled.
     */
    suspend fun addFile(householdId: Int, displayName: String, uri: String, docType: String = "manual"): String

    /** The name the app that shared the file at [uri] gives it, or null. */
    suspend fun fileName(uri: String): String?

    /** Queues an import of the PDF at [url]; returns the upload's id. */
    suspend fun addLink(householdId: Int, displayName: String, url: String, docType: String = "manual"): String

    /** Resumes a transient failure, or submits recoverable input as a fresh attempt after indexing fails. */
    suspend fun retry(uploadId: String)

    suspend fun dismiss(uploadId: String)

    /** Sign-out and rejected sessions: nothing of this account stays on the phone. */
    suspend fun wipe()
}
