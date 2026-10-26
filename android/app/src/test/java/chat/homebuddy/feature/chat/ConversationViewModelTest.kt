package chat.homebuddy.feature.chat

import androidx.lifecycle.SavedStateHandle
import chat.homebuddy.app.MainDispatcherRule
import chat.homebuddy.app.R
import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.MessageEntity
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.network.ApiError
import chat.homebuddy.core.network.ApiException
import chat.homebuddy.core.network.StreamEvent
import chat.homebuddy.core.testing.ContractFixtures
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
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

/** The real ViewModel on in-memory test repositories, fed the backend's recorded streams, on the plain JVM. */
class ConversationViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val profile = TestProfileRepository()
    private val repository = TestConversationsRepository().apply {
        answer = ContractFixtures.streamEvents("query_stream_answer.sse").asFlow()
    }

    /** A thread's screen, with its state collected as the screen would; with no [opened] id, a new thread. */
    private fun TestScope.conversation(opened: String? = null, saved: SavedStateHandle = SavedStateHandle()) =
        ConversationViewModel(opened, saved, ObserveCurrentHouseholdUseCase(profile), repository)
            .also { viewModel -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} } }

    private fun message(position: Int, role: String, content: String) =
        MessageEntity(conversationId = "filter", position = position, role = role, content = content, createdAt = null)

    @Test
    fun `dictation fills the draft and never sends on its own`() = runTest {
        profile.refresh()
        val viewModel = conversation()

        viewModel.onDraftChange("Kitchen:")
        viewModel.onDictationStarted()
        assertTrue(viewModel.state.value.listening)
        viewModel.onDictationResult("why is my dishwasher", final = false)
        viewModel.onDictationResult("why is my dishwasher leaking", final = true)

        val state = viewModel.state.value
        assertEquals("Kitchen: why is my dishwasher leaking", state.draft)
        assertFalse(state.listening)
        assertTrue(state.turns.isEmpty())
        assertTrue("nothing was sent", repository.asked.isEmpty())
    }

    @Test
    fun `sending streams the answer with its sources, then refreshes the thread`() = runTest {
        profile.refresh()
        val viewModel = conversation()

        viewModel.onDraftChange("How do I clean the dishwasher filter?")
        viewModel.send()

        val state = viewModel.state.value
        val turn = state.turns.single()
        assertFalse(turn.streaming)
        assertTrue(turn.answer.startsWith("Twist the filter counterclockwise"))
        assertEquals("Bosch Dishwasher Manual", turn.sources.single().displayName)
        assertEquals("", state.draft)
        val asked = repository.asked.single()
        assertEquals(7 to "80202", asked.householdId to asked.zipCode)
        assertEquals(listOf(asked.conversationId), repository.refreshedAfterAnswer)
    }

    @Test
    fun `a question sent before the profile is cached waits for it`() = runTest {
        val viewModel = conversation()

        viewModel.onDraftChange("How do I clean the dishwasher filter?")
        viewModel.send()
        assertTrue(repository.asked.isEmpty())
        profile.refresh()

        assertEquals(1, repository.asked.size)
        assertFalse(viewModel.state.value.turns.single().streaming)
    }

    @Test
    fun `a gas question shows the safety card`() = runTest {
        profile.refresh()
        repository.answer = ContractFixtures.streamEvents("query_stream_safety.sse").asFlow()
        val viewModel = conversation()

        viewModel.onDraftChange("I smell gas in the kitchen")
        viewModel.send()

        assertEquals(SafetyAlert(critical = true), viewModel.state.value.turns.single().safety)
    }

    @Test
    fun `when another device is still being answered the question is kept to resend`() = runTest {
        profile.refresh()
        repository.answer = flow {
            throw ApiException(ApiError(409, code = null, message = "Still answering your last question in this conversation. Try again in a moment."))
        }
        val viewModel = conversation()

        viewModel.onDraftChange("Are you there?")
        viewModel.send()

        val state = viewModel.state.value
        assertTrue(state.turns.isEmpty())
        assertEquals("Are you there?", state.draft)
        assertEquals("Still answering your last question in this conversation. Try again in a moment.".asUiText(), state.message)
    }

    @Test
    fun `stopping keeps what arrived so far`() = runTest {
        profile.refresh()
        repository.answer = flow {
            emit(StreamEvent.Token("Twist the filter"))
            awaitCancellation()
        }
        val viewModel = conversation()
        viewModel.onDraftChange("How do I clean the dishwasher filter?")
        viewModel.send()
        assertTrue(viewModel.state.value.answering)

        viewModel.stopAnswer()

        val turn = viewModel.state.value.turns.single()
        assertEquals("Twist the filter", turn.answer)
        assertFalse(turn.streaming)
        assertEquals(uiText(R.string.chat_stopped), turn.note)
    }

    @Test
    fun `opening a thread shows its title and history`() = runTest {
        profile.refresh()
        repository.server = listOf(ConversationEntity("filter", 7, "Dishwasher filter", "2026-10-01T00:00:00Z", "2026-10-20T10:00:00Z"))
        repository.sync(7)
        repository.serverMessages["filter"] = listOf(message(0, "user", "How do I clean it?"), message(1, "assistant", "Twist the filter."))

        val state = conversation(opened = "filter").state.value

        assertEquals("Dishwasher filter", state.title)
        assertEquals(listOf(HistoryMessage(true, "How do I clean it?"), HistoryMessage(false, "Twist the filter.")), state.history)
    }

    @Test
    fun `opening a thread offline shows the saved copy`() = runTest {
        profile.refresh()
        repository.failure = IOException("No connection")

        val viewModel = conversation(opened = "filter")

        assertEquals(uiText(R.string.chat_showing_saved_copy), viewModel.state.value.message)
        assertFalse(viewModel.gone.value)
    }

    @Test
    fun `a thread deleted on another device closes`() = runTest {
        profile.refresh()

        assertTrue(conversation(opened = "gone").gone.value)
    }

    @Test
    fun `a new thread keeps its id when the screen is recreated`() = runTest {
        profile.refresh()
        val saved = SavedStateHandle()

        listOf("First question", "Second question").forEach { question ->
            val viewModel = conversation(saved = saved)
            viewModel.onDraftChange(question)
            viewModel.send()
        }

        assertEquals(1, repository.asked.map { it.conversationId }.distinct().size)
        assertNull(conversation().state.value.title)
    }
}
