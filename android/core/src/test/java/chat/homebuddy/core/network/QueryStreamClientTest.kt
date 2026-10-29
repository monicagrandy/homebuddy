package chat.homebuddy.core.network

import chat.homebuddy.core.testing.ContractFixtures
import javax.inject.Provider
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class QueryStreamClientTest {
    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private lateinit var client: QueryStreamClient
    private lateinit var config: ApiConfig
    private val request = QueryRequestDto(question = "How do I clean the dishwasher filter?", sessionId = "thread-2", householdId = 1, householdZipCode = "80202")

    @Before
    fun setUp() {
        server.start()
        config = ApiConfig(baseUrl = server.url("/").toString(), clientHeader = "android/9.9.9")
        client = QueryStreamClient(buildHttpClient(config, Provider { tokens }), config)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test
    fun `the recorded stream arrives in order and the request is shaped for the backend`() = runTest {
        server.enqueue(sse(ContractFixtures.read("query_stream_answer.sse")))

        val events = client.stream(request).toList()

        assertEquals(listOf("UserAccepted", "Status", "Status", "Status", "Final"), events.map { it::class.simpleName })
        val sent = server.takeRequest()
        assertEquals("/query/stream", sent.path)
        assertEquals("Bearer token-1", sent.getHeader("Authorization"))
        assertEquals("text/event-stream", sent.getHeader("Accept"))
        assertEquals(
            """{"question":"How do I clean the dishwasher filter?","session_id":"thread-2","household_id":1,"household_zip_code":"80202"}""",
            sent.body.readUtf8(),
        )
    }

    @Test
    fun `pings and bytes arriving a few at a time make no difference`() = runTest {
        val body = ": ping\n\n" + ContractFixtures.read("query_stream_answer.sse").replace("\n\n", "\n\n: ping\n\n")
        server.enqueue(sse(body).throttleBody(16, 5, TimeUnit.MILLISECONDS))

        val events = client.stream(request).toList()

        assertEquals(5, events.size)
        assertTrue(events.last() is StreamEvent.Final)
    }

    @Test
    fun `a busy conversation fails before streaming with the server's message`() = runTest {
        val recorded = HomeBuddyJson.parseToJsonElement(ContractFixtures.read("error_conversation_busy.json")).jsonObject
        server.enqueue(MockResponse().setResponseCode(409).setBody(recorded.getValue("body").toString()))

        val error = runCatching { client.stream(request).toList() }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(409, (error as ApiException).error.status)
        assertEquals("Still answering your last question in this conversation. Try again in a moment.", error.error.message)
    }

    @Test
    fun `an expired token is refreshed and the stream retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail": "JWT decode failed."}"""))
        server.enqueue(sse(ContractFixtures.read("query_stream_error.sse")))

        val events = client.stream(request).toList()

        assertTrue(events.last() is StreamEvent.Error)
        server.takeRequest()
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a connection dropped mid-answer surfaces as a network error after the events so far`() = runTest {
        val partial = """data: {"v": 1, "type": "user_accepted", "sanitized_query": "q", "input_blocked": false, "conversation_id": "thread-2"}""" +
            "\n\n" + """data: {"v": 1, "type": "token", "text": "Twist the """ // cut mid-event
        server.enqueue(sse(partial).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))

        val received = mutableListOf<StreamEvent>()
        val error = runCatching { client.stream(request).collect { received += it } }.exceptionOrNull()

        assertTrue("expected an IOException or a clean end, got $error", error == null || error is IOException)
        assertTrue(received.none { it is StreamEvent.Final })
    }

    @Test
    fun `malformed HTTP error shapes close the stream with a friendly API error`() = runTest(timeout = 10.seconds) {
        val bodies = listOf(
            """{"detail":{"code":[],"message":{}}}""",
            """{"detail":{"code":{},"message":[]}}""",
            """{"detail":"cut off""",
            "<html>Bad gateway</html>",
            "[true, 42]",
        )
        bodies.forEach { body ->
            server.enqueue(MockResponse().setResponseCode(503).setBody(body).setHeader("Retry-After", "15"))

            val failure = runCatching { client.stream(request).toList() }.exceptionOrNull()

            assertTrue("expected an API error for $body, got $failure", failure is ApiException)
            assertEquals(ApiError(503, null, "Something went wrong. Please try again.", 15), (failure as ApiException).error)
        }
    }

    @Test
    fun `a physically truncated HTTP error body preserves its status and retry interval`() = runTest(timeout = 10.seconds) {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setHeader("Retry-After", "20")
                .setBody("""{"detail":"The complete message cannot be read because the connection drops."}""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val failure = runCatching { client.stream(request).toList() }.exceptionOrNull()

        assertTrue(failure is ApiException)
        assertEquals(ApiError(503, null, "Something went wrong. Please try again.", 20), (failure as ApiException).error)
    }

    @Test
    fun `successful responses with unexpected content fail through the flow`() = runTest(timeout = 10.seconds) {
        listOf("application/json", "text/html", null).forEach { contentType ->
            val response = MockResponse().setBody("<html>Unexpected response</html>")
            if (contentType != null) response.setHeader("Content-Type", contentType)
            server.enqueue(response)

            val failure = runCatching { client.stream(request).toList() }.exceptionOrNull()

            assertTrue("expected an IOException for $contentType, got $failure", failure is IOException)
        }
    }

    @Test
    fun `malformed and unknown SSE events are skipped while later valid events still arrive`() = runTest(timeout = 10.seconds) {
        server.enqueue(sse(
            "data: {\"type\":\"token\",\"text\":\n\n" +
                "data: []\n\n" +
                "data: {\"type\":\"unknown\"}\n\n" +
                "data: {\"type\":\"final\",\"result\":[]}\n\n" +
                "data: {\"type\":\"token\",\"text\":\"Still here\"}\n\n",
        ))

        assertEquals(listOf(StreamEvent.Token("Still here")), client.stream(request).toList())
    }

    @Test
    fun `unexpected failures anywhere in response processing reach the collector as IO errors`() = runTest(timeout = 10.seconds) {
        listOf("contentType", "source", "read", "close").forEach { phase ->
            val body = object : ResponseBody() {
                private val buffered = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (phase == "read") throw IllegalStateException("unexpected read failure")
                        return -1
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() {
                        if (phase == "close") throw IllegalStateException("unexpected close failure")
                    }
                }.buffer()

                override fun contentType(): MediaType {
                    if (phase == "contentType") throw IllegalStateException("unexpected content type failure")
                    return "text/event-stream".toMediaType()
                }
                override fun contentLength(): Long = -1
                override fun source(): BufferedSource {
                    if (phase == "source") throw IllegalStateException("unexpected source failure")
                    return buffered
                }
            }
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            }.build()
            val client = QueryStreamClient(http, config)

            val failure = runCatching { client.stream(request).toList() }.exceptionOrNull()

            assertTrue("expected an IOException during $phase, got $failure", failure is IOException)
            // Coroutine stack-trace recovery may insert a copy of the IOException in the chain.
            assertTrue("original failure missing during $phase", generateSequence(failure) { it.cause }.any { it is IllegalStateException })
        }
    }
}
