package chat.homebuddy.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Where the API lives and how this app identifies itself. Provided by :app from BuildConfig. */
data class ApiConfig(
    /** With a trailing slash, e.g. https://api.homebuddy.chat/ */
    val baseUrl: String,
    /** e.g. android/1.0.0 */
    val clientHeader: String,
)

/** Lenient on purpose: newer servers may add fields, and older apps must keep working. */
val HomeBuddyJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun buildHttpClient(config: ApiConfig, tokens: Provider<AccessTokenProvider>): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(ClientHeaderInterceptor(config.clientHeader))
        .addInterceptor(AuthInterceptor(tokens))
        .authenticator(TokenAuthenticator(tokens))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

fun buildApi(config: ApiConfig, client: OkHttpClient): HomeBuddyApi =
    Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(HomeBuddyJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(HomeBuddyApi::class.java)

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun httpClient(config: ApiConfig, tokens: Provider<AccessTokenProvider>): OkHttpClient =
        // A Provider, because the token provider's session itself calls the API (a cycle otherwise).
        buildHttpClient(config, tokens)

    @Provides
    @Singleton
    fun api(config: ApiConfig, client: OkHttpClient): HomeBuddyApi = buildApi(config, client)

    @Provides
    @Singleton
    fun queryStreamClient(config: ApiConfig, client: OkHttpClient): QueryStreamClient = QueryStreamClient(client, config)
}
