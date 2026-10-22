package chat.homebuddy.feature.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.app.R
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
    /** Signed out, signing in, or loading the profile. */
    data class SignIn(val screen: SignInUiState) : AppUiState

    data object HomeSetup : AppUiState

    data object Main : AppUiState
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
            SessionState.Loading -> AppUiState.SignIn(profileError?.let(SignInUiState::Error) ?: SignInUiState.InProgress)
            is SessionState.SignedOut -> AppUiState.SignIn(
                when {
                    signingIn -> SignInUiState.InProgress
                    signInError != null -> SignInUiState.Error(signInError)
                    else -> SignInUiState.Idle(session.reason?.message())
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState.SignIn(SignInUiState.InProgress))

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
     * The sign-in page to open, or null when the button retried loading the profile instead
     * (signed in, but the profile couldn't be fetched yet).
     */
    fun onSignInClick(): Intent? {
        if (session.value == SessionState.Loading) {
            loadProfile()
            return null
        }
        signInError.value = null
        return signIn.intent()
    }

    fun onSignInResult(data: Intent?) {
        signingIn.value = true
        viewModelScope.launch {
            signInError.value = when (signIn(data)) {
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
