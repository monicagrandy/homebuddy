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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.core.auth.SessionState
import chat.homebuddy.core.designsystem.HomeBuddyTheme
import chat.homebuddy.feature.account.UpdateRequiredScreen
import chat.homebuddy.feature.auth.SessionViewModel
import chat.homebuddy.feature.auth.SignInScreen
import chat.homebuddy.feature.auth.toSignInUiState
import chat.homebuddy.feature.documents.IncomingShares
import chat.homebuddy.feature.documents.ManualLink
import chat.homebuddy.feature.documents.SharedDocument
import chat.homebuddy.feature.documents.parseManualLink
import chat.homebuddy.feature.home.HomeSetupRoute
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var incomingShares: IncomingShares

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            HomeBuddyTheme { HomeBuddyRoot(incomingShares) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        when {
            intent.type == "application/pdf" -> {
                // Read while this activity holds the share's permission; the documents screen copies it.
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
                incomingShares.offer(SharedDocument.File(uri, fileName = null))
            }
            intent.type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val link = text.split(Regex("\\s+")).map(::parseManualLink).firstOrNull { it is ManualLink.Valid }
                if (link is ManualLink.Valid) incomingShares.offer(SharedDocument.Link(link))
            }
        }
    }
}

@Composable
private fun HomeBuddyRoot(
    incomingShares: IncomingShares,
    viewModel: SessionViewModel = hiltViewModel(),
    app: AppViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mustUpdate by app.mustUpdate.collectAsStateWithLifecycle()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSignInResult(result.data)
    }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.openInBrowser.collect { uri -> context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    if (mustUpdate) {
        UpdateRequiredScreen(onUpdate = { context.startActivity(playStoreIntent(context.packageName)) })
        return
    }

    when (val current = state) {
        is SessionState.SignedIn ->
            if (current.needsHome) {
                HomeSetupRoute()
            } else {
                MainScaffold(session = current, incomingShares = incomingShares, onSignOut = viewModel::signOut)
            }
        else -> SignInScreen(current.toSignInUiState(), onSignIn = { signIn.launch(viewModel.signInIntent()) })
    }
}

/** The app's Play Store page (opens in the Play Store app when it's installed, the browser otherwise). */
private fun playStoreIntent(packageName: String): Intent =
    Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri())
