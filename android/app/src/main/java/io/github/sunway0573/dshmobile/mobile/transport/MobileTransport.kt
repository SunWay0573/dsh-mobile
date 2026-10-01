package io.github.sunway0573.dshmobile.mobile.transport

import io.github.sunway0573.dshmobile.mobile.protocol.ComputerStatus
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import org.json.JSONObject

/**
 * How the phone reaches a computer.
 *
 * The repository above this knows nothing about HTTP, WebSocket, tunnels or
 * pairing — only that it can ask a question and get an answer or a reason. That
 * separation is what lets the same repository be driven by a fixture in a test,
 * a stub on an emulator, and a real socket later, with no branch in the code
 * that decides what to show.
 */
internal interface MobileTransport {

    /**
     * Ask a computer what it is and what this phone may do.
     *
     * @param deviceId which paired device is asking. The computer reports the
     *   scopes belonging to this device, and checks them again on every later
     *   request — so a revocation takes effect without the phone being told.
     */
    suspend fun status(deviceId: String): TransportResult<ComputerStatus>

    /** Send a command and get its outcome. */
    suspend fun command(
        commandId: String,
        operation: Operation,
        payload: JSONObject?,
    ): TransportResult<CommandReply>

    /** A human-readable address, for the diagnostics screen. */
    val endpoint: String
}

/**
 * A transport reply.
 *
 * Failure is a value, not an exception, because the difference between "the
 * computer said no" and "we never reached the computer" is exactly what the UI
 * has to show differently — and an exception loses that distinction the moment
 * somebody catches it in the wrong place.
 */
internal sealed interface TransportResult<out T> {
    data class Ok<T>(val value: T) : TransportResult<T>

    /** The computer answered and refused. */
    data class Refused(
        val code: String,
        val message: String,
    ) : TransportResult<Nothing>

    /** No answer: unreachable, timed out, or not paired. */
    data class Unreachable(val detail: String) : TransportResult<Nothing>
}

/** What the computer answered to a command. */
internal data class CommandReply(
    val status: String,
    val commandId: String,
    val result: JSONObject?,
    val code: String?,
    val message: String?,
)

/**
 * A transport backed by canned replies.
 *
 * Exists so the interface between negotiation and the screen can be exercised
 * without a computer, and so the emulator can be shown a real incompatible
 * state rather than only the happy path. It is **not** a fallback: nothing
 * constructs one unless a debug build asks for it, and the app says on screen
 * that the data is a fixture.
 */
internal class FixtureTransport(
    private val statusReply: TransportResult<ComputerStatus>,
    override val endpoint: String = "fixture://local",
) : MobileTransport {

    /** Device ids this fixture accepts, so a test can exercise "not paired". */
    var knownDevices: Set<String> = setOf("fixture-device")

    override suspend fun status(deviceId: String): TransportResult<ComputerStatus> = statusReply

    override suspend fun command(
        commandId: String,
        operation: Operation,
        payload: JSONObject?,
    ): TransportResult<CommandReply> = TransportResult.Unreachable(
        "这是个固定装置，不会真的发送命令。",
    )
}
