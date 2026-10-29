package chat.homebuddy.core.data.conversations

import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.ConversationSyncEntity
import chat.homebuddy.core.data.MessageEntity
import chat.homebuddy.core.network.ConversationDto
import chat.homebuddy.core.network.MessageDto
import chat.homebuddy.core.network.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The server's side of chat threads, in memory. */
class TestConversationsRemoteDataSource : ConversationsRemoteDataSource {
    /** What the next listing returns. */
    var conversations = listOf<ConversationDto>()

    /** Each thread's messages on the server. A thread missing here is gone (404). */
    val messages = mutableMapOf<String, List<MessageDto>>()

    /** Thrown by every call instead of answering. */
    var failure: Exception? = null

    /** Runs at the start of each listing, so a test can hold one in flight. */
    var whileListing: suspend () -> Unit = {}

    /** What asking streams. */
    var stream: Flow<StreamEvent> = emptyFlow()

    /** The updated_since each listing asked for. */
    val listings = mutableListOf<String?>()
    val deleted = mutableListOf<String>()

    override suspend fun conversations(householdId: Int, updatedSince: String?): List<ConversationDto> {
        whileListing()
        failure?.let { throw it }
        listings += updatedSince
        return conversations.filter { it.householdId == householdId }
    }

    override suspend fun messages(householdId: Int, conversationId: String): List<MessageDto>? {
        failure?.let { throw it }
        return messages[conversationId]
    }

    override suspend fun delete(householdId: Int, conversationId: String) {
        failure?.let { throw it }
        deleted += conversationId
    }

    override fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?) = stream
}

/** The cached threads, messages and sync cursors, in memory. */
class TestConversationsLocalDataSource : ConversationsLocalDataSource {
    private val threads = MutableStateFlow<Map<String, ConversationEntity>>(emptyMap())
    private val storedMessages = MutableStateFlow<Map<String, List<MessageEntity>>>(emptyMap())
    private val cursors = mutableMapOf<Int, ConversationSyncEntity>()

    override fun conversations(householdId: Int): Flow<List<ConversationEntity>> =
        threads.map { all -> all.values.filter { it.householdId == householdId }.sortedByDescending { it.updatedAt } }

    override fun conversation(id: String): Flow<ConversationEntity?> = threads.map { it[id] }

    override fun messages(conversationId: String): Flow<List<MessageEntity>> =
        storedMessages.map { it[conversationId].orEmpty().sortedBy { message -> message.position } }

    override suspend fun upsert(conversations: List<ConversationEntity>) = threads.update { it + conversations.associateBy { thread -> thread.id } }

    override suspend fun replaceAll(householdId: Int, conversations: List<ConversationEntity>) {
        val retainedIds = conversations.map { it.id }.toSet()
        val dropped = threads.value.values.filter { it.householdId == householdId && it.id !in retainedIds }.map { it.id }
        storedMessages.update { it - dropped.toSet() }
        threads.update { all -> all.filterValues { it.householdId != householdId } + conversations.associateBy { thread -> thread.id } }
    }

    override suspend fun replaceMessages(conversationId: String, messages: List<MessageEntity>) =
        storedMessages.update { it + (conversationId to messages) }

    override suspend fun delete(conversationId: String) {
        storedMessages.update { it - conversationId }
        threads.update { it - conversationId }
    }

    override suspend fun syncState(householdId: Int) = cursors[householdId]

    override suspend fun saveSyncState(state: ConversationSyncEntity) {
        cursors[state.householdId] = state
    }

    override suspend fun clear() {
        threads.value = emptyMap()
        storedMessages.value = emptyMap()
        cursors.clear()
    }
}
