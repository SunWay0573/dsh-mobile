package io.github.sunway0573.dshmobile.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sunway0573.dshmobile.R
import io.github.sunway0573.dshmobile.mobile.demo.DemoData

/** Tag for the conversation input field. See its use for why. */
internal const val ComposerFieldTag = "composer_field"

/**
 * The task list for one computer.
 *
 * Filtered by [computerId], not by "the tasks we happen to have". An earlier
 * version ended its filter with `|| true`, which let the other computer's tasks
 * through — harmless-looking in a demo with two machines, and exactly the bug
 * that would be carried into the real adapter as a "known quirk".
 */
@Composable
internal fun TasksScreen(
    computerId: String,
    computerAlias: String,
    state: UiState,
    submitGate: Gate,
    cancelGate: Gate,
    onBack: () -> Unit,
    onNewTask: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenApproval: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.tasks_title),
            subtitle = computerAlias,
            onBack = onBack,
            // Always reachable, not only from the empty state. Someone with nine
            // tasks is more likely to want a tenth than someone with none.
            action = {
                // Disabled rather than hidden, with the reason shown below the
                // list: a control that vanishes leaves the user wondering
                // whether they misremembered.
                TextButton(
                    onClick = onNewTask,
                    enabled = submitGate is Gate.Allowed,
                    modifier = MinTouchTarget,
                ) { Text(stringResource(R.string.tasks_new)) }
            },
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            DemoBanner()

            // Shown whenever the create entry is disabled, in every branch that
            // renders a list — not only the empty one. The earlier version
            // explained it in the empty state and left the normal list with a
            // greyed-out button and no explanation, which a comment claimed was
            // handled "below the list" when no such code existed.
            gateReason(submitGate)?.let { reason ->
                InfoBanner(
                    title = stringResource(R.string.tasks_new),
                    body = reason,
                    tone = Tone.WARN,
                )
                Spacer(Modifier.height(12.dp))
            }

            when (state) {
                UiState.LOADING -> repeat(4) { SkeletonCard(); Spacer(Modifier.height(12.dp)) }

                UiState.EMPTY -> StateBlock(
                    glyph = "💬",
                    title = stringResource(R.string.tasks_empty_title),
                    body = stringResource(R.string.tasks_empty_body),
                ) {
                    GatedButton(
                        label = stringResource(R.string.tasks_new),
                        gate = submitGate,
                        onClick = onNewTask,
                    )
                }

                UiState.ERROR -> {
                    InfoBanner(
                        stringResource(R.string.tasks_read_failed_title),
                        stringResource(R.string.tasks_read_failed_body),
                        Tone.ERR,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {}, modifier = MinTouchTarget.fillMaxWidth()) {
                        Text(stringResource(R.string.common_retry))
                    }
                }

                else -> {
                    if (state == UiState.OFFLINE) {
                        InfoBanner(
                            stringResource(R.string.tasks_stale_title),
                            stringResource(R.string.tasks_stale_body),
                            Tone.WARN,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    val mine = DemoData.tasks.filter { it.target.computerId == computerId }
                    if (mine.isEmpty()) {
                        StateBlock(
                            glyph = "💬",
                            title = stringResource(R.string.tasks_empty_title),
                            body = stringResource(R.string.tasks_empty_body),
                        )
                    }
                    mine.forEach { task ->
                        MobileCard(onClick = {
                            if (task.status == DemoData.TaskStatus.WAITING_APPROVAL) onOpenApproval()
                            else onOpenTask(task.target.sessionId)
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        task.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        task.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MobileColors.Muted,
                                        maxLines = 1,
                                    )
                                }
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                when (task.status) {
                                    DemoData.TaskStatus.RUNNING ->
                                        Pill(stringResource(R.string.status_running), Tone.BRAND)
                                    DemoData.TaskStatus.WAITING_APPROVAL ->
                                        Pill(stringResource(R.string.status_waiting_approval), Tone.WARN)
                                    DemoData.TaskStatus.DONE ->
                                        Pill(stringResource(R.string.status_done), Tone.OK)
                                    DemoData.TaskStatus.STOPPED ->
                                        Pill(stringResource(R.string.status_stopped), Tone.NEUTRAL)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

/**
 * One task's conversation.
 *
 * ## Why this screen does not scroll as a whole
 *
 * The messages vanish when it does. A parent `verticalScroll` passes its
 * children an unbounded height; `weight(1f)` on the message area then resolves
 * against infinity and collapses to zero, so three messages render at zero
 * height and the composer climbs to the top of the screen. Nothing errors — the
 * screen simply looks empty.
 *
 * So the viewport is bounded here: header fixed, messages scroll on their own,
 * composer pinned to the bottom and lifted by `imePadding` when the keyboard
 * opens.
 */
@Composable
internal fun ConversationScreen(
    computerAlias: String,
    state: UiState,
    sendGate: Gate,
    onBack: () -> Unit,
    onMore: () -> Unit,
) {
    // Remembered across rotation: a half-typed message that disappears when the
    // keyboard rotates is a message the user has to type twice.
    var draft by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(emptyList<String>()) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ScreenHeader(
            title = "整理下载目录中的 PDF",
            subtitle = "$computerAlias · ${stringResource(R.string.status_running)}",
            onBack = onBack,
            action = {
                TextButton(onClick = onMore, modifier = MinTouchTarget) {
                    Text(stringResource(R.string.detail_title))
                }
            },
        )

        // The one part that scrolls, with a height that actually exists.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            DemoBanner()

            when (state) {
                UiState.LOADING -> repeat(3) { SkeletonCard(); Spacer(Modifier.height(12.dp)) }

                UiState.EMPTY -> StateBlock(
                    glyph = "💬",
                    title = stringResource(R.string.conv_empty_title),
                    body = stringResource(R.string.conv_empty_body),
                )

                UiState.ERROR -> InfoBanner(
                    stringResource(R.string.conv_read_failed_title),
                    stringResource(R.string.conv_read_failed_body),
                    Tone.ERR,
                )

                else -> {
                    if (state == UiState.OFFLINE) {
                        InfoBanner(
                            stringResource(R.string.conv_offline_title),
                            stringResource(R.string.conv_offline_body),
                            Tone.WARN,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    DemoData.conversation.forEach { message ->
                        MessageBubble(
                            author = if (message.fromMe) stringResource(R.string.conv_me) else computerAlias,
                            message = message,
                        )
                    }
                    // Anything typed in this session, clearly marked as not sent.
                    sent.forEach { text ->
                        Text(
                            stringResource(R.string.conv_me),
                            style = MaterialTheme.typography.labelSmall,
                            color = MobileColors.Muted,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MobileColors.Ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MobileColors.Surface, RoundedCornerShape(12.dp))
                                .padding(13.dp),
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            stringResource(R.string.demo_value) + " · " +
                                stringResource(R.string.demo_not_connected),
                            style = MaterialTheme.typography.labelSmall,
                            color = MobileColors.Warn,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            // The demo keeps the message locally and says so. Nothing here is
            // sent anywhere, and the marker above every sent line says that —
            // a demo that looks like it delivered would be a lie about the one
            // thing the user is testing.
            onSend = {
                if (draft.isNotBlank()) {
                    sent = sent + draft.trim()
                    draft = ""
                }
            },
            // Both conditions: the screen's own connectivity state and what
            // this phone is actually permitted to send here.
            enabled = state != UiState.OFFLINE && state != UiState.ERROR &&
                sendGate is Gate.Allowed,
            gate = sendGate,
        )
    }
}

@Composable
private fun MessageBubble(author: String, message: DemoData.DemoMessage) {
    Text(
        author,
        style = MaterialTheme.typography.labelSmall,
        color = MobileColors.Muted,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        message.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MobileColors.Ink,
        modifier = Modifier
            .fillMaxWidth()
            .background(MobileColors.Surface, RoundedCornerShape(12.dp))
            .padding(13.dp),
    )
    if (message.code != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            message.code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MobileColors.Surface,
            modifier = Modifier
                .fillMaxWidth()
                .background(MobileColors.Ink, RoundedCornerShape(10.dp))
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
    if (message.meta != null) {
        Spacer(Modifier.height(5.dp))
        Text(
            message.meta,
            style = MaterialTheme.typography.labelSmall,
            color = MobileColors.Muted,
        )
    }
    Spacer(Modifier.height(14.dp))
}

/**
 * The input row.
 *
 * A real editable field with a saved draft, even in the demo. It is the only way
 * to check what the soft keyboard does to the layout, which is the part of a
 * chat screen most likely to be wrong on a device and least likely to be caught
 * by anything but a device.
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    gate: Gate,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.conv_placeholder)) },
            maxLines = 4,
            // Tagged so a test can click the field itself. Clicking its
            // placeholder text does not give it focus, and a keyboard test whose
            // click never focused anything proves nothing about the keyboard.
            modifier = Modifier.weight(1f).testTag(ComposerFieldTag),
        )
        Button(
            onClick = onSend,
            enabled = enabled && draft.isNotBlank(),
            modifier = MinTouchTarget,
        ) { Text(stringResource(R.string.conv_send)) }
    }
    gateReason(gate)?.let { reason ->
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = MobileColors.Warn,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
    }
}
