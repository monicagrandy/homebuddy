package chat.homebuddy.core.data

import chat.homebuddy.core.network.StreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * Chat threads across devices. The server is the source of truth, and nothing is edited offline,
 * so there is never anything to merge.
 */
interface ConversationsRepository {
    fun conversations(householdId: Int): Flow<List<ConversationEntity>>

    fun conversation(id: String): Flow<ConversationEntity?>

    fun messages(conversationId: String): Flow<List<MessageEntity>>

    suspend fun sync(householdId: Int, forceFull: Boolean = false)

    /** Loads a thread's messages. A 404 means it was deleted elsewhere (or expired), so it goes here too. */
    suspend fun loadMessages(householdId: Int, conversationId: String): Boolean

    suspend fun delete(householdId: Int, conversationId: String)

    /**
     * Streams an answer; afterwards the thread is refreshed from the server, which saved both sides.
     *
     * Not a suspend function because it returns a cold Flow, and making one does no work.
     */
    fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?): Flow<StreamEvent>

    suspend fun afterAnswer(householdId: Int, conversationId: String)

    suspend fun wipe()
}
