package chat.homebuddy.core.data.conversations

import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.ConversationSyncEntity
import chat.homebuddy.core.data.MessageEntity
import chat.homebuddy.core.network.ConversationDto
import chat.homebuddy.core.network.MessageDto
import chat.homebuddy.core.network.StreamEvent
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

/**
 * [ConversationsRepository] as a cache. The list syncs incrementally (updated_since) and in full at
 * most once a day, which is also how threads deleted on another device disappear here.
 */
class CachedConversationsRepository(
    private val remote: ConversationsRemoteDataSource,
    private val local: ConversationsLocalDataSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConversationsRepository {
    override fun conversations(householdId: Int): Flow<List<ConversationEntity>> = local.conversations(householdId)

    override fun conversation(id: String): Flow<ConversationEntity?> = local.conversation(id)

    override fun messages(conversationId: String): Flow<List<MessageEntity>> = local.messages(conversationId)

    override suspend fun sync(householdId: Int, forceFull: Boolean) {
        val state = local.syncState(householdId)
        val full = forceFull || state == null || clock() - state.lastFullSyncAt >= FULL_SYNC_EVERY_MILLIS
        val fetched = remote.conversations(householdId, updatedSince = if (full) null else state?.newestUpdatedAt)
        val entities = fetched.map { it.toEntity() }
        if (full) local.replaceAll(householdId, entities) else local.upsert(entities)
        val newest = (entities.map { it.updatedAt } + listOfNotNull(state?.newestUpdatedAt.takeUnless { full })).maxOrNull()
        local.saveSyncState(
            ConversationSyncEntity(
                householdId = householdId,
                lastFullSyncAt = if (full) clock() else state!!.lastFullSyncAt,
                newestUpdatedAt = newest,
            ),
        )
    }

    override suspend fun loadMessages(householdId: Int, conversationId: String): Boolean {
        val messages = remote.messages(householdId, conversationId)
        if (messages == null) {
            local.delete(conversationId)
            return false
        }
        local.replaceMessages(conversationId, messages.mapIndexed { index, message -> message.toEntity(conversationId, index) })
        return true
    }

    override suspend fun delete(householdId: Int, conversationId: String) {
        remote.delete(householdId, conversationId)
        local.delete(conversationId)
    }

    override fun ask(householdId: Int, conversationId: String, question: String, zipCode: String?): Flow<StreamEvent> =
        remote.ask(householdId, conversationId, question, zipCode)

    override suspend fun afterAnswer(householdId: Int, conversationId: String) {
        // The server has saved the answer, so a refresh that fails here is caught up by the next sync.
        try {
            sync(householdId)
            loadMessages(householdId, conversationId)
        } catch (_: IOException) {
        } catch (_: HttpException) {
        }
    }

    override suspend fun wipe() {
        local.clear()
    }

    companion object {
        const val FULL_SYNC_EVERY_MILLIS = 24 * 60 * 60 * 1000L
    }
}

internal fun ConversationDto.toEntity() =
    ConversationEntity(id = id, householdId = householdId, title = title, createdAt = createdAt, updatedAt = updatedAt)

internal fun MessageDto.toEntity(conversationId: String, position: Int) = MessageEntity(
    conversationId = conversationId,
    position = position,
    role = role,
    content = content,
    createdAt = createdAt,
    serverId = id,
    sources = sources,
    urgencyLevel = urgencyLevel,
    shouldEscalate = shouldEscalate,
)
