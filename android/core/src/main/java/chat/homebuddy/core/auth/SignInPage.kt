package chat.homebuddy.core.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues

/**
 * The hosted sign-in page in a Custom Tab, on AppAuth like [AppAuthClient]. It's the only sign-in
 * code that sees Intents: the screen launches it, and what the page returns reaches the ViewModel
 * and use cases as a [SignInPageResult].
 */
class SignInPage(
    /** From the Hilt graph, built on the application context, so no Activity can leak. */
    private val service: AuthorizationService,
    private val config: AuthConfig,
) : ActivityResultContract<Unit, SignInPageResult>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        val request = AuthorizationRequest.Builder(
            config.serviceConfiguration(),
            config.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(config.redirectUri),
        ).setScopes(config.scopes).build()
        return service.getAuthorizationRequestIntent(request)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): SignInPageResult {
        val data = intent ?: return SignInPageResult.Cancelled
        AuthorizationResponse.fromIntent(data)?.let { return SignInPageResult.Code(it.jsonSerializeString()) }
        val error = AuthorizationException.fromIntent(data)
        return if (error?.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) {
            SignInPageResult.Cancelled
        } else {
            SignInPageResult.Failed
        }
    }
}
