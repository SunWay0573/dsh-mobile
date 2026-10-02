package io.github.sunway0573.dshmobile.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.sunway0573.dshmobile.R
import io.github.sunway0573.dshmobile.mobile.demo.DemoData
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.repository.ComputerState

/**
 * Test tags for the three navigation items.
 *
 * The labels are not unique — 电脑 is also the screen title and a section
 * heading, so `onNodeWithText("电脑")` matches three nodes and fails. Selecting
 * by tag is the only matcher that stays correct as the copy changes.
 */
internal const val NavTagComputers = "nav_computers"
internal const val NavTagTasks = "nav_tasks"
internal const val NavTagApprovals = "nav_approvals"

/** The three top-level destinations. */
internal enum class Tab { COMPUTERS, TASKS, APPROVALS }

/** Screens below the tabs. */
internal sealed interface Route {
    data object Tabs : Route
    data object Pairing : Route
    data object NewTask : Route
    data object Approvals : Route
    data class Conversation(val sessionId: String) : Route
    data class Detail(val sessionId: String) : Route
    data object Decided : Route
    data object Settings : Route
    data object Diagnostics : Route
}

/**
 * The native interface.
 *
 * ## Scrolling is per-screen, not global
 *
 * An earlier version wrapped every screen in one outer `verticalScroll`. That
 * gives its children an unbounded height, which silently breaks any screen that
 * needs a bounded one — the conversation's `weight(1f)` resolved to nothing, so
 * its messages rendered at zero height and the composer floated to the top. The
 * screen looked empty while the code drawing it was correct, which is why "there
 * is no backend yet" was the wrong explanation for it.
 *
 * The rule now: a screen either scrolls as a whole, or owns a bounded viewport
 * and scrolls part of itself. Never both.
 */
@Composable
internal fun MobileApp(
    state: UiState,
    computerStates: Map<String, ComputerState>,
    wakeConfig: WakeConfig,
    onSaveWakeConfig: (WakeConfig) -> Unit,
    onOpenLegacyWebView: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.COMPUTERS) }
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    // Saved, not just remembered: losing which computer you were looking at
    // because the screen turned is the kind of small breakage that makes an app
    // feel unreliable.
    var selectedComputerId by rememberSaveable { mutableStateOf(DemoData.computers.first().computerId) }

    val computers = DemoData.computers
    val selected = computers.firstOrNull { it.computerId == selectedComputerId } ?: computers.first()
    // The state of the computer being looked at, not of "the" computer. Two
    // machines can be on different versions with different grants, so a single
    // shared state would show one's permissions while talking to the other.
    val computerState: ComputerState =
        computerStates[selected.computerId] ?: ComputerState.Offline("尚未连接")
    // The badge counts approvals — but only when this phone may actually answer
    // them. A badge saying "1 waiting" over a screen that refuses every decision
    // tells the user there is something to do and then prevents them doing it.
    val approvalsGate = gate(computerState, Operation.ApprovalDecide)
    val pendingForSelected = if (approvalsGate is Gate.Allowed) {
        DemoData.tasks.count {
            it.target.computerId == selected.computerId &&
                it.status == DemoData.TaskStatus.WAITING_APPROVAL
        }
    } else {
        0
    }

    Surface(
        color = MobileColors.Canvas,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (val current = route) {
                    Route.Tabs -> when (tab) {
                        Tab.COMPUTERS -> Scrolling {
                            ComputersScreen(
                                computers = computers,
                                selectedComputerId = selectedComputerId,
                                state = state,
                                onScan = { route = Route.Pairing },
                                onSelect = { id ->
                                    selectedComputerId = id
                                    tab = Tab.TASKS
                                },
                                onOpenSettings = { route = Route.Settings },
                            )
                        }

                        Tab.TASKS -> Scrolling {
                            TasksScreen(
                                computerId = selected.computerId,
                                computerAlias = selected.alias,
                                state = state,
                                submitGate = gate(computerState, Operation.TaskSubmit),
                                cancelGate = gate(computerState, Operation.TaskCancel),
                                onBack = { tab = Tab.COMPUTERS },
                                onNewTask = { route = Route.NewTask },
                                onOpenTask = { route = Route.Conversation(it) },
                                onOpenApproval = { route = Route.Approvals },
                            )
                        }

                        Tab.APPROVALS -> Scrolling {
                            ApprovalsScreen(
                                computerId = selected.computerId,
                                computerAlias = selected.alias,
                                state = state,
                                decideGate = approvalsGate,
                                onBack = { tab = Tab.TASKS },
                                onDecide = { route = Route.Decided },
                            )
                        }
                    }

                    // Owns its viewport: header, scrolling messages, pinned composer.
                    is Route.Conversation -> ConversationScreen(
                        computerAlias = selected.alias,
                        state = state,
                        sendGate = gate(computerState, Operation.TaskSubmit),
                        onBack = { route = Route.Tabs },
                        onMore = { route = Route.Detail(current.sessionId) },
                    )

                    else -> Scrolling {
                        when (current) {
                            Route.Pairing -> PairingScreen(state = state, onBack = { route = Route.Tabs })
                            Route.NewTask -> NewTaskScreen(
                                computerAlias = selected.alias,
                                onBack = { route = Route.Tabs },
                            )
                            Route.Approvals -> ApprovalsScreen(
                                computerId = selected.computerId,
                                computerAlias = selected.alias,
                                state = state,
                                decideGate = approvalsGate,
                                onBack = { route = Route.Tabs },
                                onDecide = { route = Route.Decided },
                            )
                            is Route.Detail -> DetailScreen(
                                computerAlias = selected.alias,
                                onBack = { route = Route.Conversation(current.sessionId) },
                            )
                            Route.Decided -> ApprovalDecidedScreen(
                                onViewTask = { route = Route.Tabs; tab = Tab.TASKS },
                                onBack = { route = Route.Tabs; tab = Tab.APPROVALS },
                            )
                            Route.Settings -> SettingsScreen(
                                alias = selected.alias,
                                onBack = { route = Route.Tabs },
                                onDiagnostics = { route = Route.Diagnostics },
                                onRevoke = {},
                            )
                            Route.Diagnostics -> DiagnosticsScreen(
                                computerState = computerState,
                                onBack = { route = Route.Settings },
                                onOpenLegacyWebView = onOpenLegacyWebView,
                                wakeConfig = wakeConfig,
                                onSaveWakeConfig = onSaveWakeConfig,
                            )
                            Route.Tabs -> Unit
                            // Handled by the branch above; unreachable here,
                            // but Kotlin needs it for exhaustiveness.
                            is Route.Conversation -> Unit
                        }
                    }
                }
            }

            if (route == Route.Tabs) {
                AppNavigationBar(
                    tab = tab,
                    pending = pendingForSelected,
                    onSelect = { tab = it },
                )
            }
        }
    }
}

/** Wraps a screen that scrolls as a whole. */
@Composable
private fun Scrolling(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) { content() }
}

/**
 * The bottom bar.
 *
 * Icons are local vector drawables, not emoji. The emoji version —
 * `Text("🖥")`, `Text("💬")`, `Text("✓")` — rendered in three different colours
 * and weights on the device, because emoji are font glyphs and the platform
 * chooses their look. A navigation bar whose three items do not match reads as
 * unfinished no matter how well it works.
 */
@Composable
private fun AppNavigationBar(tab: Tab, pending: Int, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MobileColors.Surface) {
        NavigationBarItem(
            selected = tab == Tab.COMPUTERS,
            onClick = { onSelect(Tab.COMPUTERS) },
            modifier = Modifier.testTag(NavTagComputers),
            icon = { NavIcon(R.drawable.ic_desktop_windows) },
            label = { Text(stringResource(R.string.nav_computers)) },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = tab == Tab.TASKS,
            onClick = { onSelect(Tab.TASKS) },
            modifier = Modifier.testTag(NavTagTasks),
            icon = { NavIcon(R.drawable.ic_chat_bubble) },
            label = { Text(stringResource(R.string.nav_tasks)) },
            colors = navColors(),
        )
        NavigationBarItem(
            selected = tab == Tab.APPROVALS,
            onClick = { onSelect(Tab.APPROVALS) },
            modifier = Modifier.testTag(NavTagApprovals),
            icon = {
                BadgedBox(
                    badge = {
                        // Hidden at zero rather than showing "0": a badge that is
                        // always present stops meaning "something is waiting".
                        if (pending > 0) Badge { Text(pending.toString()) }
                    },
                ) { NavIcon(R.drawable.ic_pending_actions) }
            },
            label = { Text(stringResource(R.string.nav_approvals)) },
            colors = navColors(),
        )
    }
}

@Composable
private fun NavIcon(resource: Int) {
    Icon(
        imageVector = ImageVector.vectorResource(resource),
        // Empty on purpose: the label beside it already names the destination,
        // and TalkBack reads both. A description here would announce every tab
        // twice — once as the icon, once as the label.
        contentDescription = null,
        modifier = Modifier.clearAndSetSemantics { },
    )
}

/**
 * Explicit colours rather than the Material defaults.
 *
 * The defaults use the theme's primary, which is a second purple close enough to
 * the brand colour to look like a mistake and far enough to look like a
 * different state.
 */
@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MobileColors.Brand,
    selectedTextColor = MobileColors.Brand,
    indicatorColor = MobileColors.BrandSoft,
    unselectedIconColor = MobileColors.Muted,
    unselectedTextColor = MobileColors.Muted,
    disabledIconColor = MobileColors.Neutral,
    disabledTextColor = MobileColors.Neutral,
)
