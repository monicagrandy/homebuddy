package chat.homebuddy.feature.chat

import chat.homebuddy.app.R
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import chat.homebuddy.core.network.SourceDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ConversationItem(val id: String, val title: String, val updatedLabel: UiText)

data class ConversationListUiState(
    val items: List<ConversationItem> = emptyList(),
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val message: UiText? = null,
)

/** A saved message. An answer keeps the sources and safety card it had when it was live. */
data class HistoryMessage(
    val fromUser: Boolean,
    val content: String,
    val id: Int? = null,
    val sources: List<SourceDto> = emptyList(),
    val safety: SafetyAlert? = null,
)

data class ConversationUiState(
    /** Null until the first question: the screen says "New question". */
    val title: String? = null,
    val history: List<HistoryMessage> = emptyList(),
    val turns: List<ChatTurn> = emptyList(),
    val draft: String = "",
    val listening: Boolean = false,
    val voiceAvailable: Boolean = true,
    val message: UiText? = null,
    val requestInFlight: Boolean = false,
) {
    val answering: Boolean get() = requestInFlight || turns.lastOrNull()?.streaming == true
    val canSend: Boolean get() = draft.isNotBlank() && !answering
}

/** "Today, 3:05 PM", "Oct 21" or "Oct 21, 2025" for a server timestamp, in the phone's time zone. */
fun shortDate(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): UiText {
    val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) } // SQLite in local dev omits the offset
        .getOrNull() ?: return "".asUiText()
    val local = instant.atZone(zone)
    fun format(pattern: String) = local.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
    return when {
        local.toLocalDate() == today -> uiText(R.string.chat_updated_today, format("h:mm a"))
        local.year == today.year -> format("MMM d").asUiText()
        else -> format("MMM d, yyyy").asUiText()
    }
}
