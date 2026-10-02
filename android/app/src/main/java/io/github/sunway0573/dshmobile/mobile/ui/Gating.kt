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
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.repository.ComputerState

/**
 * Whether an action may be attempted, and if not, why.
 *
 * ## Why the reason is part of the answer
 *
 * A disabled button with no explanation is worse than no button: the user knows
 * something is wrong and cannot find out what. Each of these has a different
 * fix, and they are not interchangeable — updating the app does not grant a
 * permission, and waiting does not fix a version mismatch.
 */
internal sealed interface Gate {

    data object Allowed : Gate

    /** The computer could not be reached. */
    data object Offline : Gate

    /** Paired, but this operation's scope was not granted. */
    data object NoScope : Gate

    /** The computer's Harness cannot do this. */
    data object NoCapability : Gate

    /** Reachable, but this build of the phone cannot do it either. */
    data object NotImplemented : Gate

    /** Nothing is connected. */
    data object NoComputer : Gate
}

/**
 * Decide whether an action is allowed, from the state of *this* computer.
 *
 * Deliberately takes a [ComputerState] rather than a repository, so it cannot
 * accidentally read another computer's state. Two machines can be on different
 * versions with different grants, and a control that is enabled because the
 * other computer allowed it is the multi-computer bug in its most dangerous
 * form.
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
            // Which of the two it is matters, because the fixes differ.
            operation in state.unavailable -> if (scopeGranted(state, operation)) {
                Gate.NoCapability
            } else {
                Gate.NoScope
            }
            else -> Gate.NoCapability
        }
        is ComputerState.Offline -> Gate.Offline
        is ComputerState.Unauthenticated, is ComputerState.Revoked -> Gate.NoComputer
        is ComputerState.Forbidden -> Gate.NoScope
        is ComputerState.Incompatible, is ComputerState.Failed -> Gate.NoCapability
    }
}

/**
 * Whether the computer advertised the operation and only the grant is missing.
 *
 * `unavailable` is "everything this build implements that is not available",
 * so an operation that is absent from `available` because of a grant is still
 * listed there. Without this the message would blame the Harness version for
 * what is a permission problem.
 */
private fun scopeGranted(state: ComputerState.Connected, operation: Operation): Boolean =
    operation in state.unavailable

/** The sentence to show for a gate that is not [Gate.Allowed]. */
@Composable
internal fun gateReason(gate: Gate): String? = when (gate) {
    Gate.Allowed -> null
    Gate.Offline -> stringResource(R.string.gate_offline)
    Gate.NoScope -> stringResource(R.string.gate_no_scope)
    Gate.NoCapability -> stringResource(R.string.gate_no_capability)
    Gate.NotImplemented -> stringResource(R.string.gate_not_implemented)
    Gate.NoComputer -> stringResource(R.string.gate_unavailable)
}

/**
 * A button that is disabled, with its reason, when the action is not available.
 *
 * The reason sits directly under the control rather than elsewhere on the
 * screen: an explanation the user has to go looking for is one they will not
 * find.
 */
@Composable
internal fun GatedButton(
    label: String,
    gate: Gate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reason = gateReason(gate)
    Column(modifier.fillMaxWidth()) {
        Button(
            onClick = onClick,
            // Disabled rather than hidden when the reason is knowable, and
            // disabled rather than clickable-and-failing always: a button that
            // looks pressable and does nothing teaches the user to distrust it.
            enabled = gate is Gate.Allowed,
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
