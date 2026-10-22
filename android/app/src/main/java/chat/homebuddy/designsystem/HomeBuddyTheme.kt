package chat.homebuddy.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm neutrals with a deep green, set out in full so Material's default purple never shows
// through on cards, buttons and progress tracks.
private val Light = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE8DD),
    onPrimaryContainer = Color(0xFF0D2A22),
    secondary = Color(0xFFB4562E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6DCCF),
    onSecondaryContainer = Color(0xFF3A1607),
    background = Color(0xFFFBFAF7),
    onBackground = Color(0xFF1F2933),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF1F2933),
    surfaceVariant = Color(0xFFE9E5DD),
    onSurfaceVariant = Color(0xFF4A4740),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F4EF),
    surfaceContainer = Color(0xFFF1EEE8),
    surfaceContainerHigh = Color(0xFFECE8E1),
    surfaceContainerHighest = Color(0xFFE6E2DA),
    outline = Color(0xFF7A766E),
    outlineVariant = Color(0xFFCFCAC0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FC7B5),
    onPrimary = Color(0xFF0D2A22),
    primaryContainer = Color(0xFF1E4337),
    onPrimaryContainer = Color(0xFFCFE8DD),
    secondary = Color(0xFFE59A76),
    onSecondary = Color(0xFF3A1607),
    secondaryContainer = Color(0xFF5A2A14),
    onSecondaryContainer = Color(0xFFF6DCCF),
    background = Color(0xFF1C1B19),
    onBackground = Color(0xFFE6E2DA),
    surface = Color(0xFF1C1B19),
    onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF3A3732),
    onSurfaceVariant = Color(0xFFCBC6BC),
    surfaceContainerLowest = Color(0xFF161513),
    surfaceContainerLow = Color(0xFF22211E),
    surfaceContainer = Color(0xFF262522),
    surfaceContainerHigh = Color(0xFF302E2B),
    surfaceContainerHighest = Color(0xFF3B3935),
    outline = Color(0xFF948F86),
    outlineVariant = Color(0xFF4A4740),
)

@Composable
fun HomeBuddyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
