package io.github.sunway0573.dshmobile.mobile.repository

import io.github.sunway0573.dshmobile.mobile.protocol.Incompatibility
import io.github.sunway0573.dshmobile.mobile.protocol.Negotiation
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.protocol.negotiate
import io.github.sunway0573.dshmobile.mobile.transport.MobileTransport
import io.github.sunway0573.dshmobile.mobile.transport.TransportResult

/**
 * What a screen should show about one computer.
 *
 * Four states that a user must be able to tell apart, because each has a
 * different next action:
 *
 * - {@link Connected} — usable, possibly with fewer operations than this build
 *   implements.
 * - {@link Offline} — the computer could not be reached. Waiting, or fixing the
 *   network, may help.
 * - {@link Incompatible} — it was reached and cannot be used until one end is
 *   updated. **Retrying will never help**, and saying so is the point.
 * - {@link Failed} — it answered, but with something unusable.
 *
 * Collapsing the middle two into "error" would send a user to check their Wi-Fi
 * when they need to update a plugin.
 */
internal sealed interface ComputerState {

    data class Connected(
        val computerId: String,
        val computerName: String,
        val hostVersion: String,
        val adapterVersion: String,
        val available: Set<Operation>,
        val unavailable: Set<Operation>,
    ) : ComputerState {

        fun can(operation: Operation): Boolean = available.contains(operation)
    }

    data class Offline(val detail: String) : ComputerState

    data class Incompatible(val kind: Incompatibility, val message: String) : ComputerState

    data class Failed(val detail: String) : ComputerState
}

/**
 * The phone's view of one computer.
 *
 * One repository per paired computer, keyed by `computerId` above this. Two
 * computers must not share one: they can be on different Harness versions, with
 * different capabilities and different grants, and a shared cache would show one
 * machine's permissions while talking to the other.
 */
internal class SessionRepository(
    private val transport: MobileTransport,
) {

    /** The last state, for a screen that needs to render before a refresh. */
    var state: ComputerState = ComputerState.Offline("尚未连接")
        private set

    /**
     * Connect and work out what this phone may do here.
     *
     * Never throws: every failure mode is one of the states above, so a caller
     * cannot forget to handle one and leave a screen blank.
     */
    suspend fun refresh(): ComputerState {
        val reached = try {
            transport.status()
        } catch (error: Throwable) {
            // A transport is third-party-adjacent code — an HTTP client, a
            // socket, a tunnel. An exception escaping here would take down the
            // screen rather than reporting a connection problem.
            val detail = error.message ?: error::class.simpleName ?: "未知错误"
            return ComputerState.Offline("连接${transport.endpoint}时出错：$detail").also { state = it }
        }

        val next = when (reached) {
            is TransportResult.Unreachable ->
                ComputerState.Offline(reached.detail)

            is TransportResult.Refused ->
                // The computer answered and refused the handshake itself. That
                // is not a network problem, so it must not read like one.
                ComputerState.Incompatible(
                    Incompatibility.NO_USABLE_OPERATIONS,
                    reached.message,
                )

            is TransportResult.Ok -> when (val negotiated = negotiate(reached.value)) {
                is Negotiation.Ready -> ComputerState.Connected(
                    computerId = negotiated.status.computerId,
                    computerName = negotiated.status.computerName,
                    hostVersion = negotiated.status.hostVersion,
                    adapterVersion = negotiated.status.adapterVersion,
                    available = negotiated.available,
                    unavailable = negotiated.unavailableKnown,
                )

                is Negotiation.Incompatible ->
                    ComputerState.Incompatible(negotiated.kind, negotiated.message)

                is Negotiation.Malformed ->
                    ComputerState.Failed(negotiated.detail)
            }
        }

        state = next
        return next
    }
}
