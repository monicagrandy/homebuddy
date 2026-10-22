package chat.homebuddy.app

import chat.homebuddy.core.auth.AuthConfig
import chat.homebuddy.core.network.ApiConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun apiConfig(): ApiConfig = ApiConfig(
        baseUrl = BuildConfig.API_BASE_URL,
        clientHeader = "android/${BuildConfig.VERSION_NAME}",
    )

    @Provides
    fun authConfig(): AuthConfig = AuthConfig(
        cognitoDomain = BuildConfig.COGNITO_DOMAIN,
        clientId = BuildConfig.COGNITO_CLIENT_ID,
    )
}
