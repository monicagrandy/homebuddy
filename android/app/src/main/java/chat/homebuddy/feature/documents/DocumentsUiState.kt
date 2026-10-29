package chat.homebuddy.feature.documents

import chat.homebuddy.app.R
import chat.homebuddy.app.webPageUrl
import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState
import chat.homebuddy.core.data.documents.UploadRetry
import chat.homebuddy.core.data.documents.retryAction
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText

data class DocumentItem(val entryId: String, val name: String, val docType: String, val indexing: Boolean)

enum class UploadStatus { WAITING, UPLOADING, READING, FAILED, DUPLICATE, FINISHED }

data class UploadItem(
    val id: String,
    val name: String,
    val status: UploadStatus,
    val message: UiText? = null,
    /** For a link that turned out to be a web page: open it so the user can find the PDF. */
    val openUrl: String? = null,
    val canRetry: Boolean = false,
)

/** A file picked, or shared from another app, waiting for the user to name it. */
data class PickedFile(val uri: String, val suggestedName: String?)

data class DocumentsUiState(
    val documents: List<DocumentItem> = emptyList(),
    val uploads: List<UploadItem> = emptyList(),
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val message: UiText? = null,
    val picked: PickedFile? = null,
    /** Asking for a link to paste. */
    val pastingLink: Boolean = false,
    /** A link pasted, scanned or shared, waiting to be named. */
    val pendingLink: ManualLink.Valid? = null,
) {
    val isEmpty: Boolean get() = loaded && documents.isEmpty() && uploads.isEmpty()
}

fun UploadEntity.toItem(): UploadItem = UploadItem(
    id = id,
    name = displayName,
    status = when (state) {
        UploadState.QUEUED -> UploadStatus.WAITING
        UploadState.UPLOADING -> UploadStatus.UPLOADING
        UploadState.INDEXING -> UploadStatus.READING
        UploadState.FAILED -> UploadStatus.FAILED
        UploadState.DUPLICATE -> UploadStatus.DUPLICATE
        UploadState.COMPLETED -> UploadStatus.FINISHED
    },
    message = when {
        state == UploadState.DUPLICATE -> uiText(R.string.documents_duplicate)
        state != UploadState.FAILED -> null
        errorCode == "not_pdf" && url != null -> uiText(R.string.documents_not_a_pdf_link)
        else -> errorMessage?.asUiText() ?: uiText(R.string.documents_add_failed)
    },
    openUrl = webPageUrl(url)?.takeIf { state == UploadState.FAILED && errorCode == "not_pdf" },
    canRetry = retryAction != UploadRetry.NONE,
)

/**
 * The documents list, leaving out ones still shown as an upload in progress, so a new document
 * isn't listed twice while the server reads it.
 */
fun documentsUiState(documents: List<DocumentEntity>, uploads: List<UploadEntity>, refreshing: Boolean, message: UiText?) =
    DocumentsUiState(
        documents = documents
            .filterNot { document -> uploads.any { it.state == UploadState.INDEXING && it.entryId == document.entryId } }
            .map { DocumentItem(it.entryId, it.displayName, it.docType, indexing = it.status == "indexing") },
        uploads = uploads.filterNot { it.state == UploadState.COMPLETED }.map { it.toItem() },
        refreshing = refreshing,
        loaded = true,
        message = message,
    )
