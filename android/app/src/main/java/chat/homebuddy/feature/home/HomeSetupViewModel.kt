package chat.homebuddy.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.app.R
import chat.homebuddy.core.data.ProfileRepository
import chat.homebuddy.core.network.toApiError
import chat.homebuddy.designsystem.asUiText
import chat.homebuddy.designsystem.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

@HiltViewModel
class HomeSetupViewModel @Inject constructor(private val profile: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(HomeSetupUiState())
    val state: StateFlow<HomeSetupUiState> = _state.asStateFlow()

    fun create(details: HomeDetails) {
        if (_state.value.saving) return
        _state.value = HomeSetupUiState(saving = true)
        viewModelScope.launch {
            _state.value = try {
                // Once it's cached, the session has a home and the app moves on to the main screens.
                profile.createHousehold(details.name, details.zipCode, details.homeType)
                HomeSetupUiState()
            } catch (error: HttpException) {
                HomeSetupUiState(error = error.toApiError().message.asUiText())
            } catch (_: IOException) {
                HomeSetupUiState(error = uiText(R.string.error_offline))
            }
        }
    }
}
