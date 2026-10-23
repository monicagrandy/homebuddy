package chat.homebuddy.core.domain

import chat.homebuddy.core.auth.AuthRepository
import chat.homebuddy.core.auth.SignOutReason
import chat.homebuddy.core.network.AccessTokenProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Gives the HTTP client this phone's tokens, and ends the session when the server rejects it. */
class NetworkSession @Inject constructor(
    private val auth: AuthRepository,
    private val endSession: EndSessionUseCase,
) : AccessTokenProvider {
    override suspend fun accessToken(forceRefresh: Boolean): String? = auth.accessToken(forceRefresh)

    override suspend fun onSessionRejected() {
        if (auth.isSignedIn()) {
            // For example the account was deleted on another device, or the refresh token expired.
            endSession(SignOutReason.SESSION_ENDED)
        } else {
            // A sign-in still being set up: SignInUseCase reports the failure.
            auth.forgetUnsavedSignIn()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {
    @Binds
    abstract fun accessTokenProvider(session: NetworkSession): AccessTokenProvider
}
