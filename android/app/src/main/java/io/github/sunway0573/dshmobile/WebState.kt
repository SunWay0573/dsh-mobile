package io.github.sunway0573.dshmobile

/**
 * What the session WebView is currently showing.
 *
 * Kept as plain data with no Android imports so the classification below is
 * unit testable — it is the part with actual judgement in it, and the part most
 * likely to be wrong in a way that wastes a user's afternoon.
 */
internal sealed interface WebState {

    /** Nothing rendered yet. */
    data object Loading : WebState

    /** The host answered and the page loaded. */
    data object Ready : WebState

    /** Something went wrong, with text written to be actionable. */
    data class Failed(val message: String) : WebState
}

/**
 * Turn failures into things a user can act on.
 *
 * The 401 case is the one that matters. DSH hands out a one-time sign-in link on
 * every start, the phone exchanges it for a 30-day cookie, and there is no way
 * to authenticate without it. A user who has just rebuilt their host, or
 * restarted it, will hit a bare "401 Unauthorized" page and have no idea that
 * the fix is a URL they saw scroll past in a terminal.
 */
internal object WebErrors {

    /**
     * Explain an HTTP failure on the main frame.
     *
     * @param status the status code the host returned.
     * @returns advice worth showing, or null when the page should just render —
     *   an unclear banner on top of a page that works is worse than nothing.
     */
    fun forHttpStatus(status: Int): String? = when (status) {
        401, 403 ->
            "The host refused this device. DSH prints a one-time sign-in link " +
                "every time it starts — open that link on this phone, then come back here."

        404 ->
            "Something answered, but not the DSH interface. Check the host address."

        in 500..599 ->
            "The host is running but returned an error ($status). Its logs will say why."

        else -> null
    }

    /**
     * Explain a transport failure.
     *
     * @param description the platform's own wording, kept because it is more
     *   specific than anything this function could invent.
     */
    fun forNetworkError(description: String): String {
        val reason = description.ifBlank { "no route to the host" }
        return "Could not reach the host: $reason. " +
            "If the machine is asleep, wake it; if it is awake, check the tunnel."
    }

    /**
     * Whether the last failure was the host being asleep rather than absent.
     *
     * Both look identical from the phone — a refused or timed-out connection —
     * so this cannot actually tell them apart, and does not pretend to. It
     * exists to point at the more likely cause given how the project is meant to
     * be deployed, and the banner says both.
     */
    fun suggestsWake(retryable: Boolean): Boolean = retryable
}
