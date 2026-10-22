package chat.homebuddy.feature.documents

import java.net.URI
import java.util.Locale

sealed interface ManualLink {
    /** Worth importing. [host] is shown before importing, so a misleading QR code is easy to spot. */
    data class Valid(val url: String, val host: String, val suggestedName: String) : ManualLink

    /** Plain http, which could be tampered with on the way. */
    data object NotSecure : ManualLink

    /** Not a web link at all (Wi-Fi details, text, a phone number...). */
    data object NotALink : ManualLink
}

/** Reads what a QR code (or a shared or pasted link) contains. Only https links are accepted. */
fun parseManualLink(raw: String?): ManualLink {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.any(Char::isWhitespace)) return ManualLink.NotALink
    val uri = runCatching { URI(text) }.getOrNull() ?: return ManualLink.NotALink
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "https" -> {
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return ManualLink.NotALink
            ManualLink.Valid(url = text, host = host, suggestedName = nameFromPath(uri.path) ?: host)
        }
        "http" -> ManualLink.NotSecure
        else -> ManualLink.NotALink
    }
}

/** "Bosch_SHX3AR75UC-manual.pdf" becomes "Bosch SHX3AR75UC manual". */
fun nameFromFileName(fileName: String?): String? {
    val base = fileName?.substringAfterLast('/')?.substringBeforeLast('.', missingDelimiterValue = fileName.substringAfterLast('/'))
    val cleaned = base?.replace(Regex("[_\\-+]+"), " ")?.replace(Regex("\\s+"), " ")?.trim()
    return cleaned?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun nameFromPath(path: String?): String? =
    path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let(::nameFromFileName)
