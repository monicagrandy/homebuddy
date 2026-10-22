package chat.homebuddy.feature.auth

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.core.auth.SessionRepository
import chat.homebuddy.core.auth.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SessionViewModel @Inject constructor(private val session: SessionRepository) : ViewModel() {
    val state: StateFlow<SessionState> = session.state

    //TODO: can you explain why you chose a channel here over a shared flow? And also why in general this is an event stream
    private val _openInBrowser = Channel<Uri>(Channel.BUFFERED)
    /** Pages to open outside the app, such as the hosted UI's logout. */
    val openInBrowser: Flow<Uri> = _openInBrowser.receiveAsFlow() // TODO: is this converting our hot flow into a cold flow and why do we need to do that?

    init {
        if (session.state.value is SessionState.Restoring) {
            viewModelScope.launch { session.restore() }
        }
    }

    fun signInIntent(): Intent = session.signInIntent()

    fun onSignInResult(data: Intent?) {
        viewModelScope.launch { session.completeSignIn(data) }
    }

    fun signOut() {
        viewModelScope.launch { _openInBrowser.send(Uri.parse(session.signOut())) }
    }
}

fun SessionState.toSignInUiState(): SignInUiState = when (this) {
    is SessionState.SignedOut -> SignInUiState.Idle(message)
    is SessionState.Failed -> SignInUiState.Error(message)
    SessionState.SigningIn, SessionState.Restoring -> SignInUiState.InProgress
    is SessionState.SignedIn -> SignInUiState.InProgress
}
