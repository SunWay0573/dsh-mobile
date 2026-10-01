package io.github.sunway0573.dshmobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sunway0573.dshmobile.mobile.ui.NavTagApprovals
import io.github.sunway0573.dshmobile.mobile.ui.NavTagComputers
import io.github.sunway0573.dshmobile.mobile.ui.NavTagTasks
import org.junit.Assert.assertEquals
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

        // The bug this guards: the messages rendered at zero height and the
        // screen looked empty while the drawing code was correct.
        awaitText("找到了 30 个 PDF")
        compose.onNodeWithText("发消息或创建任务…", substring = true).assertIsDisplayed()
    }
}
