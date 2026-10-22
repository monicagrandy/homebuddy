package chat.homebuddy.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ConversationDto(
    /** The session id the client chose when the thread started. */
    val id: String,
    @SerialName("household_id") val householdId: Int,
    val title: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class MessageDto(
    val id: Int,
    @SerialName("household_id") val householdId: Int,
    @SerialName("session_id") val sessionId: String,
    /** "user" or "assistant". */
    val role: String,
    val content: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class QueryRequestDto(
    val question: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("household_id") val householdId: Int,
    @SerialName("household_zip_code") val householdZipCode: String? = null,
)

@Serializable
data class SourceDto(
    @SerialName("entry_id") val entryId: String,
    @SerialName("display_name") val displayName: String,
    val page: Int? = null,
    @SerialName("doc_type") val docType: String? = null,
    /** Set for documents added by link. */
    val url: String? = null,
)

/** The parts of a finished answer the app uses; the server sends more (ignored). */
@Serializable
data class QueryResultDto(
    val answer: String,
    @SerialName("sanitized_query") val sanitizedQuery: String? = null,
    @SerialName("input_blocked") val inputBlocked: Boolean = false,
    val route: List<String> = emptyList(),
    @SerialName("urgency_level") val urgencyLevel: String? = null,
    @SerialName("should_escalate") val shouldEscalate: Boolean = false,
    val sources: List<SourceDto> = emptyList(),
)

/** One event of a streamed answer (POST /query/stream). */
sealed interface StreamEvent {
    data class UserAccepted(val sanitizedQuery: String?, val inputBlocked: Boolean, val conversationId: String?) : StreamEvent
    data class Status(val message: String) : StreamEvent
    data class Token(val text: String) : StreamEvent
    data class Final(val result: QueryResultDto) : StreamEvent
    data class Error(val message: String) : StreamEvent
}

/**
 * Decodes one event's JSON. Returns null for event types this version doesn't know and for
 * malformed events, so a newer server never breaks an older app.
 */
fun parseStreamEvent(data: String, json: Json = HomeBuddyJson): StreamEvent? {
    val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    fun text(name: String) = event[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    return runCatching {
        when (text("type")) {
            "user_accepted" -> StreamEvent.UserAccepted(
                sanitizedQuery = text("sanitized_query"),
                inputBlocked = text("input_blocked") == "true",
                conversationId = text("conversation_id"),
            )
            "status" -> text("message")?.let(StreamEvent::Status)
            "token" -> text("text")?.let(StreamEvent::Token)
            "final" -> StreamEvent.Final(json.decodeFromJsonElement(QueryResultDto.serializer(), event.getValue("result") as JsonObject))
            "error" -> StreamEvent.Error(text("message") ?: "Something went wrong while answering. Please try again.")
            else -> null
        }
    }.getOrNull()
}
