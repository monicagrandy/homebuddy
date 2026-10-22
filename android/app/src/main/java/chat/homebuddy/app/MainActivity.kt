package chat.homebuddy.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.core.auth.SessionState
import chat.homebuddy.feature.auth.HomeBuddyTheme
import chat.homebuddy.feature.auth.SessionViewModel
import chat.homebuddy.feature.auth.SignInScreen
import chat.homebuddy.feature.auth.SignedInPlaceholder
import chat.homebuddy.feature.auth.toSignInUiState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeBuddyTheme { HomeBuddyRoot() }
        }
    }
}

@Composable
private fun HomeBuddyRoot(viewModel: SessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSignInResult(result.data)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.openInBrowser.collect { uri -> context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    when (val current = state) {
        is SessionState.SignedIn -> SignedInPlaceholder(current, onSignOut = viewModel::signOut)
        else -> SignInScreen(current.toSignInUiState(), onSignIn = { signIn.launch(viewModel.signInIntent()) })
    }
}
