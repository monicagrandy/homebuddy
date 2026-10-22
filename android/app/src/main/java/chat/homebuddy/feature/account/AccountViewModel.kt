package chat.homebuddy.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.core.domain.DeleteAccountResult
import chat.homebuddy.core.domain.DeleteAccountUseCase
import chat.homebuddy.core.domain.ObserveSessionUseCase
import chat.homebuddy.core.domain.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AccountViewModel @Inject constructor(
    observeSession: ObserveSessionUseCase,
    private val deleteAccount: DeleteAccountUseCase,
) : ViewModel() {
    private val deleting = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<AccountUiState?> = combine(observeSession(), deleting, error) { current, deleting, error ->
        (current as? SessionState.SignedIn)?.let {
            AccountUiState(
                name = it.user.displayName ?: it.user.email,
                email = it.user.email,
                householdName = it.households.firstOrNull()?.name,
                deleting = deleting,
                error = error,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete() {
        if (deleting.value) return
        deleting.value = true
        error.value = null
        viewModelScope.launch {
            // On success the session signs out, and the app returns to the sign-in screen with a message.
            when (val result = deleteAccount()) {
                DeleteAccountResult.Deleted -> Unit
                DeleteAccountResult.Offline -> error.value = "Couldn't reach Home Buddy. Check your connection and try again."
                is DeleteAccountResult.Failed -> error.value = result.message
            }
            deleting.value = false
        }
    }
}
