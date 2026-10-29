package chat.homebuddy.core.network

import chat.homebuddy.core.testing.ContractFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private fun parse(vararg lines: String): List<String> {
        val parser = SseParser()
        return lines.mapNotNull(parser::feed) + listOfNotNull(parser.finish())
    }

    @Test
    fun `a data line followed by a blank line is one event`() {
        assertEquals(listOf("""{"type":"token"}"""), parse("""data: {"type":"token"}""", ""))
    }

    @Test
    fun `ping comments and other fields are ignored`() {
        assertEquals(listOf("one", "two"), parse(": ping", "", "event: message", "id: 7", "data: one", "", ": ping", "retry: 1000", "data: two", ""))
    }

    @Test
    fun `several data lines join with newlines`() {
        assertEquals(listOf("first\nsecond"), parse("data: first", "data: second", ""))
    }

    @Test
    fun `data without a space after the colon still counts`() {
        assertEquals(listOf("x"), parse("data:x", ""))
    }

    @Test
    fun `an event cut off before its blank line is still delivered at the end`() {
        assertEquals(listOf("last"), parse("data: last"))
    }

    @Test
    fun `blank lines on their own produce nothing`() {
        assertTrue(parse("", "", ": ping", "").isEmpty())
    }
}

class StreamEventTest {
    private fun events(fixture: String): List<StreamEvent> {
        val parser = SseParser()
        val lines = ContractFixtures.read(fixture).lines()
        return (lines.mapNotNull(parser::feed) + listOfNotNull(parser.finish())).mapNotNull(::parseStreamEvent)
    }

    @Test
    fun `the recorded answer stream decodes event by event`() {
        val events = events("query_stream_answer.sse")

        assertEquals(StreamEvent.UserAccepted("How do I clean the dishwasher filter?", false, "thread-2", messageId = 3), events[0])
        assertEquals(StreamEvent.Status("🧭 Routing complete: document_qa_agent"), events[1])
        val final = (events.last() as StreamEvent.Final).result
        assertTrue(final.answer.startsWith("Twist the filter counterclockwise"))
        assertEquals(listOf(SourceDto("bosch-dishwasher-manual", "Bosch Dishwasher Manual", 12, "manual", null)), final.sources)
        assertEquals(false, final.shouldEscalate)
        assertEquals(4, (events.last() as StreamEvent.Final).messageId)
    }

    @Test
    fun `the recorded safety stream carries the escalation`() {
        val final = (events("query_stream_safety.sse").last() as StreamEvent.Final).result

        assertTrue(final.shouldEscalate)
        assertEquals("critical", final.urgencyLevel)
    }

    @Test
    fun `the recorded error stream ends with an error event`() {
        val event = events("query_stream_error.sse").last() as StreamEvent.Error
        assertEquals("Something went wrong while answering. Please try again.", event.message)
        assertTrue(event.messageId != null)
    }

    @Test
    fun `older streams without message IDs and malformed optional IDs remain readable`() {
        assertEquals(StreamEvent.UserAccepted("q", false, "thread"), parseStreamEvent("""{"type":"user_accepted","sanitized_query":"q","conversation_id":"thread"}"""))
        assertEquals(StreamEvent.Final(QueryResultDto("Answer")), parseStreamEvent("""{"type":"final","result":{"answer":"Answer"},"message_id":{}}"""))
    }

    @Test
    fun `tokens decode`() {
        assertEquals(StreamEvent.Token("Clean "), parseStreamEvent("""{"v": 1, "type": "token", "text": "Clean "}"""))
    }

    @Test
    fun `unknown types, malformed JSON and broken finals are skipped, not fatal`() {
        assertNull(parseStreamEvent("""{"v": 2, "type": "suggestion", "text": "new in a later version"}"""))
        assertNull(parseStreamEvent("""{"type": "token", "text": """))
        assertNull(parseStreamEvent("""["not", "an", "object"]"""))
        assertNull(parseStreamEvent("""{"type": "final"}"""))
        assertNull(parseStreamEvent("""{"type": "final", "result": {"no_answer": true}}"""))
    }

    @Test
    fun `fields added by a newer server are ignored`() {
        val event = parseStreamEvent("""{"v": 1, "type": "final", "result": {"answer": "Hi", "brand_new_field": [1, 2]}}""")

        assertEquals("Hi", (event as StreamEvent.Final).result.answer)
    }
}
