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
}
