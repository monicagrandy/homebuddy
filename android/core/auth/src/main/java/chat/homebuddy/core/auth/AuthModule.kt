package chat.homebuddy.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import chat.homebuddy.core.network.AccessTokenProvider
import chat.homebuddy.core.network.HomeBuddyApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindings {
    /** Features add their caches here (Room, files) so sign-out wipes them too. */
    @Multibinds
    abstract fun localDataWipers(): Set<LocalDataWiper>
}

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun authStateStore(@ApplicationContext context: Context): AuthStateStore {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("auth_state")
        }
        return EncryptedAuthStateStore(dataStore, KeystoreCipher())
    }

    @Provides
    @Singleton
    fun authClient(@ApplicationContext context: Context, config: AuthConfig, store: AuthStateStore): AuthClient =
        AppAuthClient(context, config, store)

    @Provides
    @Singleton
    fun sessionRepository(
        authClient: AuthClient,
        api: HomeBuddyApi,
        wipers: Set<@JvmSuppressWildcards LocalDataWiper>,
        @ApplicationScope scope: CoroutineScope,
    ): SessionRepository = SessionRepository(authClient, api, wipers, scope)

    @Provides
    fun accessTokenProvider(session: SessionRepository): AccessTokenProvider = session
}
