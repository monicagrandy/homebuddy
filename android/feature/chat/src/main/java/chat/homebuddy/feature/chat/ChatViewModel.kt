package chat.homebuddy.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.core.auth.Household
import chat.homebuddy.core.auth.SessionRepository
import chat.homebuddy.core.auth.SessionState
import chat.homebuddy.core.data.ConversationsRepository
import chat.homebuddy.core.network.ApiException
import chat.homebuddy.core.network.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

private const val OFFLINE = "Couldn't reach Home Buddy. Check your connection and try again."

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    session: SessionRepository,
    private val repository: ConversationsRepository,
) : ViewModel() {
    private val household = session.state
        .map { (it as? SessionState.SignedIn)?.households?.firstOrNull() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The open thread's id, or null for the list. A new question gets a fresh id the server adopts. */
    private val openId = MutableStateFlow<String?>(null)
    private val turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    private val draft = MutableStateFlow("")
    private val listening = MutableStateFlow(false)
    private val listRefreshing = MutableStateFlow(false)
    private val listMessage = MutableStateFlow<String?>(null)
    private val chatMessage = MutableStateFlow<String?>(null)
    private var draftBeforeDictation = ""
    private var answer: Job? = null

    val voiceAvailable = MutableStateFlow(true)

    val listState: StateFlow<ConversationListUiState> = household
        .flatMapLatest { home ->
            if (home == null) {
                flowOf(ConversationListUiState())
            } else {
                combine(repository.conversations(home.id), listRefreshing, listMessage) { conversations, refreshing, message ->
                    ConversationListUiState(
                        items = conversations.map { ConversationItem(it.id, it.title, shortDate(it.updatedAt)) },
                        refreshing = refreshing,
                        loaded = true,
                        message = message,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState())

    private val inputs = combine(draft, listening, voiceAvailable, chatMessage) { draft, listening, voice, message ->
        Inputs(draft, listening, voice, message)
    }

    val conversationState: StateFlow<ConversationUiState?> = openId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
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
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refreshList(full: Boolean = false) {
        val home = household.value ?: return
        viewModelScope.launch {
            listRefreshing.value = true
            try {
                repository.sync(home.id, forceFull = full)
            } catch (_: IOException) {
                // Offline: keep the cached list.
            } catch (error: HttpException) {
                listMessage.value = error.toApiError().message
            } finally {
                listRefreshing.value = false
            }
        }
    }

    fun open(item: ConversationItem) {
        val home = household.value ?: return
        reset(item.id)
        viewModelScope.launch {
            try {
                if (!repository.loadMessages(home.id, item.id)) {
                    openId.value = null
                    listMessage.value = "That conversation was deleted, or expired after 90 days without activity."
                }
            } catch (_: IOException) {
                chatMessage.value = "Showing the last saved copy. $OFFLINE"
            } catch (error: HttpException) {
                chatMessage.value = error.toApiError().message
            }
        }
    }

    fun newConversation() = reset(UUID.randomUUID().toString())

    fun close() {
        stopAnswer()
        openId.value = null
        refreshList()
    }

    fun deleteConversation(item: ConversationItem) {
        val home = household.value ?: return
        viewModelScope.launch {
            try {
                repository.delete(home.id, item.id)
            } catch (_: IOException) {
                listMessage.value = OFFLINE
            } catch (error: HttpException) {
                listMessage.value = error.toApiError().message
            }
        }
    }

    fun onDraftChange(text: String) {
        draft.value = text
    }

    fun send() {
        val home: Household = household.value ?: return
        val id = openId.value ?: return
        val question = draft.value.trim()
        if (question.isEmpty() || turns.value.lastOrNull()?.streaming == true) return
        draft.value = ""
        chatMessage.value = null
        turns.update { it + ChatTurn(question = question) }
        answer = viewModelScope.launch {
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
                chatMessage.value = error.error.message
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

    fun onDictationError(message: String) {
        listening.value = false
        chatMessage.value = message
    }

    fun onDictationStopped() {
        listening.value = false
    }

    fun chatMessageShown() {
        chatMessage.value = null
    }

    fun listMessageShown() {
        listMessage.value = null
    }

    private fun reset(id: String) {
        stopAnswer()
        turns.value = emptyList()
        draft.value = ""
        chatMessage.value = null
        listening.value = false
        openId.value = id
    }

    private fun updateLast(transform: (ChatTurn) -> ChatTurn) {
        turns.update { current -> if (current.isEmpty()) current else current.dropLast(1) + transform(current.last()) }
    }

    private data class Inputs(val draft: String, val listening: Boolean, val voiceAvailable: Boolean, val message: String?)
}
