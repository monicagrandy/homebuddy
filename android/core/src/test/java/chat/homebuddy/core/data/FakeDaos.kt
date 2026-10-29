package chat.homebuddy.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// In-memory DAOs, so the local data sources can be tested on the JVM. Room's own queries are
// tested against a real database in the …DaoTest classes.

class FakeProfileDao : ProfileDao() {
    private val user = MutableStateFlow<UserEntity?>(null)
    private val households = MutableStateFlow<List<HouseholdEntity>>(emptyList())

    override fun observe(): Flow<ProfileWithHouseholds?> = combine(user, households) { user, households ->
        user?.let { ProfileWithHouseholds(it, households.filter { home -> home.userId == it.id }) }
    }

    override suspend fun userId() = user.value?.id

    override suspend fun insertUser(user: UserEntity) {
        this.user.value = user
    }

    override suspend fun upsertHouseholds(households: List<HouseholdEntity>) = households.forEach { upsertHousehold(it) }

    override suspend fun upsertHousehold(household: HouseholdEntity) =
        households.update { homes -> homes.filterNot { it.userId == household.userId && it.id == household.id } + household }

    override suspend fun clearUsers() {
        user.value = null
    }

    override suspend fun clearHouseholds() {
        households.value = emptyList()
    }
}

class FakeDocumentDao : DocumentDao() {
    private val stored = MutableStateFlow<List<DocumentEntity>>(emptyList())

    override fun observe(householdId: Int): Flow<List<DocumentEntity>> =
        stored.map { all -> all.filter { it.householdId == householdId }.sortedBy { it.displayName.lowercase() } }

    override suspend fun upsertAll(documents: List<DocumentEntity>) = stored.update { all ->
        all.filterNot { old -> documents.any { it.householdId == old.householdId && it.entryId == old.entryId } } + documents
    }

    override suspend fun deleteForHousehold(householdId: Int) = stored.update { all -> all.filterNot { it.householdId == householdId } }

    override suspend fun delete(householdId: Int, entryId: String) =
        stored.update { all -> all.filterNot { it.householdId == householdId && it.entryId == entryId } }

    override suspend fun clear() {
        stored.value = emptyList()
    }
}

class FakeUploadDao : UploadDao() {
    private val stored = MutableStateFlow<Map<String, UploadEntity>>(emptyMap())

    override fun observe(householdId: Int): Flow<List<UploadEntity>> =
        stored.map { all -> all.values.filter { it.householdId == householdId }.sortedByDescending { it.createdAt } }

    override suspend fun get(id: String) = stored.value[id]

    override suspend fun all() = stored.value.values.toList()

    override suspend fun upsert(upload: UploadEntity) = stored.update { it + (upload.id to upload) }

    override suspend fun clearInput(id: String, attemptId: String?, state: UploadState, filePath: String) {
        stored.update { all ->
            val row = all[id]
            if (row != null && row.attemptId == attemptId && row.state == state && row.filePath == filePath) all + (id to row.copy(filePath = null)) else all
        }
    }

    override suspend fun delete(id: String) = stored.update { it - id }

    override suspend fun clear() {
        stored.value = emptyMap()
    }
}

class FakeConversationDao : ConversationDao() {
    private val threads = MutableStateFlow<Map<String, ConversationEntity>>(emptyMap())
    private val storedMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    private val cursors = mutableMapOf<Int, ConversationSyncEntity>()

    override fun observe(householdId: Int): Flow<List<ConversationEntity>> =
        threads.map { all -> all.values.filter { it.householdId == householdId }.sortedByDescending { it.updatedAt } }

    override fun observeOne(id: String): Flow<ConversationEntity?> = threads.map { it[id] }

    override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        storedMessages.map { all -> all.filter { it.conversationId == conversationId }.sortedBy { it.position } }

    override suspend fun upsert(conversations: List<ConversationEntity>) = threads.update { it + conversations.associateBy { thread -> thread.id } }

    override suspend fun idsForHousehold(householdId: Int) = threads.value.values.filter { it.householdId == householdId }.map { it.id }

    override suspend fun insertMessages(messages: List<MessageEntity>) = storedMessages.update { all ->
        all.filterNot { old -> messages.any { it.conversationId == old.conversationId && it.position == old.position } } + messages
    }

    override suspend fun deleteMessages(conversationId: String) = storedMessages.update { all -> all.filterNot { it.conversationId == conversationId } }

    override suspend fun deleteConversation(conversationId: String) = threads.update { it - conversationId }

    override suspend fun deleteMessagesForHousehold(householdId: Int) {
        val ids = threads.value.values.filter { it.householdId == householdId }.map { it.id }.toSet()
        storedMessages.update { all -> all.filterNot { it.conversationId in ids } }
    }

    override suspend fun deleteConversationsForHousehold(householdId: Int) = threads.update { all -> all.filterValues { it.householdId != householdId } }

    override suspend fun syncState(householdId: Int) = cursors[householdId]

    override suspend fun saveSyncState(state: ConversationSyncEntity) {
        cursors[state.householdId] = state
    }

    override suspend fun clearConversations() {
        threads.value = emptyMap()
    }

    override suspend fun clearMessages() {
        storedMessages.value = emptyList()
    }

    override suspend fun clearSync() = cursors.clear()
}
