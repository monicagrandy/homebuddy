package chat.homebuddy.feature.chat

import chat.homebuddy.app.MainDispatcherRule
import chat.homebuddy.app.R
import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.core.testing.httpError
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The real ViewModel on in-memory test repositories, on the plain JVM. */
class ConversationListViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val profile = TestProfileRepository()
    private val repository = TestConversationsRepository().apply {
        server = listOf(thread("filter", "Dishwasher filter", "2026-10-20T10:00:00Z"), thread("leak", "Washer leak", "2026-10-21T10:00:00Z"))
    }

    private fun thread(id: String, title: String, updatedAt: String) =
        ConversationEntity(id = id, householdId = 7, title = title, createdAt = "2026-10-01T00:00:00Z", updatedAt = updatedAt)

    /** The chat tab, with its state collected as the screen would. */
    private fun TestScope.conversations() = ConversationListViewModel(ObserveCurrentHouseholdUseCase(profile), repository)
        .also { viewModel -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} } }

    @Test
    fun `a home's threads are listed newest first`() = runTest {
        profile.refresh()
        val viewModel = conversations()

        viewModel.refresh()

        val state = viewModel.state.value
        assertEquals(listOf("Washer leak", "Dishwasher filter"), state.items.map { it.title })
        assertTrue(state.loaded)
        assertFalse(state.refreshing)
    }

    @Test
    fun `the list refreshes even before the cached profile has been read`() = runTest {
        val viewModel = conversations()

        viewModel.refresh()
        assertTrue(repository.syncs.isEmpty())
        profile.refresh()

        assertEquals(2, viewModel.state.value.items.size)
    }

    @Test
    fun `pulling to refresh fetches the whole list`() = runTest {
        profile.refresh()
        val viewModel = conversations()

        viewModel.refresh()
        viewModel.refresh(full = true)

        assertEquals(listOf(false, true), repository.syncs)
    }

    @Test
    fun `a refresh while offline keeps the list without a message`() = runTest {
        profile.refresh()
        val viewModel = conversations()
        repository.failure = IOException("No connection")

        viewModel.refresh()

        assertNull(viewModel.state.value.message)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun `a refresh the server refuses shows its reason, until it's been shown`() = runTest {
        profile.refresh()
        val viewModel = conversations()
        repository.failure = httpError(500, "Something went wrong.")

        viewModel.refresh()
        assertEquals("Something went wrong.".asUiText(), viewModel.state.value.message)

        viewModel.messageShown()
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `deleting a thread removes it`() = runTest {
        profile.refresh()
        val viewModel = conversations()
        viewModel.refresh()

        viewModel.delete(viewModel.state.value.items.first())

        assertEquals(listOf("Dishwasher filter"), viewModel.state.value.items.map { it.title })
    }

    @Test
    fun `a delete that fails says why and keeps the thread`() = runTest {
        profile.refresh()
        val viewModel = conversations()
        viewModel.refresh()
        val leak = viewModel.state.value.items.first()

        repository.failure = IOException("No connection")
        viewModel.delete(leak)
        assertEquals(uiText(R.string.error_offline), viewModel.state.value.message)

        repository.failure = httpError(500, "Something went wrong.")
        viewModel.delete(leak)
        assertEquals("Something went wrong.".asUiText(), viewModel.state.value.message)
        assertEquals(2, viewModel.state.value.items.size)
    }

    @Test
    fun `a thread deleted on another device says why`() = runTest {
        profile.refresh()
        val viewModel = conversations()

        viewModel.onConversationGone()

        assertEquals(uiText(R.string.chat_conversation_gone), viewModel.state.value.message)
    }
}
