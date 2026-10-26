package chat.homebuddy.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.app.R
import chat.homebuddy.core.data.profile.ProfileRepository
import chat.homebuddy.core.domain.DeleteAccountResult
import chat.homebuddy.core.domain.DeleteAccountUseCase
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
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
    profile: ProfileRepository,
    observeCurrentHousehold: ObserveCurrentHouseholdUseCase,
    private val deleteAccount: DeleteAccountUseCase,
) : ViewModel() {
    private val deleting = MutableStateFlow(false)
    private val error = MutableStateFlow<UiText?>(null)

    /** From the cached profile; this screen doesn't need the session itself, only who's signed in. */
    val state: StateFlow<AccountUiState?> = combine(profile.profile, observeCurrentHousehold(), deleting, error) { profile, home, deleting, error ->
        profile?.let {
            AccountUiState(
                name = it.user.displayName ?: it.user.email,
                email = it.user.email,
                householdName = home?.name,
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
                DeleteAccountResult.Offline -> error.value = uiText(R.string.error_offline)
                is DeleteAccountResult.Failed -> error.value = result.message.asUiText()
            }
            deleting.value = false
        }
    }
}
