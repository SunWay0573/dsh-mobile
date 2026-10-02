package io.github.sunway0573.dshmobile.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sunway0573.dshmobile.R
import io.github.sunway0573.dshmobile.mobile.demo.DemoData

/**
 * The approval screen.
 *
 * ## Natural language first, the command behind a disclosure
 *
 * The command used to be the first thing on the screen. That is right for an
 * engineer and wrong for everyone else: the person being asked is deciding
 * whether to let an agent touch their files, and a shell pipeline is not the
 * clearest way to say that. The summary comes first because it is what the
 * decision is actually about; the exact command is one tap away because a
 * summary the user cannot check is a summary they have to trust.
 *
 * ## What is deliberately absent
 *
 * No "always allow". The plan rules it out — the reason approvals exist is that
 * an unattended agent should not be able to do arbitrary things on its own, and
 * a button that removes the asking would remove the reason for the screen.
 */
@Composable
internal fun ApprovalsScreen(
    computerId: String,
    computerAlias: String,
    state: UiState,
    decideGate: Gate,
    onBack: () -> Unit,
    onDecide: (allowed: Boolean) -> Unit,
) {
    var showCommand by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(title = stringResource(R.string.appr_title), subtitle = computerAlias, onBack = onBack)
        Column(Modifier.padding(horizontal = 12.dp)) {
            DemoBanner()

            when (state) {
                UiState.LOADING -> repeat(2) { SkeletonCard(); Spacer(Modifier.height(12.dp)) }

                UiState.EMPTY -> StateBlock(
                    glyph = "✓",
                    title = stringResource(R.string.appr_empty_title),
                    body = stringResource(R.string.appr_empty_body),
                )

                UiState.ERROR -> InfoBanner(
                    stringResource(R.string.appr_submit_failed_title),
                    stringResource(R.string.appr_submit_failed_body),
                    Tone.ERR,
                )

                else -> {
                    if (state == UiState.OFFLINE) {
                        InfoBanner(
                            stringResource(R.string.appr_offline_title),
                            stringResource(R.string.appr_offline_body),
                            Tone.WARN,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    MobileCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "把季度报表转成 PDF",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(R.string.status_waiting_approval),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MobileColors.Muted,
                                )
                            }
                            Pill(stringResource(R.string.status_waiting_approval), Tone.WARN)
                        }

                        SectionHeader(stringResource(R.string.appr_impact))
                        Text(
                            stringResource(R.string.appr_impact_pending),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MobileColors.Warn,
                        )

                        TextButton(onClick = { showCommand = !showCommand }, modifier = MinTouchTarget) {
                            Text(
                                stringResource(
                                    if (showCommand) R.string.appr_hide_command else R.string.appr_show_command,
                                ),
                            )
                        }
                        if (showCommand) {
                            Text(
                                DemoData.approvalCommand,
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

                        SectionHeader(stringResource(R.string.appr_source))
                        KeyValue(stringResource(R.string.appr_computer), computerAlias)
                        KeyValue(stringResource(R.string.appr_task), "把季度报表转成 PDF")
                        KeyValue(stringResource(R.string.appr_requested_at), "1 分钟前")
                    }
                    Spacer(Modifier.height(12.dp))

                    // While there is no approval owner, this is not "allowed":
                    // the computer would refuse every decision, so offering the
                    // buttons would promise something the system cannot do.
                    gateReason(decideGate)?.let { reason ->
                        InfoBanner(
                            title = stringResource(R.string.appr_title),
                            body = reason,
                            tone = Tone.WARN,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onDecide(false) },
                            enabled = decideGate is Gate.Allowed,
                            modifier = MinTouchTarget.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MobileColors.NeutralSoft,
                                contentColor = MobileColors.Ink,
                            ),
                        ) { Text(stringResource(R.string.appr_deny)) }
                        Button(
                            onClick = { onDecide(true) },
                            enabled = decideGate is Gate.Allowed,
                            modifier = MinTouchTarget.weight(1f),
                        ) { Text(stringResource(R.string.appr_allow_once)) }
                    }
                    Spacer(Modifier.height(12.dp))

                    InfoBanner(
                        stringResource(R.string.appr_once_title),
                        stringResource(R.string.appr_once_body),
                        Tone.BRAND,
                    )
                }
            }
        }
    }
}

/**
 * Shown after a decision.
 *
 * The wording says "submitted, awaiting confirmation" and not "the computer has
 * received your decision". Those are different facts: the request can be in
 * flight, the answer can be lost, and the host can refuse it. Claiming receipt
 * for something this consequential is the one thing this screen must not do.
 */
@Composable
internal fun ApprovalDecidedScreen(onViewTask: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(title = stringResource(R.string.appr_allowed_title), subtitle = null, onBack = onBack)
        Column(Modifier.padding(horizontal = 12.dp)) {
            DemoBanner()
            StateBlock(
                glyph = "✓",
                title = stringResource(R.string.appr_allowed_title),
                body = stringResource(R.string.appr_allowed_body),
            ) {
                Button(onClick = onViewTask, modifier = MinTouchTarget) {
                    Text(stringResource(R.string.appr_view_task))
                }
            }
        }
    }
}
