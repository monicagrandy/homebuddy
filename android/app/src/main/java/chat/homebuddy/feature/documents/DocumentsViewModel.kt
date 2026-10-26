package chat.homebuddy.feature.documents

import android.content.Context
import android.net.Uri
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import retrofit2.HttpException

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentsViewModel @Inject constructor(
    observeCurrentHousehold: ObserveCurrentHouseholdUseCase,
    private val repository: DocumentsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val householdId = observeCurrentHousehold()
        .map { it?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<UiText?>(null)

    val state: StateFlow<DocumentsUiState> = householdId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(DocumentsUiState())
            } else {
                combine(repository.documents(id), repository.uploads(id), refreshing, message, ::documentsUiState)
            }
        }
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

    fun addFile(uri: Uri, name: String) = launchWithHousehold { id ->
        try {
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the file.")
                repository.addFile(id, name, input)
            }
        } catch (_: UploadFiles.FileTooLarge) {
            message.value = uiText(R.string.documents_too_large)
        } catch (_: IOException) {
            message.value = uiText(R.string.documents_unreadable)
        } catch (_: SecurityException) {
            message.value = uiText(R.string.documents_unreadable)
        }
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

    /** A readable name from the file's name, or null when it has none worth using. */
    fun displayNameFor(uri: Uri, fallback: String?): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return nameFromFileName(fromProvider ?: fallback)
    }

    /** Waits for the household when the cached profile hasn't been read yet (right after launch). */
    private fun launchWithHousehold(block: suspend (Int) -> Unit) {
        viewModelScope.launch { block(householdId.filterNotNull().first()) }
    }
}
