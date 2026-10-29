package chat.homebuddy.feature.documents

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.app.R
import chat.homebuddy.app.openWebPage
import chat.homebuddy.app.startedOrMissing
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Adding a link or a file is kept in [viewModel], so it survives rotation and process death; the
 * menu and the delete confirmation are saved here.
 */
@Composable
fun DocumentsRoute(viewModel: DocumentsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var deletingEntryId by rememberSaveable { mutableStateOf<String?>(null) }

    // Refresh whenever the screen comes back, so changes made on other devices show up.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toString())
    }

    fun toast(text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    fun scanQrCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        // Google's code scanner runs on the device and needs no camera permission.
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                when (val link = parseManualLink(barcode.rawValue)) {
                    is ManualLink.Valid -> viewModel.onLinkChosen(link)
                    ManualLink.NotSecure -> toast(R.string.documents_qr_insecure)
                    ManualLink.NotALink -> toast(R.string.documents_qr_not_a_link)
                }
            }
            .addOnFailureListener { toast(R.string.documents_qr_unavailable) }
    }

    DocumentsScreen(
        state = state,
        actions = DocumentsActions(
            onRefresh = viewModel::refresh,
            onAdd = { menuOpen = true },
            onDelete = { deletingEntryId = it.entryId },
            onRetry = viewModel::retry,
            onDismiss = viewModel::dismiss,
            onOpenLink = { url -> if (!context.openWebPage(url)) toast(R.string.error_no_browser) },
            onMessageShown = viewModel::messageShown,
        ),
        modifier = modifier,
    )

    if (menuOpen) {
        AddDocumentMenu(
            onUpload = {
                menuOpen = false
                if (!startedOrMissing { pickPdf.launch(arrayOf("application/pdf")) }) toast(R.string.documents_no_file_picker)
            },
            onScan = { menuOpen = false; scanQrCode() },
            onLink = { menuOpen = false; viewModel.onPasteLink() },
            onDismiss = { menuOpen = false },
        )
    }
    AddingDocument(state, viewModel)
    // Looked up by id, so the dialog closes if the document disappears meanwhile (deleted on another device).
    state.documents.firstOrNull { it.entryId == deletingEntryId }?.let { document ->
        ConfirmDeleteDialog(
            document,
            onConfirm = { deletingEntryId = null; viewModel.delete(document) },
            onDismiss = { deletingEntryId = null },
        )
    }
}

/** The step of adding a link or a file that's in progress, if any. */
@Composable
private fun AddingDocument(state: DocumentsUiState, viewModel: DocumentsViewModel) {
    if (state.pastingLink) {
        PasteLinkDialog(onConfirm = viewModel::onLinkChosen, onDismiss = viewModel::cancelAdding)
    }
    state.picked?.let { file ->
        NameDocumentDialog(
            initialName = file.suggestedName ?: stringResource(R.string.documents_default_name),
            source = null,
            onConfirm = viewModel::addPickedFile,
            onDismiss = viewModel::cancelAdding,
        )
    }
    state.pendingLink?.let { link ->
        NameDocumentDialog(
            initialName = link.suggestedName,
            source = link.host,
            onConfirm = viewModel::addPendingLink,
            onDismiss = viewModel::cancelAdding,
        )
    }
}
