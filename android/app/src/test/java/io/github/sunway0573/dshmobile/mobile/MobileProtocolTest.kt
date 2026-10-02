package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.protocol.ComputerStatus
import io.github.sunway0573.dshmobile.mobile.protocol.Incompatibility
import io.github.sunway0573.dshmobile.mobile.protocol.MobileProtocol
import io.github.sunway0573.dshmobile.mobile.protocol.Negotiation
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.protocol.Refusal
import io.github.sunway0573.dshmobile.mobile.protocol.Scope
import io.github.sunway0573.dshmobile.mobile.protocol.StatusParseResult
import org.json.JSONArray
import org.junit.Assert.assertNotEquals
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
        val parsed = parsed(json)
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
        assertEquals(setOf(Scope.SessionsRead), parsed(json)!!.grantedScopes)
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
        val json = encodeCommand(
            computerId = "pc-1",
            deviceId = "dev-1",
            commandId = "c-1",
            operation = Operation.SessionList,
            payload = null,
            protocolVersion = 7,
        )
        assertEquals(7, json.getInt("protocolVersion"))
    }
}

/** Unwrap a successful parse, for tests that only care about the happy path. */
private fun parsed(json: org.json.JSONObject): ComputerStatus? =
    (parseStatus(json) as? StatusParseResult.Ok)?.status

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

        val parsed = parsed(json)!!
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
        assertTrue(parseStatus(json) is StatusParseResult.Malformed)
    }

    @Test
    fun a_status_without_a_computer_id_is_rejected() {
        val json = JSONObject().put("protocolVersion", 1)
        assertTrue(parseStatus(json) is StatusParseResult.Malformed)
    }

    @Test
    fun a_zero_or_negative_protocol_version_is_rejected() {
        assertTrue(
            parseStatus(JSONObject().put("protocolVersion", 0).put("computerId", "p"))
                is StatusParseResult.Malformed,
        )
        assertTrue(
            parseStatus(JSONObject().put("protocolVersion", -3).put("computerId", "p"))
                is StatusParseResult.Malformed,
        )
    }

    @Test
    fun a_missing_name_falls_back_to_the_id_rather_than_blank() {
        val json = JSONObject().put("protocolVersion", 1).put("computerId", "pc-1")
        assertEquals("pc-1", parsed(json)!!.computerName)
    }

    @Test
    fun missing_capability_lists_are_empty_not_fatal() {
        val json = JSONObject().put("protocolVersion", 1).put("computerId", "pc-1")
        val parsed = parsed(json)!!
        assertTrue(parsed.capabilities.isEmpty())
        assertTrue(parsed.grantedScopes.isEmpty())
    }
}

/**
 * Strict reading of the handshake.
 *
 * The review drove the compiled parser with three malformed `supportedProtocols`
 * values and got `Ready` for all three — the parser *repaired* each one into
 * compatibility. An error that becomes "compatible" is worse than an error,
 * because nothing downstream can tell it happened.
 */
class StrictVersionParsingTest {

    private fun reply(supported: Any?): JSONObject = JSONObject().apply {
        put("protocolVersion", 1)
        put("computerId", "pc-1")
        put("capabilities", JSONArray(listOf("session.list")))
        put("grantedScopes", JSONArray(listOf("sessions.read")))
        if (supported != null) put("supportedProtocols", supported)
    }

    // `[]` — a computer that declares nothing is broken, not compatible.
    @Test
    fun an_empty_version_list_is_malformed_not_inferred() {
        val result = parseStatus(reply(JSONArray()))
        assertTrue(result is StatusParseResult.Malformed)
        assertTrue((result as StatusParseResult.Malformed).detail.contains("空数组"))
    }

    // `[1.9]` — truncating a non-integer version fabricates agreement.
    @Test
    fun a_non_integer_version_is_rejected_not_truncated() {
        val result = parseStatus(reply(JSONArray(listOf(1.9))))
        assertTrue(result is StatusParseResult.Malformed)
    }

    // `"invalid"` — a field of the wrong type means the two ends already
    // disagree about the protocol.
    @Test
    fun a_wrong_field_type_is_rejected_not_treated_as_a_legacy_handshake() {
        val result = parseStatus(reply("invalid"))
        assertTrue(result is StatusParseResult.Malformed)
        assertTrue((result as StatusParseResult.Malformed).detail.contains("不是数组"))
    }

    @Test
    fun a_string_version_element_is_rejected() {
        assertTrue(parseStatus(reply(JSONArray(listOf("1")))) is StatusParseResult.Malformed)
    }

    @Test
    fun a_zero_or_negative_version_is_rejected() {
        assertTrue(parseStatus(reply(JSONArray(listOf(0)))) is StatusParseResult.Malformed)
        assertTrue(parseStatus(reply(JSONArray(listOf(-1)))) is StatusParseResult.Malformed)
    }

    @Test
    fun a_null_field_is_treated_as_absent() {
        assertTrue(parseStatus(reply(JSONObject.NULL)) is StatusParseResult.Ok)
    }

    /**
     * The one permitted compatibility branch. A field that is entirely absent
     * is a legacy single-version handshake — and it is written as its own rule
     * precisely because sharing a branch with the errors above is how an error
     * becomes "compatible".
     */
    @Test
    fun an_absent_list_falls_back_to_the_declared_single_version() {
        val result = parseStatus(reply(null))
        assertTrue(result is StatusParseResult.Ok)
        assertEquals(setOf(1), (result as StatusParseResult.Ok).status.supportedProtocols)
    }

    @Test
    fun a_fractional_protocol_version_is_rejected() {
        val json = reply(JSONArray(listOf(1)))
        json.put("protocolVersion", 1.5)
        assertTrue(parseStatus(json) is StatusParseResult.Malformed)
    }

    @Test
    fun a_string_protocol_version_is_rejected() {
        val json = reply(JSONArray(listOf(1)))
        json.put("protocolVersion", "1")
        assertTrue(parseStatus(json) is StatusParseResult.Malformed)
    }

    // A well-formed multi-version list is read as written.
    @Test
    fun a_valid_multi_version_list_is_kept() {
        val result = parseStatus(reply(JSONArray(listOf(1, 2, 3))))
        assertEquals(
            setOf(1, 2, 3),
            (result as StatusParseResult.Ok).status.supportedProtocols,
        )
    }
}

/**
 * The two ends must fit together.
 *
 * The review's counterexample: the phone's encoder produced an envelope with no
 * `deviceId`, and the computer's adapter answered `UNAUTHENTICATED`. Each side
 * passed its own tests. This checks the encoder's actual output against the
 * field the computer requires, in one place.
 */
class CrossLanguageContractTest {

    @Test
    fun the_encoded_command_carries_every_field_the_computer_requires() {
        val json = encodeCommand(
            computerId = "pc-1",
            deviceId = "phone-7",
            commandId = "cmd-1",
            operation = Operation.SessionList,
            payload = null,
            protocolVersion = 1,
        )
        // These are exactly the fields the TS CommandEnvelope declares, and
        // `deviceId` is one the encoder used to omit.
        for (field in listOf("protocolVersion", "computerId", "deviceId", "commandId", "operation")) {
            assertTrue("the wire form must carry $field", json.has(field))
        }
        assertEquals("phone-7", json.getString("deviceId"))
    }

    @Test
    fun the_encoded_version_is_the_negotiated_one_and_not_a_constant() {
        val json = encodeCommand(
            computerId = "pc-1",
            deviceId = "phone-7",
            commandId = "cmd-1",
            operation = Operation.SessionList,
            payload = null,
            protocolVersion = 42,
        )
        assertEquals(42, json.getInt("protocolVersion"))
        assertNotEquals(
            "a default would silently send a version the ends did not agree on",
            MobileProtocol.VERSION,
            json.getInt("protocolVersion"),
        )
    }

    @Test
    fun the_operation_is_the_wire_name_not_the_kotlin_name() {
        val json = encodeCommand(
            computerId = "pc-1",
            deviceId = "phone-7",
            commandId = "cmd-1",
            operation = Operation.SessionPage,
            payload = JSONObject().put("sessionId", "s-1"),
            protocolVersion = 1,
        )
        assertEquals("session.page", json.getString("operation"))
    }
}

class CommandEncodingTest {

    @Test
    fun a_command_carries_the_protocol_version_and_target() {
        val json = encodeCommand(
            computerId = "pc-1",
            deviceId = "dev-1",
            commandId = "cmd-1",
            operation = Operation.TaskSubmit,
            payload = JSONObject().put("text", "hi"),
            protocolVersion = MobileProtocol.VERSION,
        )
        assertEquals(MobileProtocol.VERSION, json.getInt("protocolVersion"))
        assertEquals("pc-1", json.getString("computerId"))
        assertEquals("cmd-1", json.getString("commandId"))
        assertEquals("task.submit", json.getString("operation"))
        assertEquals("hi", json.getJSONObject("payload").getString("text"))
    }

    @Test
    fun a_command_without_a_payload_omits_the_field() {
        val json = encodeCommand(
            computerId = "pc-1",
            deviceId = "dev-1",
            commandId = "cmd-1",
            operation = Operation.SessionList,
            payload = null,
            protocolVersion = MobileProtocol.VERSION,
        )
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
