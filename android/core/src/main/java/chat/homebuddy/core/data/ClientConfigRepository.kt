package chat.homebuddy.core.data

import chat.homebuddy.core.network.HomeBuddyApi

/**
 * Whether [current] (e.g. "1.2.0" or "1.2.0-dev") is at least [minimum]. Missing parts count as 0.
 * An unreadable version never blocks anyone.
 */
fun isVersionSupported(current: String, minimum: String?): Boolean {
    if (minimum.isNullOrBlank()) return true
    val have = parseVersion(current) ?: return true
    val need = parseVersion(minimum) ?: return true
    for (index in 0 until maxOf(have.size, need.size)) {
        val difference = have.getOrElse(index) { 0 } - need.getOrElse(index) { 0 }
        if (difference != 0) return difference > 0
    }
    return true
}

private fun parseVersion(version: String): List<Int>? =
    version.trim().substringBefore('-').substringBefore('+').split('.')
        .map { it.toIntOrNull() ?: return null }
        .takeIf { it.isNotEmpty() }

/** Asks the server which app versions it still supports. Offline or unknown means "supported". */
class ClientConfigRepository(private val api: HomeBuddyApi) {
    suspend fun mustUpdate(currentVersion: String): Boolean {
        val config = runCatching { api.clientConfig() }.getOrNull() ?: return false
        return !isVersionSupported(currentVersion, config.minSupported["android"])
    }
}
