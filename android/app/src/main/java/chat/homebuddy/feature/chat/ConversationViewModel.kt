package chat.homebuddy.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import chat.homebuddy.core.data.ConversationsRepository
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.network.ApiException
import chat.homebuddy.core.network.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** One thread, scoped to its screen: leaving it stops the answer (the server still finishes and saves it). */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeCurrentHousehold: ObserveCurrentHouseholdUseCase,
    private val repository: ConversationsRepository,
) : ViewModel() {
    private val opened = savedStateHandle.toRoute<ConversationDestination>().id

    /** A new thread's id is made up here, and kept across process death until the server adopts it. */
    private val id: String = opened
        ?: savedStateHandle.get<String>(NEW_ID)
        ?: UUID.randomUUID().toString().also { savedStateHandle[NEW_ID] = it }

    private val household = observeCurrentHousehold().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    private val draft = MutableStateFlow("")
    private val listening = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val _gone = MutableStateFlow(false)
    private var draftBeforeDictation = ""
    private var answer: Job? = null

    val voiceAvailable = MutableStateFlow(true)

    /** The thread was deleted on another device, or expired; the screen should close. */
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    private val inputs = combine(draft, listening, voiceAvailable, message) { draft, listening, voice, message ->
        Inputs(draft, listening, voice, message)
    }

    val state: StateFlow<ConversationUiState> =
        combine(repository.conversation(id), repository.messages(id), turns, inputs) { conversation, messages, turns, inputs ->
            ConversationUiState(
                title = conversation?.title ?: turns.firstOrNull()?.question ?: "New question",
                history = messages.map { HistoryMessage(fromUser = it.role == "user", content = it.content) },
                turns = turns,
                draft = inputs.draft,
                listening = inputs.listening,
                voiceAvailable = inputs.voiceAvailable,
                message = inputs.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    init {
        if (opened != null) load()
    }

    private fun load() {
        viewModelScope.launch {
            val home = household.filterNotNull().first()
            try {
                if (!repository.loadMessages(home.id, id)) _gone.value = true
            } catch (_: IOException) {
                message.value = "Showing the last saved copy. $OFFLINE"
            } catch (error: HttpException) {
                message.value = error.toApiError().message
            }
        }
    }

    fun onDraftChange(text: String) {
        draft.value = text
    }

    fun send() {
        val question = draft.value.trim()
        if (question.isEmpty() || turns.value.lastOrNull()?.streaming == true) return
        draft.value = ""
        message.value = null
        turns.update { it + ChatTurn(question = question) }
        answer = viewModelScope.launch {
            val home = household.filterNotNull().first()
            try {
                repository.ask(home.id, id, question, home.zipCode).collect { event -> updateLast { it.reduce(event) } }
                updateLast { it.finish() }
            } catch (cancelled: CancellationException) {
                updateLast { it.stop() }
                throw cancelled
            } catch (error: ApiException) {
                // 409: another device is still being answered in this thread. Keep the question to resend.
                turns.update { it.dropLast(1) }
                draft.value = question
                message.value = error.error.message
            } catch (_: IOException) {
                updateLast { it.finish() }
            }
            repository.afterAnswer(home.id, id)
        }
    }

    /** Stops listening here; the server still finishes the answer and saves it in this thread. */
    fun stopAnswer() {
        answer?.cancel()
        answer = null
    }

    fun onDictationStarted() {
        draftBeforeDictation = draft.value
        listening.value = true
    }

    fun onDictationResult(heard: String, final: Boolean) {
        draft.value = dictatedDraft(draftBeforeDictation, heard)
        if (final) listening.value = false
    }

    fun onDictationError(text: String) {
        listening.value = false
        message.value = text
    }

    fun onDictationStopped() {
        listening.value = false
    }

    fun messageShown() {
        message.value = null
    }

    private fun updateLast(transform: (ChatTurn) -> ChatTurn) {
        turns.update { current -> if (current.isEmpty()) current else current.dropLast(1) + transform(current.last()) }
    }

    private data class Inputs(val draft: String, val listening: Boolean, val voiceAvailable: Boolean, val message: String?)

    private companion object {
        const val NEW_ID = "new_conversation_id"
    }
}
