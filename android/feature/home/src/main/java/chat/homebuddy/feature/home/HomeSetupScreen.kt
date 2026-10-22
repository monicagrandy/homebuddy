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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Values the backend's households.home_type expects, with what people see. */
val HOME_TYPES = listOf(
    "single_family" to "House",
    "townhouse" to "Townhouse",
    "condo" to "Condo",
    "apartment" to "Apartment",
    "other" to "Something else",
)

data class HomeSetupUiState(val saving: Boolean = false, val error: String? = null)

data class HomeDetails(val name: String, val zipCode: String, val homeType: String)

/** Field problems to show, empty when the details can be sent. */
fun HomeDetails.problems(): Map<String, String> = buildMap {
    if (name.isBlank()) put("name", "Give your home a name.")
    if (name.trim().length > 50) put("name", "Keep the name under 50 characters.")
    if (!Regex("""\d{5}""").matches(zipCode.trim())) put("zip", "Enter a 5-digit zip code.")
    if (HOME_TYPES.none { it.first == homeType }) put("type", "Choose a home type.")
}

@Composable
fun HomeSetupScreen(state: HomeSetupUiState, onSubmit: (HomeDetails) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    var zip by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(HOME_TYPES.first().first) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val details = HomeDetails(name, zip, type)
    val problems = if (submitted) details.problems() else emptyMap()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Set up your home", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Home Buddy keeps your documents and answers for this home. The zip code helps with local advice.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name, e.g. Elm Street") },
                isError = "name" in problems,
                supportingText = problems["name"]?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = zip,
                onValueChange = { zip = it.filter(Char::isDigit).take(5) },
                label = { Text("Zip code") },
                isError = "zip" in problems,
                supportingText = problems["zip"]?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Type of home", style = MaterialTheme.typography.titleSmall)
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
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.saving) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = {
                        submitted = true
                        if (details.problems().isEmpty()) onSubmit(details.copy(name = name.trim(), zipCode = zip.trim()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
            }
        }
    }
}

@Composable
fun HomeSetupRoute(modifier: Modifier = Modifier, viewModel: HomeSetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeSetupScreen(state, onSubmit = viewModel::create, modifier = modifier)
}
