package chat.homebuddy.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.core.data.ConversationsRepository
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.network.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

internal const val OFFLINE = "Couldn't reach Home Buddy. Check your connection and try again."

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationListViewModel @Inject constructor(
    observeCurrentHousehold: ObserveCurrentHouseholdUseCase,
    private val repository: ConversationsRepository,
) : ViewModel() {
    private val household = observeCurrentHousehold().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ConversationListUiState> = household
        .flatMapLatest { home ->
            if (home == null) {
                flowOf(ConversationListUiState())
            } else {
                combine(repository.conversations(home.id), refreshing, message) { conversations, refreshing, message ->
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

    fun refresh(full: Boolean = false) {
        viewModelScope.launch {
            val home = household.filterNotNull().first()
            refreshing.value = true
            try {
                repository.sync(home.id, forceFull = full)
            } catch (_: IOException) {
                // Offline: keep the cached list.
            } catch (error: HttpException) {
                message.value = error.toApiError().message
            } finally {
                refreshing.value = false
            }
        }
    }

    fun delete(item: ConversationItem) {
        viewModelScope.launch {
            val home = household.filterNotNull().first()
            try {
                repository.delete(home.id, item.id)
            } catch (_: IOException) {
                message.value = OFFLINE
            } catch (error: HttpException) {
                message.value = error.toApiError().message
            }
        }
    }

    /** The thread that was opened had been deleted on another device, or expired. */
    fun onConversationGone() {
        message.value = "That conversation was deleted, or expired after 90 days without activity."
    }

    fun messageShown() {
        message.value = null
    }
}
