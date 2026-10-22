package chat.homebuddy.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.designsystem.HomeBuddyTheme
import chat.homebuddy.feature.auth.AppUiState
import chat.homebuddy.feature.auth.SessionViewModel
import chat.homebuddy.feature.auth.SignInScreen
import chat.homebuddy.feature.documents.DocumentsDestination
import chat.homebuddy.feature.documents.ManualLink
import chat.homebuddy.feature.documents.parseManualLink
import chat.homebuddy.feature.home.HomeSetupRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Something shared from another app, held until the signed-in app can open it (it may arrive signed out). */
    private var pendingShare by mutableStateOf<DocumentsDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingShare = if (savedInstanceState == null) {
            intent.toShare()
        } else {
            savedInstanceState.getString(PENDING_SHARE)?.let { Json.decodeFromString<DocumentsDestination>(it) }
        }
        setContent {
            HomeBuddyTheme { HomeBuddyRoot(pendingShare, onShareOpened = { pendingShare = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.toShare()?.let { pendingShare = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingShare?.let { outState.putString(PENDING_SHARE, Json.encodeToString(it)) }
    }

    private companion object {
        const val PENDING_SHARE = "pending_share"
    }
}

/**
 * A PDF or a link shared to Home Buddy. The activity holds the permission to read a shared file,
 * so the documents screen can copy it while the activity is alive.
 */
private fun Intent.toShare(): DocumentsDestination? {
    if (action != Intent.ACTION_SEND) return null
    return when (type) {
        "application/pdf" -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let { DocumentsDestination(sharedFile = it.toString()) }
        "text/plain" -> getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            .split(Regex("\\s+"))
            .firstNotNullOfOrNull { parseManualLink(it) as? ManualLink.Valid }
            ?.let { DocumentsDestination(sharedLink = it.url) }
        else -> null
    }
}

/**
 * Which part of the app shows follows the session, not navigation: you can't go "back" to
 * signing in, and the stored session decides it again on every launch.
 */
@Composable
private fun HomeBuddyRoot(
    pendingShare: DocumentsDestination?,
    onShareOpened: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logoutUrl by viewModel.pendingLogoutUrl.collectAsStateWithLifecycle()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSignInResult(result.data)
    }
    val context = LocalContext.current
    LaunchedEffect(logoutUrl) {
        logoutUrl?.let { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            viewModel.onLogoutPageOpened()
        }
    }

    when (val current = state) {
        AppUiState.Main -> MainScaffold(share = pendingShare, onShareOpened = onShareOpened, onSignOut = viewModel::onSignOutClick)
        AppUiState.HomeSetup -> HomeSetupRoute()
        is AppUiState.SignIn -> SignInScreen(current.screen, onSignIn = { viewModel.onSignInClick()?.let(signIn::launch) })
    }
}
