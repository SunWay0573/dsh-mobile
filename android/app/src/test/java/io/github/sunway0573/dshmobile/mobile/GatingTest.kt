package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.protocol.Incompatibility
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.protocol.Scope
import io.github.sunway0573.dshmobile.mobile.repository.ComputerState
import io.github.sunway0573.dshmobile.mobile.ui.Gate
import io.github.sunway0573.dshmobile.mobile.ui.gate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which reason a user is given when an action is unavailable.
 *
 * The review drove the compiled gating code and found that a connected computer
 * missing a *permission* was reported as missing a *capability*. The cause was
 * a helper that returned the very expression its caller had just tested, so the
 * branch was always true and the permission case was unreachable — and the state
 * it needed had already been discarded before the question was asked.
 *
 * These are not cosmetic: "update the plugin" and "you were not granted this"
 * send the user to different places, and one of them is a dead end.
 */
class GatingTest {

    private fun connected(
        capabilities: Set<Operation>,
        granted: Set<Scope>,
    ) = ComputerState.Connected(
        computerId = "pc-1",
        computerName = "我的 Mac",
        protocolVersion = 1,
        hostVersion = "0.2.0-rc.2",
        adapterVersion = "1.0.0",
        capabilities = capabilities,
        grantedScopes = granted,
        available = capabilities.filter { op ->
            io.github.sunway0573.dshmobile.mobile.protocol.OPERATION_SCOPE[op]?.let { granted.contains(it) } == true
        }.toSet(),
        unavailable = capabilities.filter { op ->
            io.github.sunway0573.dshmobile.mobile.protocol.OPERATION_SCOPE[op]?.let { granted.contains(it) } != true
        }.toSet(),
    )

    /** The case the review reproduced: capability present, grant missing. */
    @Test
    fun a_missing_grant_is_reported_as_a_permission_problem() {
        val state = connected(
            capabilities = setOf(Operation.ComputerStatus, Operation.SessionList, Operation.TaskSubmit),
            granted = setOf(Scope.SessionsRead),
        )
        assertEquals(Gate.NoScope, gate(state, Operation.TaskSubmit))
    }

    /** Capability absent: a different problem, with a different fix. */
    @Test
    fun a_missing_capability_is_reported_as_a_capability_problem() {
        val state = connected(
            capabilities = setOf(Operation.ComputerStatus, Operation.SessionList),
            granted = Scope.entries.toSet(),
        )
        assertEquals(Gate.NoCapability, gate(state, Operation.TaskCancel))
    }

    @Test
    fun the_two_reasons_are_not_the_same_answer() {
        val missingGrant = connected(
            capabilities = setOf(Operation.ComputerStatus, Operation.TaskSubmit),
            granted = setOf(Scope.SessionsRead),
        )
        val missingCapability = connected(
            capabilities = setOf(Operation.ComputerStatus),
            granted = Scope.entries.toSet(),
        )
        assertNotEquals(
            gate(missingGrant, Operation.TaskSubmit),
            gate(missingCapability, Operation.TaskSubmit),
        )
    }

    @Test
    fun an_available_operation_is_allowed() {
        val state = connected(
            capabilities = setOf(Operation.ComputerStatus, Operation.SessionList),
            granted = setOf(Scope.SessionsRead),
        )
        assertEquals(Gate.Allowed, gate(state, Operation.SessionList))
    }

    // A phone too old to talk to this computer must not be told to update the
    // computer: updating the wrong end changes nothing.
    @Test
    fun a_newer_computer_points_at_this_phone() {
        val state = ComputerState.Incompatible(Incompatibility.CLIENT_TOO_OLD, "手机端过旧")
        assertEquals(Gate.WrongVersion(clientTooOld = true), gate(state, Operation.TaskSubmit))
    }

    @Test
    fun an_older_computer_points_at_the_computer() {
        val state = ComputerState.Incompatible(Incompatibility.COMPUTER_TOO_OLD, "电脑端过旧")
        assertEquals(Gate.WrongVersion(clientTooOld = false), gate(state, Operation.TaskSubmit))
    }

    @Test
    fun the_two_version_directions_differ() {
        val client = gate(ComputerState.Incompatible(Incompatibility.CLIENT_TOO_OLD, "x"), Operation.TaskSubmit)
        val computer = gate(ComputerState.Incompatible(Incompatibility.COMPUTER_TOO_OLD, "x"), Operation.TaskSubmit)
        assertNotEquals(client, computer)
    }

    // It used to be reported as a capability problem, telling a user whose phone
    // was out of date to go and update their computer.
    @Test
    fun an_incompatible_computer_is_never_a_capability_problem() {
        for (kind in Incompatibility.entries) {
            val state = ComputerState.Incompatible(kind, "原因")
            assertNotEquals(
                "$kind must not read as a missing capability",
                Gate.NoCapability,
                gate(state, Operation.TaskSubmit),
            )
        }
    }

    /**
     * The two states that used to borrow the "update the computer" wording.
     *
     * NO_COMMON_VERSION gives no basis for naming one end: neither is strictly
     * newer, so telling someone to update "the computer" is a coin flip.
     * NO_USABLE_OPERATIONS can happen when the protocol matches perfectly and
     * the device simply holds no usable grant — a version instruction there
     * sends the user to update software that is already fine.
     */
    @Test
    fun no_common_version_does_not_name_a_single_end() {
        val state = ComputerState.Incompatible(Incompatibility.NO_COMMON_VERSION, "无共同版本")
        assertEquals(Gate.NoCommonVersion, gate(state, Operation.TaskSubmit))
        assertNotEquals(
            "must not claim the computer needs updating",
            Gate.WrongVersion(clientTooOld = false),
            gate(state, Operation.TaskSubmit),
        )
        assertNotEquals(Gate.WrongVersion(clientTooOld = true), gate(state, Operation.TaskSubmit))
    }

    @Test
    fun no_usable_operations_is_not_a_version_problem() {
        val state = ComputerState.Incompatible(Incompatibility.NO_USABLE_OPERATIONS, "没有可用操作")
        assertEquals(Gate.NoUsableOperations, gate(state, Operation.TaskSubmit))
        assertNotEquals(
            "the protocol matches here; this is capability and permission",
            Gate.WrongVersion(clientTooOld = false),
            gate(state, Operation.TaskSubmit),
        )
    }

    // Every incompatible kind must produce a *specific* reason, not just "not
    // the capability one". Asserting only the negative is how the previous
    // version passed while three kinds shared one wrong message.
    @Test
    fun every_incompatible_kind_has_its_own_reason() {
        val reasons = Incompatibility.entries.map { kind ->
            gate(ComputerState.Incompatible(kind, "原因"), Operation.TaskSubmit)
        }
        assertEquals("four kinds, four distinct reasons", 4, reasons.toSet().size)
    }

    @Test
    fun a_failed_handshake_keeps_its_own_reason() {
        assertEquals(
            Gate.Failed,
            gate(ComputerState.Failed("回复无法解析"), Operation.TaskSubmit),
        )
    }

    @Test
    fun offline_and_unpaired_are_distinct() {
        assertEquals(Gate.Offline, gate(ComputerState.Offline("超时"), Operation.TaskSubmit))
        assertEquals(Gate.NoComputer, gate(ComputerState.Unauthenticated("未配对"), Operation.TaskSubmit))
        assertEquals(Gate.NoComputer, gate(ComputerState.Revoked("已移除"), Operation.TaskSubmit))
    }

    @Test
    fun a_forbidden_computer_is_a_permission_problem() {
        assertEquals(
            Gate.NoScope,
            gate(ComputerState.Forbidden("缺 sessions.read"), Operation.SessionList),
        )
    }

    /**
     * Two computers, the same action, different reasons.
     *
     * The acceptance criterion asks for this directly: switching machines must
     * change *why* a control is unavailable, not merely whether it is.
     */
    @Test
    fun two_computers_give_different_reasons_for_the_same_action() {
        val generous = connected(
            capabilities = setOf(Operation.ComputerStatus, Operation.TaskSubmit),
            granted = setOf(Scope.SessionsRead, Scope.TasksSubmit),
        )
        val restricted = connected(
            capabilities = setOf(Operation.ComputerStatus, Operation.TaskSubmit),
            granted = setOf(Scope.SessionsRead),
        )
        val oldHarness = connected(
            capabilities = setOf(Operation.ComputerStatus),
            granted = Scope.entries.toSet(),
        )

        val reasons = listOf(
            gate(generous, Operation.TaskSubmit),
            gate(restricted, Operation.TaskSubmit),
            gate(oldHarness, Operation.TaskSubmit),
        )
        assertEquals(Gate.Allowed, reasons[0])
        assertEquals(Gate.NoScope, reasons[1])
        assertEquals(Gate.NoCapability, reasons[2])
        assertEquals("three computers, three different reasons", 3, reasons.toSet().size)
    }

    // A build that cannot do an operation says so regardless of permissions: the
    // phone's own limitation is not something the computer can grant away.
    @Test
    fun an_unimplemented_operation_says_so_first() {
        val state = connected(
            capabilities = Operation.entries.toSet(),
            granted = Scope.entries.toSet(),
        )
        assertEquals(
            Gate.NotImplemented,
            gate(state, Operation.ApprovalDecide, implemented = false),
        )
    }
}
