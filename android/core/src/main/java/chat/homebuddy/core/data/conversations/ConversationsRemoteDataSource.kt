package chat.homebuddy.core.data.conversations

import chat.homebuddy.core.network.ConversationDto
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.MessageDto
import chat.homebuddy.core.network.QueryRequestDto
import chat.homebuddy.core.network.QueryStreamClient
import chat.homebuddy.core.network.StreamEvent
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

/** Chat threads on the server. Failures throw [HttpException], or [IOException] offline. */
interface ConversationsRemoteDataSource {
    /** A home's threads, newest first. With [updatedSince] (ISO 8601), only the ones changed since. */
    suspend fun conversations(householdId: Int, updatedSince: String?): List<ConversationDto>

    /** Null when the thread is gone (404): deleted on another device, or expired. */
    suspend fun messages(householdId: Int, conversationId: String): List<MessageDto>?

    /** Deleting a thread that's already gone (404) counts as done. */
    suspend fun delete(householdId: Int, conversationId: String)

    /**
     * Streams an answer. It's a cold Flow: the request starts when it's collected and runs on
     * OkHttp's threads, so collecting on the main thread never blocks it.
     */
    fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?): Flow<StreamEvent>
}

class RetrofitConversationsRemoteDataSource(
    private val api: HomeBuddyApi,
    private val streams: QueryStreamClient,
) : ConversationsRemoteDataSource {
    override suspend fun conversations(householdId: Int, updatedSince: String?) = api.conversations(householdId, updatedSince)

    override suspend fun messages(householdId: Int, conversationId: String): List<MessageDto>? = try {
        api.conversationMessages(conversationId, householdId)
    } catch (error: HttpException) {
        if (error.code() == 404) null else throw error
    }

    override suspend fun delete(householdId: Int, conversationId: String) {
        val response = api.deleteConversation(conversationId, householdId)
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
    }

    override fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?) =
        streams.stream(QueryRequestDto(question = question, sessionId = conversationId, householdId = householdId, householdZipCode = zipCode))
}
