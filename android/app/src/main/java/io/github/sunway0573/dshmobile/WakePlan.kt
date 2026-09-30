package io.github.sunway0573.dshmobile

/**
 * How to wake the machine, and what to send when the answer is "over the
 * network".
 *
 * Kept free of Android imports so the decision is unit testable. The decision is
 * the part worth testing: the transport is a one-line HTTP call, but *which*
 * transport to use is where a user gets stuck, because the wrong one fails
 * silently.
 */
internal sealed interface WakePlan {

    /**
     * Ask a `wol-bridge` on the LAN to send the packet.
     *
     * This is the only path that works when the phone is away from home, which
     * is the whole point of the feature.
     */
    data class ViaBridge(val url: String, val token: String?, val mac: String?) : WakePlan

    /**
     * Send the packet directly.
     *
     * Works only when the phone is on the same subnet as the machine, because
     * Wake-on-LAN packets are broadcast and do not cross routers.
     */
    data class ViaBroadcast(val mac: String, val broadcast: String) : WakePlan

    /** Not enough is configured to try anything. */
    data object NotConfigured : WakePlan
}

internal object Wake {

    /**
     * Choose a transport.
     *
     * **The bridge wins when both are configured.** A direct broadcast works
     * only on the same subnet, and a phone on the same subnet as the machine is
     * by definition not the situation this feature exists for. Preferring the
     * one that works from anywhere is the right default, and the settings screen
     * says so.
     *
     * @param bridgeUrl base URL of a `wol-bridge`, blank when unset.
     * @param token shared secret for that bridge, blank when unset.
     * @param mac target MAC for a direct broadcast.
     * @param broadcast subnet broadcast address for a direct broadcast.
     * @returns what to attempt, and with what.
     */
    fun plan(
        bridgeUrl: String,
        token: String,
        mac: String,
        broadcast: String,
    ): WakePlan {
        val trimmedBridge = bridgeUrl.trim()
        if (trimmedBridge.isNotEmpty()) {
            return WakePlan.ViaBridge(
                url = bridgeEndpoint(trimmedBridge),
                token = token.trim().ifEmpty { null },
                mac = mac.trim().ifEmpty { null },
            )
        }
        val trimmedMac = mac.trim()
        val trimmedBroadcast = broadcast.trim()
        if (trimmedMac.isNotEmpty() && trimmedBroadcast.isNotEmpty()) {
            return WakePlan.ViaBroadcast(trimmedMac, trimmedBroadcast)
        }
        return WakePlan.NotConfigured
    }

    /**
     * Append the endpoint path to a configured base URL.
     *
     * Tolerates a trailing slash, because people paste both. Getting this wrong
     * produces `//wake`, which some servers accept and some do not — the kind of
     * bug that works on one deployment and not another.
     */
    fun bridgeEndpoint(baseUrl: String): String {
        // trimEnd rather than removeSuffix: one stray slash survives the latter
        // and produces `//wake`, and a user pasting a URL with a doubled slash
        // is not a hypothetical.
        val trimmed = baseUrl.trim().trimEnd('/')
        return "$trimmed/wake"
    }

    /**
     * The JSON body for a bridge request.
     *
     * The MAC is included only when the bridge was not configured with one; the
     * bridge treats an absent `mac` as "use your own target", so sending an
     * empty string would be read as a malformed address rather than as "unset".
     */
    fun bridgeBody(mac: String?): String =
        if (mac == null) "{}" else """{"mac":"$mac"}"""
}
