package chat.homebuddy.feature.documents

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SharedDocument {
    data class File(val uri: Uri, val fileName: String?) : SharedDocument
    data class Link(val link: ManualLink.Valid) : SharedDocument
}

/** Something shared to Home Buddy from another app, waiting for the documents screen to pick it up. */
@Singleton
class IncomingShares @Inject constructor() {
    private val _pending = MutableStateFlow<SharedDocument?>(null)
    val pending: StateFlow<SharedDocument?> = _pending.asStateFlow()

    fun offer(document: SharedDocument) {
        _pending.value = document
    }

    fun take(): SharedDocument? = _pending.value.also { _pending.value = null }
}
