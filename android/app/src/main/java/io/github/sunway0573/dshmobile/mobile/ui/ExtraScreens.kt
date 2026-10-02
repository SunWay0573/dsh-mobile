package io.github.sunway0573.dshmobile.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sunway0573.dshmobile.R

/** New task. The fields are drafted; the transport is work package 3. */
@Composable
internal fun NewTaskScreen(
    computerAlias: String,
    submitGate: Gate,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.new_task_title),
            subtitle = computerAlias,
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            DemoBanner()
            MobileCard {
                Text(
                    stringResource(R.string.new_task_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            SectionHeader(stringResource(R.string.new_task_how))
            MobileCard {
                KeyValue(stringResource(R.string.new_task_workspace), "我的电脑")
                KeyValue(stringResource(R.string.new_task_mode), "标准模式")
                KeyValue(
                    stringResource(R.string.new_task_tools),
                    stringResource(R.string.new_task_tools_value),
                )
            }
            Spacer(Modifier.height(12.dp))
            InfoBanner(
                stringResource(R.string.demo_banner_title),
                stringResource(R.string.demo_banner_body),
                Tone.BRAND,
            )
            Spacer(Modifier.height(12.dp))
            // Gated at the moment of the action, not when the screen opened: a
            // grant revoked while this page is open must disable the button, and
            // a gate captured on entry would not notice.
            GatedButton(
                label = stringResource(R.string.new_task_start),
                gate = submitGate,
                onClick = onBack,
                // Task submission has no transport yet. Allowed would be a lie
                // even when the protocol says it is permitted.
                demoOnly = true,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Task detail: model, tools, artifacts. No real data yet. */
@Composable
internal fun DetailScreen(
    computerAlias: String,
    cancelGate: Gate,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.detail_title),
            subtitle = "整理下载目录中的 PDF",
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            DemoBanner()
            MobileCard {
                KeyValue(stringResource(R.string.appr_computer), computerAlias)
                KeyValue(stringResource(R.string.settings_status), stringResource(R.string.demo_value))
                KeyValue(stringResource(R.string.detail_elapsed), stringResource(R.string.demo_value))
                KeyValue(stringResource(R.string.detail_model), stringResource(R.string.demo_value))
                KeyValue(
                    stringResource(R.string.new_task_tools),
                    stringResource(R.string.new_task_tools_value),
                )
            }
            SectionHeader(stringResource(R.string.detail_artifacts))
            MobileCard {
                Text(
                    stringResource(R.string.detail_artifact_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MobileColors.Muted,
                )
            }
            SectionHeader(stringResource(R.string.detail_actions))
            // Previously `onClick = {}`, which looked like a working control and
            // did nothing. The cancel gate computed for the task list was never
            // passed anywhere, so this is where it actually belongs.
            GatedButton(
                label = stringResource(R.string.detail_stop),
                gate = cancelGate,
                onClick = onBack,
                demoOnly = true,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
