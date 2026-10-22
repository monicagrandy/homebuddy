package chat.homebuddy.feature.documents

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.core.data.DocumentsRepository
import chat.homebuddy.core.data.UploadFiles
import chat.homebuddy.core.domain.ObserveCurrentHouseholdUseCase
import chat.homebuddy.core.network.toApiError
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
    val incomingShares: IncomingShares,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val householdId = observeCurrentHousehold()
        .map { it?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

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
        val id = householdId.value ?: return
        viewModelScope.launch {
            refreshing.value = true
            try {
                repository.refresh(id)
            } catch (_: IOException) {
                // Offline: keep showing the cached list.
            } catch (error: HttpException) {
                message.value = error.toApiError().message
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
            message.value = "That PDF is larger than 50 MB."
        } catch (_: IOException) {
            message.value = "Couldn't read that file."
        } catch (_: SecurityException) {
            message.value = "Couldn't read that file."
        }
    }

    fun addLink(link: ManualLink.Valid, name: String) = launchWithHousehold { id -> repository.addLink(id, name, link.url) }

    fun delete(document: DocumentItem) = launchWithHousehold { id ->
        try {
            repository.delete(id, document.entryId)
        } catch (_: IOException) {
            message.value = "Couldn't reach Home Buddy to delete it. Try again when you're online."
        } catch (error: HttpException) {
            message.value = error.toApiError().message
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

    fun displayNameFor(uri: Uri, fallback: String?): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return nameFromFileName(fromProvider ?: fallback) ?: "Manual"
    }

    private fun launchWithHousehold(block: suspend (Int) -> Unit) {
        val id = householdId.value ?: return
        viewModelScope.launch { block(id) }
    }
}
