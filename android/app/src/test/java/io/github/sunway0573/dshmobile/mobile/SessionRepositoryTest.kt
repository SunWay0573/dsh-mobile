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
    capabilities: Set<Operation> = Operation.entries.toSet(),
    scopes: Set<Scope> = Scope.entries.toSet(),
) = ComputerStatus(
    protocolVersion = protocolVersion,
    adapterVersion = "1.0.0",
    hostVersion = "0.2.0-rc.2",
    computerId = "pc-1",
    computerName = "我的 Mac",
    capabilities = capabilities,
    grantedScopes = scopes,
)

/** A transport that returns whatever the test asks for. */
private class StubTransport(
    private val reply: TransportResult<ComputerStatus>,
    private val throws: Throwable? = null,
    override val endpoint: String = "stub://pc-1",
) : MobileTransport {
    override suspend fun status(): TransportResult<ComputerStatus> {
        throws?.let { throw it }
        return reply
    }

    override suspend fun command(
        commandId: String,
        operation: Operation,
        payload: JSONObject?,
    ): TransportResult<CommandReply> = TransportResult.Unreachable("stub")
}

/**
 * The repository is where "what should this screen show" is decided, so these
 * tests are about telling four situations apart that a user must not confuse.
 */
class SessionRepositoryTest {

    @Test
    fun a_healthy_computer_reports_what_is_available() = runBlocking {
        val state = SessionRepository(StubTransport(TransportResult.Ok(status()))).refresh()
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
        val state = SessionRepository(
            StubTransport(TransportResult.Unreachable("连接超时")),
        ).refresh()
        assertTrue(state is ComputerState.Offline)
        assertEquals("连接超时", (state as ComputerState.Offline).detail)
    }

    @Test
    fun a_newer_computer_is_incompatible_and_says_the_app_needs_updating() = runBlocking {
        val state = SessionRepository(
            StubTransport(TransportResult.Ok(status(protocolVersion = MobileProtocol.VERSION + 1))),
        ).refresh()
        assertTrue(state is ComputerState.Incompatible)
        state as ComputerState.Incompatible
        assertEquals(Incompatibility.CLIENT_TOO_OLD, state.kind)
        assertTrue(state.message.contains("重试不会有帮助"))
    }

    @Test
    fun an_older_computer_says_the_plugin_needs_updating() = runBlocking {
        val state = SessionRepository(
            StubTransport(TransportResult.Ok(status(protocolVersion = MobileProtocol.VERSION - 1))),
        ).refresh()
        state as ComputerState.Incompatible
        assertEquals(Incompatibility.COMPUTER_TOO_OLD, state.kind)
    }

    @Test
    fun a_handshake_the_computer_refuses_is_reported_with_its_reason() = runBlocking {
        val state = SessionRepository(
            StubTransport(TransportResult.Refused("FORBIDDEN_SCOPE", "这台手机没有获得授权")),
        ).refresh()
        assertTrue(state is ComputerState.Incompatible)
        assertTrue((state as ComputerState.Incompatible).message.contains("没有获得授权"))
    }

    // A transport is a socket, an HTTP client, a tunnel — code that throws. If
    // an exception escaped here it would take down the screen instead of
    // reporting a connection problem, which is a much worse outcome for the
    // same underlying event.
    @Test
    fun a_transport_that_throws_becomes_offline_rather_than_a_crash() = runBlocking {
        val state = SessionRepository(
            StubTransport(TransportResult.Unreachable("unused"), throws = IllegalStateException("socket closed")),
        ).refresh()
        assertTrue(state is ComputerState.Offline)
        assertTrue((state as ComputerState.Offline).detail.contains("socket closed"))
    }

    // An empty task list and an unusable connection look identical on screen.
    // This one has to reach the user as a sentence.
    @Test
    fun a_computer_with_no_usable_operation_is_incompatible() = runBlocking {
        val state = SessionRepository(
            StubTransport(TransportResult.Ok(status(capabilities = emptySet()))),
        ).refresh()
        assertTrue(state is ComputerState.Incompatible)
    }

    @Test
    fun partial_grants_narrow_what_the_ui_may_offer() = runBlocking {
        val state = SessionRepository(
            StubTransport(TransportResult.Ok(status(scopes = setOf(Scope.SessionsRead)))),
        ).refresh()
        state as ComputerState.Connected
        assertTrue(state.can(Operation.SessionList))
        assertFalse("reading must not imply cancelling", state.can(Operation.TaskCancel))
        assertTrue("and the absent ones are named", state.unavailable.contains(Operation.TaskCancel))
    }

    @Test
    fun the_state_is_kept_for_a_screen_that_renders_before_refreshing() = runBlocking {
        val repository = SessionRepository(StubTransport(TransportResult.Ok(status())))
        assertTrue(repository.state is ComputerState.Offline)
        repository.refresh()
        assertTrue(repository.state is ComputerState.Connected)
    }

    // Two computers can be on different Harness versions with different grants.
    // A shared repository would show one machine's permissions while talking to
    // the other, which is the multi-computer bug in its most dangerous form.
    @Test
    fun two_repositories_do_not_share_state() = runBlocking {
        val first = SessionRepository(StubTransport(TransportResult.Ok(status())))
        val second = SessionRepository(
            StubTransport(TransportResult.Ok(status(scopes = setOf(Scope.SessionsRead)))),
        )
        first.refresh()
        second.refresh()

        assertTrue((first.state as ComputerState.Connected).can(Operation.TaskSubmit))
        assertFalse((second.state as ComputerState.Connected).can(Operation.TaskSubmit))
    }
}
