package chat.homebuddy.feature.documents

import chat.homebuddy.core.data.DocumentEntity
import chat.homebuddy.core.data.UploadEntity
import chat.homebuddy.core.data.UploadState

data class DocumentItem(val entryId: String, val name: String, val docType: String, val indexing: Boolean)

enum class UploadStatus { WAITING, UPLOADING, READING, FAILED, DUPLICATE }

data class UploadItem(
    val id: String,
    val name: String,
    val status: UploadStatus,
    val message: String? = null,
    /** For a link that turned out to be a web page: open it so the user can find the PDF. */
    val openUrl: String? = null,
)

data class DocumentsUiState(
    val documents: List<DocumentItem> = emptyList(),
    val uploads: List<UploadItem> = emptyList(),
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val message: String? = null,
) {
    val isEmpty: Boolean get() = loaded && documents.isEmpty() && uploads.isEmpty()
}

const val NOT_A_PDF_LINK =
    "This link opens a web page, not a PDF. Open it, find the manual, and share the PDF to Home Buddy."

fun UploadEntity.toItem(): UploadItem = UploadItem(
    id = id,
    name = displayName,
    status = when (state) {
        UploadState.QUEUED -> UploadStatus.WAITING
        UploadState.UPLOADING -> UploadStatus.UPLOADING
        UploadState.INDEXING -> UploadStatus.READING
        UploadState.FAILED -> UploadStatus.FAILED
        UploadState.DUPLICATE -> UploadStatus.DUPLICATE
    },
    message = when {
        state == UploadState.DUPLICATE -> "Already in your documents."
        state != UploadState.FAILED -> null
        errorCode == "not_pdf" && url != null -> NOT_A_PDF_LINK
        else -> errorMessage ?: "The document couldn't be added."
    },
    openUrl = url?.takeIf { state == UploadState.FAILED && errorCode == "not_pdf" },
)

/**
 * The documents list, leaving out ones still shown as an upload in progress, so a new document
 * isn't listed twice while the server reads it.
 */
fun documentsUiState(documents: List<DocumentEntity>, uploads: List<UploadEntity>, refreshing: Boolean, message: String?) =
    DocumentsUiState(
        documents = documents
            .filterNot { document -> uploads.any { it.state == UploadState.INDEXING && it.entryId == document.entryId } }
            .map { DocumentItem(it.entryId, it.displayName, it.docType, indexing = it.status == "indexing") },
        uploads = uploads.map { it.toItem() },
        refreshing = refreshing,
        loaded = true,
        message = message,
    )
