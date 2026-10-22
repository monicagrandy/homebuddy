package chat.homebuddy.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Cognito's logout page redirects here; bring the app back to the front. */
class SignOutRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
