package chat.homebuddy.feature.chat

import chat.homebuddy.app.R
import chat.homebuddy.core.network.QueryResultDto
import chat.homebuddy.core.network.SourceDto
import chat.homebuddy.core.network.StreamEvent
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import java.util.UUID

data class SafetyAlert(val critical: Boolean)

/** One question and its answer as it streams in. */
data class ChatTurn(
    val question: String,
    val answer: String = "",
    /** What the server is doing ("Routing complete…"); cleared once text arrives. */
    val status: UiText? = uiText(R.string.chat_thinking),
    val streaming: Boolean = true,
    val sources: List<SourceDto> = emptyList(),
    val safety: SafetyAlert? = null,
    val error: UiText? = null,
    val note: UiText? = null,
    val id: String = UUID.randomUUID().toString(),
    val userMessageId: Int? = null,
    val assistantMessageId: Int? = null,
)

fun safetyAlertFor(result: QueryResultDto): SafetyAlert? = safetyAlertFor(result.urgencyLevel, result.shouldEscalate)

/** Urgent hazards (a gas smell, smoke, flooding) get a prominent card with a Call 911 button, live or reopened. */
fun safetyAlertFor(urgencyLevel: String?, shouldEscalate: Boolean): SafetyAlert? {
    val urgency = urgencyLevel?.lowercase()
    if (!shouldEscalate && urgency != "high" && urgency != "critical") return null
    return SafetyAlert(critical = shouldEscalate || urgency == "critical")
}

fun ChatTurn.reduce(event: StreamEvent): ChatTurn = when (event) {
    is StreamEvent.UserAccepted -> copy(userMessageId = event.messageId ?: userMessageId)
    is StreamEvent.Status -> if (answer.isEmpty()) copy(status = event.message.withoutLeadingEmoji().asUiText()) else this
    is StreamEvent.Token -> copy(answer = answer + event.text, status = null)
    // The final text replaces what streamed: the output guardrail may have rewritten it.
    is StreamEvent.Final -> copy(
        answer = event.result.answer,
        status = null,
        streaming = false,
        sources = event.result.sources,
        safety = safetyAlertFor(event.result),
        assistantMessageId = event.messageId ?: assistantMessageId,
    )
    is StreamEvent.Error -> copy(status = null, streaming = false, error = event.message.asUiText(), assistantMessageId = event.messageId ?: assistantMessageId)
}

/** The stream ended. Without a final answer or an error, the connection was cut. */
fun ChatTurn.finish(): ChatTurn = if (!streaming) this else copy(status = null, streaming = false, error = uiText(R.string.chat_connection_dropped))

fun ChatTurn.stop(): ChatTurn = if (!streaming) this else copy(status = null, streaming = false, note = uiText(R.string.chat_stopped))

/** "🧭 Routing complete" reads as "Routing complete"; screen readers would announce the emoji. */
internal fun String.withoutLeadingEmoji(): String {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        if (Character.isLetterOrDigit(codePoint)) break
        index += Character.charCount(codePoint)
    }
    return substring(index).ifEmpty { this }
}

/** Dictation adds to what's already typed. It only ever changes the draft; sending is always a tap. */
fun dictatedDraft(before: String, heard: String): String =
    listOf(before.trimEnd(), heard.trim()).filter { it.isNotEmpty() }.joinToString(" ")
