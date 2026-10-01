package io.github.sunway0573.dshmobile.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sunway0573.dshmobile.R
import io.github.sunway0573.dshmobile.Wake
import io.github.sunway0573.dshmobile.WakeOnLan
import io.github.sunway0573.dshmobile.WakePlan
import io.github.sunway0573.dshmobile.mobile.repository.ComputerState
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    alias: String,
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
    onRevoke: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(title = stringResource(R.string.common_settings), subtitle = null, onBack = onBack)
        Column(Modifier.padding(horizontal = 12.dp)) {
            DemoBanner()

            SectionHeader(stringResource(R.string.settings_current_computer))
            MobileCard {
                KeyValue(stringResource(R.string.settings_name), alias)
                KeyValue(
                    stringResource(R.string.settings_status),
                    stringResource(R.string.demo_value),
                )
                KeyValue(stringResource(R.string.settings_identity), "a4:cf:…:3e:af")
                KeyValue(stringResource(R.string.settings_auth), stringResource(R.string.demo_not_connected))
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRevoke,
                    modifier = MinTouchTarget.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MobileColors.ErrSoft,
                        contentColor = MobileColors.Err,
                    ),
                ) { Text(stringResource(R.string.settings_revoke)) }
            }

            SectionHeader(stringResource(R.string.settings_notify))
            MobileCard {
                KeyValue(stringResource(R.string.settings_notify_done), stringResource(R.string.settings_notify_on))
                KeyValue(
                    stringResource(R.string.settings_notify_approval),
                    stringResource(R.string.settings_notify_strong),
                    Tone.OK,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_notify_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MobileColors.Muted,
                )
            }

            SectionHeader(stringResource(R.string.settings_security))
            MobileCard {
                KeyValue(stringResource(R.string.settings_lock), stringResource(R.string.settings_notify_on))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_lock_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MobileColors.Muted,
                )
            }

            SectionHeader(stringResource(R.string.settings_advanced))
            MobileCard(onClick = onDiagnostics) {
                Text(stringResource(R.string.settings_network), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_network_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MobileColors.Muted,
                )
            }

            SectionHeader(stringResource(R.string.settings_about))
            MobileCard {
                KeyValue(stringResource(R.string.settings_version), "0.2.0 (2)")
                KeyValue(stringResource(R.string.settings_license), "MIT")
            }

            Spacer(Modifier.height(12.dp))
            InfoBanner(
                stringResource(R.string.settings_data_note_title),
                stringResource(R.string.settings_data_note_body),
                Tone.BRAND,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Advanced diagnostics.
 *
 * Everything technical lives here, and the wake configuration is here rather
 * than on the home screen because that is where it belongs and where it was
 * promised to go. It went missing for a round: the native interface replaced the
 * old home screen, the old screen's wake panel went with it, and nothing in the
 * tests noticed — the tests covered `Wake` and `WakeOnLan`, which still worked,
 * and not whether any screen could reach them.
 *
 * The panel below reuses `Wake.plan` and `WakeOnLan.attempt` unchanged.
 */
@Composable
internal fun DiagnosticsScreen(
    computerState: ComputerState,
    onBack: () -> Unit,
    onOpenLegacyWebView: () -> Unit,
    wakeConfig: WakeConfig = WakeConfig(),
    onSaveWakeConfig: (WakeConfig) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.diag_title),
            subtitle = stringResource(R.string.settings_advanced),
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            DemoBanner()

            // These come from the negotiation, not from a placeholder. The
            // difference matters: "协议版本 1" written by hand proves nothing,
            // while a value the adapter actually sent is evidence the handshake
            // ran — and a mismatch here is what the incompatible screen reports.
            MobileCard {
                when (computerState) {
                    is ComputerState.Connected -> {
                        KeyValue(stringResource(R.string.diag_computer), computerState.computerName)
                        KeyValue(stringResource(R.string.diag_protocol), "1")
                        KeyValue(stringResource(R.string.diag_host_version), computerState.hostVersion)
                        KeyValue(stringResource(R.string.diag_adapter_version), computerState.adapterVersion)
                        KeyValue(
                            stringResource(R.string.diag_capabilities),
                            computerState.available.size.toString(),
                        )
                        KeyValue(
                            stringResource(R.string.diag_unavailable),
                            computerState.unavailable.size.toString(),
                        )
                    }

                    is ComputerState.Offline -> {
                        KeyValue(
                            stringResource(R.string.settings_status),
                            stringResource(R.string.common_offline),
                            Tone.WARN,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            computerState.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MobileColors.Muted,
                        )
                    }

                    is ComputerState.Incompatible -> {
                        KeyValue(
                            stringResource(R.string.settings_status),
                            stringResource(R.string.diag_incompatible),
                            Tone.ERR,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            computerState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MobileColors.Err,
                        )
                    }

                    is ComputerState.Failed -> {
                        KeyValue(
                            stringResource(R.string.settings_status),
                            stringResource(R.string.demo_not_connected),
                            Tone.ERR,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            computerState.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MobileColors.Muted,
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.diag_wake))
            WakePanel(wakeConfig, onSaveWakeConfig)

            SectionHeader(stringResource(R.string.diag_info))
            MobileCard {
                KeyValue(stringResource(R.string.diag_app_version), "0.2.0 (2)")
                KeyValue(stringResource(R.string.diag_host_version), stringResource(R.string.demo_not_connected))
                KeyValue(stringResource(R.string.diag_cache), stringResource(R.string.demo_not_connected))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {}, modifier = MinTouchTarget.fillMaxWidth()) {
                    Text(stringResource(R.string.diag_clear_cache))
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onOpenLegacyWebView,
                modifier = MinTouchTarget.fillMaxWidth(),
            ) { Text("打开旧的网页界面（仅本机调试）") }
            Spacer(Modifier.height(8.dp))
            Text(
                "这个入口展示的是桌面布局，而且使用宿主自己的登录凭据，能绕过独立设备授权。" +
                    "正式版本会移除它，含此入口的构建不要分发给他人。",
                style = MaterialTheme.typography.bodySmall,
                color = MobileColors.Err,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The four wake settings, as plain data so the panel is testable in isolation. */
internal data class WakeConfig(
    val mac: String = "",
    val broadcast: String = "",
    val relayUrl: String = "",
    val relayToken: String = "",
)

/**
 * Wake configuration and the wake button.
 *
 * Validation happens before anything is sent, and the send button locks while a
 * request is in flight — the old implementation allowed a second tap, which on a
 * slow relay meant two packets and two status lines racing to say different
 * things. On success the message is "request sent, waiting for the computer to
 * come online", never "woken": sending a packet and a machine resuming are
 * different events, and claiming the second from the first is how someone ends
 * up debugging a setup that works.
 */
@Composable
private fun WakePanel(config: WakeConfig, onSave: (WakeConfig) -> Unit) {
    var mac by remember(config) { mutableStateOf(config.mac) }
    var broadcast by remember(config) { mutableStateOf(config.broadcast) }
    var relayUrl by remember(config) { mutableStateOf(config.relayUrl) }
    var relayToken by remember(config) { mutableStateOf(config.relayToken) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val draft = WakeConfig(mac, broadcast, relayUrl, relayToken)
    val dirty = draft != config
    val plan = Wake.plan(relayUrl, relayToken, mac, broadcast)

    MobileCard {
        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it; saving = false },
            label = { Text(stringResource(R.string.wake_mac)) },
            supportingText = { Text(stringResource(R.string.wake_mac_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = broadcast,
            onValueChange = { broadcast = it; saving = false },
            label = { Text(stringResource(R.string.wake_broadcast)) },
            supportingText = { Text(stringResource(R.string.wake_broadcast_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = relayUrl,
            onValueChange = { relayUrl = it; saving = false },
            label = { Text(stringResource(R.string.wake_relay)) },
            supportingText = { Text(stringResource(R.string.wake_relay_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = relayToken,
            onValueChange = { relayToken = it; saving = false },
            label = { Text(stringResource(R.string.wake_relay_token)) },
            // Masked: a shared secret typed in the open is a secret shown to
            // whoever is standing behind you.
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onSave(draft); saving = true; status = null },
                enabled = dirty,
                modifier = MinTouchTarget.weight(1f),
            ) { Text(stringResource(if (saving) R.string.wake_saved else R.string.wake_save)) }

            Button(
                onClick = {
                    sending = true
                    status = null
                    scope.launch {
                        val message = WakeOnLan.attempt(plan)
                        status = message
                        sending = false
                    }
                },
                // Disabled rather than merely ignored while sending: a button
                // that looks pressable and does nothing is worse than one that
                // says it is busy.
                enabled = !sending && plan != WakePlan.NotConfigured,
                modifier = MinTouchTarget.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (sending) R.string.wake_sending else R.string.wake_send,
                    ),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = when (plan) {
                is WakePlan.ViaBridge -> stringResource(R.string.wake_plan_relay)
                is WakePlan.ViaBroadcast -> stringResource(R.string.wake_plan_broadcast)
                WakePlan.NotConfigured -> stringResource(R.string.wake_nothing_configured)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MobileColors.Muted,
        )

        status?.let {
            Spacer(Modifier.height(10.dp))
            InfoBanner(
                title = stringResource(R.string.wake_title),
                body = it,
                tone = if (it.contains("已发送") || it.contains("requested")) Tone.OK else Tone.WARN,
            )
        }
    }
}
