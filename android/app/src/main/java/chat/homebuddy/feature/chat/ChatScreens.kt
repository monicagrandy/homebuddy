package chat.homebuddy.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.homebuddy.app.R
import chat.homebuddy.core.network.SourceDto

data class ConversationListActions(
    val onOpen: (ConversationItem) -> Unit = {},
    val onNew: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDelete: (ConversationItem) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(state: ConversationListUiState, actions: ConversationListActions, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item { Text("Chat", style = MaterialTheme.typography.headlineMedium) }
                    item { Text(RETENTION_NOTE, style = MaterialTheme.typography.bodySmall) }
                    state.message?.let { message -> item { Banner(message, actions.onMessageShown) } }
                    if (state.loaded && state.items.isEmpty()) {
                        item {
                            Text(
                                "Ask about anything in your home's manuals and warranties, like \"Why is my dishwasher not draining?\"",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                    items(state.items, key = { it.id }) { item ->
                        Card(onClick = { actions.onOpen(item) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(item.updatedLabel, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { actions.onDelete(item) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete conversation ${item.title}")
                                }
                            }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = actions.onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New question") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

data class ConversationActions(
    val onBack: () -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onMic: () -> Unit = {},
    val onCallEmergency: () -> Unit = {},
    val onOpenSource: (SourceDto) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
fun ConversationScreen(state: ConversationUiState, actions: ConversationActions, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val itemCount = state.history.size + state.turns.size
    LaunchedEffect(itemCount, state.turns.lastOrNull()?.answer?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to conversations") }
                Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(state.history) { message ->
                    if (message.fromUser) UserBubble(message.content) else AnswerBlock(message.content)
                }
                items(state.turns) { turn -> Turn(turn, actions) }
            }
            state.message?.let { Banner(it, actions.onMessageShown, Modifier.padding(horizontal = 16.dp)) }
            Composer(state, actions)
        }
    }
}

@Composable
private fun Turn(turn: ChatTurn, actions: ConversationActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UserBubble(turn.question)
        turn.safety?.let { SafetyCard(it, actions.onCallEmergency) }
        if (turn.answer.isNotEmpty()) AnswerBlock(turn.answer)
        turn.status?.let { status ->
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        if (turn.sources.isNotEmpty()) Sources(turn.sources, actions.onOpenSource)
        turn.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        turn.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun AnswerBlock(markdown: String) {
    MarkdownText(markdown, Modifier.fillMaxWidth())
}

@Composable
private fun SafetyCard(alert: SafetyAlert, onCall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    if (alert.critical) "This could be an emergency" else "This could be dangerous",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                "Get everyone to safety first. Home Buddy can't call anyone for you.",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = onCall,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.semantics { contentDescription = "Call 911. Opens the phone app; you place the call." },
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Text("Call 911", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Sources(sources: List<SourceDto>, onOpen: (SourceDto) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("From your documents", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.take(3).forEach { source ->
                if (source.url != null) {
                    AssistChip(onClick = { onOpen(source) }, label = { Text(source.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                } else {
                    // Uploaded documents have no page to open, so they're a label rather than a button.
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            source.label(),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

internal fun SourceDto.label(): String = if (page != null) "$displayName · p. $page" else displayName

@Composable
private fun Composer(state: ConversationUiState, actions: ConversationActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = actions.onDraftChange,
            placeholder = { Text(if (state.listening) "Listening…" else "Ask about your home") },
            modifier = Modifier.weight(1f),
            maxLines = 5,
        )
        if (state.voiceAvailable && !state.answering) {
            IconButton(onClick = actions.onMic) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(if (state.listening) R.drawable.ic_mic_on else R.drawable.ic_mic),
                    contentDescription = if (state.listening) "Stop dictating" else "Dictate your question",
                    tint = if (state.listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.answering) {
            FilledIconButton(onClick = actions.onStop) { Icon(Icons.Filled.Close, contentDescription = "Stop the answer") }
        } else {
            FilledIconButton(onClick = actions.onSend, enabled = state.canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun Banner(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = modifier) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onClose) { Text("OK") }
        }
    }
}
