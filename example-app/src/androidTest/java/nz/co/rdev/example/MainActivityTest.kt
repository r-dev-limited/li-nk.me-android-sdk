package nz.co.rdev.example

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Smoke test for the packaged consumer app on a real Android runtime. */
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndRendersHomeScreen() {
        composeRule.onNodeWithText("LinkMe Example").assertIsDisplayed()
    }

    @Test
    fun customSchemeAndAppLinkIntentsResolveToTheConsumerActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager

        fun resolvesToConsumer(uri: String): Boolean {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            // Use an unrestricted query: a clean emulator cannot verify the
            // production HTTPS domain, but the packaged intent filter must
            // still be present and discoverable.
            return packageManager.queryIntentActivities(intent, 0)
                .any { it.activityInfo?.name == MainActivity::class.java.name }
        }

        assertTrue("custom URL scheme must resolve", resolvesToConsumer(
            "nz.co.rdev.example://open?path=%2Fprofile&cid=cid-test",
        ))

        assertTrue("https App Link must resolve", resolvesToConsumer(
            "https://${BuildConfig.LINKME_APP_LINKS_HOST}/profile?cid=cid-test",
        ))
    }
}
