package chat.homebuddy.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.homebuddy.app.R
import chat.homebuddy.designsystem.UiText
import chat.homebuddy.designsystem.asString

/** Values the backend's households.home_type expects, with what people see. */
val HOME_TYPES = listOf(
    "single_family" to R.string.home_type_single_family,
    "townhouse" to R.string.home_type_townhouse,
    "condo" to R.string.home_type_condo,
    "apartment" to R.string.home_type_apartment,
    "other" to R.string.home_type_other,
)

data class HomeSetupUiState(val saving: Boolean = false, val error: UiText? = null)

data class HomeDetails(val name: String, val zipCode: String, val homeType: String)

/** Field problems to show (string resources by field), empty when the details can be sent. */
fun HomeDetails.problems(): Map<String, Int> = buildMap {
    if (name.isBlank()) put("name", R.string.home_setup_error_name_missing)
    if (name.trim().length > 50) put("name", R.string.home_setup_error_name_long)
    if (!Regex("""\d{5}""").matches(zipCode.trim())) put("zip", R.string.home_setup_error_zip)
    if (HOME_TYPES.none { it.first == homeType }) put("type", R.string.home_setup_error_type)
}

@Composable
fun HomeSetupScreen(state: HomeSetupUiState, onSubmit: (HomeDetails) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    var zip by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(HOME_TYPES.first().first) }
    // Field errors only show after the first tap on Continue, not while someone is still typing.
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val details = HomeDetails(name, zip, type)
    val problems = if (showErrors) details.problems() else emptyMap()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.home_setup_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.home_setup_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.home_setup_name_label)) },
                isError = "name" in problems,
                supportingText = problems["name"]?.let { { Text(stringResource(it)) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = zip,
                onValueChange = { zip = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.home_setup_zip_label)) },
                isError = "zip" in problems,
                supportingText = problems["zip"]?.let { { Text(stringResource(it)) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.home_setup_type_label), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                HOME_TYPES.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = type == value, onClick = { type = value }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = type == value, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            if (state.saving) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = {
                        showErrors = true
                        if (details.problems().isEmpty()) onSubmit(details.copy(name = name.trim(), zipCode = zip.trim()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.home_setup_continue)) }
            }
        }
    }
}

@Composable
fun HomeSetupRoute(modifier: Modifier = Modifier, viewModel: HomeSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeSetupScreen(state, onSubmit = viewModel::create, modifier = modifier)
}
