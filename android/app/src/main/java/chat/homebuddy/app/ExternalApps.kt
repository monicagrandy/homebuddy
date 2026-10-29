package chat.homebuddy.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import java.net.URI
import java.util.Locale

/**
 * [raw] if it's a web page (http or https, with a host), else null. Links come from the server
 * and from documents, and anything else (intent:, javascript:, file:, content:...) could start
 * another app or expose this one's files, so it's never opened.
 */
fun webPageUrl(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    val uri = runCatching { URI(text) }.getOrNull()
    return text.takeIf { uri?.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri?.host.isNullOrEmpty() }
}

/** Opens a web page in a Custom Tab. False when [url] isn't one, or nothing on the phone can show it. */
fun Context.openWebPage(url: String?): Boolean {
    val page = webPageUrl(url) ?: return false
    return startedOrMissing { CustomTabsIntent.Builder().build().launchUrl(this, page.toUri()) }
}

/** Opens the dialer with [number] filled in; the person places the call. False with no dialer, as on most tablets. */
fun Context.dial(number: String): Boolean =
    startedOrMissing { startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri())) }

/** Runs [launch], which starts another app's activity. False when no app can handle it. */
inline fun startedOrMissing(launch: () -> Unit): Boolean = try {
    launch()
    true
} catch (_: ActivityNotFoundException) {
    false
}
