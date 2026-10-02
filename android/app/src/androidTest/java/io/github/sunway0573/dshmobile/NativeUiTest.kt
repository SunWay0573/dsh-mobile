package io.github.sunway0573.dshmobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sunway0573.dshmobile.mobile.ui.ComposerFieldTag
import io.github.sunway0573.dshmobile.mobile.ui.NavTagApprovals
import io.github.sunway0573.dshmobile.mobile.ui.NavTagComputers
import io.github.sunway0573.dshmobile.mobile.ui.NavTagTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The native interface, on a device.
 *
 * These replace `SessionNavigationTest` and `SessionRecoveryTest`, which drove
 * the old WebView screens. Those screens still exist behind the diagnostics
 * entry for debugging, but they are no longer what a user sees, so tests aimed
 * at them were testing the wrong surface — and passing, which is worse.
 *
 * Method names use underscores: instrumentation is DEXed, and DEX below version
 * 040 rejects spaces in SimpleName. Version 040 needs minSdk 30 and this app
 * supports 26.
 */
@RunWith(AndroidJUnit4::class)
class NativeUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Whether the soft keyboard is actually on screen.
     *
     * The review's point: the earlier keyboard test clicked the field and
     * checked the composer was visible, which passes just as well when the IME
     * never appeared — and a composer that is "visible" because no keyboard
     * covers it proves nothing about the case the test exists for. This reads
     * the real inset rather than inferring from layout.
     */
    private fun imeVisible(): Boolean {
        val view = compose.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view) ?: return false
        return insets.isVisible(WindowInsetsCompat.Type.ime())
    }

    private fun awaitIme(timeoutMs: Long = 10_000): Boolean {
        compose.waitUntil(timeoutMillis = timeoutMs) { imeVisible() }
        return imeVisible()
    }

    private fun awaitText(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun the_app_opens_on_the_computer_list() {
        compose.onNodeWithTag(NavTagComputers).assertIsDisplayed()
        compose.onNodeWithText("我的 Mac").assertIsDisplayed()
    }

    // The point of the whole redesign: this is a native screen, not the desktop
    // page in a WebView. If the native shell ever stops being the default, this
    // fails rather than quietly regressing to the old surface.
    @Test
    fun the_interface_is_native_and_says_so() {
        compose.onNodeWithText("界面预览", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(NavTagComputers).assertIsDisplayed()
        compose.onNodeWithTag(NavTagTasks).assertIsDisplayed()
        compose.onNodeWithTag(NavTagApprovals).assertIsDisplayed()
    }

    @Test
    fun the_three_tabs_navigate() {
        compose.onNodeWithTag(NavTagTasks).performClick()
        awaitText("新建任务")
        compose.onNodeWithText("整理下载目录中的 PDF").assertIsDisplayed()

        compose.onNodeWithTag(NavTagApprovals).performClick()
        awaitText("把季度报表转成 PDF")

        compose.onNodeWithTag(NavTagComputers).performClick()
        awaitText("添加电脑")
    }

    // The review found the other computer's tasks leaking into this list, from a
    // filter that ended in `|| true`. Both machines have a task with the same
    // title and the same session id, so a leak is invisible by title alone.
    @Test
    fun the_task_list_shows_only_the_selected_computer() {
        compose.onNodeWithTag(NavTagTasks).performClick()
        awaitText("新建任务")

        // Both computers have a task called 整理下载目录中的 PDF. Only one is
        // on 我的 Mac, and the mini's copy must not be here.
        val matches = compose.onAllNodesWithText("整理下载目录中的 PDF").fetchSemanticsNodes().size
        assertEquals("the same title exists on both computers; exactly one belongs here", 1, matches)
    }

    @Test
    fun opening_a_task_shows_its_messages() {
        compose.onNodeWithTag(NavTagTasks).performClick()
        awaitText("整理下载目录中的 PDF")
        compose.onNodeWithText("整理下载目录中的 PDF").performClick()

        awaitText("找到了 30 个 PDF")

        // `assertIsDisplayed`, not just "the node exists". The bug this guards
        // against was messages laid out at zero height: the semantics tree had
        // them, so presence checks passed while the screen was blank. Only a
        // display assertion catches that.
        // The actual first user message. An invented string here failed the
        // assertion and was the test's fault, not the app's — which is why the
        // fixture's text is quoted rather than paraphrased.
        compose.onNodeWithText("先给我分类预览", substring = true).assertIsDisplayed()
        compose.onNodeWithText("找到了 30 个 PDF", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在读取文件内容", substring = true).assertIsDisplayed()

        compose.onNodeWithText("发消息或创建任务…", substring = true).assertIsDisplayed()
    }

    /**
     * The composer must stay on screen when the keyboard opens.
     *
     * A chat input that the keyboard covers is unusable, and the failure is
     * invisible to any test that never opens the keyboard.
     */
    @Test
    fun the_composer_stays_visible_with_the_keyboard_open() {
        compose.onNodeWithTag(NavTagTasks).performClick()
        awaitText("整理下载目录中的 PDF")
        compose.onNodeWithText("整理下载目录中的 PDF").performClick()
        awaitText("发消息或创建任务…")

        // Click the field, not its placeholder: clicking the placeholder text
        // does not focus the input, and the keyboard then never opens.
        compose.onNodeWithTag(ComposerFieldTag).performClick()

        // The precondition, asserted rather than assumed. Without this the test
        // passes in an environment where the keyboard never opens, which is
        // exactly the situation it is supposed to cover.
        assertTrue(
            "the soft keyboard did not open, so this test would prove nothing",
            awaitIme(),
        )

        // And now the thing being tested: the composer is still on screen after
        // the IME claims the bottom of the display.
        compose.onNodeWithText("发消息或创建任务…", substring = true).assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsDisplayed()
    }

    /**
     * A task whose approval is pending opens the approval screen, not the
     * conversation: the user's next action is to decide, not to read.
     */
    @Test
    fun a_pending_approval_opens_the_approval_screen() {
        compose.onNodeWithTag(NavTagTasks).performClick()
        awaitText("把季度报表转成 PDF")
        compose.onNodeWithText("把季度报表转成 PDF").performClick()

        awaitText("展开原始命令")
        compose.onNodeWithText("允许一次").assertIsDisplayed()
        compose.onNodeWithText("拒绝").assertIsDisplayed()
    }

    /**
     * The approval impact must not claim to have analysed anything.
     *
     * An earlier version hardcoded "will not delete any files", which is a
     * promise no code was making.
     */
    /**
     * The approval controls must not be offered while the computer would refuse
     * every decision.
     *
     * There is no approval owner, so `approval.decide` is deliberately not a
     * capability. A phone that showed enabled Allow/Deny buttons would promise
     * something the system cannot do.
     */
    @Test
    fun the_approval_controls_are_not_offered_as_usable() {
        compose.onNodeWithTag(NavTagApprovals).performClick()
        awaitText("允许一次")

        // Present, so the user can see what is being asked, and disabled, with a
        // reason — rather than hidden, which would leave them wondering.
        compose.onNodeWithText("允许一次").assertIsNotEnabled()
        compose.onNodeWithText("拒绝").assertIsNotEnabled()
    }

    /**
     * The approval badge must not appear when approvals cannot be answered: a
     * "1 waiting" over a screen that refuses every decision tells the user there
     * is something to do and then prevents them doing it.
     */
    @Test
    fun the_approval_badge_is_absent_when_approvals_are_unavailable() {
        compose.onNodeWithTag(NavTagApprovals).assertIsDisplayed()
        // The demo fixture has one pending approval; it must not be counted
        // while the operation is unavailable.
        compose.onNodeWithText("1").assertDoesNotExist()
    }

    @Test
    fun the_approval_does_not_pretend_to_have_analysed_the_impact() {
        compose.onNodeWithTag(NavTagApprovals).performClick()
        awaitText("影响分析尚未接入")
        compose.onNodeWithText("影响分析尚未接入", substring = true).assertIsDisplayed()
    }
}
