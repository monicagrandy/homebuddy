package chat.homebuddy.core.data.conversations

import chat.homebuddy.core.network.ConversationDto
import chat.homebuddy.core.network.MessageDto
import chat.homebuddy.core.testing.httpError
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class CachedConversationsRepositoryTest {
    private var now = 1_000_000L
    private val remote = TestConversationsRemoteDataSource()
    private val local = TestConversationsLocalDataSource()
    private val repository = CachedConversationsRepository(remote, local, clock = { now })

    private fun thread(id: String, updatedAt: String) =
        ConversationDto(id = id, householdId = 7, title = "Title $id", createdAt = "2026-10-01T00:00:00+00:00", updatedAt = updatedAt)

    private fun message(id: Int, role: String, content: String) = MessageDto(id = id, householdId = 7, sessionId = "a", role = role, content = content)

    private suspend fun cachedIds() = repository.conversations(7).first().map { it.id }

    private suspend fun synced(vararg threads: ConversationDto) {
        remote.conversations = threads.toList()
        repository.sync(7)
    }

    @Test
    fun `the first sync fetches everything`() = runTest {
        synced(thread("a", "2026-10-20T10:00:00+00:00"), thread("b", "2026-10-19T10:00:00+00:00"))

        assertEquals(listOf<String?>(null), remote.listings)
        assertEquals(listOf("a", "b"), cachedIds())
    }

    @Test
    fun `later syncs ask only for changes and merge them in`() = runTest {
        synced(thread("a", "2026-10-20T10:00:00+00:00"), thread("b", "2026-10-19T10:00:00+00:00"))

        synced(thread("b", "2026-10-21T09:00:00+00:00"))

        assertEquals("2026-10-20T10:00:00+00:00", remote.listings.last())
        assertEquals(listOf("b", "a"), cachedIds())
    }

    @Test
    fun `once a day the full list replaces the cache, dropping threads deleted elsewhere`() = runTest {
        synced(thread("a", "2026-10-20T10:00:00+00:00"), thread("deleted-on-the-web", "2026-10-19T10:00:00+00:00"))
        now += CachedConversationsRepository.FULL_SYNC_EVERY_MILLIS

        synced(thread("a", "2026-10-20T10:00:00+00:00"))

        assertNull(remote.listings.last())
        assertEquals(listOf("a"), cachedIds())
    }

    @Test
    fun `messages load in order`() = runTest {
        synced(thread("a", "2026-10-20T10:00:00+00:00"))
        remote.messages["a"] = listOf(message(1, "user", "Q"), message(2, "assistant", "A"))

        assertTrue(repository.loadMessages(7, "a"))

        assertEquals(listOf("user" to "Q", "assistant" to "A"), repository.messages("a").first().map { it.role to it.content })
    }

    @Test
    fun `a thread that's gone on the server is removed here`() = runTest {
        synced(thread("a", "2026-10-20T10:00:00+00:00"))

        assertFalse(repository.loadMessages(7, "a"))

        assertNull(repository.conversation("a").first())
    }

    @Test
    fun `deleting removes it on the server and here`() = runTest {
        synced(thread("a", "2026-10-20T10:00:00+00:00"))

        repository.delete(7, "a")

        assertEquals(listOf("a"), remote.deleted)
        assertTrue(cachedIds().isEmpty())
    }

    @Test
    fun `a failed delete keeps the thread`() = runTest {
        synced(thread("a", "2026-10-20T10:00:00+00:00"))
        remote.failure = httpError(500)

        val error = runCatching { repository.delete(7, "a") }.exceptionOrNull()

        assertTrue(error is HttpException)
        assertEquals(listOf("a"), cachedIds())
    }

    @Test
    fun `wiping clears threads, messages and the sync cursor`() = runTest {
        synced(thread("a", "2026-10-20T10:00:00+00:00"))

        repository.wipe()

        assertTrue(cachedIds().isEmpty())
        assertNull(local.syncState(7))
    }

    @Test
    fun `a refresh after an answer that fails is left for the next sync`() = runTest {
        listOf(IOException("No connection"), httpError(503)).forEach { failure ->
            remote.failure = failure

            repository.afterAnswer(7, "a")
        }
    }

    @Test
    fun `cancelling the refresh after an answer stops its caller there`() = runTest {
        val listing = CompletableDeferred<Unit>()
        remote.whileListing = {
            listing.complete(Unit)
            awaitCancellation()
        }
        var carriedOn = false

        val caller = launch {
            repository.afterAnswer(7, "a")
            carriedOn = true
        }
        listing.await()
        caller.cancelAndJoin()

        assertFalse("the caller kept going after it was cancelled", carriedOn)
    }
}
