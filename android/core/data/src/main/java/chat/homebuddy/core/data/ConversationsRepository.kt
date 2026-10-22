package chat.homebuddy.core.data

import chat.homebuddy.core.network.ConversationDto
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.MessageDto
import chat.homebuddy.core.network.QueryRequestDto
import chat.homebuddy.core.network.QueryStreamClient
import chat.homebuddy.core.network.StreamEvent
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

/**
 * Chat threads across devices. The server is the source of truth: the list syncs incrementally
 * (updated_since) and in full at most once a day, which is also how threads deleted on another
 * device disappear here. Nothing is edited offline, so there is never anything to merge.
 */
class ConversationsRepository(
    private val api: HomeBuddyApi,
    private val streams: QueryStreamClient,
    private val dao: ConversationDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun conversations(householdId: Int): Flow<List<ConversationEntity>> = dao.observe(householdId)

    fun conversation(id: String): Flow<ConversationEntity?> = dao.observeOne(id)

    fun messages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    suspend fun sync(householdId: Int, forceFull: Boolean = false) {
        val state = dao.syncState(householdId)
        val full = forceFull || state == null || clock() - state.lastFullSyncAt >= FULL_SYNC_EVERY_MILLIS
        val fetched = api.conversations(householdId, updatedSince = if (full) null else state?.newestUpdatedAt)
        val entities = fetched.map { it.toEntity() }
        if (full) dao.replaceAll(householdId, entities) else dao.upsert(entities)
        val newest = (entities.map { it.updatedAt } + listOfNotNull(state?.newestUpdatedAt.takeUnless { full })).maxOrNull()
        dao.saveSyncState(
            ConversationSyncEntity(
                householdId = householdId,
                lastFullSyncAt = if (full) clock() else state!!.lastFullSyncAt,
                newestUpdatedAt = newest,
            ),
        )
    }

    /** Loads a thread's messages. A 404 means it was deleted elsewhere (or expired), so it goes here too. */
    suspend fun loadMessages(householdId: Int, conversationId: String): Boolean {
        val messages = try {
            api.conversationMessages(conversationId, householdId)
        } catch (error: HttpException) {
            if (error.code() == 404) {
                dao.delete(conversationId)
                return false
            }
            throw error
        }
        dao.replaceMessages(conversationId, messages.mapIndexed { index, message -> message.toEntity(conversationId, index) })
        return true
    }

    suspend fun delete(householdId: Int, conversationId: String) {
        val response = api.deleteConversation(conversationId, householdId)
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
        dao.delete(conversationId)
    }

    /** Streams an answer; afterwards the thread is refreshed from the server, which saved both sides. */
    fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?): Flow<StreamEvent> =
        streams.stream(QueryRequestDto(question = question, sessionId = conversationId, householdId = householdId, householdZipCode = zipCode))

    suspend fun afterAnswer(householdId: Int, conversationId: String) {
        runCatching {
            sync(householdId)
            loadMessages(householdId, conversationId)
        }
    }

    suspend fun wipe() {
        dao.clear()
    }

    companion object {
        const val FULL_SYNC_EVERY_MILLIS = 24 * 60 * 60 * 1000L
    }
}

internal fun ConversationDto.toEntity() =
    ConversationEntity(id = id, householdId = householdId, title = title, createdAt = createdAt, updatedAt = updatedAt)

internal fun MessageDto.toEntity(conversationId: String, position: Int) =
    MessageEntity(conversationId = conversationId, position = position, role = role, content = content, createdAt = createdAt)
