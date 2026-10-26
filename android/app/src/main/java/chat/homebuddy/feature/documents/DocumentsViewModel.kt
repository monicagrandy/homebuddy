package chat.homebuddy.feature.documents

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
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentsViewModel @Inject constructor(
    observeCurrentHousehold: ObserveCurrentHouseholdUseCase,
    private val repository: DocumentsRepository,
) : ViewModel() {
    private val householdId = observeCurrentHousehold()
        .map { it?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<UiText?>(null)
    private val picked = MutableStateFlow<PickedFile?>(null)

    val state: StateFlow<DocumentsUiState> = householdId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(DocumentsUiState())
            } else {
                combine(repository.documents(id), repository.uploads(id), refreshing, message, ::documentsUiState)
            }
        }
        .combine(picked) { state, picked -> state.copy(picked = picked) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsUiState())

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
        viewModelScope.launch { picked.value = PickedFile(uri, nameFromFileName(repository.fileName(uri))) }
    }

    fun addPickedFile(name: String) {
        val file = picked.value ?: return
        picked.value = null
        launchWithHousehold { id ->
            try {
                repository.addFile(id, name, file.uri)
            } catch (_: UploadFiles.FileTooLarge) {
                message.value = uiText(R.string.documents_too_large)
            } catch (_: IOException) {
                message.value = uiText(R.string.documents_unreadable)
            }
        }
    }

    fun dismissPickedFile() {
        picked.value = null
    }

    fun addLink(link: ManualLink.Valid, name: String) = launchWithHousehold { id -> repository.addLink(id, name, link.url) }

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

    /** Waits for the household when the cached profile hasn't been read yet (right after launch). */
    private fun launchWithHousehold(block: suspend (Int) -> Unit) {
        viewModelScope.launch { block(householdId.filterNotNull().first()) }
    }
}
