package io.github.sunway0573.dshmobile

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Seeds the app's stored settings before the activity starts.
 *
 * The ordering is the whole point, and it is why this is a rule rather than a
 * `@Before`: the activity reads its settings in `onCreate`, so writing them
 * afterwards needs `recreate()` to take effect — and recreating the activity
 * under `createAndroidComposeRule` leaves the rule querying a semantics tree
 * that no longer belongs to the running activity. The first version of this test
 * did exactly that and sat there for six minutes before the device dropped,
 * which looked like a device fault and was a test-design fault.
 *
 * @param hostUrl what to put in the host field, or null to leave it unset.
 */
private class SeedSettingsRule(private val hostUrl: String?) : TestWatcher() {
    override fun starting(description: Description) {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
        if (hostUrl != null) prefs.putString(AppSettings.KEY_HOST_URL, hostUrl)
        prefs.commit()
    }
}

/**
 * Drives the real app on a real device.
 *
 * These exist because the JVM tests cannot see any of this. `SessionLoad`'s
 * ordering rules and `WebErrors`' text are unit tested, but whether the app
 * *wires them correctly* — that `onPageStarted` fires before `onPageFinished`,
 * that the banner survives, that a failed host is reported rather than rendered
 * as a blank page — only shows up on a device with a WebView in it.
 *
 * The host is deliberately unreachable, so the tests are deterministic and need
 * no running DSH. What they verify is the failure path, which is what was wrong.
 *
 * Method names use underscores, not backticks with spaces: instrumentation tests
 * are DEXed, and DEX before version 040 rejects spaces in SimpleName. Version 040
 * needs minSdk 30 and this app supports 26.
 */
@RunWith(AndroidJUnit4::class)
class SessionNavigationTest {

    /** A port nothing listens on, so the connection is refused immediately. */
    private companion object {
        const val UNREACHABLE_HOST = "http://127.0.0.1:9/"
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(SeedSettingsRule(UNREACHABLE_HOST))
        .around(compose)

    private fun awaitText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun the_app_launches_to_the_connection_screen() {
        compose.onNodeWithText("dsh-mobile").assertExists()
        compose.onNodeWithText("Open session").assertExists()
    }

    // A host that cannot be reached must be reported as a failure, not rendered
    // as a blank page that looks like it is working. This is the assertion the
    // app used to fail: `onPageFinished` fires for the error page too, and the
    // old code reported Ready on that basis.
    @Test
    fun an_unreachable_host_reports_a_failure_rather_than_appearing_ready() {
        compose.onNodeWithText("Open session").performClick()
        awaitText("Not connected")
        compose.onNodeWithText("Not connected").assertExists()
    }
}
