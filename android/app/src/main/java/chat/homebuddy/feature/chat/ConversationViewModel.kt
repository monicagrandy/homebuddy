package chat.homebuddy.feature.chat

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.app.R
import chat.homebuddy.core.data.conversations.ConversationsRepository
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.network.ApiException
import chat.homebuddy.core.network.toApiError
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.UUID
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

/**
 * One thread, scoped to its screen: leaving it stops the answer (the server still finishes and
 * saves it). The nav graph decodes [ConversationDestination] and passes its id in, so this class
 * doesn't depend on Navigation.
 */
@HiltViewModel(assistedFactory = ConversationViewModel.Factory::class)
class ConversationViewModel @AssistedInject constructor(
    /** The thread to open; null starts a new one. */
    @Assisted private val opened: String?,
    private val savedStateHandle: SavedStateHandle,
    observeCurrentHousehold: ObserveCurrentHouseholdUseCase,
    private val repository: ConversationsRepository,
) : ViewModel() {
    // The saved state survives process death, so it holds only a new thread's id, the draft and
    // whether anything was sent. The messages come back from the cache and the server instead.

    /** A new thread's id is made up here, and kept across process death until the server adopts it. */
    private val id: String = opened
        ?: savedStateHandle.get<String>(NEW_ID)
        ?: UUID.randomUUID().toString().also { savedStateHandle[NEW_ID] = it }

    /** What's being typed, kept across process death. */
    private val draft = savedStateHandle.getStateFlow(DRAFT, "")

    private val household = observeCurrentHousehold().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val timeline = MutableStateFlow(ConversationTimeline())
    private val activeRequest = MutableStateFlow<String?>(null)
    private val listening = MutableStateFlow(false)
    private val message = MutableStateFlow<UiText?>(null)
    private val _gone = MutableStateFlow(false)
    private var draftBeforeDictation = ""
    private var answer: Job? = null

    val voiceAvailable = MutableStateFlow(true)

    /** The thread was deleted on another device, or expired; the screen should close. */
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    private val inputs = combine(draft, listening, voiceAvailable, message, activeRequest) { draft, listening, voice, message, request ->
        Inputs(draft, listening, voice, message, request != null)
    }
    val state: StateFlow<ConversationUiState> =
        combine(repository.conversation(id), timeline, inputs) { conversation, timeline, inputs ->
            ConversationUiState(
                title = conversation?.title ?: timeline.messages.firstOrNull { it.role == "user" }?.content ?: timeline.turns.firstOrNull()?.question,
                history = timeline.history(),
                turns = timeline.turns,
                draft = inputs.draft,
                listening = inputs.listening,
                voiceAvailable = inputs.voiceAvailable,
                message = inputs.message,
                requestInFlight = inputs.requestInFlight,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    init {
        viewModelScope.launch {
            repository.messages(id).collect { messages ->
                timeline.update { it.copy(messages = messages).reconcile() }
            }
        }
        // A new thread that was sent may have been answered while the app was gone: the server has it.
        if (opened != null || savedStateHandle.get<Boolean>(SUBMITTED) == true) load()
    }

    private fun load() {
        viewModelScope.launch {
            val home = household.filterNotNull().first()
            try {
                if (!repository.loadMessages(home.id, id)) _gone.value = true
            } catch (_: IOException) {
                message.value = uiText(R.string.chat_showing_saved_copy)
            } catch (error: HttpException) {
                message.value = error.toApiError().message.asUiText()
            }
        }
    }

    fun onDraftChange(text: String) {
        setDraft(text)
    }

    fun send() {
        val question = draft.value.trim()
        if (question.isEmpty() || activeRequest.value != null) return
        setDraft("")
        message.value = null
        savedStateHandle[SUBMITTED] = true
        val turn = ChatTurn(question = question)
        timeline.update { it.copy(turns = it.turns + turn) }
        activeRequest.value = turn.id
        answer = viewModelScope.launch {
            try {
                val home = household.filterNotNull().first()
                try {
                    repository.ask(home.id, id, question, home.zipCode).collect { event -> updateTurn(turn.id) { it.reduce(event) } }
                    updateTurn(turn.id) { it.finish() }
                } catch (error: ApiException) {
                    // 409: another device is still being answered in this thread. Keep the question to resend.
                    timeline.update { current -> current.copy(turns = current.turns.filterNot { it.id == turn.id }) }
                    setDraft(question)
                    message.value = error.error.message.asUiText()
                } catch (_: IOException) {
                    updateTurn(turn.id) { it.finish() }
                }
                repository.afterAnswer(home.id, id)
            } catch (cancelled: CancellationException) {
                updateTurn(turn.id) { it.stop() }
                throw cancelled
            } finally {
                if (activeRequest.value == turn.id) activeRequest.value = null
            }
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
        setDraft(dictatedDraft(draftBeforeDictation, heard))
        if (final) listening.value = false
    }

    fun onDictationError(@StringRes text: Int) {
        listening.value = false
        message.value = uiText(text)
    }

    fun onDictationStopped() {
        listening.value = false
    }

    /** No app on the phone could do what was tapped (place a call, show a web page): [text] says what to do instead. */
    fun onExternalAppMissing(@StringRes text: Int) {
        message.value = uiText(text)
    }

    fun messageShown() {
        message.value = null
    }

    private fun setDraft(text: String) {
        savedStateHandle[DRAFT] = text
    }

    private fun updateTurn(turnId: String, transform: (ChatTurn) -> ChatTurn) {
        timeline.update { current ->
            current.copy(turns = current.turns.map { if (it.id == turnId) transform(it) else it }).reconcile()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(opened: String?): ConversationViewModel
    }

    private data class Inputs(val draft: String, val listening: Boolean, val voiceAvailable: Boolean, val message: UiText?, val requestInFlight: Boolean)

    private companion object {
        const val NEW_ID = "new_conversation_id"
        const val DRAFT = "draft"

        /** Set once a question has been sent in this thread. */
        const val SUBMITTED = "submitted"
    }
}
