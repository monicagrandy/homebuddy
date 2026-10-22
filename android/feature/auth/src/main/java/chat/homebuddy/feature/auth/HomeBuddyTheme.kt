package chat.homebuddy.feature.auth

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Lives here until there's more than one feature module; then it moves to :core:designsystem.
private val Light = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color.White,
    secondary = Color(0xFFB4562E),
    background = Color(0xFFFBFAF7),
    surface = Color(0xFFFBFAF7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FC7B5),
    onPrimary = Color(0xFF0D2A22),
    secondary = Color(0xFFE59A76),
    background = Color(0xFF1C1B19),
    surface = Color(0xFF1C1B19),
)

@Composable
fun HomeBuddyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
