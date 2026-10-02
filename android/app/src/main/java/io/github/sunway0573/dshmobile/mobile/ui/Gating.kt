package io.github.sunway0573.dshmobile.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sunway0573.dshmobile.R
import io.github.sunway0573.dshmobile.mobile.protocol.Incompatibility
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.repository.ComputerState

/**
 * Whether an action may be attempted, and if not, why.
 *
 * ## Why the reason is part of the answer
 *
 * A disabled button with no explanation is worse than no button: the user knows
 * something is wrong and cannot find out what. And the reasons are not
 * interchangeable — updating the app does not grant a permission, waiting does
 * not fix a version mismatch, and updating the *other* end does not help when
 * this one is too old.
 */
internal sealed interface Gate {

    data object Allowed : Gate

    /** The computer could not be reached. */
    data object Offline : Gate

    /** The computer offers it; this device was not granted the scope. */
    data object NoScope : Gate

    /** The computer's Harness cannot do this at all. */
    data object NoCapability : Gate

    /** This phone's build cannot do it. */
    data object NotImplemented : Gate

    /** Nothing is connected, or the device was removed. */
    data object NoComputer : Gate

    /** The two ends share no protocol version. */
    data class WrongVersion(val clientTooOld: Boolean) : Gate

    /** The computer answered with something unusable. */
    data object Failed : Gate

    /**
     * The two ends share no protocol version.
     *
     * Deliberately carries no direction: neither end is strictly newer, and
     * telling someone to update one of them is a guess with a 50% chance of
     * wasting their time.
     */
    data object NoCommonVersion : Gate

    /**
     * The protocol matches but nothing is usable here.
     *
     * Usually a grant problem, sometimes a capability problem, never a version
     * problem — so it must not borrow the version wording.
     */
    data object NoUsableOperations : Gate
}

/**
 * Decide whether an action is allowed, from the state of *this* computer.
 *
 * Deliberately takes a [ComputerState] rather than a repository, so it cannot
 * accidentally read another computer's state. Two machines can be on different
 * versions with different grants, and a control enabled because the *other*
 * computer allowed it is the multi-computer bug in its most dangerous form.
 *
 * ## The bug this replaces
 *
 * The previous version had a helper `scopeGranted(state, operation)` that
 * returned `operation in state.unavailable` — the very expression the caller
 * had just tested. The branch was therefore always true, every unavailable
 * operation was reported as a missing *capability*, and `NoScope` was
 * unreachable from a connected computer. `Connected` only carried `available`
 * and `unavailable`, so the information needed to tell the two apart had
 * already been thrown away before the question was asked. Naming a function
 * differently cannot recover it; the state has to keep it.
 *
 * It also mapped `Incompatible` and `Failed` to `NoCapability`, telling a user
 * whose *phone* was too old to update their *computer*.
 *
 * This improves the experience. It is not access control: the computer checks
 * every request again, and the plan is explicit that a hidden button is not a
 * permission.
 */
internal fun gate(state: ComputerState, operation: Operation, implemented: Boolean = true): Gate {
    if (!implemented) return Gate.NotImplemented

    return when (state) {
        is ComputerState.Connected -> when {
            state.can(operation) -> Gate.Allowed
            // Order matters: capability is the outer question. A computer that
            // cannot do this at all has no scope to be missing.
            !state.hasCapability(operation) -> Gate.NoCapability
            !state.hasScope(operation) -> Gate.NoScope
            // Advertised, granted, and still not available. Something narrowed
            // it that this build does not model; blaming the Harness is the
            // safer of the two remaining guesses.
            else -> Gate.NoCapability
        }

        is ComputerState.Offline -> Gate.Offline
        is ComputerState.Unauthenticated, is ComputerState.Revoked -> Gate.NoComputer
        is ComputerState.Forbidden -> Gate.NoScope

        // Carries the direction with it, and only claims a direction when there
        // is one. The previous version mapped everything that was not
        // CLIENT_TOO_OLD to "update the computer", which is wrong twice over:
        // NO_COMMON_VERSION gives no basis for naming a single end, and
        // NO_USABLE_OPERATIONS can happen when the protocol matches perfectly and
        // the device simply holds no usable grant.
        is ComputerState.Incompatible -> when (state.kind) {
            Incompatibility.CLIENT_TOO_OLD -> Gate.WrongVersion(clientTooOld = true)
            Incompatibility.COMPUTER_TOO_OLD -> Gate.WrongVersion(clientTooOld = false)
            // No instruction about which end to update, because the two ends
            // share nothing and neither is strictly newer.
            Incompatibility.NO_COMMON_VERSION -> Gate.NoCommonVersion
            // Protocol is fine; this is about capability and permission, so it
            // must not read as a version problem at all.
            Incompatibility.NO_USABLE_OPERATIONS -> Gate.NoUsableOperations
        }

        is ComputerState.Failed -> Gate.Failed
    }
}

/** The sentence to show for a gate that is not [Gate.Allowed]. */
@Composable
internal fun gateReason(gate: Gate): String? = when (gate) {
    Gate.Allowed -> null
    Gate.Offline -> stringResource(R.string.gate_offline)
    Gate.NoScope -> stringResource(R.string.gate_no_scope)
    Gate.NoCapability -> stringResource(R.string.gate_no_capability)
    Gate.NotImplemented -> stringResource(R.string.gate_not_implemented)
    Gate.NoComputer -> stringResource(R.string.gate_unavailable)
    Gate.Failed -> stringResource(R.string.gate_failed)
    Gate.NoCommonVersion -> stringResource(R.string.gate_no_common_version)
    Gate.NoUsableOperations -> stringResource(R.string.gate_no_usable_operations)
    is Gate.WrongVersion -> stringResource(
        if (gate.clientTooOld) R.string.gate_client_too_old else R.string.gate_computer_too_old,
    )
}

/**
 * A button that is disabled, with its reason, when the action is not available.
 *
 * The reason sits directly under the control rather than elsewhere on the
 * screen: an explanation the user has to go looking for is one they will not
 * find.
 *
 * `demoOnly` marks an action wired to the real protocol with no transport
 * behind it yet. Those stay disabled with a reason rather than appearing to
 * succeed — a button that looks like it worked is worse than one that says it
 * cannot.
 */
@Composable
internal fun GatedButton(
    label: String,
    gate: Gate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    demoOnly: Boolean = false,
) {
    val effective: Gate = if (gate is Gate.Allowed && demoOnly) Gate.NotImplemented else gate
    val reason = gateReason(effective)
    Column(modifier.fillMaxWidth()) {
        Button(
            onClick = onClick,
            // Disabled rather than hidden when the reason is knowable, and
            // disabled rather than clickable-and-failing always: a button that
            // looks pressable and does nothing teaches the user to distrust it.
            enabled = effective is Gate.Allowed,
            modifier = MinTouchTarget.fillMaxWidth(),
        ) { Text(label) }
        if (reason != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MobileColors.Warn,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
