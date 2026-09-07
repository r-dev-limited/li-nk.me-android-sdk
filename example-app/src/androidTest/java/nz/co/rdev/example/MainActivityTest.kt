package nz.co.rdev.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
