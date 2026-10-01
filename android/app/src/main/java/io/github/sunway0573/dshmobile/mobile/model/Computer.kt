package io.github.sunway0573.dshmobile.mobile.model

/**
 * Which computer, and which task on it.
 *
 * Never a bare `sessionId`. The same `sessionId` can exist on two computers —
 * they are independent hosts that happen to number their sessions the same way —
 * and a notification, a draft, a pending approval or a cache entry keyed on the
 * session alone would land on the wrong machine. Every one of those is a bug
 * that only appears once someone owns two computers, which is exactly when it is
 * most expensive to find.
 *
 * @param computerId stable identity of the host, generated once when it is
 *   paired. Changing its address, its alias or its network does not change this.
 * @param sessionId the session's id on that host.
 */
internal data class MobileTarget(
    val computerId: String,
    val sessionId: String,
) {
    init {
        require(computerId.isNotBlank()) { "computerId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    /** For logs and diagnostics. Not for equality — use the data class for that. */
    override fun toString(): String = "$computerId/$sessionId"
}

/**
 * Whether this phone may talk to a computer.
 *
 * Deliberately not a boolean. "Not connected right now" and "your authorisation
 * was revoked" look identical on screen and need opposite responses: the first
 * is worth retrying, the second never will be until the user pairs again.
 */
internal enum class AuthState {
    /** Paired and not revoked. Says nothing about whether it is reachable now. */
    AUTHORIZED,

    /** Revoked on the computer. Only re-pairing fixes this. */
    REVOKED,

    /** Paired, but nothing has confirmed the authorisation is still valid. */
    UNKNOWN,
}

/**
 * One computer this phone has paired with.
 *
 * @param computerId stable identity; see [MobileTarget].
 * @param alias user-visible name. Cosmetic: two computers may share one, so it
 *   is never used to identify anything.
 * @param identityFingerprint SHA-256 of the computer's public identity key, from
 *   the pairing QR. Reconnecting must verify the same fingerprint rather than
 *   quietly trusting a new one — otherwise an attacker who can answer on the old
 *   address inherits the pairing.
 * @param endpoints candidate base URLs, most preferred first.
 * @param authState see [AuthState].
 * @param lastSeenEpochMs when this phone last had a confirmed connection, or
 *   null if never.
 */
internal data class ComputerRecord(
    val computerId: String,
    val alias: String,
    val identityFingerprint: String,
    val endpoints: List<String>,
    val authState: AuthState,
    val lastSeenEpochMs: Long?,
) {
    init {
        require(computerId.isNotBlank()) { "computerId must not be blank" }
    }

    /** The endpoint to try first, or null when none is configured. */
    val primaryEndpoint: String? get() = endpoints.firstOrNull()
}
