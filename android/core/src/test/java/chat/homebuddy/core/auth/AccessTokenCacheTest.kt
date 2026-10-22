package chat.homebuddy.core.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessTokenCacheTest {
    private var now = 1_000_000L
    private val cache = AccessTokenCache(now = { now })
    private var refreshes = 0

    private fun tokens(access: String, expiresAt: Long? = now + 3_600_000) =
        TokenSet(accessToken = access, idToken = null, refreshToken = "refresh", expiresAtMillis = expiresAt)

    private suspend fun accessToken(forceRefresh: Boolean = false, renewed: TokenSet? = tokens("renewed")) =
        cache.accessToken(forceRefresh) {
            refreshes++
            renewed
        }

    @Test
    fun `a fresh token is reused without refreshing`() = runTest {
        cache.put(tokens("current"))

        assertEquals("current", accessToken())
        assertEquals(0, refreshes)
    }

    @Test
    fun `a token about to expire is refreshed first`() = runTest {
        cache.put(tokens("current", expiresAt = now + 30_000))

        assertEquals("renewed", accessToken())
        assertEquals(1, refreshes)
    }

    @Test
    fun `a forced refresh always refreshes`() = runTest {
        cache.put(tokens("current"))

        assertEquals("renewed", accessToken(forceRefresh = true))
        assertEquals(1, refreshes)
    }

    @Test
    fun `parallel requests share one refresh`() = runTest {
        val results = (1..5).map {
            async {
                cache.accessToken(forceRefresh = false) {
                    refreshes++
                    delay(100)
                    tokens("renewed")
                }
            }
        }.awaitAll()

        assertEquals(List(5) { "renewed" }, results)
        assertEquals(1, refreshes)
    }

    @Test
    fun `a rejected refresh leaves no token`() = runTest {
        cache.put(tokens("current", expiresAt = now))

        assertNull(accessToken(renewed = null))
        assertNull(cache.refreshToken())
    }
}
