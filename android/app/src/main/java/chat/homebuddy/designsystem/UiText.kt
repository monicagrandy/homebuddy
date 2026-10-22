package chat.homebuddy.designsystem

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource

/**
 * Text a ViewModel hands to a screen: one of the app's string resources, or a message from the
 * server, which arrives ready to show. ViewModels never read resources themselves, so the text
 * follows the phone's language even after it changes.
 */
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Verbatim(val text: String) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Resource(id, args.toList())

fun String.asUiText(): UiText = UiText.Verbatim(this)

@Composable
@ReadOnlyComposable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> stringResource(id, *args.toTypedArray())
    is UiText.Verbatim -> text
}
