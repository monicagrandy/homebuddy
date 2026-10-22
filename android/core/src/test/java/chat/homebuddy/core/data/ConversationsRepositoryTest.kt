package chat.homebuddy.core.data

import chat.homebuddy.core.network.ApiConfig
import chat.homebuddy.core.network.QueryStreamClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationsRepositoryTest {
    private val server = MockWebServer()
    private var now = 1_000_000L
    private lateinit var database: HomeBuddyDatabase
    private lateinit var repository: ConversationsRepository

    @Before
    fun setUp() {
        server.start()
        database = inMemoryDatabase()
        val streams = QueryStreamClient(OkHttpClient(), ApiConfig(server.url("/").toString(), "android/test"))
        repository = ConversationsRepository(apiFor(server), streams, database.conversations(), clock = { now })
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private fun conversations(vararg items: Pair<String, String>) = json(
        items.joinToString(prefix = "[", postfix = "]") { (id, updatedAt) ->
            """{"id": "$id", "household_id": 7, "title": "Title $id", "created_at": "2026-10-01T00:00:00+00:00", "updated_at": "$updatedAt"}"""
        },
    )

    private suspend fun cachedIds() = repository.conversations(7).first().map { it.id }

    @Test
    fun `the first sync fetches everything`() = runTest {
        server.enqueue(conversations("a" to "2026-10-20T10:00:00+00:00", "b" to "2026-10-19T10:00:00+00:00"))

        repository.sync(7)

        assertEquals("/conversations?household_id=7&limit=100", server.takeRequest().path)
        assertEquals(listOf("a", "b"), cachedIds())
    }

    @Test
    fun `later syncs ask only for changes and merge them in`() = runTest {
        server.enqueue(conversations("a" to "2026-10-20T10:00:00+00:00", "b" to "2026-10-19T10:00:00+00:00"))
        repository.sync(7)
        server.takeRequest()
        server.enqueue(conversations("b" to "2026-10-21T09:00:00+00:00"))

        repository.sync(7)

        assertTrue(server.takeRequest().path!!.contains("updated_since=2026-10-20T10%3A00%3A00%2B00%3A00"))
        assertEquals(listOf("b", "a"), cachedIds())
    }

    @Test
    fun `once a day the full list replaces the cache, dropping threads deleted elsewhere`() = runTest {
        server.enqueue(conversations("a" to "2026-10-20T10:00:00+00:00", "deleted-on-the-web" to "2026-10-19T10:00:00+00:00"))
        repository.sync(7)
        server.takeRequest()
        now += ConversationsRepository.FULL_SYNC_EVERY_MILLIS
        server.enqueue(conversations("a" to "2026-10-20T10:00:00+00:00"))

        repository.sync(7)

        assertFalse(server.takeRequest().path!!.contains("updated_since"))
        assertEquals(listOf("a"), cachedIds())
    }

    @Test
    fun `messages load in order`() = runTest {
        server.enqueue(conversations("a" to "2026-10-20T10:00:00+00:00"))
        repository.sync(7)
        server.enqueue(
            json(
                """[{"id": 1, "household_id": 7, "session_id": "a", "role": "user", "content": "Q", "created_at": null},
                    {"id": 2, "household_id": 7, "session_id": "a", "role": "assistant", "content": "A", "created_at": null}]""",
            ),
        )

        assertTrue(repository.loadMessages(7, "a"))

        assertEquals(listOf("user" to "Q", "assistant" to "A"), repository.messages("a").first().map { it.role to it.content })
    }

    @Test
    fun `a thread that's gone on the server is removed here`() = runTest {
        server.enqueue(conversations("a" to "2026-10-20T10:00:00+00:00"))
        repository.sync(7)
        server.enqueue(json("""{"detail": "Conversation not found."}""", code = 404))

        assertFalse(repository.loadMessages(7, "a"))

        assertNull(repository.conversation("a").first())
    }

    @Test
    fun `deleting removes it, even if another device already did`() = runTest {
        server.enqueue(conversations("a" to "2026-10-20T10:00:00+00:00", "b" to "2026-10-19T10:00:00+00:00"))
        repository.sync(7)
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(json("""{"detail": "Conversation not found."}""", code = 404))

        repository.delete(7, "a")
        repository.delete(7, "b")

        assertTrue(cachedIds().isEmpty())
    }

    @Test
    fun `wiping clears threads, messages and the sync cursor`() = runTest {
        server.enqueue(conversations("a" to "2026-10-20T10:00:00+00:00"))
        repository.sync(7)

        repository.wipe()

        assertTrue(cachedIds().isEmpty())
        assertNull(database.conversations().syncState(7))
    }
}
