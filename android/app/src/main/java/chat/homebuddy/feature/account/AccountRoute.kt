package chat.homebuddy.feature.account

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AccountRoute(onSignOut: () -> Unit, modifier: Modifier = Modifier, viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    state?.let { current ->
        AccountScreen(
            state = current,
            actions = AccountActions(
                onOpenLink = { CustomTabsIntent.Builder().build().launchUrl(context, it.toUri()) },
                onSignOut = onSignOut,
                onDelete = viewModel::delete,
            ),
            modifier = modifier,
        )
    }
}
