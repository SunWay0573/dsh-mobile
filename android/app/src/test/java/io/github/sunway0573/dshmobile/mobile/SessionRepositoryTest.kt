package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.protocol.ComputerStatus
import io.github.sunway0573.dshmobile.mobile.protocol.Incompatibility
import io.github.sunway0573.dshmobile.mobile.protocol.MobileProtocol
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.protocol.Scope
import io.github.sunway0573.dshmobile.mobile.repository.ComputerState
import io.github.sunway0573.dshmobile.mobile.repository.SessionRepository
import io.github.sunway0573.dshmobile.mobile.transport.CommandReply
import io.github.sunway0573.dshmobile.mobile.transport.MobileTransport
import io.github.sunway0573.dshmobile.mobile.transport.TransportResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun status(
    protocolVersion: Int = MobileProtocol.VERSION,
    supported: Set<Int> = setOf(MobileProtocol.VERSION),
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

private class StubTransport(
    private val reply: TransportResult<ComputerStatus>,
    private val throws: Throwable? = null,
    override val endpoint: String = "stub://pc-1",
) : MobileTransport {
    var asked: String? = null
    override suspend fun status(deviceId: String): TransportResult<ComputerStatus> {
        asked = deviceId
        throws?.let { throw it }
        return reply
    }

    override suspend fun command(
        commandId: String,
        operation: Operation,
        payload: JSONObject?,
    ): TransportResult<CommandReply> = TransportResult.Unreachable("stub")
}

private fun repo(
    reply: TransportResult<ComputerStatus>,
    deviceId: String = "dev-1",
    throws: Throwable? = null,
) = SessionRepository(StubTransport(reply, throws = throws), deviceId)

/**
 * The repository is where "what should this screen show" is decided, so these
 * tests are about telling apart situations a user must not confuse.
 */
class SessionRepositoryTest {

    @Test
    fun a_healthy_computer_reports_what_is_available() = runBlocking {
        val state = repo(TransportResult.Ok(status())).refresh()
        assertTrue(state is ComputerState.Connected)
        state as ComputerState.Connected
        assertEquals("我的 Mac", state.computerName)
        assertTrue(state.can(Operation.TaskSubmit))
        assertEquals("0.2.0-rc.2", state.hostVersion)
    }

    // The distinction the whole state machine exists for. Unreachable means wait
    // or fix the network; incompatible means update something. Sending a user to
    // the wrong one wastes their time and makes the app look broken.
    @Test
    fun an_unreachable_computer_is_offline_not_incompatible() = runBlocking {
        val state = repo(TransportResult.Unreachable("连接超时")).refresh()
        assertTrue(state is ComputerState.Offline)
        assertEquals("连接超时", (state as ComputerState.Offline).detail)
    }

    @Test
    fun a_newer_computer_says_the_app_needs_updating() = runBlocking {
        val state = repo(
            TransportResult.Ok(status(protocolVersion = 2, supported = setOf(2))),
        ).refresh()
        assertTrue(state is ComputerState.Incompatible)
        state as ComputerState.Incompatible
        assertEquals(Incompatibility.CLIENT_TOO_OLD, state.kind)
        assertTrue(state.message.contains("重试不会有帮助"))
    }

    @Test
    fun no_shared_version_is_reported_as_such() = runBlocking {
        val state = repo(
            TransportResult.Ok(status(protocolVersion = 0, supported = emptySet())),
        ).refresh()
        state as ComputerState.Incompatible
        assertEquals(Incompatibility.NO_COMMON_VERSION, state.kind)
    }

    /**
     * The review's point: every refusal used to be reported as a protocol
     * incompatibility, and a test had locked that in. Updating the app does not
     * grant a permission that was never given, and does not restore a device the
     * computer removed.
     */
    @Test
    fun a_scope_refusal_is_a_permission_problem_not_a_version_problem() = runBlocking {
        val state = repo(TransportResult.Refused("FORBIDDEN_SCOPE", "这台手机没有获得授权")).refresh()
        assertTrue("should be its own state", state is ComputerState.Forbidden)
        assertFalse("updating the app would not help", state is ComputerState.Incompatible)
        assertTrue((state as ComputerState.Forbidden).detail.contains("没有获得授权"))
    }

    @Test
    fun a_revoked_device_is_its_own_state() = runBlocking {
        val state = repo(TransportResult.Refused("DEVICE_REVOKED", "设备已被移除")).refresh()
        assertTrue(state is ComputerState.Revoked)
        assertFalse(state is ComputerState.Incompatible)
    }

    @Test
    fun a_missing_identity_is_its_own_state() = runBlocking {
        val state = repo(TransportResult.Refused("UNAUTHENTICATED", "没有设备身份")).refresh()
        assertTrue(state is ComputerState.Unauthenticated)
    }

    @Test
    fun a_protocol_refusal_is_incompatible() = runBlocking {
        val state = repo(TransportResult.Refused("UNSUPPORTED_PROTOCOL", "版本不一致")).refresh()
        assertTrue(state is ComputerState.Incompatible)
    }

    @Test
    fun a_missing_capability_reads_as_incompatible_not_as_a_crash() = runBlocking {
        val state = repo(TransportResult.Refused("MISSING_CAPABILITY", "做不到")).refresh()
        assertTrue(state is ComputerState.Incompatible)
    }

    @Test
    fun an_unrecognised_refusal_falls_back_to_failed() = runBlocking {
        val state = repo(TransportResult.Refused("SOMETHING_NEW", "未知原因")).refresh()
        assertTrue(state is ComputerState.Failed)
    }

    @Test
    fun an_unpaired_phone_does_not_ask_the_computer_anything() = runBlocking {
        // No device id means there is nobody to ask as. Going out and coming back
        // with "unauthenticated" would be a round trip to learn something already
        // known here.
        val transport = StubTransport(TransportResult.Unreachable("不应被调用"))
        val state = SessionRepository(transport, "").refresh()
        assertTrue(state is ComputerState.Unauthenticated)
        assertEquals("the transport must not have been touched", null, transport.asked)
    }

    @Test
    fun the_device_identity_is_the_one_passed_in() = runBlocking {
        val transport = StubTransport(TransportResult.Ok(status()))
        SessionRepository(transport, "phone-7").refresh()
        assertEquals("phone-7", transport.asked)
    }

    @Test
    fun the_negotiated_version_reaches_the_state() = runBlocking {
        val state = repo(TransportResult.Ok(status())).refresh()
        state as ComputerState.Connected
        assertEquals(MobileProtocol.VERSION, state.protocolVersion)
    }

    // A transport is a socket, an HTTP client, a tunnel — code that throws. If an
    // exception escaped here it would take down the screen instead of reporting
    // a connection problem, which is a much worse outcome for the same event.
    @Test
    fun a_transport_that_throws_becomes_offline_rather_than_a_crash() = runBlocking {
        val state = repo(
            TransportResult.Unreachable("unused"),
            throws = IllegalStateException("socket closed"),
        ).refresh()
        assertTrue(state is ComputerState.Offline)
        assertTrue((state as ComputerState.Offline).detail.contains("socket closed"))
    }

    // An empty task list and an unusable connection look identical on screen.
    @Test
    fun a_computer_with_no_usable_operation_is_incompatible() = runBlocking {
        val state = repo(TransportResult.Ok(status(capabilities = emptySet()))).refresh()
        assertTrue(state is ComputerState.Incompatible)
    }

    @Test
    fun partial_grants_narrow_what_the_ui_may_offer() = runBlocking {
        val state = repo(TransportResult.Ok(status(scopes = setOf(Scope.SessionsRead)))).refresh()
        state as ComputerState.Connected
        assertTrue(state.can(Operation.SessionList))
        assertFalse("reading must not imply cancelling", state.can(Operation.TaskCancel))
        assertTrue("and the absent ones are named", state.unavailable.contains(Operation.TaskCancel))
    }

    @Test
    fun the_state_is_kept_for_a_screen_that_renders_before_refreshing() = runBlocking {
        val r = repo(TransportResult.Ok(status()))
        assertTrue(r.state is ComputerState.Offline)
        r.refresh()
        assertTrue(r.state is ComputerState.Connected)
    }

    /**
     * Two computers can be on different Harness versions with different grants.
     * A shared repository would show one machine's permissions while talking to
     * the other — a button that works because the *other* computer allowed it.
     */
    @Test
    fun two_repositories_do_not_share_state() = runBlocking {
        val first = repo(TransportResult.Ok(status()), deviceId = "phone-a")
        val second = repo(
            TransportResult.Ok(status(scopes = setOf(Scope.SessionsRead))),
            deviceId = "phone-b",
        )
        first.refresh()
        second.refresh()

        assertTrue((first.state as ComputerState.Connected).can(Operation.TaskSubmit))
        assertFalse((second.state as ComputerState.Connected).can(Operation.TaskSubmit))
    }

    @Test
    fun two_computers_keep_their_own_identity() = runBlocking {
        val mac = SessionRepository(StubTransport(TransportResult.Ok(status())), "dev-mac")
        val mini = SessionRepository(
            StubTransport(
                TransportResult.Ok(
                    status().copy(computerId = "pc-2", computerName = "办公室的 Mac mini"),
                ),
            ),
            "dev-mini",
        )
        mac.refresh()
        mini.refresh()
        assertEquals("我的 Mac", (mac.state as ComputerState.Connected).computerName)
        assertEquals("办公室的 Mac mini", (mini.state as ComputerState.Connected).computerName)
        assertEquals("pc-2", (mini.state as ComputerState.Connected).computerId)
    }
}
