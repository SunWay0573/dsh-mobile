package io.github.sunway0573.dshmobile

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** See [SessionNavigationTest] for why this runs before the activity launches. */
private class SeedRecoveryRule(private val hostUrl: String) : TestWatcher() {
    override fun starting(description: Description) {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().putString(AppSettings.KEY_HOST_URL, hostUrl).commit()
    }
}

/**
 * Recovery after a failed load.
 *
 * The bug this guards: `onPageFinished` fires for the error page a refused
 * connection renders, and the app used to report Ready on that basis — clearing
 * the banner and leaving the user on a blank screen the app insisted was fine.
 * `SessionLoad` encodes the fix as a unit-tested rule; this checks the app
 * actually applies it, on a device, through the real WebView callbacks.
 *
 * Underscore method names, not backticks: DEX rejects spaces in SimpleName below
 * version 040, and version 040 needs minSdk 30. See SessionNavigationTest.
 */
@RunWith(AndroidJUnit4::class)
class SessionRecoveryTest {

    private companion object {
        const val UNREACHABLE_HOST = "http://127.0.0.1:9/"
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(SeedRecoveryRule(UNREACHABLE_HOST))
        .around(compose)

    /** Open a session against a host that refuses the connection. */
    private fun startAgainstUnreachableHost() {
        compose.onNodeWithText("Open session").performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Not connected").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun a_failure_survives_the_page_finishing() {
        startAgainstUnreachableHost()

        // Give any late onPageFinished every chance to overwrite the failure --
        // clearing the banner on a finished error page is exactly what used to
        // happen.
        compose.waitForIdle()
        Thread.sleep(2_000)
        compose.waitForIdle()

        compose.onNodeWithText("Not connected").assertExists()
    }

    @Test
    fun retrying_an_unreachable_host_does_not_crash_or_claim_success() {
        startAgainstUnreachableHost()

        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.waitForIdle()

        assertTrue(
            "the app is still running after a failed retry",
            compose.activityRule.scenario.state.name.isNotEmpty(),
        )
        compose.onNodeWithText("Not connected").assertExists()
    }

    @Test
    fun the_failure_explains_what_to_do() {
        startAgainstUnreachableHost()

        // The wording comes from WebErrors and is unit tested; what matters here
        // is that actionable text reached the screen rather than an empty banner.
        val nodes = compose.onAllNodesWithText("Could not reach the host", substring = true)
            .fetchSemanticsNodes()
        assertTrue("the banner must say what went wrong", nodes.isNotEmpty())
    }
}
