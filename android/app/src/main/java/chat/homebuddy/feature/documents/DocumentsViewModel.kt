package chat.homebuddy.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.app.R
import chat.homebuddy.core.data.documents.DocumentsRepository
import chat.homebuddy.core.data.documents.UploadFiles
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.network.toApiError
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * The documents tab. Adding a link or a file takes a few steps (paste, then name it), and whatever
 * is in progress is kept in the saved state, together with whether [share] has been taken in yet,
 * so neither rotating nor the app being killed in the background drops it or shows it twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = DocumentsViewModel.Factory::class)
class DocumentsViewModel @AssistedInject constructor(
    /** Something shared to Home Buddy from another app, from the destination; null when there's none. */
    @Assisted share: SharedDocument?,
    private val savedStateHandle: SavedStateHandle,
    observeCurrentHousehold: ObserveCurrentHouseholdUseCase,
    private val repository: DocumentsRepository,
) : ViewModel() {
    private val householdId = observeCurrentHousehold()
        .map { it?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<UiText?>(null)

    /** The address of a file waiting to be named. */
    private val pickedUri = savedStateHandle.getStateFlow<String?>(PICKED_FILE, null)

    /** [pickedUri] once its name has been looked up, so there's a name to suggest. */
    private val picked = MutableStateFlow<PickedFile?>(null)

    private val pastingLink = savedStateHandle.getStateFlow(PASTING_LINK, false)
    private val pendingLink = savedStateHandle.getStateFlow<String?>(PENDING_LINK, null)
        .map { url -> parseManualLink(url) as? ManualLink.Valid }

    private val inProgress = combine(picked, pastingLink, pendingLink) { picked, pasting, link -> InProgress(picked, pasting, link) }

    val state: StateFlow<DocumentsUiState> = householdId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(DocumentsUiState())
            } else {
                combine(repository.documents(id), repository.uploads(id), refreshing, message, ::documentsUiState)
            }
        }
        .combine(inProgress) { state, adding ->
            state.copy(picked = adding.picked, pastingLink = adding.pastingLink, pendingLink = adding.pendingLink)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsUiState())

    init {
        // After process death, a file still waiting to be named needs its name again.
        pickedUri.value?.let(::lookUpName)
        // Taken in once, and the flag is saved together with the step it starts.
        if (share != null && savedStateHandle.get<Boolean>(SHARE_TAKEN) != true) {
            savedStateHandle[SHARE_TAKEN] = true
            when (share) {
                is SharedDocument.File -> onFilePicked(share.uri)
                is SharedDocument.Link -> onLinkChosen(share.link)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val id = householdId.filterNotNull().first()
            refreshing.value = true
            try {
                repository.refresh(id)
            } catch (_: IOException) {
                // Offline: keep showing the cached list.
            } catch (error: HttpException) {
                message.value = error.toApiError().message.asUiText()
            } finally {
                refreshing.value = false
            }
        }
    }

    /** A file picked, or shared from another app ([uri] is its content:// address): the screen asks for its name next. */
    fun onFilePicked(uri: String) {
        savedStateHandle[PICKED_FILE] = uri
        lookUpName(uri)
    }

    fun addPickedFile(name: String) {
        val file = picked.value ?: return
        clearPickedFile()
        launchWithHousehold { id ->
            try {
                repository.addFile(id, name, file.uri)
            } catch (_: UploadFiles.FileTooLarge) {
                message.value = uiText(R.string.documents_too_large)
            } catch (_: UploadFiles.CopyFailed) {
                message.value = uiText(R.string.documents_copy_failed)
            } catch (_: IOException) {
                message.value = uiText(R.string.documents_unreadable)
            }
        }
    }

    /** The screen asks for a link to paste. */
    fun onPasteLink() {
        savedStateHandle[PASTING_LINK] = true
    }

    /** A link pasted, scanned or shared: the screen asks for its name next. */
    fun onLinkChosen(link: ManualLink.Valid) {
        savedStateHandle[PASTING_LINK] = false
        savedStateHandle[PENDING_LINK] = link.url
    }

    fun addPendingLink(name: String) {
        val link = parseManualLink(savedStateHandle.get<String>(PENDING_LINK)) as? ManualLink.Valid ?: return
        savedStateHandle[PENDING_LINK] = null
        launchWithHousehold { id -> repository.addLink(id, name, link.url) }
    }

    /** Cancel on any step of adding a document: nothing is added. */
    fun cancelAdding() {
        savedStateHandle[PASTING_LINK] = false
        savedStateHandle[PENDING_LINK] = null
        clearPickedFile()
    }

    fun delete(document: DocumentItem) = launchWithHousehold { id ->
        try {
            repository.delete(id, document.entryId)
        } catch (_: IOException) {
            message.value = uiText(R.string.documents_delete_offline)
        } catch (error: HttpException) {
            message.value = error.toApiError().message.asUiText()
        }
    }

    fun retry(upload: UploadItem) {
        viewModelScope.launch { repository.retry(upload.id) }
    }

    fun dismiss(upload: UploadItem) {
        viewModelScope.launch { repository.dismiss(upload.id) }
    }

    fun messageShown() {
        message.value = null
    }

    private fun lookUpName(uri: String) {
        viewModelScope.launch {
            val name = nameFromFileName(repository.fileName(uri))
            if (pickedUri.value == uri) picked.value = PickedFile(uri, name)
        }
    }

    private fun clearPickedFile() {
        savedStateHandle[PICKED_FILE] = null
        picked.value = null
    }

    /** Waits for the household when the cached profile hasn't been read yet (right after launch). */
    private fun launchWithHousehold(block: suspend (Int) -> Unit) {
        viewModelScope.launch { block(householdId.filterNotNull().first()) }
    }

    @AssistedFactory
    interface Factory {
        fun create(share: SharedDocument?): DocumentsViewModel
    }

    private data class InProgress(val picked: PickedFile?, val pastingLink: Boolean, val pendingLink: ManualLink.Valid?)

    private companion object {
        const val SHARE_TAKEN = "share_taken"
        const val PICKED_FILE = "picked_file"
        const val PASTING_LINK = "pasting_link"
        const val PENDING_LINK = "pending_link"
    }
}
