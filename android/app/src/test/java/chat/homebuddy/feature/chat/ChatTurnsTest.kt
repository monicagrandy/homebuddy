package chat.homebuddy.feature.chat

import chat.homebuddy.core.network.QueryResultDto
import chat.homebuddy.core.network.SourceDto
import chat.homebuddy.core.network.StreamEvent
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnsTest {
    private val source = SourceDto("bosch", "Bosch Dishwasher Manual", 12, "manual", null)

    private fun result(answer: String = "Twist the filter out.", urgency: String? = "low", escalate: Boolean = false) =
        QueryResultDto(answer = answer, urgencyLevel = urgency, shouldEscalate = escalate, sources = listOf(source))

    @Test
    fun `status, then streamed tokens, then the final answer replaces them`() {
        val turn = listOf(
            StreamEvent.UserAccepted("q", false, "thread"),
            StreamEvent.Status("🧭 Routing complete: document_qa_agent"),
            StreamEvent.Token("Twist the "),
            StreamEvent.Token("filter."),
        ).fold(ChatTurn("How do I clean the filter?")) { turn, event -> turn.reduce(event) }

        assertEquals("Twist the filter.", turn.answer)
        assertNull(turn.status)
        assertTrue(turn.streaming)

        val done = turn.reduce(StreamEvent.Final(result(answer = "Twist the filter out [redacted].")))

        assertEquals("Twist the filter out [redacted].", done.answer)
        assertFalse(done.streaming)
        assertEquals(listOf(source), done.sources)
        assertNull(done.safety)
    }

    @Test
    fun `status lines lose their emoji and stop once text is arriving`() {
        val turn = ChatTurn("q").reduce(StreamEvent.Status("✨ Writing the final response."))
        assertEquals("Writing the final response.", turn.status)

        val withText = turn.reduce(StreamEvent.Token("Hi")).reduce(StreamEvent.Status("✨ Final response is ready."))
        assertNull(withText.status)
    }

    @Test
    fun `an error event ends the turn with the message`() {
        val turn = ChatTurn("q").reduce(StreamEvent.Error("Something went wrong while answering. Please try again."))

        assertFalse(turn.streaming)
        assertEquals("Something went wrong while answering. Please try again.", turn.error)
    }

    @Test
    fun `a stream that ends without an answer says the connection dropped`() {
        val turn = ChatTurn("q").reduce(StreamEvent.Token("Twist")).finish()

        assertEquals(CONNECTION_DROPPED, turn.error)
        assertEquals(turn, turn.finish())
    }

    @Test
    fun `stopping keeps what arrived and explains the answer is still saved`() {
        val stopped = ChatTurn("q").reduce(StreamEvent.Token("Twist")).stop()

        assertEquals("Twist", stopped.answer)
        assertEquals(STOPPED, stopped.note)
        assertFalse(stopped.streaming)
    }

    @Test
    fun `urgent hazards raise the safety card`() {
        assertEquals(SafetyAlert(critical = true), safetyAlertFor(result(urgency = "critical")))
        assertEquals(SafetyAlert(critical = true), safetyAlertFor(result(urgency = "medium", escalate = true)))
        assertEquals(SafetyAlert(critical = false), safetyAlertFor(result(urgency = "HIGH")))
        assertNull(safetyAlertFor(result(urgency = "medium")))
        assertNull(safetyAlertFor(result(urgency = null)))
    }

    @Test
    fun `dictation adds to the draft and never sends`() {
        assertEquals("My dishwasher is leaking", dictatedDraft("My dishwasher ", "is leaking"))
        assertEquals("is leaking", dictatedDraft("", " is leaking "))
        assertEquals("Typed", dictatedDraft("Typed", ""))
    }

    @Test
    fun `dates read naturally in the phone's time zone`() {
        val denver = ZoneId.of("America/Denver")
        val today = LocalDate.of(2026, 10, 21)

        assertEquals("Today, 9:05 AM", shortDate("2026-10-21T15:05:00+00:00", denver, today))
        assertEquals("Oct 2", shortDate("2026-10-02T15:05:00Z", denver, today))
        assertEquals("Dec 30, 2025", shortDate("2025-12-30T15:05:00", denver, today))
        assertEquals("", shortDate("not a date", denver, today))
    }
}

class MarkdownTest {
    @Test
    fun `answers become paragraphs, headings and lists`() {
        val blocks = parseMarkdown(
            """
            ## Clean the filter
            First, turn off the dishwasher.
            It takes a minute.

            1. Twist the filter **counterclockwise**.
            2) Rinse it under *warm* water.
            - Don't use `bleach`.
            * See [the manual](https://example.com) for photos.
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "Heading:Clean the filter",
                "Paragraph:First, turn off the dishwasher. It takes a minute.",
                "Numbered:1:Twist the filter counterclockwise.",
                "Numbered:2:Rinse it under warm water.",
                "Bullet:Don't use bleach.",
                "Bullet:See the manual for photos.",
            ),
            blocks.map {
                when (it) {
                    is MarkdownBlock.Heading -> "Heading:${it.text}"
                    is MarkdownBlock.Paragraph -> "Paragraph:${it.text}"
                    is MarkdownBlock.Numbered -> "Numbered:${it.number}:${it.text}"
                    is MarkdownBlock.Bullet -> "Bullet:${it.text}"
                }
            },
        )
    }

    @Test
    fun `bold text is styled, not shown with asterisks`() {
        val text = inline("Turn **off** the power")

        assertEquals("Turn off the power", text.text)
        assertEquals(1, text.spanStyles.size)
    }

    @Test
    fun `plain text and an empty answer are fine`() {
        assertEquals(1, parseMarkdown("Just text").size)
        assertTrue(parseMarkdown("").isEmpty())
    }
}
