package chat.homebuddy.core.testing

import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.MessageEntity
import chat.homebuddy.core.data.conversations.ConversationsRepository
import chat.homebuddy.core.network.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * [ConversationsRepository] in memory, for ViewModel and use case tests on the JVM. [server] is what
 * syncing brings into the cache. Set [failure] to make the calls to the server throw instead.
 */
class TestConversationsRepository : ConversationsRepository {
    var server = listOf<ConversationEntity>()
    var failure: Exception? = null

    /** Whether each sync asked for the full list, in order. */
    val syncs = mutableListOf<Boolean>()
    var wiped = false
        private set

    private val cached = MutableStateFlow<List<ConversationEntity>>(emptyList())

    override fun conversations(householdId: Int): Flow<List<ConversationEntity>> =
        cached.map { all -> all.filter { it.householdId == householdId }.sortedByDescending { it.updatedAt } }

    override fun conversation(id: String): Flow<ConversationEntity?> = notFaked()

    override fun messages(conversationId: String): Flow<List<MessageEntity>> = notFaked()

    override suspend fun sync(householdId: Int, forceFull: Boolean) {
        failure?.let { throw it }
        syncs += forceFull
        cached.update { all -> all.filterNot { it.householdId == householdId } + server.filter { it.householdId == householdId } }
    }

    override suspend fun loadMessages(householdId: Int, conversationId: String): Boolean = notFaked()

    override suspend fun delete(householdId: Int, conversationId: String) {
        failure?.let { throw it }
        cached.update { all -> all.filterNot { it.id == conversationId } }
    }

    override fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?): Flow<StreamEvent> = notFaked()

    override suspend fun afterAnswer(householdId: Int, conversationId: String) = notFaked()

    override suspend fun wipe() {
        wiped = true
        cached.value = emptyList()
    }
}
