package chat.homebuddy.core.data

import chat.homebuddy.core.network.SourceDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room's queries for cached threads, messages and sync cursors, on an in-memory database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationDaoTest {
    private lateinit var database: HomeBuddyDatabase
    private lateinit var dao: ConversationDao

    private fun thread(id: String, updatedAt: String = "2026-10-20T10:00:00+00:00", householdId: Int = 7) =
        ConversationEntity(id = id, householdId = householdId, title = "Title $id", createdAt = "2026-10-01T00:00:00+00:00", updatedAt = updatedAt)

    private fun message(conversationId: String, position: Int, content: String = "Message $position") =
        MessageEntity(conversationId = conversationId, position = position, role = "user", content = content, createdAt = null)

    private suspend fun ids(householdId: Int = 7) = dao.observe(householdId).first().map { it.id }

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        dao = database.conversations()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a home's threads are listed newest first`() = runTest {
        dao.upsert(listOf(thread("older", "2026-10-19T10:00:00+00:00"), thread("newer", "2026-10-21T10:00:00+00:00"), thread("cabin", householdId = 8)))

        assertEquals(listOf("newer", "older"), ids())
        assertEquals("Title cabin", dao.observeOne("cabin").first()?.title)
    }

    @Test
    fun `messages come back in order`() = runTest {
        dao.upsert(listOf(thread("a")))

        dao.replaceMessages("a", listOf(message("a", 1, "answer"), message("a", 0, "question")))

        assertEquals(listOf("question", "answer"), dao.observeMessages("a").first().map { it.content })
    }

    @Test
    fun `an answer's sources and urgency are stored with it`() = runTest {
        dao.upsert(listOf(thread("a")))
        val answer = message("a", 0, "Leave now.").copy(
            role = "assistant",
            sources = listOf(SourceDto("ge-washer", "GE Washer", page = 3, docType = "manual", url = "https://example.com/ge.pdf")),
            urgencyLevel = "critical",
            shouldEscalate = true,
        )

        dao.replaceMessages("a", listOf(answer, message("a", 1)))

        assertEquals(listOf(answer, message("a", 1)), dao.observeMessages("a").first())
    }

    @Test
    fun `full sync updates retained threads without erasing history and removes only missing threads`() = runTest {
        dao.upsert(listOf(thread("kept"), thread("dropped"), thread("cabin", householdId = 8)))
        val history = listOf(
            message("kept", 0, "Offline question").copy(serverId = 101),
            message("kept", 1, "Offline answer").copy(role = "assistant", serverId = 102),
        )
        dao.replaceMessages("kept", history)
        dao.replaceMessages("dropped", listOf(message("dropped", 0)))
        dao.replaceMessages("cabin", listOf(message("cabin", 0)))
        val updated = thread("kept", updatedAt = "2026-10-22T10:00:00+00:00").copy(title = "Updated title")

        dao.replaceAll(7, listOf(updated, thread("new")))

        assertEquals(listOf("kept", "new"), ids())
        assertEquals(updated, dao.observeOne("kept").first())
        assertEquals(history, dao.observeMessages("kept").first())
        assertTrue(dao.observeMessages("new").first().isEmpty())
        assertNull(dao.observeOne("dropped").first())
        assertTrue(dao.observeMessages("dropped").first().isEmpty())
        assertEquals(thread("cabin", householdId = 8), dao.observeOne("cabin").first())
        assertEquals(listOf(message("cabin", 0)), dao.observeMessages("cabin").first())
    }

    @Test
    fun `an empty full sync deletes all threads and messages for only that home`() = runTest {
        dao.upsert(listOf(thread("a"), thread("b"), thread("cabin", householdId = 8)))
        listOf("a", "b", "cabin").forEach { dao.replaceMessages(it, listOf(message(it, 0))) }

        dao.replaceAll(7, emptyList())

        assertTrue(ids().isEmpty())
        assertNull(dao.observeOne("a").first())
        assertNull(dao.observeOne("b").first())
        assertTrue(dao.observeMessages("a").first().isEmpty())
        assertTrue(dao.observeMessages("b").first().isEmpty())
        assertEquals(listOf("cabin"), ids(8))
        assertEquals(listOf(message("cabin", 0)), dao.observeMessages("cabin").first())
    }

    @Test
    fun `deleting a thread deletes its messages`() = runTest {
        dao.upsert(listOf(thread("a")))
        dao.replaceMessages("a", listOf(message("a", 0)))

        dao.delete("a")

        assertNull(dao.observeOne("a").first())
        assertTrue(dao.observeMessages("a").first().isEmpty())
    }

    @Test
    fun `each home has its own sync cursor`() = runTest {
        dao.saveSyncState(ConversationSyncEntity(householdId = 7, lastFullSyncAt = 1, newestUpdatedAt = "2026-10-20T10:00:00+00:00"))

        assertEquals("2026-10-20T10:00:00+00:00", dao.syncState(7)?.newestUpdatedAt)
        assertNull(dao.syncState(8))
    }

    @Test
    fun `clearing removes threads, messages and cursors`() = runTest {
        dao.upsert(listOf(thread("a")))
        dao.replaceMessages("a", listOf(message("a", 0)))
        dao.saveSyncState(ConversationSyncEntity(householdId = 7, lastFullSyncAt = 1, newestUpdatedAt = null))

        dao.clear()

        assertTrue(ids().isEmpty())
        assertTrue(dao.observeMessages("a").first().isEmpty())
        assertNull(dao.syncState(7))
    }
}
