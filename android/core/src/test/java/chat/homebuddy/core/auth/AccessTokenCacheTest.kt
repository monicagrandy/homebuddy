package chat.homebuddy.core.auth

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTokenCacheTest {
    private var now = 1_000_000L
    private val cache = AccessTokenCache(now = { now })
    private var refreshes = 0

    private fun tokens(access: String, expiresAt: Long? = now + 3_600_000) =
        TokenSet(accessToken = access, idToken = null, refreshToken = "refresh", expiresAtMillis = expiresAt)

    private suspend fun accessToken(forceRefresh: Boolean = false, renewed: TokenSet? = tokens("renewed")) =
        cache.accessToken(forceRefresh, loadRefreshToken = { "refresh" }) {
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
        cache.put(tokens("expired", expiresAt = now))
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

    @Test
    fun `signing out discards an in-flight refresh`() = runTest {
        cache.put(tokens("old"))
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                cache.accessToken(forceRefresh = true) {
                    started.complete(Unit)
                    resume.await()
                    tokens("old-renewed")
                }
            }
        }
        started.await()

        cache.clear()
        resume.complete(Unit)

        assertTrue(result.await().exceptionOrNull() is IOException)
        assertNull(cache.refreshToken())
        assertNull(cache.accessToken(forceRefresh = false) { error("No session should be refreshed") })
    }

    @Test
    fun `a queued refresh cannot use a replacement session`() = runTest {
        cache.put(tokens("old"))
        val resume = CompletableDeferred<Unit>()
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                cache.accessToken(forceRefresh = true) {
                    resume.await()
                    tokens("old-renewed")
                }
            }
        }
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { cache.accessToken(forceRefresh = true) { error("Superseded request must not refresh") } }
        }

        cache.put(tokens("new"))
        // The new session can refresh without waiting for the old network call.
        assertEquals("new-renewed", accessToken(forceRefresh = true, renewed = tokens("new-renewed")))
        resume.complete(Unit)

        assertTrue(active.await().exceptionOrNull() is IOException)
        assertTrue(queued.await().exceptionOrNull() is IOException)
        assertEquals("new-renewed", accessToken())
    }

    @Test
    fun `a rejected old refresh cannot clear a replacement session`() = runTest {
        cache.put(tokens("old"))
        val resume = CompletableDeferred<Unit>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                cache.accessToken(forceRefresh = true) {
                    resume.await()
                    null
                }
            }
        }

        cache.put(tokens("new"))
        resume.complete(Unit)

        assertTrue(result.await().exceptionOrNull() is IOException)
        assertEquals("new", accessToken())
    }

    @Test
    fun `sign-out waits for a started rotation write and then clears it`() = runTest {
        var stored: String? = "refresh"
        val writing = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            cache.accessToken(
                forceRefresh = true,
                loadRefreshToken = { stored },
                saveRefreshToken = {
                    writing.complete(Unit)
                    resume.await()
                    stored = it
                },
            ) { tokens("renewed").copy(refreshToken = "rotated") }
        }
        writing.await()

        val cleared = async(start = CoroutineStart.UNDISPATCHED) { cache.clear { stored = null } }
        resume.complete(Unit)
        result.await()
        cleared.await()

        assertNull(stored)
        assertNull(cache.refreshToken())
    }
}
