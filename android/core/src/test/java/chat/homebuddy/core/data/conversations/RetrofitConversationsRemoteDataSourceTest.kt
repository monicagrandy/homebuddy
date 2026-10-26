package chat.homebuddy.core.data.conversations

import chat.homebuddy.core.data.apiFor
import chat.homebuddy.core.data.json
import chat.homebuddy.core.network.ApiConfig
import chat.homebuddy.core.network.QueryStreamClient
import chat.homebuddy.core.network.StreamEvent
import chat.homebuddy.core.testing.ContractFixtures
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class RetrofitConversationsRemoteDataSourceTest {
    private val server = MockWebServer()
    private lateinit var remote: RetrofitConversationsRemoteDataSource

    @Before
    fun setUp() {
        server.start()
        val streams = QueryStreamClient(OkHttpClient(), ApiConfig(server.url("/").toString(), "android/test"))
        remote = RetrofitConversationsRemoteDataSource(apiFor(server), streams)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun threads() = json(
        """[{"id": "a", "household_id": 7, "title": "Dishwasher filter", "created_at": "2026-10-01T00:00:00+00:00", "updated_at": "2026-10-20T10:00:00+00:00"}]""",
    )

    private fun <T> assertHttpError(code: Int, call: suspend () -> T) = runTest {
        try {
            call()
            fail("expected an HttpException")
        } catch (error: HttpException) {
            assertEquals(code, error.code())
        }
    }

    @Test
    fun `the first listing asks for everything`() = runTest {
        server.enqueue(threads())

        assertEquals(listOf("a"), remote.conversations(7, updatedSince = null).map { it.id })
        assertEquals("/conversations?household_id=7&limit=100", server.takeRequest().path)
    }

    @Test
    fun `a later listing asks only for what changed`() = runTest {
        server.enqueue(threads())

        remote.conversations(7, updatedSince = "2026-10-20T10:00:00+00:00")

        assertTrue(server.takeRequest().path!!.contains("updated_since=2026-10-20T10%3A00%3A00%2B00%3A00"))
    }

    @Test
    fun `a thread's messages`() = runTest {
        server.enqueue(json("""[{"id": 1, "household_id": 7, "session_id": "a", "role": "user", "content": "Q", "created_at": null}]"""))

        assertEquals(listOf("Q"), remote.messages(7, "a")?.map { it.content })
        assertEquals("/conversations/a/messages?household_id=7", server.takeRequest().path)
    }

    @Test
    fun `a thread that's gone comes back as null`() = runTest {
        server.enqueue(json("""{"detail": "Conversation not found."}""", code = 404))

        assertNull(remote.messages(7, "a"))
    }

    @Test
    fun `any other failure loading messages throws`() {
        server.enqueue(json("""{"detail": "Something went wrong."}""", code = 500))

        assertHttpError(500) { remote.messages(7, "a") }
    }

    @Test
    fun `deleting a thread, including one another device already deleted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(json("""{"detail": "Conversation not found."}""", code = 404))

        remote.delete(7, "a")
        remote.delete(7, "b")

        val request = server.takeRequest()
        assertEquals("DELETE /conversations/a?household_id=7", "${request.method} ${request.path}")
    }

    @Test
    fun `any other failed delete throws`() {
        server.enqueue(json("""{"detail": "Something went wrong."}""", code = 500))

        assertHttpError(500) { remote.delete(7, "a") }
    }

    @Test
    fun `asking streams the answer into that thread`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ContractFixtures.read("query_stream_answer.sse")))

        val events = remote.ask(7, "a", "How do I clean the dishwasher filter?", "80202").toList()

        assertTrue(events.last() is StreamEvent.Final)
        assertEquals(
            """{"question":"How do I clean the dishwasher filter?","session_id":"a","household_id":7,"household_zip_code":"80202"}""",
            server.takeRequest().body.readUtf8(),
        )
    }
}
