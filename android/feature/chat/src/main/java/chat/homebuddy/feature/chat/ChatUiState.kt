package chat.homebuddy.feature.chat

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

const val RETENTION_NOTE = "Chats are kept for 90 days after your last message."

data class ConversationItem(val id: String, val title: String, val updatedLabel: String)

data class ConversationListUiState(
    val items: List<ConversationItem> = emptyList(),
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val message: String? = null,
)

data class HistoryMessage(val fromUser: Boolean, val content: String)

data class ConversationUiState(
    val title: String = "New question",
    val history: List<HistoryMessage> = emptyList(),
    val turns: List<ChatTurn> = emptyList(),
    val draft: String = "",
    val listening: Boolean = false,
    val voiceAvailable: Boolean = true,
    val message: String? = null,
) {
    val answering: Boolean get() = turns.lastOrNull()?.streaming == true
    val canSend: Boolean get() = draft.isNotBlank() && !answering
}

/** "Today, 3:05 PM", "Oct 21" or "Oct 21, 2025" for a server timestamp, in the phone's time zone. */
fun shortDate(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) } // SQLite in local dev omits the offset
        .getOrNull() ?: return ""
    val local = instant.atZone(zone)
    val pattern = when {
        local.toLocalDate() == today -> "'Today', h:mm a"
        local.year == today.year -> "MMM d"
        else -> "MMM d, yyyy"
    }
    return local.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
}
