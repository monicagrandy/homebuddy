package chat.homebuddy.feature.chat

import chat.homebuddy.core.data.MessageEntity

/** A single snapshot prevents history and the streamed copy from being rendered together. */
internal data class ConversationTimeline(
    val messages: List<MessageEntity> = emptyList(),
    val turns: List<ChatTurn> = emptyList(),
) {
    fun reconcile(): ConversationTimeline {
        val byId = messages.mapNotNull { message -> message.serverId?.let { it to message } }.toMap()
        val resolved = turns.map { turn ->
            // If the connection ended before Final, the assistant immediately following this
            // stable user ID is still its saved answer. Anchor to that ID, using the server's
            // question/answer ordering rather than comparing text.
            val index = messages.indexOfFirst { turn.userMessageId != null && it.serverId == turn.userMessageId && it.role == "user" }
            val following = messages.getOrNull(index + 1).takeIf { index >= 0 && it?.role == "assistant" }
            if (turn.assistantMessageId == null) turn.copy(assistantMessageId = following?.serverId) else turn
        }
        // The saved answer carries the same sources and urgency as the live one, so it can take over.
        val confirmed = resolved.filter { turn ->
            !turn.streaming && turn.assistantMessageId != null && byId[turn.assistantMessageId]?.role == "assistant" &&
                (turn.userMessageId == null || byId[turn.userMessageId]?.role == "user")
        }
        return copy(turns = resolved - confirmed.toSet())
    }

    fun history(): List<HistoryMessage> {
        // A persisted question can arrive before its answer. Keep the complete live turn until
        // both are present, hiding only the exact persisted IDs it currently represents.
        val liveIds = turns.flatMap { listOfNotNull(it.userMessageId, it.assistantMessageId) }.toSet()
        return messages.filterNot { it.serverId != null && it.serverId in liveIds }.map { message ->
            HistoryMessage(
                fromUser = message.role == "user",
                content = message.content,
                id = message.serverId,
                sources = message.sources,
                safety = safetyAlertFor(message.urgencyLevel, message.shouldEscalate),
            )
        }
    }
}
