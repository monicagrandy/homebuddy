package chat.homebuddy.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A read cache of the server's documents. The server always wins: a refresh replaces it. */
@Entity(tableName = "documents", primaryKeys = ["householdId", "entryId"])
data class DocumentEntity(
    val householdId: Int,
    val entryId: String,
    val displayName: String,
    val docType: String,
    val status: String,
    val downloadUrl: String?,
    val uploadedAt: String?,
)

enum class UploadState { QUEUED, UPLOADING, INDEXING, FAILED, DUPLICATE }

/** A document being added from this phone, from picking it until the server has indexed it. */
@Entity(tableName = "uploads")
data class UploadEntity(
    /** Also sent as the Idempotency-Key, so a retried upload never creates a second document. */
    @PrimaryKey val id: String,
    val householdId: Int,
    val displayName: String,
    val docType: String,
    /** App-private copy of the picked PDF, kept until the server has it. */
    val filePath: String?,
    val url: String?,
    val state: UploadState,
    val jobId: String? = null,
    val entryId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
)

@Dao
abstract class DocumentDao {
    @Query("SELECT * FROM documents WHERE householdId = :householdId ORDER BY displayName COLLATE NOCASE")
    abstract fun observe(householdId: Int): Flow<List<DocumentEntity>>

    @Transaction
    open suspend fun replaceAll(householdId: Int, documents: List<DocumentEntity>) {
        deleteForHousehold(householdId)
        upsertAll(documents)
    }

    @Upsert
    abstract suspend fun upsertAll(documents: List<DocumentEntity>)

    @Query("DELETE FROM documents WHERE householdId = :householdId")
    abstract suspend fun deleteForHousehold(householdId: Int)

    @Query("DELETE FROM documents WHERE householdId = :householdId AND entryId = :entryId")
    abstract suspend fun delete(householdId: Int, entryId: String)

    @Query("DELETE FROM documents")
    abstract suspend fun clear()
}

@Dao
abstract class UploadDao {
    @Query("SELECT * FROM uploads WHERE householdId = :householdId ORDER BY createdAt DESC")
    abstract fun observe(householdId: Int): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE id = :id")
    abstract suspend fun get(id: String): UploadEntity?

    @Query("SELECT * FROM uploads")
    abstract suspend fun all(): List<UploadEntity>

    @Upsert
    abstract suspend fun upsert(upload: UploadEntity)

    @Query("DELETE FROM uploads WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM uploads")
    abstract suspend fun clear()
}

@Database(entities = [DocumentEntity::class, UploadEntity::class], version = 1)
abstract class HomeBuddyDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun uploads(): UploadDao
}
