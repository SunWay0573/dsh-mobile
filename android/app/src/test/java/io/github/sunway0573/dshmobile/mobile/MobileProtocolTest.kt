package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.protocol.ComputerStatus
import io.github.sunway0573.dshmobile.mobile.protocol.Incompatibility
import io.github.sunway0573.dshmobile.mobile.protocol.MobileProtocol
import io.github.sunway0573.dshmobile.mobile.protocol.Negotiation
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.protocol.Refusal
import io.github.sunway0573.dshmobile.mobile.protocol.Scope
import io.github.sunway0573.dshmobile.mobile.protocol.encodeCommand
import io.github.sunway0573.dshmobile.mobile.protocol.negotiate
import io.github.sunway0573.dshmobile.mobile.protocol.selectProtocolVersion
import io.github.sunway0573.dshmobile.mobile.protocol.parseStatus
import io.github.sunway0573.dshmobile.mobile.protocol.refusalMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun status(
    protocolVersion: Int = MobileProtocol.VERSION,
    // Declared separately from `protocolVersion` on purpose: a server can be
    // speaking one version while listing several, and the negotiation must use
    // the list.
    supported: Set<Int> = setOf(protocolVersion),
    capabilities: Set<Operation> = Operation.entries.toSet(),
    scopes: Set<Scope> = Scope.entries.toSet(),
) = ComputerStatus(
    protocolVersion = protocolVersion,
    supportedProtocols = supported,
    adapterVersion = "1.0.0",
    hostVersion = "0.2.0-rc.2",
    computerId = "pc-1",
    computerName = "我的 Mac",
    capabilities = capabilities,
    grantedScopes = scopes,
)

/**
 * Version negotiation.
 *
 * The failure mode this guards against is not a crash. It is a screen that looks
 * like an empty computer, sending a user to check their network when the actual
 * answer is "one of these two things needs updating, and retrying will never
 * help".
 */
class NegotiationTest {

    @Test
    fun matching_versions_are_usable() {
        val result = negotiate(status())
        assertTrue(result is Negotiation.Ready)
    }

    // The two mismatch directions have different fixes, and telling a user the
    // wrong one sends them to update a thing that is already current.
    @Test
    fun a_newer_computer_means_the_phone_needs_updating() {
        val result = negotiate(status(protocolVersion = MobileProtocol.VERSION + 1))
        assertTrue(result is Negotiation.Incompatible)
        result as Negotiation.Incompatible
        assertEquals(Incompatibility.CLIENT_TOO_OLD, result.kind)
        assertTrue("should say to update the app", result.message.contains("App"))
    }

    @Test
    fun an_older_computer_means_its_plugin_needs_updating() {
        val result = negotiate(status(protocolVersion = MobileProtocol.VERSION - 1))
        result as Negotiation.Incompatible
        assertEquals(Incompatibility.COMPUTER_TOO_OLD, result.kind)
        assertTrue("should say to update the plugin", result.message.contains("插件"))
    }

    // A version mismatch does not resolve itself. If the message reads like a
    // transient problem the user retries forever and concludes the app is broken.
    @Test
    fun both_mismatches_say_that_retrying_will_not_help() {
        for (version in listOf(MobileProtocol.VERSION + 1, MobileProtocol.VERSION - 1)) {
            val result = negotiate(status(protocolVersion = version)) as Negotiation.Incompatible
            assertTrue(
                "message must rule out retrying: ${result.message}",
                result.message.contains("重试不会有帮助"),
            )
        }
    }

    @Test
    fun both_versions_appear_in_the_message() {
        val result = negotiate(status(protocolVersion = 7)) as Negotiation.Incompatible
        assertTrue("should name the computer's version", result.message.contains("7"))
        assertTrue(
            "should name this build's version",
            result.message.contains(MobileProtocol.VERSION.toString()),
        )
    }
}

/**
 * What the phone may actually offer.
 *
 * All three of protocol, capability and permission, and the phone's own
 * implementation as a fourth filter. A capability the computer advertises but
 * this build cannot render is not available, whatever the handshake says.
 */
class AvailabilityTest {

    @Test
    fun everything_is_available_when_everything_lines_up() {
        val ready = negotiate(status()) as Negotiation.Ready
        assertEquals(Operation.entries.toSet(), ready.available)
    }

    @Test
    fun a_capability_the_computer_lacks_is_not_available() {
        val ready = negotiate(
            status(capabilities = setOf(Operation.ComputerStatus, Operation.SessionList)),
        ) as Negotiation.Ready
        assertTrue(ready.available.contains(Operation.SessionList))
        assertFalse(ready.available.contains(Operation.TaskSubmit))
        assertTrue("and it is reported as known-missing", ready.unavailableKnown.contains(Operation.TaskSubmit))
    }

    @Test
    fun an_operation_whose_scope_was_not_granted_is_not_available() {
        val ready = negotiate(status(scopes = setOf(Scope.SessionsRead))) as Negotiation.Ready
        assertTrue(ready.available.contains(Operation.SessionList))
        // Read and write are separate scopes: watching a build is not cancelling it.
        assertFalse(ready.available.contains(Operation.TaskSubmit))
        assertFalse(ready.available.contains(Operation.TaskCancel))
        assertFalse(ready.available.contains(Operation.ApprovalDecide))
    }

    @Test
    fun approving_needs_its_own_scope_not_the_read_one() {
        val ready = negotiate(status(scopes = setOf(Scope.SessionsRead, Scope.QuestionsRead)))
            as Negotiation.Ready
        assertFalse(
            "reading sessions must not imply the right to answer approvals",
            ready.available.contains(Operation.ApprovalDecide),
        )
    }

    // An empty task list and an unusable connection look the same on screen.
    // This is the difference.
    @Test
    fun no_usable_operation_is_incompatible_rather_than_empty() {
        val result = negotiate(
            status(capabilities = setOf(Operation.SessionList), scopes = emptySet()),
        )
        assertTrue(result is Negotiation.Incompatible)
        assertEquals(Incompatibility.NO_USABLE_OPERATIONS, (result as Negotiation.Incompatible).kind)
    }

    // A newer computer may advertise operations this build has never heard of.
    // They are dropped rather than guessed at.
    @Test
    fun an_unknown_capability_from_the_wire_is_ignored() {
        val json = JSONObject()
            .put("protocolVersion", MobileProtocol.VERSION)
            .put("computerId", "pc-1")
            .put("capabilities", org.json.JSONArray(listOf("computer.status", "host.reboot", "quantum.entangle")))
            .put("grantedScopes", org.json.JSONArray(listOf("sessions.read")))
        val parsed = parseStatus(json)
        assertNotNull(parsed)
        assertEquals(setOf(Operation.ComputerStatus), parsed!!.capabilities)
    }

    @Test
    fun an_unknown_scope_from_the_wire_is_ignored() {
        val json = JSONObject()
            .put("protocolVersion", MobileProtocol.VERSION)
            .put("computerId", "pc-1")
            .put("capabilities", org.json.JSONArray(listOf("computer.status")))
            .put("grantedScopes", org.json.JSONArray(listOf("sessions.read", "root.everything")))
        assertEquals(setOf(Scope.SessionsRead), parseStatus(json)!!.grantedScopes)
    }
}

/**
 * Choosing a version both ends speak.
 *
 * An earlier implementation compared a single reported number against a
 * constant, so an adapter that listed several versions was judged by one of
 * them — and a client following the handshake was refused by the adapter that
 * told it what to send.
 */
class CommonVersionTest {

    @Test
    fun the_highest_shared_version_wins() {
        assertEquals(2, selectProtocolVersion(setOf(1, 2, 3), setOf(1, 2)))
    }

    @Test
    fun a_single_shared_version_is_enough() {
        assertEquals(1, selectProtocolVersion(setOf(1), setOf(1)))
    }

    @Test
    fun no_overlap_means_no_version() {
        assertEquals(null, selectProtocolVersion(setOf(2, 3), setOf(1)))
    }

    @Test
    fun an_empty_server_list_means_no_version() {
        assertEquals(null, selectProtocolVersion(emptySet(), setOf(1)))
    }

    // A version the adapter did not list must never be chosen, however new it
    // looks: the list is a statement about what it can actually do.
    @Test
    fun a_version_outside_both_lists_is_never_chosen() {
        assertEquals(1, selectProtocolVersion(setOf(1, 99), setOf(1, 98)))
    }

    @Test
    fun an_agreed_version_reaches_the_caller() {
        val ready = negotiate(status(supported = setOf(1, 5))) as Negotiation.Ready
        // 5 is the server's, not this build's, so it cannot be chosen.
        assertEquals(
            MobileProtocol.SUPPORTED_VERSIONS.max(),
            ready.protocolVersion,
        )
    }

    @Test
    fun a_newer_server_reports_that_the_app_needs_updating() {
        val result = negotiate(status(protocolVersion = 9, supported = setOf(9))) as Negotiation.Incompatible
        assertEquals(Incompatibility.CLIENT_TOO_OLD, result.kind)
    }

    @Test
    fun an_older_server_reports_that_the_plugin_needs_updating() {
        val result = negotiate(status(protocolVersion = 0, supported = setOf(0))) as Negotiation.Incompatible
        assertEquals(Incompatibility.COMPUTER_TOO_OLD, result.kind)
    }

    // A server that declares nothing usable cannot be negotiated with at all,
    // and that is a different fact from "one end is behind".
    @Test
    fun a_server_with_no_usable_versions_is_not_a_direction_problem() {
        val result = negotiate(status(protocolVersion = 1, supported = emptySet())) as Negotiation.Incompatible
        assertEquals(Incompatibility.NO_COMMON_VERSION, result.kind)
    }

    @Test
    fun the_message_names_both_sides_versions() {
        val result = negotiate(status(protocolVersion = 9, supported = setOf(9))) as Negotiation.Incompatible
        assertTrue("server version", result.message.contains("9"))
        assertTrue("client version", result.message.contains("1"))
        assertTrue("and rules out retrying", result.message.contains("重试不会有帮助"))
    }

    @Test
    fun a_command_is_encoded_with_the_agreed_version() {
        val json = encodeCommand("pc-1", "c-1", Operation.SessionList, null, protocolVersion = 7)
        assertEquals(7, json.getInt("protocolVersion"))
    }
}

class StatusParsingTest {

    @Test
    fun a_full_status_round_trips() {
        val json = JSONObject()
            .put("protocolVersion", 1)
            .put("adapterVersion", "1.0.0")
            .put("hostVersion", "0.2.0-rc.2")
            .put("computerId", "pc-1")
            .put("computerName", "我的 Mac")
            .put("capabilities", org.json.JSONArray(listOf("computer.status", "session.list")))
            .put("grantedScopes", org.json.JSONArray(listOf("sessions.read")))

        val parsed = parseStatus(json)!!
        assertEquals("pc-1", parsed.computerId)
        assertEquals("我的 Mac", parsed.computerName)
        assertEquals("0.2.0-rc.2", parsed.hostVersion)
        assertEquals(2, parsed.capabilities.size)
    }

    // A partial status must not be repaired with defaults. Assuming an absent
    // protocol version is this build's turns "we do not understand this reply"
    // into "everything is fine".
    @Test
    fun a_status_without_a_protocol_version_is_rejected() {
        val json = JSONObject().put("computerId", "pc-1")
        assertNull(parseStatus(json))
    }

    @Test
    fun a_status_without_a_computer_id_is_rejected() {
        val json = JSONObject().put("protocolVersion", 1)
        assertNull(parseStatus(json))
    }

    @Test
    fun a_zero_or_negative_protocol_version_is_rejected() {
        assertNull(parseStatus(JSONObject().put("protocolVersion", 0).put("computerId", "p")))
        assertNull(parseStatus(JSONObject().put("protocolVersion", -3).put("computerId", "p")))
    }

    @Test
    fun a_missing_name_falls_back_to_the_id_rather_than_blank() {
        val json = JSONObject().put("protocolVersion", 1).put("computerId", "pc-1")
        assertEquals("pc-1", parseStatus(json)!!.computerName)
    }

    @Test
    fun missing_capability_lists_are_empty_not_fatal() {
        val json = JSONObject().put("protocolVersion", 1).put("computerId", "pc-1")
        val parsed = parseStatus(json)!!
        assertTrue(parsed.capabilities.isEmpty())
        assertTrue(parsed.grantedScopes.isEmpty())
    }
}

class CommandEncodingTest {

    @Test
    fun a_command_carries_the_protocol_version_and_target() {
        val json = encodeCommand("pc-1", "cmd-1", Operation.TaskSubmit, JSONObject().put("text", "hi"))
        assertEquals(MobileProtocol.VERSION, json.getInt("protocolVersion"))
        assertEquals("pc-1", json.getString("computerId"))
        assertEquals("cmd-1", json.getString("commandId"))
        assertEquals("task.submit", json.getString("operation"))
        assertEquals("hi", json.getJSONObject("payload").getString("text"))
    }

    @Test
    fun a_command_without_a_payload_omits_the_field() {
        val json = encodeCommand("pc-1", "cmd-1", Operation.SessionList, null)
        assertFalse(json.has("payload"))
    }
}

class RefusalWordingTest {

    @Test
    fun every_refusal_from_the_wire_is_recognised() {
        val wires = listOf(
            "UNSUPPORTED_PROTOCOL", "UNKNOWN_OPERATION", "MISSING_CAPABILITY",
            "FORBIDDEN_SCOPE", "WRONG_COMPUTER", "COMMAND_CONFLICT",
            "COMMAND_STATE_UNKNOWN", "UNKNOWN_APPROVAL_TYPE", "HANDLER_FAILED",
        )
        for (wire in wires) {
            assertTrue("$wire should be recognised", Refusal.fromWire(wire) != Refusal.Unknown)
        }
    }

    @Test
    fun an_unrecognised_code_degrades_to_unknown_rather_than_crashing() {
        assertEquals(Refusal.Unknown, Refusal.fromWire("SOMETHING_FROM_THE_FUTURE"))
        assertEquals(Refusal.Unknown, Refusal.fromWire(null))
    }

    // Confirming that a command may or may not have run is the whole point:
    // telling the user it failed invites a resend that could run it twice.
    @Test
    fun an_unknown_command_outcome_says_so_and_points_at_the_task_list() {
        val message = refusalMessage(Refusal.CommandStateUnknown, "我的 Mac")
        assertTrue(message.contains("结果未知"))
        assertTrue(message.contains("任务列表"))
    }

    /**
     * Two different facts, two different sentences.
     *
     * "The answer could not be read" and "the question could not be placed" are
     * not the same refusal, and the earlier report claimed coverage of the
     * second by testing the first. Both must say nothing happened — neither may
     * ever read as an allow.
     */
    @Test
    fun an_unreadable_decision_says_it_was_not_applied() {
        val message = refusalMessage(Refusal.MalformedApprovalDecision, "我的 Mac")
        assertTrue(message.contains("没有被执行"))
    }

    @Test
    fun an_unplaceable_approval_says_so_and_points_at_the_computer() {
        val message = refusalMessage(Refusal.UnknownApprovalType, "我的 Mac")
        assertTrue(message.contains("没有执行"))
        assertTrue("and says where to handle it", message.contains("电脑"))
    }

    @Test
    fun the_two_approval_refusals_do_not_share_wording() {
        assertNotEquals(
            refusalMessage(Refusal.MalformedApprovalDecision, "我的 Mac"),
            refusalMessage(Refusal.UnknownApprovalType, "我的 Mac"),
        )
    }

    // Updating the app does not restore a revoked device, and a message that
    // reads like a version problem sends the user to the wrong fix.
    @Test
    fun a_revoked_device_says_updating_will_not_help() {
        val message = refusalMessage(Refusal.DeviceRevoked, "我的 Mac")
        assertTrue(message.contains("更新 App 不会恢复"))
    }

    @Test
    fun a_scope_refusal_names_the_computer_to_fix_it_on() {
        val message = refusalMessage(Refusal.ForbiddenScope, "办公室的 Mac mini")
        assertTrue(message.contains("办公室的 Mac mini"))
    }

    @Test
    fun every_refusal_produces_a_non_empty_sentence() {
        for (refusal in Refusal.entries) {
            val message = refusalMessage(refusal, "我的 Mac")
            assertTrue("${refusal.name} needs a message", message.length > 8)
        }
    }
}
