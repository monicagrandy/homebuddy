package chat.homebuddy.core.data

import java.io.InputStream
import kotlinx.coroutines.flow.Flow

/** Schedules the background work that uploads a document (WorkManager in the app, a fake in tests). */
interface UploadScheduler {
    fun schedule(uploadId: String)
    fun cancel(uploadId: String)
    fun cancelAll()
}

/** A home's documents, and the uploads still on their way to the server. */
interface DocumentsRepository {
    fun documents(householdId: Int): Flow<List<DocumentEntity>>

    fun uploads(householdId: Int): Flow<List<UploadEntity>>

    /** Replaces the cache with the server's list, so documents deleted on another device disappear. */
    suspend fun refresh(householdId: Int)

    /** Deleting something already deleted elsewhere (404) counts as done. */
    suspend fun delete(householdId: Int, entryId: String)

    /** Copies [content] and queues its upload; returns the upload's id. */
    suspend fun addFile(householdId: Int, displayName: String, content: InputStream, docType: String = "manual"): String

    /** Queues an import of the PDF at [url]; returns the upload's id. */
    suspend fun addLink(householdId: Int, displayName: String, url: String, docType: String = "manual"): String

    suspend fun retry(uploadId: String)

    suspend fun dismiss(uploadId: String)

    /** Sign-out and rejected sessions: nothing of this account stays on the phone. */
    suspend fun wipe()
}
