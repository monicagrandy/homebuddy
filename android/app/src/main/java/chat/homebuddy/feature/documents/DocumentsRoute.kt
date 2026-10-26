package chat.homebuddy.feature.documents

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.app.R
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning



@Composable
fun DocumentsRoute(
    share: SharedDocument?,
    onShareOpened: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<ManualLink.Valid?>(null) }
    var deleting by remember { mutableStateOf<DocumentItem?>(null) }

    // Refresh whenever the screen comes back, so changes made on other devices show up.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    LaunchedEffect(share) {
        when (share) {
            is SharedDocument.File -> viewModel.onFilePicked(share.uri)
            is SharedDocument.Link -> pendingLink = share.link
            null -> return@LaunchedEffect
        }
        onShareOpened()
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toString())
    }

    fun scanQrCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        // Google's code scanner runs on the device and needs no camera permission.
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                when (val link = parseManualLink(barcode.rawValue)) {
                    is ManualLink.Valid -> pendingLink = link
                    ManualLink.NotSecure -> Toast.makeText(context, R.string.documents_qr_insecure, Toast.LENGTH_LONG).show()
                    ManualLink.NotALink -> Toast.makeText(context, R.string.documents_qr_not_a_link, Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, R.string.documents_qr_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    DocumentsScreen(
        state = state,
        actions = DocumentsActions(
            onRefresh = viewModel::refresh,
            onAdd = { menuOpen = true },
            onDelete = { deleting = it },
            onRetry = viewModel::retry,
            onDismiss = viewModel::dismiss,
            onOpenLink = { url -> CustomTabsIntent.Builder().build().launchUrl(context, url.toUri()) },
            onMessageShown = viewModel::messageShown,
        ),
        modifier = modifier,
    )

    if (menuOpen) {
        AddDocumentMenu(
            onUpload = { menuOpen = false; pickPdf.launch(arrayOf("application/pdf")) },
            onScan = { menuOpen = false; scanQrCode() },
            onLink = { menuOpen = false; pasteOpen = true },
            onDismiss = { menuOpen = false },
        )
    }
    if (pasteOpen) {
        PasteLinkDialog(onConfirm = { pasteOpen = false; pendingLink = it }, onDismiss = { pasteOpen = false })
    }
    state.picked?.let { file ->
        NameDocumentDialog(
            initialName = file.suggestedName ?: stringResource(R.string.documents_default_name),
            source = null,
            onConfirm = viewModel::addPickedFile,
            onDismiss = viewModel::dismissPickedFile,
        )
    }
    pendingLink?.let { link ->
        NameDocumentDialog(
            initialName = link.suggestedName,
            source = link.host,
            onConfirm = { name -> pendingLink = null; viewModel.addLink(link, name) },
            onDismiss = { pendingLink = null },
        )
    }
    deleting?.let { document ->
        ConfirmDeleteDialog(document, onConfirm = { deleting = null; viewModel.delete(document) }, onDismiss = { deleting = null })
    }
}
