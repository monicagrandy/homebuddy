package chat.homebuddy.core.testing

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.DocumentsRepository
import chat.homebuddy.core.data.UploadEntity
import java.io.InputStream
import kotlinx.coroutines.flow.Flow

/** [DocumentsRepository] in memory, for ViewModel tests on the JVM. So far only signing out needs it. */
class TestDocumentsRepository : DocumentsRepository {
    override fun documents(householdId: Int): Flow<List<DocumentEntity>> = notFaked()

    override fun uploads(householdId: Int): Flow<List<UploadEntity>> = notFaked()

    override suspend fun refresh(householdId: Int) = notFaked()

    override suspend fun delete(householdId: Int, entryId: String) = notFaked()

    override suspend fun addFile(householdId: Int, displayName: String, content: InputStream, docType: String): String = notFaked()

    override suspend fun addLink(householdId: Int, displayName: String, url: String, docType: String): String = notFaked()

    override suspend fun retry(uploadId: String) = notFaked()

    override suspend fun dismiss(uploadId: String) = notFaked()

    /** Nothing is cached yet, so there's nothing to wipe. */
    override suspend fun wipe() = Unit
}
