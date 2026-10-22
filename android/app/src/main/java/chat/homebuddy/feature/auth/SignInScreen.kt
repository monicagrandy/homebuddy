package chat.homebuddy.feature.auth

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import chat.homebuddy.app.R
import chat.homebuddy.designsystem.HomeBuddyTheme
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asString

sealed interface SignInUiState {
    data class Idle(val message: UiText? = null) : SignInUiState
    data object InProgress : SignInUiState
    data class Error(val message: UiText) : SignInUiState
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
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.sign_in_tagline),
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
                    notice.asString(),
                    color = if (state is SignInUiState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state is SignInUiState.InProgress) {
                CircularProgressIndicator()
                Text(stringResource(R.string.sign_in_in_progress), style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state is SignInUiState.Error) R.string.action_try_again else R.string.sign_in_action))
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
