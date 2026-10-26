package chat.homebuddy.core.testing

import chat.homebuddy.core.data.profile.Household
import chat.homebuddy.core.data.profile.Profile
import chat.homebuddy.core.data.profile.ProfileRepository
import chat.homebuddy.core.data.profile.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [ProfileRepository] in memory, for ViewModel tests on the JVM. [server] is the account the
 * backend has, and signing in or refreshing copies it into the cache. Set [failure] to make those
 * calls throw instead, such as an IOException when offline or an [httpError].
 */
class TestProfileRepository : ProfileRepository {
    var server = Profile(
        User(id = 1, email = "pat@example.com", displayName = "Pat"),
        listOf(Household(id = 7, name = "Elm Street", zipCode = "80202", homeType = "condo", role = "owner")),
    )
    var failure: Exception? = null
    private val cached = MutableStateFlow<Profile?>(null)

    override val profile: Flow<Profile?> = cached

    override suspend fun bootstrap(idToken: String, accessToken: String) = fetch()

    override suspend fun refresh() = fetch()

    override suspend fun createHousehold(name: String, zipCode: String, homeType: String) = notFaked()

    override suspend fun deleteAccount() = notFaked()

    override suspend fun clear() {
        cached.value = null
    }

    private fun fetch() {
        failure?.let { throw it }
        cached.value = server
    }
}
