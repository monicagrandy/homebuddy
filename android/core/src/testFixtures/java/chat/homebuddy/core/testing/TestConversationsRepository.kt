package chat.homebuddy.core.testing

import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.MessageEntity
import chat.homebuddy.core.data.conversations.ConversationsRepository
import chat.homebuddy.core.network.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * [ConversationsRepository] in memory, for ViewModel and use case tests on the JVM. [server] and
 * [serverMessages] are what syncing and loading bring into the cache, and [answer] is what asking
 * streams. Set [failure] to make the calls to the server throw instead.
 */
class TestConversationsRepository : ConversationsRepository {
    var server = listOf<ConversationEntity>()
    var failure: Exception? = null

    /** Each thread's messages on the server. A thread missing here is gone (deleted elsewhere, or expired). */
    val serverMessages = mutableMapOf<String, List<MessageEntity>>()
    var answer: Flow<StreamEvent> = emptyFlow()

    /** Whether each sync asked for the full list, in order. */
    val syncs = mutableListOf<Boolean>()

    /** The threads whose messages were loaded from the server, in order. */
    val loaded = mutableListOf<String>()
    val asked = mutableListOf<Question>()

    /** The threads refreshed after an answer, in order. */
    val refreshedAfterAnswer = mutableListOf<String>()
    var whileRefreshingAfterAnswer: suspend (Int, String) -> Unit = { _, _ -> }

    /** Runs at the start of each [wipe], so a test can hold one in flight. */
    var whileWiping: suspend () -> Unit = {}
    var wipes = 0
        private set
    val wiped get() = wipes > 0

    data class Question(val householdId: Int, val conversationId: String, val question: String, val zipCode: String?)

    private val cached = MutableStateFlow<List<ConversationEntity>>(emptyList())
    private val cachedMessages = MutableStateFlow<Map<String, List<MessageEntity>>>(emptyMap())

    override fun conversations(householdId: Int): Flow<List<ConversationEntity>> =
        cached.map { all -> all.filter { it.householdId == householdId }.sortedByDescending { it.updatedAt } }

    override fun conversation(id: String): Flow<ConversationEntity?> = cached.map { all -> all.firstOrNull { it.id == id } }

    override fun messages(conversationId: String): Flow<List<MessageEntity>> = cachedMessages.map { it[conversationId].orEmpty() }

    override suspend fun sync(householdId: Int, forceFull: Boolean) {
        failure?.let { throw it }
        syncs += forceFull
        cached.update { all -> all.filterNot { it.householdId == householdId } + server.filter { it.householdId == householdId } }
    }

    override suspend fun loadMessages(householdId: Int, conversationId: String): Boolean {
        loaded += conversationId
        failure?.let { throw it }
        val messages = serverMessages[conversationId]
        if (messages == null) {
            cached.update { all -> all.filterNot { it.id == conversationId } }
            cachedMessages.update { it - conversationId }
            return false
        }
        cachedMessages.update { it + (conversationId to messages) }
        return true
    }

    override suspend fun delete(householdId: Int, conversationId: String) {
        failure?.let { throw it }
        cached.update { all -> all.filterNot { it.id == conversationId } }
    }

    override fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?): Flow<StreamEvent> {
        asked += Question(householdId, conversationId, question, zipCode)
        return answer
    }

    override suspend fun afterAnswer(householdId: Int, conversationId: String) {
        refreshedAfterAnswer += conversationId
        whileRefreshingAfterAnswer(householdId, conversationId)
    }

    override suspend fun wipe() {
        wipes++
        whileWiping()
        cached.value = emptyList()
        cachedMessages.value = emptyMap()
    }
}
