package chat.homebuddy.feature.documents

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
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
    data class File(val uri: Uri) : SharedDocument
    data class Link(val link: ManualLink.Valid) : SharedDocument
}

fun NavGraphBuilder.documentsDestination() {
    composable<DocumentsDestination> { entry ->
        val destination = entry.toRoute<DocumentsDestination>()
        // Asked about once: not again after rotation, or when coming back to this tab.
        var shareOpened by rememberSaveable { mutableStateOf(false) }
        DocumentsRoute(
            share = destination.sharedDocument().takeUnless { shareOpened },
            onShareOpened = { shareOpened = true },
        )
    }
}

private fun DocumentsDestination.sharedDocument(): SharedDocument? = when {
    sharedFile != null -> SharedDocument.File(sharedFile.toUri())
    sharedLink != null -> (parseManualLink(sharedLink) as? ManualLink.Valid)?.let(SharedDocument::Link)
    else -> null
}
