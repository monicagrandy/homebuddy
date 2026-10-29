package chat.homebuddy.core.data.conversations

import chat.homebuddy.core.data.ConversationDao
import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.ConversationSyncEntity
import chat.homebuddy.core.data.MessageEntity
import kotlinx.coroutines.flow.Flow

/** The cached threads, their messages, and how far each home's list has synced. */
interface ConversationsLocalDataSource {
    /** Newest first. */
    fun conversations(householdId: Int): Flow<List<ConversationEntity>>

    fun conversation(id: String): Flow<ConversationEntity?>

    /** In order. */
    fun messages(conversationId: String): Flow<List<MessageEntity>>

    suspend fun upsert(conversations: List<ConversationEntity>)

    /** Replaces one home's threads, preserving retained history; dropped threads take their messages with them. */
    suspend fun replaceAll(householdId: Int, conversations: List<ConversationEntity>)

    suspend fun replaceMessages(conversationId: String, messages: List<MessageEntity>)

    /** A thread and its messages. */
    suspend fun delete(conversationId: String)

    suspend fun syncState(householdId: Int): ConversationSyncEntity?

    suspend fun saveSyncState(state: ConversationSyncEntity)

    /** Every thread, message and sync cursor. */
    suspend fun clear()
}

class RoomConversationsLocalDataSource(private val dao: ConversationDao) : ConversationsLocalDataSource {
    override fun conversations(householdId: Int) = dao.observe(householdId)

    override fun conversation(id: String) = dao.observeOne(id)

    override fun messages(conversationId: String) = dao.observeMessages(conversationId)

    override suspend fun upsert(conversations: List<ConversationEntity>) = dao.upsert(conversations)

    override suspend fun replaceAll(householdId: Int, conversations: List<ConversationEntity>) = dao.replaceAll(householdId, conversations)

    override suspend fun replaceMessages(conversationId: String, messages: List<MessageEntity>) = dao.replaceMessages(conversationId, messages)

    override suspend fun delete(conversationId: String) = dao.delete(conversationId)

    override suspend fun syncState(householdId: Int) = dao.syncState(householdId)

    override suspend fun saveSyncState(state: ConversationSyncEntity) = dao.saveSyncState(state)

    override suspend fun clear() = dao.clear()
}
