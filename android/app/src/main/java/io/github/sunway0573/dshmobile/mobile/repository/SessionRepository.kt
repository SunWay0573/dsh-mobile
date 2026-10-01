package io.github.sunway0573.dshmobile.mobile.repository

import io.github.sunway0573.dshmobile.mobile.protocol.Incompatibility
import io.github.sunway0573.dshmobile.mobile.protocol.Negotiation
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.protocol.Refusal
import io.github.sunway0573.dshmobile.mobile.protocol.negotiate
import io.github.sunway0573.dshmobile.mobile.protocol.refusalMessage
import io.github.sunway0573.dshmobile.mobile.transport.MobileTransport
import io.github.sunway0573.dshmobile.mobile.transport.TransportResult

/**
 * What a screen should show about one computer.
 *
 * Six states, because each has a different next action, and sending someone to
 * the wrong one wastes their time:
 *
 * | state | what happened | what to do |
 * | --- | --- | --- |
 * | {@link Connected} | usable | nothing |
 * | {@link Offline} | could not be reached | wait, or fix the network |
 * | {@link Unauthenticated} | never paired | scan the computer's code |
 * | {@link Revoked} | the computer removed this phone | pair again — updating the app will not help |
 * | {@link Forbidden} | paired, but this permission is missing | change the grant on the computer |
 * | {@link Incompatible} | the two ends share no protocol | update one of them; **retrying never helps** |
 *
 * An earlier version collapsed every refusal into {@link Incompatible}, and a
 * test locked that in. The consequence is a user told to update the app when the
 * actual answer is that their authorisation was revoked — and updating would not
 * have restored it.
 */
internal sealed interface ComputerState {

    data class Connected(
        val computerId: String,
        val computerName: String,
        /** The version both ends agreed on, not the constant this build speaks. */
        val protocolVersion: Int,
        val hostVersion: String,
        val adapterVersion: String,
        val available: Set<Operation>,
        val unavailable: Set<Operation>,
    ) : ComputerState {

        fun can(operation: Operation): Boolean = available.contains(operation)
    }

    data class Offline(val detail: String) : ComputerState

    /** No device identity, or the computer does not recognise this phone. */
    data class Unauthenticated(val detail: String) : ComputerState

    /** The computer removed this device. Not fixable from the phone. */
    data class Revoked(val detail: String) : ComputerState

    /** Paired, but the grant this operation needs is missing. */
    data class Forbidden(val detail: String) : ComputerState

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
    /** Which paired device this repository speaks as. Empty means not paired. */
    private val deviceId: String,
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
        if (deviceId.isEmpty()) {
            return ComputerState.Unauthenticated("这台手机还没有和任何电脑配对。")
                .also { state = it }
        }

        val reached = try {
            transport.status(deviceId)
        } catch (error: Throwable) {
            // A transport is a socket, an HTTP client, a tunnel — code that
            // throws. An exception escaping here would take down the screen
            // instead of reporting a connection problem, which is a much worse
            // outcome for the same underlying event.
            val detail = error.message ?: error::class.simpleName ?: "未知错误"
            return ComputerState.Offline("连接${transport.endpoint}时出错：$detail")
                .also { state = it }
        }

        val next = when (reached) {
            is TransportResult.Unreachable -> ComputerState.Offline(reached.detail)

            // The computer answered and refused. Which refusal matters: three of
            // these are not version problems and updating would not fix them.
            is TransportResult.Refused -> classify(reached.code, reached.message)

            is TransportResult.Ok -> when (val negotiated = negotiate(reached.value)) {
                is Negotiation.Ready -> ComputerState.Connected(
                    computerId = negotiated.status.computerId,
                    computerName = negotiated.status.computerName,
                    protocolVersion = negotiated.protocolVersion,
                    hostVersion = negotiated.status.hostVersion,
                    adapterVersion = negotiated.status.adapterVersion,
                    available = negotiated.available,
                    unavailable = negotiated.unavailableKnown,
                )

                is Negotiation.Incompatible ->
                    ComputerState.Incompatible(negotiated.kind, negotiated.message)

                is Negotiation.Malformed -> ComputerState.Failed(negotiated.detail)
            }
        }

        state = next
        return next
    }

    private fun classify(code: String, message: String): ComputerState = when (Refusal.fromWire(code)) {
        Refusal.Unauthenticated -> ComputerState.Unauthenticated(message)
        Refusal.DeviceRevoked -> ComputerState.Revoked(message)
        Refusal.ForbiddenScope -> ComputerState.Forbidden(message)
        Refusal.UnsupportedProtocol ->
            ComputerState.Incompatible(Incompatibility.NO_COMMON_VERSION, message)
        Refusal.MissingCapability, Refusal.UnknownOperation ->
            ComputerState.Incompatible(Incompatibility.NO_USABLE_OPERATIONS, message)
        else -> ComputerState.Failed(message)
    }

    /** The sentence to show for a refusal from this computer, if any. */
    fun refusalText(code: String, computerName: String): String =
        refusalMessage(Refusal.fromWire(code), computerName)
}
