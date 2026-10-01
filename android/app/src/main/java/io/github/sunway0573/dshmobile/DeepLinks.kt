package io.github.sunway0573.dshmobile

/**
 * Notification deep links.
 *
 * A notification's tap target cannot be the host's own URL. That link opens a
 * **browser**, and the sign-in cookie lives in this app's WebView — so following
 * it lands on an unauthenticated page. The link is correct and the destination
 * is useless.
 *
 * So the tap target is `dshmobile://session/<id>`, which this app handles, and
 * the app supplies the host address from its own settings because it is the only
 * party that knows it.
 *
 * Kept free of Android imports so the parsing is unit testable. Parsing is worth
 * testing: a notification that opens the wrong session, or nothing, is a
 * confusing failure and the input comes from another process.
 */
internal object DeepLinks {

    /** The scheme registered in the manifest. Must match `mobile-bridge`'s config. */
    const val SCHEME = "dshmobile"

    /**
     * Extract the session id from a deep link.
     *
     * Accepts `dshmobile://session/<id>`, with or without a trailing slash or
     * query, and with the authority written as `session` or as an empty
     * authority and a `session/` path — the two forms a URI parser can produce
     * for the same string depending on how it is built.
     *
     * @param uri the incoming URI, as a string; null when there was none.
     * @returns the session id, or null when this is not a session deep link.
     */
    fun sessionId(uri: String?): String? {
        if (uri == null) return null
        val trimmed = uri.trim()
        if (!trimmed.startsWith("$SCHEME://", ignoreCase = true)) return null

        val rest = trimmed.substring(SCHEME.length + 3)
        // Drop any query or fragment before looking at the path.
        val pathOnly = rest.substringBefore('?').substringBefore('#')

        val segments = pathOnly.split('/').filter { it.isNotEmpty() }

        // The multi-computer format is not this one, and reading it as this one
        // is worse than not reading it at all: `computer/<id>/session/<sid>`
        // would yield the literal string "computer" as a session id, and the app
        // would then try to open a session by that name on whichever computer it
        // had. Caught by a test rather than by a user, which is the point of
        // having both formats covered in the same file.
        if (segments.firstOrNull()?.equals("computer", ignoreCase = true) == true) return null

        val afterSession = when {
            // dshmobile://session/<id>
            segments.firstOrNull()?.equals("session", ignoreCase = true) == true -> segments.drop(1)
            // dshmobile:///<id> or dshmobile://<id>
            else -> segments
        }

        val id = afterSession.firstOrNull()?.trim()
        if (id.isNullOrEmpty()) return null
        // The id arrives from another process, so it is decoded here rather than
        // assumed to be plain.
        return runCatching { java.net.URLDecoder.decode(id, "UTF-8") }.getOrDefault(id)
    }

    /**
     * Build the URL the WebView should load for a session.
     *
     * @param hostUrl the configured host address; blank when unset.
     * @param sessionId the session from the deep link.
     * @returns the session URL, or null when there is no host to point at yet.
     */
    fun sessionUrl(hostUrl: String, sessionId: String): String? {
        val base = hostUrl.trim()
        if (base.isEmpty()) return null
        val normalised = Urls.normalize(base).trimEnd('/')
        return "$normalised/#/session/${java.net.URLEncoder.encode(sessionId, "UTF-8")}"
    }

    /**
     * Parse the multi-computer deep link: `dshmobile://computer/<id>/session/<sid>`.
     *
     * ## Why there is no fallback
     *
     * When the computer id is not recognised, this returns null and the caller
     * must send the user to pairing. The tempting alternative — "we only have one
     * computer, so it must be that one" — is exactly the bug this format exists
     * to prevent: a notification from a computer that has since been removed, or
     * from one paired on a different phone, would open a session on whichever
     * machine happens to be first in the list. On a single-computer setup the two
     * behaviours are indistinguishable, which is why the mistake survives testing
     * and only shows up after someone pairs a second machine.
     *
     * The single-host form (`dshmobile://session/<id>`) is still accepted by
     * [sessionId] for links already sitting in notification trays, and is
     * resolved to the only computer at the call site — not here, where the
     * computer list is not available.
     *
     * @param uri the incoming URI; null when there was none.
     * @returns the target, or null when this is not a multi-computer link or the
     *   id is missing. The caller decides whether the id is known.
     */
    fun computerTarget(uri: String?): MobileTargetParts? {
        if (uri == null) return null
        val trimmed = uri.trim()
        if (!trimmed.startsWith("$SCHEME://", ignoreCase = true)) return null

        val rest = trimmed.substring(SCHEME.length + 3)
        val path = rest.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }

        // computer/<id>/session/<sid>
        if (segments.size < 4) return null
        if (!segments[0].equals("computer", ignoreCase = true)) return null
        if (!segments[2].equals("session", ignoreCase = true)) return null

        val computerId = decode(segments[1])
        val sessionId = decode(segments[3])
        if (computerId.isEmpty() || sessionId.isEmpty()) return null
        return MobileTargetParts(computerId, sessionId)
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/**
 * The two ids from a multi-computer deep link, before anyone has checked that
 * the computer is one this phone knows. Kept separate from `MobileTarget`, which
 * refuses to exist for an unpaired computer.
 */
internal data class MobileTargetParts(val computerId: String, val sessionId: String)
