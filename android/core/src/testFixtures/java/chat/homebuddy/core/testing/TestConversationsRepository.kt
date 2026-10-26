package chat.homebuddy.core.testing

import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.MessageEntity
import chat.homebuddy.core.data.conversations.ConversationsRepository
import chat.homebuddy.core.network.StreamEvent
import kotlinx.coroutines.flow.Flow

/** [ConversationsRepository] in memory, for ViewModel and use case tests on the JVM. So far only signing out needs it. */
class TestConversationsRepository : ConversationsRepository {
    override fun conversations(householdId: Int): Flow<List<ConversationEntity>> = notFaked()

    override fun conversation(id: String): Flow<ConversationEntity?> = notFaked()

    override fun messages(conversationId: String): Flow<List<MessageEntity>> = notFaked()

    override suspend fun sync(householdId: Int, forceFull: Boolean) = notFaked()

    override suspend fun loadMessages(householdId: Int, conversationId: String): Boolean = notFaked()

    override suspend fun delete(householdId: Int, conversationId: String) = notFaked()

    override fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?): Flow<StreamEvent> = notFaked()

    override suspend fun afterAnswer(householdId: Int, conversationId: String) = notFaked()

    var wiped = false
        private set

    /** Nothing else is faked yet, so wiping only records that it happened. */
    override suspend fun wipe() {
        wiped = true
    }
}
