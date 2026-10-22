package chat.homebuddy.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.homebuddy.core.data.HouseholdRepository
import chat.homebuddy.core.network.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

@HiltViewModel
class HomeSetupViewModel @Inject constructor(private val households: HouseholdRepository) : ViewModel() {
    private val _state = MutableStateFlow(HomeSetupUiState())
    val state: StateFlow<HomeSetupUiState> = _state.asStateFlow()

    fun create(details: HomeDetails) {
        if (_state.value.saving) return
        _state.value = HomeSetupUiState(saving = true)
        viewModelScope.launch {
            _state.value = try {
                households.create(details.name, details.zipCode, details.homeType)
                HomeSetupUiState()
            } catch (error: HttpException) {
                HomeSetupUiState(error = error.toApiError().message)
            } catch (_: IOException) {
                HomeSetupUiState(error = "Couldn't reach Home Buddy. Check your connection and try again.")
            }
        }
    }
}
