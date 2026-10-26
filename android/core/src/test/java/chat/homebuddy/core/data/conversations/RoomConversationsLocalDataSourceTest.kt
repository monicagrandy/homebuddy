package chat.homebuddy.core.data.conversations

import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.ConversationSyncEntity
import chat.homebuddy.core.data.FakeConversationDao
import chat.homebuddy.core.data.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomConversationsLocalDataSourceTest {
    private val local = RoomConversationsLocalDataSource(FakeConversationDao())

    private fun thread(id: String) =
        ConversationEntity(id = id, householdId = 7, title = "Title $id", createdAt = "2026-10-01T00:00:00+00:00", updatedAt = "2026-10-20T10:00:00+00:00")

    private val question = MessageEntity(conversationId = "a", position = 0, role = "user", content = "Q", createdAt = null)

    @Test
    fun `threads and their messages are saved and read back`() = runTest {
        local.upsert(listOf(thread("a")))
        local.replaceMessages("a", listOf(question))

        assertEquals(listOf(thread("a")), local.conversations(7).first())
        assertEquals(thread("a"), local.conversation("a").first())
        assertEquals(listOf(question), local.messages("a").first())
    }

    @Test
    fun `replacing a home's threads and deleting one`() = runTest {
        local.upsert(listOf(thread("a"), thread("b")))

        local.replaceAll(7, listOf(thread("a")))
        assertEquals(listOf("a"), local.conversations(7).first().map { it.id })

        local.delete("a")
        assertNull(local.conversation("a").first())
    }

    @Test
    fun `clearing removes threads, messages and the sync cursor`() = runTest {
        local.upsert(listOf(thread("a")))
        local.replaceMessages("a", listOf(question))
        local.saveSyncState(ConversationSyncEntity(householdId = 7, lastFullSyncAt = 1, newestUpdatedAt = null))
        assertEquals(1L, local.syncState(7)?.lastFullSyncAt)

        local.clear()

        assertTrue(local.conversations(7).first().isEmpty())
        assertTrue(local.messages("a").first().isEmpty())
        assertNull(local.syncState(7))
    }
}
