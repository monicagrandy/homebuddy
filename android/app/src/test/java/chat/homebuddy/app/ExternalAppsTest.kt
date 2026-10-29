package chat.homebuddy.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Starting another app on a phone that has none for the job (a tablet with no dialer, a phone with
 * no browser) must not crash. Robolectric, because only Android's own startActivity can fail that way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalAppsTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Before
    fun setUp() {
        // Starting an activity nothing can handle fails, as it does on a phone.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).checkActivities(true)
    }

    private fun install(name: String, filter: IntentFilter) {
        val component = ComponentName("com.example.$name", "$name.Main")
        shadowOf(activity.packageManager).apply {
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(component, filter.apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    private fun installDialer() = install("dialer", IntentFilter(Intent.ACTION_DIAL).apply { addDataScheme("tel") })

    private fun installBrowser() = install(
        "browser",
        IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
        },
    )

    private fun started() = shadowOf(activity).nextStartedActivity

    @Test
    fun `dialing with no dialer reports it instead of crashing`() {
        assertFalse(activity.dial("911"))
        assertNull(started())
    }

    @Test
    fun `dialing fills in the number for the person to call`() {
        installDialer()

        assertTrue(activity.dial("911"))

        val dial = started()
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals(Uri.parse("tel:911"), dial.data)
    }

    @Test
    fun `a web page with no browser reports it instead of crashing`() {
        assertFalse(activity.openWebPage("https://example.com/manual.pdf"))
        assertNull(started())
    }

    @Test
    fun `a web page opens in the browser`() {
        installBrowser()

        assertTrue(activity.openWebPage("https://example.com/manual.pdf"))

        assertEquals(Uri.parse("https://example.com/manual.pdf"), started().data)
    }

    @Test
    fun `a link that isn't a web page is never started, even with a browser`() {
        installBrowser()

        assertFalse(activity.openWebPage("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(activity.openWebPage("file:///data/data/chat.homebuddy.app/databases/homebuddy.db"))
        assertNull(started())
    }
}
