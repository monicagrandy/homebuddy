package chat.homebuddy.feature.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.app.R

@Composable
fun ConversationListRoute(
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    conversationGone: Boolean,
    onConversationGoneShown: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ConversationItem?>(null) }

    // Also runs when coming back from a conversation, so its new title and time show up.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    LaunchedEffect(conversationGone) {
        if (conversationGone) {
            viewModel.onConversationGone()
            onConversationGoneShown()
        }
    }

    ConversationListScreen(
        state = state,
        actions = ConversationListActions(
            onOpen = { onOpen(it.id) },
            onNew = onNew,
            onRefresh = { viewModel.refresh(full = true) },
            onDelete = { deleting = it },
            onMessageShown = viewModel::messageShown,
        ),
        modifier = modifier,
    )

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = { Text(stringResource(R.string.chat_delete_text)) },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(item) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun ConversationRoute(
    onBack: () -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gone by viewModel.gone.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val speech = remember { SpeechInput(context.applicationContext) }

    LaunchedEffect(speech) { viewModel.voiceAvailable.value = speech.isAvailable }
    DisposableEffect(speech) { onDispose { speech.release() } }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { speech.stop() }
    }
    LaunchedEffect(gone) {
        if (gone) onGone()
    }

    fun startDictation() {
        viewModel.onDictationStarted()
        speech.start(
            object : SpeechListener {
                override fun onPartial(text: String) = viewModel.onDictationResult(text, final = false)
                override fun onFinal(text: String) = viewModel.onDictationResult(text, final = true)
                override fun onError(message: Int) = viewModel.onDictationError(message)
            },
        )
    }

    // Asked the first time the mic is tapped, never before.
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation() else viewModel.onDictationError(R.string.chat_mic_permission_denied)
    }

    ConversationScreen(
        state = state,
        actions = ConversationActions(
            onBack = onBack,
            onDraftChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            onStop = viewModel::stopAnswer,
            onMic = {
                when {
                    state.listening -> {
                        speech.stop()
                        viewModel.onDictationStopped()
                    }
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startDictation()
                    else -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            // ACTION_DIAL fills in the number; the person places the call themselves.
            onCallEmergency = { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:911".toUri())) },
            onOpenSource = { source -> source.url?.let { CustomTabsIntent.Builder().build().launchUrl(context, it.toUri()) } },
            onMessageShown = viewModel::messageShown,
        ),
        modifier = modifier,
    )
}
