package chat.homebuddy.feature.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ChatRoute(modifier: Modifier = Modifier, viewModel: ChatViewModel = hiltViewModel()) {
    val list by viewModel.listState.collectAsStateWithLifecycle()
    val conversation by viewModel.conversationState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val speech = remember { SpeechInput(context.applicationContext) }
    var deleting by remember { mutableStateOf<ConversationItem?>(null) }

    LaunchedEffect(speech) { viewModel.voiceAvailable.value = speech.isAvailable }
    DisposableEffect(speech) { onDispose { speech.release() } }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshList()
        onPauseOrDispose { speech.stop() }
    }

    fun startDictation() {
        viewModel.onDictationStarted()
        speech.start(
            object : SpeechListener {
                override fun onPartial(text: String) = viewModel.onDictationResult(text, final = false)
                override fun onFinal(text: String) = viewModel.onDictationResult(text, final = true)
                override fun onError(message: String) = viewModel.onDictationError(message)
            },
        )
    }

    // Asked the first time the mic is tapped, never before.
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation() else viewModel.onDictationError("Allow microphone access to dictate, or type your question.")
    }

    val current = conversation
    if (current == null) {
        ConversationListScreen(
            state = list,
            actions = ConversationListActions(
                onOpen = viewModel::open,
                onNew = viewModel::newConversation,
                onRefresh = { viewModel.refreshList(full = true) },
                onDelete = { deleting = it },
                onMessageShown = viewModel::listMessageShown,
            ),
            modifier = modifier,
        )
    } else {
        BackHandler { viewModel.close() }
        ConversationScreen(
            state = current,
            actions = ConversationActions(
                onBack = viewModel::close,
                onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::send,
                onStop = viewModel::stopAnswer,
                onMic = {
                    when {
                        current.listening -> {
                            speech.stop()
                            viewModel.onDictationStopped()
                        }
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startDictation()
                        else -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                // ACTION_DIAL fills in the number; the person places the call themselves.
                onCallEmergency = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) },
                onOpenSource = { source -> source.url?.let { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(it)) } },
                onMessageShown = viewModel::chatMessageShown,
            ),
            modifier = modifier,
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this conversation?") },
            text = { Text("It's removed from every device you're signed in on.") },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.deleteConversation(item) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
