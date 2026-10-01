package io.github.sunway0573.dshmobile.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sunway0573.dshmobile.R
import io.github.sunway0573.dshmobile.mobile.demo.DemoData
import io.github.sunway0573.dshmobile.mobile.model.AuthState
import io.github.sunway0573.dshmobile.mobile.model.ComputerRecord

/** Which of the four states a screen is showing. Mirrors the approved prototype. */
internal enum class UiState { NORMAL, LOADING, EMPTY, OFFLINE, ERROR }

/**
 * The computer list.
 *
 * The empty state is the whole app's onboarding: a user with no computers has
 * exactly one useful action, so the screen offers exactly that and hides the
 * rest behind a secondary "enter an address" path.
 */
@Composable
internal fun ComputersScreen(
    computers: List<ComputerRecord>,
    selectedComputerId: String,
    state: UiState,
    onScan: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.computers_title),
            subtitle = when (state) {
                UiState.LOADING -> stringResource(R.string.computers_checking)
                UiState.OFFLINE -> stringResource(R.string.computers_disconnected)
                else -> stringResource(R.string.computers_count, computers.size)
            },
            onSettings = onOpenSettings,
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            DemoBanner()

            when {
                state == UiState.LOADING -> repeat(2) { SkeletonCard() }

                state == UiState.EMPTY || computers.isEmpty() -> StateBlock(
                    glyph = "🖥",
                    title = stringResource(R.string.computers_empty_title),
                    body = stringResource(R.string.computers_empty_body),
                ) {
                    Button(onClick = onScan, modifier = MinTouchTarget) {
                        Text(stringResource(R.string.computers_scan))
                    }
                }

                state == UiState.ERROR -> {
                    InfoBanner(
                        stringResource(R.string.computers_revoked_title),
                        stringResource(R.string.computers_revoked_body),
                        Tone.ERR,
                    )
                    Spacer(Modifier.height(12.dp))
                    computers.take(1).forEach {
                        ComputerCard(it, online = false, isSelected = false, onClick = null)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onScan, modifier = MinTouchTarget.fillMaxWidth()) {
                        Text(stringResource(R.string.computers_repair))
                    }
                }

                else -> {
                    if (state == UiState.OFFLINE) {
                        InfoBanner(
                            stringResource(R.string.computers_offline_title),
                            stringResource(R.string.computers_offline_body),
                            Tone.WARN,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    // Tapping a computer selects it *and* opens its tasks. The
                    // selection is what every later screen keys on, so a user
                    // who owns two must be able to see which one they are
                    // looking at rather than inferring it from the tasks.
                    computers.forEach { computer ->
                        ComputerCard(
                            record = computer,
                            online = state != UiState.OFFLINE,
                            isSelected = computer.computerId == selectedComputerId,
                            onClick = { onSelect(computer.computerId) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedButton(onClick = onScan, modifier = MinTouchTarget.fillMaxWidth()) {
                        Text(stringResource(R.string.computers_add))
                    }
                    SectionHeader(stringResource(R.string.common_computer))
                    MobileCard {
                        Text(
                            stringResource(R.string.computers_isolated),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = MobileColors.Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComputerCard(
    record: ComputerRecord,
    online: Boolean,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
) {
    MobileCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    record.alias,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (online) {
                        record.primaryEndpoint ?: stringResource(R.string.common_online)
                    } else {
                        stringResource(R.string.common_offline)
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = MobileColors.Muted,
                )
            }
            if (isSelected) {
                Pill(stringResource(R.string.computers_selected), Tone.BRAND)
                Spacer(Modifier.padding(horizontal = 4.dp))
            }
            when {
                record.authState == AuthState.REVOKED -> Pill(stringResource(R.string.common_revoked), Tone.ERR)
                online -> StatusDot(Tone.OK)
                else -> StatusDot(Tone.NEUTRAL)
            }
        }
        if (record.lastSeenEpochMs != null || online) {
            Spacer(Modifier.height(8.dp))
            KeyValue(stringResource(R.string.computers_recent_task), DemoData.tasks.first().title)
        }
    }
}

/**
 * The pairing entry point.
 *
 * The camera preview belongs to work package 4; what is here is the shape of the
 * screen so the flow, the wording and the failure states can be reviewed before
 * a camera permission is added to the manifest.
 */
@Composable
internal fun PairingScreen(state: UiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = stringResource(R.string.pair_title),
            subtitle = null,
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = 12.dp)) {
            when (state) {
                UiState.ERROR -> {
                    InfoBanner(
                        stringResource(R.string.pair_expired_title),
                        stringResource(R.string.pair_expired_body),
                        Tone.ERR,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                UiState.OFFLINE -> {
                    InfoBanner(
                        stringResource(R.string.pair_unreachable_title),
                        stringResource(R.string.pair_unreachable_body),
                        Tone.WARN,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                else -> Unit
            }

            DemoBanner()

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(MobileColors.Ink, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(190.dp)
                            .background(MobileColors.Surface.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (state == UiState.LOADING) {
                            stringResource(R.string.pair_recognising)
                        } else {
                            stringResource(R.string.pair_hint)
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = MobileColors.Surface,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (state == UiState.LOADING) {
                SkeletonCard()
            } else {
                MobileCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "我的 Mac",
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.pair_code, "7 3 K 9"),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = MobileColors.Muted,
                            )
                        }
                        StatusDot(Tone.WARN)
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValue(stringResource(R.string.pair_identity), "a4:cf:…:3e:af")
                }
                Spacer(Modifier.height(12.dp))
                InfoBanner(
                    stringResource(R.string.pair_waiting_title),
                    stringResource(R.string.pair_waiting_body),
                    Tone.BRAND,
                )
            }
        }
    }
}

/** Shown on any screen whose content came from [DemoData]. */
@Composable
internal fun DemoBanner() {
    InfoBanner(
        stringResource(R.string.demo_banner_title),
        stringResource(R.string.demo_banner_body),
        Tone.BRAND,
    )
    Spacer(Modifier.height(12.dp))
}

/** The header every screen uses, so back and settings always sit in one place. */
@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            androidx.compose.material3.TextButton(onClick = onBack, modifier = MinTouchTarget) {
                Text("‹", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = MobileColors.Muted,
                )
            }
        }
        if (action != null) action()
        if (onSettings != null) {
            androidx.compose.material3.TextButton(onClick = onSettings, modifier = MinTouchTarget) {
                Text(stringResource(R.string.common_settings))
            }
        }
    }
}
