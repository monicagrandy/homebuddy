package chat.homebuddy.feature.documents

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/**
 * The documents tab. [sharedFile] (a content URI) or [sharedLink] is something shared to Home Buddy
 * from another app; the screen asks for its name before adding it.
 */
@Serializable
data class DocumentsDestination(val sharedFile: String? = null, val sharedLink: String? = null)

sealed interface SharedDocument {
    /** [uri] is the file's content:// address. */
    data class File(val uri: String) : SharedDocument
    data class Link(val link: ManualLink.Valid) : SharedDocument
}

fun NavGraphBuilder.documentsDestination() {
    composable<DocumentsDestination> { entry ->
        // The ViewModel takes the share in once, and remembers that it did across rotation and process death.
        val share = entry.toRoute<DocumentsDestination>().sharedDocument()
        DocumentsRoute(viewModel = hiltViewModel<DocumentsViewModel, DocumentsViewModel.Factory> { it.create(share) })
    }
}

private fun DocumentsDestination.sharedDocument(): SharedDocument? = when {
    sharedFile != null -> SharedDocument.File(sharedFile)
    sharedLink != null -> (parseManualLink(sharedLink) as? ManualLink.Valid)?.let(SharedDocument::Link)
    else -> null
}
