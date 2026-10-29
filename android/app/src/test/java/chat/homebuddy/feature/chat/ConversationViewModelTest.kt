package chat.homebuddy.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import chat.homebuddy.app.MainDispatcherRule
import chat.homebuddy.app.R
import chat.homebuddy.core.data.ConversationEntity
import chat.homebuddy.core.data.MessageEntity
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.network.ApiError
import chat.homebuddy.core.network.ApiException
import chat.homebuddy.core.network.StreamEvent
import chat.homebuddy.core.network.QueryResultDto
import chat.homebuddy.core.network.SourceDto
import chat.homebuddy.core.testing.ContractFixtures
import chat.homebuddy.core.testing.TestConversationsRepository
import chat.homebuddy.core.testing.TestProfileRepository
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
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

    private fun savedMessages(id: String, question: String = "Question", answer: String = "Answer", firstId: Int = 101) = listOf(
        MessageEntity(id, 0, "user", question, null, serverId = firstId),
        MessageEntity(id, 1, "assistant", answer, null, serverId = firstId + 1),
    )

    private fun answer(firstId: Int = 101, text: String = "Answer") = flow {
        emit(StreamEvent.UserAccepted("Question", false, null, firstId))
        emit(StreamEvent.Token(text))
        emit(StreamEvent.Final(QueryResultDto(text), firstId + 1))
    }

    @Test
    fun `saved messages replace the live turn once without losing sources or safety`() = runTest {
        profile.refresh()
        val source = SourceDto("manual", "Manual")
        repository.answer = flow {
            emit(StreamEvent.UserAccepted("Sanitized question", false, null, 101))
            emit(StreamEvent.Final(QueryResultDto("Answer", sources = listOf(source), shouldEscalate = true), 102))
        }
        repository.whileRefreshingAfterAnswer = { home, id ->
            val (question, answer) = savedMessages(id, question = "Sanitized question")
            repository.serverMessages[id] = listOf(question, answer.copy(sources = listOf(source), shouldEscalate = true))
            repository.loadMessages(home, id)
        }
        val viewModel = conversation()
        val observed = mutableListOf<ConversationUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect { observed += it } }
        viewModel.onDraftChange("Raw question")

        viewModel.send()

        val state = viewModel.state.value
        assertTrue(state.turns.isEmpty())
        assertEquals(listOf(101, 102), state.history.map { it.id })
        assertEquals(listOf(source), state.history.last().sources)
        assertEquals(SafetyAlert(true), state.history.last().safety)
        assertTrue(observed.all { snapshot ->
            snapshot.history.count { it.content == "Answer" } + snapshot.turns.count { it.answer == "Answer" } <= 1
        })
    }

    @Test
    fun `a partial refresh keeps the live turn until both saved messages arrive`() = runTest {
        profile.refresh()
        repository.answer = answer()
        repository.whileRefreshingAfterAnswer = { home, id ->
            repository.serverMessages[id] = savedMessages(id).take(1)
            repository.loadMessages(home, id)
        }
        val viewModel = conversation()
        viewModel.onDraftChange("Question")
        viewModel.send()

        assertEquals("Answer", viewModel.state.value.turns.single().answer)
        assertTrue(viewModel.state.value.history.isEmpty())

        val id = repository.asked.single().conversationId
        repository.serverMessages[id] = savedMessages(id)
        repository.loadMessages(7, id)

        assertTrue(viewModel.state.value.turns.isEmpty())
        assertEquals(listOf("Question", "Answer"), viewModel.state.value.history.map { it.content })
    }

    @Test
    fun `cache confirmation before the final event never shows a second copy`() = runTest {
        profile.refresh()
        val final = CompletableDeferred<Unit>()
        repository.answer = flow {
            emit(StreamEvent.UserAccepted("Question", false, null, 101))
            emit(StreamEvent.Token("Partial"))
            final.await()
            emit(StreamEvent.Final(QueryResultDto("Answer"), 102))
        }
        val viewModel = conversation()
        viewModel.onDraftChange("Question")
        viewModel.send()
        val id = repository.asked.single().conversationId
        repository.serverMessages[id] = savedMessages(id)

        repository.loadMessages(7, id)

        assertTrue(viewModel.state.value.history.isEmpty())
        assertEquals("Partial", viewModel.state.value.turns.single().answer)
        final.complete(Unit)
        assertTrue(viewModel.state.value.turns.isEmpty())
        assertEquals(listOf(101, 102), viewModel.state.value.history.map { it.id })
    }

    @Test
    fun `identical question and answer text from separate turns stays separate`() = runTest {
        profile.refresh()
        repository.serverMessages["filter"] = savedMessages("filter", firstId = 1)
        repository.answer = answer()
        val refresh = CompletableDeferred<Unit>()
        repository.whileRefreshingAfterAnswer = { home, id ->
            refresh.await()
            repository.serverMessages[id] = savedMessages(id, firstId = 1) + savedMessages(id).map { it.copy(position = it.position + 2) }
            repository.loadMessages(home, id)
        }
        val viewModel = conversation(opened = "filter")
        viewModel.onDraftChange("Question")
        viewModel.send()

        assertEquals(listOf(1, 2), viewModel.state.value.history.map { it.id })
        assertEquals("Answer", viewModel.state.value.turns.single().answer)
        refresh.complete(Unit)

        assertTrue(viewModel.state.value.turns.isEmpty())
        assertEquals(listOf(1, 2, 101, 102), viewModel.state.value.history.map { it.id })
        assertEquals(2, viewModel.state.value.history.count { it.content == "Answer" })
    }

    @Test
    fun `a failed refresh keeps the answer until a later reload confirms it`() = runTest {
        profile.refresh()
        repository.answer = answer()
        val viewModel = conversation()
        viewModel.onDraftChange("Question")
        viewModel.send()

        assertEquals("Answer", viewModel.state.value.turns.single().answer)
        assertTrue(viewModel.state.value.history.isEmpty())
        val id = repository.asked.single().conversationId
        repository.serverMessages[id] = savedMessages(id)
        repository.loadMessages(7, id)

        assertTrue(viewModel.state.value.turns.isEmpty())
        assertEquals(2, viewModel.state.value.history.size)
    }

    @Test
    fun `a dropped stream uses its stable user ID to find the saved complete answer`() = runTest {
        profile.refresh()
        repository.answer = flow {
            emit(StreamEvent.UserAccepted("Question", false, null, 101))
            emit(StreamEvent.Token("Partial"))
            throw IOException("connection lost before Final")
        }
        repository.whileRefreshingAfterAnswer = { home, id ->
            repository.serverMessages[id] = savedMessages(id)
            repository.loadMessages(home, id)
        }
        val viewModel = conversation()
        viewModel.onDraftChange("Question")
        viewModel.send()

        assertTrue(viewModel.state.value.turns.isEmpty())
        assertEquals(listOf("Question", "Answer"), viewModel.state.value.history.map { it.content })
    }

    @Test
    fun `a final event does not allow another send before EOF and cannot mutate the next turn`() = runTest {
        profile.refresh()
        val eof = CompletableDeferred<Unit>()
        repository.answer = flow {
            emit(StreamEvent.UserAccepted("Question", false, null, 101))
            emit(StreamEvent.Final(QueryResultDto("Answer"), 102))
            eof.await()
        }
        val viewModel = conversation()
        viewModel.onDraftChange("Question")
        viewModel.send()
        val first = viewModel.state.value.turns.single()
        viewModel.onDraftChange("Next question")

        viewModel.send()

        assertFalse(viewModel.state.value.canSend)
        assertEquals(1, repository.asked.size)
        assertEquals("Next question", viewModel.state.value.draft)
        eof.complete(Unit)
        assertTrue(viewModel.state.value.canSend)
        repository.answer = flow { emit(StreamEvent.Token("Next answer")); awaitCancellation() }
        viewModel.send()

        assertEquals(2, repository.asked.size)
        assertEquals(first, viewModel.state.value.turns.first())
        assertEquals("Next answer", viewModel.state.value.turns.last().answer)
        viewModel.stopAnswer()
    }

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
    fun `a reopened thread shows its answers' sources and safety card as they were live`() = runTest {
        profile.refresh()
        val source = SourceDto("gas-range", "GE Gas Range Manual", page = 4, url = "https://example.com/range.pdf")
        repository.serverMessages["gas"] = listOf(
            MessageEntity("gas", 0, "user", "I smell gas", null, serverId = 5),
            MessageEntity("gas", 1, "assistant", "Leave now.", null, serverId = 6, sources = listOf(source), urgencyLevel = "critical"),
            MessageEntity("gas", 2, "user", "Is the stove safe now?", null, serverId = 7),
            MessageEntity("gas", 3, "assistant", "Have it checked first.", null, serverId = 8, urgencyLevel = "high"),
        )

        val history = conversation(opened = "gas").state.value.history

        assertEquals(listOf(null, SafetyAlert(critical = true), null, SafetyAlert(critical = false)), history.map { it.safety })
        assertEquals(listOf(emptyList(), listOf(source), emptyList(), emptyList()), history.map { it.sources })
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

    @Test
    fun `when no app can place the call or open a source, the screen says what to do instead`() = runTest {
        profile.refresh()
        val viewModel = conversation()

        viewModel.onExternalAppMissing(R.string.chat_no_dialer)
        assertEquals(uiText(R.string.chat_no_dialer), viewModel.state.value.message)

        viewModel.messageShown()
        viewModel.onExternalAppMissing(R.string.error_no_browser)
        assertEquals(uiText(R.string.error_no_browser), viewModel.state.value.message)
    }

    /** The saved state as it comes back after process death: only what was in the bundle. */
    private fun SavedStateHandle.afterProcessDeath() = SavedStateHandle(keys().associateWith { get<Any?>(it) })

    @Test
    fun `a half-typed question survives process death`() = runTest {
        profile.refresh()
        listOf(null, "filter").forEach { opened ->
            val saved = SavedStateHandle()
            val before = conversation(opened, saved)
            before.onDraftChange("Why does it smell")
            before.viewModelScope.cancel()

            assertEquals("Why does it smell", conversation(opened, saved.afterProcessDeath()).state.value.draft)
        }
    }

    @Test
    fun `a new thread sent before process death shows its saved answer once restored`() = runTest {
        profile.refresh()
        repository.answer = flow {
            emit(StreamEvent.UserAccepted("Question", false, null, 101))
            awaitCancellation()
        }
        val saved = SavedStateHandle()
        val before = conversation(saved = saved)
        before.onDraftChange("Question")
        before.send()
        before.viewModelScope.cancel()
        // The server finished the answer and saved it while the app was gone.
        val id = repository.asked.single().conversationId
        repository.serverMessages[id] = savedMessages(id)

        val state = conversation(saved = saved.afterProcessDeath()).state.value

        assertEquals(listOf(id), repository.loaded)
        assertEquals("Question", state.title)
        assertEquals(listOf("Question", "Answer"), state.history.map { it.content })
        assertEquals("", state.draft)
    }

    @Test
    fun `a new thread never sent doesn't ask the server for its history once restored`() = runTest {
        profile.refresh()
        val saved = SavedStateHandle()
        conversation(saved = saved).onDraftChange("Not sent yet")

        val restored = conversation(saved = saved.afterProcessDeath())

        assertTrue(repository.loaded.isEmpty())
        assertFalse(restored.gone.value)
        assertEquals("Not sent yet", restored.state.value.draft)
    }
}
