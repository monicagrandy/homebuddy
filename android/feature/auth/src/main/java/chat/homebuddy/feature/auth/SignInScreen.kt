package chat.homebuddy.feature.auth

import chat.homebuddy.core.designsystem.HomeBuddyTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

sealed interface SignInUiState {
    data class Idle(val message: String? = null) : SignInUiState
    data object InProgress : SignInUiState
    data class Error(val message: String) : SignInUiState
}

@Composable
fun SignInScreen(state: SignInUiState, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Home Buddy", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Your home's manuals and warranties, ready to answer questions.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            val notice = when (state) {
                is SignInUiState.Idle -> state.message
                is SignInUiState.Error -> state.message
                SignInUiState.InProgress -> null
            }
            if (notice != null) {
                Text(
                    notice,
                    color = if (state is SignInUiState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state is SignInUiState.InProgress) {
                CircularProgressIndicator()
                Text("Signing you in…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state is SignInUiState.Error) "Try again" else "Sign in")
                }
            }
        }
    }
}

@Preview
@Composable
private fun SignInScreenPreview() {
    HomeBuddyTheme { SignInScreen(SignInUiState.Idle(), onSignIn = {}) }
}
