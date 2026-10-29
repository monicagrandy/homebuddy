package chat.homebuddy.core.domain

import chat.homebuddy.core.auth.AuthRepository
import chat.homebuddy.core.network.AccessTokenProvider
import chat.homebuddy.core.network.RequestCredentials
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
    override suspend fun credentials(): RequestCredentials = auth.requestCredentials()

    override suspend fun refresh(rejected: RequestCredentials): RequestCredentials? = auth.refreshCredentials(rejected)

    override suspend fun onSessionRejected(rejected: RequestCredentials) = endSession.reject(rejected)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {
    @Binds
    abstract fun accessTokenProvider(session: NetworkSession): AccessTokenProvider
}
