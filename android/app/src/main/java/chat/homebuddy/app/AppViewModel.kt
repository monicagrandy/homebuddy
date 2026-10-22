package chat.homebuddy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.core.data.ClientConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-wide checks at launch. */
@HiltViewModel
class AppViewModel @Inject constructor(clientConfig: ClientConfigRepository) : ViewModel() {
    private val _mustUpdate = MutableStateFlow(false)
    val mustUpdate: StateFlow<Boolean> = _mustUpdate.asStateFlow()

    init {
        viewModelScope.launch { _mustUpdate.value = clientConfig.mustUpdate(BuildConfig.VERSION_NAME) }
    }
}
