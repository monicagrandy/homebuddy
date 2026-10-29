package chat.homebuddy.feature.account

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.app.R
import chat.homebuddy.app.openWebPage

@Composable
fun AccountRoute(onSignOut: () -> Unit, modifier: Modifier = Modifier, viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    state?.let { current ->
        AccountScreen(
            state = current,
            actions = AccountActions(
                onOpenLink = { url ->
                    if (!context.openWebPage(url)) Toast.makeText(context, R.string.error_no_browser, Toast.LENGTH_LONG).show()
                },
                onSignOut = onSignOut,
                onDelete = viewModel::delete,
            ),
            modifier = modifier,
        )
    }
}
