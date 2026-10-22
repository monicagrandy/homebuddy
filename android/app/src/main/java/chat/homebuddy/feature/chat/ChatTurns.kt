package chat.homebuddy.feature.chat

import chat.homebuddy.app.R
import chat.homebuddy.core.network.QueryResultDto
import chat.homebuddy.core.network.SourceDto
import chat.homebuddy.core.network.StreamEvent
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText

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
)

/** Urgent hazards (a gas smell, smoke, flooding) get a prominent card with a Call 911 button. */
fun safetyAlertFor(result: QueryResultDto): SafetyAlert? {
    val urgency = result.urgencyLevel?.lowercase()
    if (!result.shouldEscalate && urgency != "high" && urgency != "critical") return null
    return SafetyAlert(critical = result.shouldEscalate || urgency == "critical")
}

fun ChatTurn.reduce(event: StreamEvent): ChatTurn = when (event) {
    is StreamEvent.UserAccepted -> this
    is StreamEvent.Status -> if (answer.isEmpty()) copy(status = event.message.withoutLeadingEmoji().asUiText()) else this
    is StreamEvent.Token -> copy(answer = answer + event.text, status = null)
    // The final text replaces what streamed: the output guardrail may have rewritten it.
    is StreamEvent.Final -> copy(
        answer = event.result.answer,
        status = null,
        streaming = false,
        sources = event.result.sources,
        safety = safetyAlertFor(event.result),
    )
    is StreamEvent.Error -> copy(status = null, streaming = false, error = event.message.asUiText())
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
