package chat.homebuddy.core.auth

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.openid.appauth.AuthorizationService

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    /** Built on the application context, so it can't leak an Activity. */
    @Provides
    @Singleton
    fun authorizationService(@ApplicationContext context: Context): AuthorizationService = AuthorizationService(context)

    @Provides
    @Singleton
    fun authClient(service: AuthorizationService, config: AuthConfig): AuthClient = AppAuthClient(service, config)

    @Provides
    @Singleton
    fun signInPage(service: AuthorizationService, config: AuthConfig): SignInPage = SignInPage(service, config)

    @Provides
    @Singleton
    fun authStore(@ApplicationContext context: Context): AuthStore = AuthStore(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("auth") },
        KeystoreCipher(),
    )

    @Provides
    @Singleton
    fun accessTokenCache(): AccessTokenCache = AccessTokenCache()

    @Provides
    @Singleton
    fun authRepository(client: AuthClient, store: AuthStore, cache: AccessTokenCache): AuthRepository =
        AuthRepository(client, store, cache)
}
