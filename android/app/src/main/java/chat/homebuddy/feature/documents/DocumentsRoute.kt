package chat.homebuddy.feature.documents

import android.net.Uri
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private sealed interface Pending {
    data class File(val uri: Uri, val name: String) : Pending
    data class Link(val link: ManualLink.Valid) : Pending
}

@Composable
fun DocumentsRoute(modifier: Modifier = Modifier, viewModel: DocumentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var deleting by remember { mutableStateOf<DocumentItem?>(null) }

    // Refresh whenever the screen comes back, so changes made on other devices show up.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    val shared by viewModel.incomingShares.pending.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        when (val document = viewModel.incomingShares.take()) {
            is SharedDocument.File -> pending = Pending.File(document.uri, viewModel.displayNameFor(document.uri, document.fileName))
            is SharedDocument.Link -> pending = Pending.Link(document.link)
            null -> {}
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = Pending.File(uri, viewModel.displayNameFor(uri, null))
    }

    fun scanQrCode() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        // Google's code scanner runs on the device and needs no camera permission.
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                when (val link = parseManualLink(barcode.rawValue)) {
                    is ManualLink.Valid -> pending = Pending.Link(link)
                    ManualLink.NotSecure -> Toast.makeText(context, "That QR code uses an insecure link, so it wasn't added.", Toast.LENGTH_LONG).show()
                    ManualLink.NotALink -> Toast.makeText(context, "That QR code doesn't contain a web link.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "The QR scanner isn't available on this phone right now.", Toast.LENGTH_LONG).show()
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
            onOpenLink = { url -> CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) },
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
        PasteLinkDialog(onConfirm = { pasteOpen = false; pending = Pending.Link(it) }, onDismiss = { pasteOpen = false })
    }
    when (val item = pending) {
        is Pending.File -> NameDocumentDialog(
            initialName = item.name,
            source = null,
            onConfirm = { name -> pending = null; viewModel.addFile(item.uri, name) },
            onDismiss = { pending = null },
        )
        is Pending.Link -> NameDocumentDialog(
            initialName = item.link.suggestedName,
            source = item.link.host,
            onConfirm = { name -> pending = null; viewModel.addLink(item.link, name) },
            onDismiss = { pending = null },
        )
        null -> {}
    }
    deleting?.let { document ->
        ConfirmDeleteDialog(document, onConfirm = { deleting = null; viewModel.delete(document) }, onDismiss = { deleting = null })
    }
}
