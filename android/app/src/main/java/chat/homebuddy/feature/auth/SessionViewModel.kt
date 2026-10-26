package chat.homebuddy.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.app.R
import chat.homebuddy.core.auth.SignInPageResult
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.domain.ObserveSessionUseCase
import chat.homebuddy.core.domain.RefreshProfileUseCase
import chat.homebuddy.core.domain.SessionState
import chat.homebuddy.core.domain.SignInResult
import chat.homebuddy.core.domain.SignInUseCase
import chat.homebuddy.core.domain.SignOutUseCase
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which part of the app to show. */
sealed interface AppUiState {
    /**
     * Reading the stored session, or (signed in) fetching the profile for the first time on this
     * phone. [error] when that fetch failed.
     */
    data class Loading(val error: UiText? = null) : AppUiState

    data class SignedOut(val signIn: SignInUiState) : AppUiState

    data object HomeSetup : AppUiState

    data object Main : AppUiState
}

/** What the sign-in screen shows. It's also the screen at launch, while the session loads. */
fun AppUiState.signInScreen(): SignInUiState = when (this) {
    is AppUiState.Loading -> error?.let(SignInUiState::Error) ?: SignInUiState.InProgress
    is AppUiState.SignedOut -> signIn
    // For the moment the sign-in screen is being replaced.
    AppUiState.HomeSetup, AppUiState.Main -> SignInUiState.InProgress
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    observeSession: ObserveSessionUseCase,
    private val signIn: SignInUseCase,
    private val signOut: SignOutUseCase,
    private val refreshProfile: RefreshProfileUseCase,
) : ViewModel() {
    private val session = observeSession().stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)
    private val signingIn = MutableStateFlow(false)
    private val signInError = MutableStateFlow<UiText?>(null)
    private val profileError = MutableStateFlow<UiText?>(null)

    val state: StateFlow<AppUiState> = combine(session, signingIn, signInError, profileError) { session, signingIn, signInError, profileError ->
        when (session) {
            is SessionState.SignedIn -> if (session.needsHome) AppUiState.HomeSetup else AppUiState.Main
            SessionState.Loading -> AppUiState.Loading(profileError)
            is SessionState.SignedOut -> AppUiState.SignedOut(
                when {
                    signingIn -> SignInUiState.InProgress
                    signInError != null -> SignInUiState.Error(signInError)
                    else -> SignInUiState.Idle(session.reason?.message())
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.Loading())

    private val _pendingLogoutUrl = MutableStateFlow<String?>(null)

    /**
     * The hosted UI's logout page, to open once after signing out. It's state rather than a
     * one-off event, so it survives rotation; the screen calls [onLogoutPageOpened] when it's done.
     */
    val pendingLogoutUrl: StateFlow<String?> = _pendingLogoutUrl.asStateFlow()

    init {
        loadProfile()
    }

    /**
     * True when the screen should open the sign-in page; false when the button retried loading
     * the profile instead (signed in, but the profile couldn't be fetched yet).
     */
    fun onSignInClick(): Boolean {
        if (session.value == SessionState.Loading) {
            loadProfile()
            return false
        }
        signInError.value = null
        return true
    }

    fun onSignInResult(page: SignInPageResult) {
        signingIn.value = true
        viewModelScope.launch {
            signInError.value = when (signIn(page)) {
                SignInResult.SignedIn, SignInResult.Cancelled -> null
                SignInResult.NotInBeta -> uiText(R.string.sign_in_not_in_beta)
                SignInResult.Offline -> uiText(R.string.error_offline)
                SignInResult.Failed -> uiText(R.string.sign_in_failed)
            }
            signingIn.value = false
        }
    }

    fun onSignOutClick() {
        viewModelScope.launch { _pendingLogoutUrl.value = signOut() }
    }

    fun onLogoutPageOpened() {
        _pendingLogoutUrl.value = null
    }

    private fun loadProfile() {
        profileError.value = null
        viewModelScope.launch {
            if (!refreshProfile()) profileError.value = uiText(R.string.error_offline)
        }
    }

    private companion object {
        fun SignOutReason.message(): UiText = when (this) {
            SignOutReason.SESSION_ENDED -> uiText(R.string.sign_in_session_ended)
            SignOutReason.ACCOUNT_DELETED -> uiText(R.string.sign_in_account_deleted)
            SignOutReason.NOT_IN_BETA -> uiText(R.string.sign_in_not_in_beta)
        }
    }
}
