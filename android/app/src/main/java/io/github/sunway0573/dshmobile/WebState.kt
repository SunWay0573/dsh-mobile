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

    /** The host answered and the client inside it is running. */
    data object Ready : WebState

    /** Something went wrong, with text written to be actionable. */
    data class Failed(val kind: FailureKind, val message: String) : WebState
}

/**
 * Which kind of failure this is.
 *
 * The distinction is not cosmetic. `401` and `403` look alike on screen and have
 * opposite fixes: one is "you have not signed in on this device", the other is
 * "the host does not trust this address at all", and no amount of signing in
 * resolves the second. Treating them as one thing — which this app did — sends
 * people to re-open a sign-in link that cannot possibly help.
 */
internal enum class FailureKind {
    /** 401: reachable, but this device holds no valid credential. */
    AUTHENTICATION,

    /**
     * 403: rejected before authentication was even considered.
     *
     * DSH rejects a request whose `Host` is neither loopback nor listed in its
     * trusted hosts, and whose `Origin` does not match the `Host` authority.
     * A tunnel brings a public hostname, so it has to be trusted explicitly.
     */
    TRUST_FENCE,

    /** No route, refused, or timed out. */
    TRANSPORT,

    /** The page loaded, but the client inside it did not start. */
    CLIENT,

    /** Answered, but not the interface we expected. */
    ADDRESS,

    /** The host is up and unhappy. */
    SERVER,

    /** Something else, described in the platform's own words. */
    OTHER,
}

/**
 * Turn failures into things a user can act on.
 *
 * Every message here has to answer "what do I do next", not "what went wrong".
 */
internal object WebErrors {

    /**
     * Classify an HTTP failure on the main frame.
     *
     * @param status the status code the host returned.
     * @returns the kind and advice, or null when the page should just render —
     *   an unclear banner on top of a page that works is worse than nothing.
     */
    fun classifyHttpStatus(status: Int): Pair<FailureKind, String>? = when (status) {
        // Reachable and speaking our protocol, but this device is not signed in.
        401 -> FailureKind.AUTHENTICATION to
            "The host is reachable, but this phone is not signed in. DSH prints a " +
                "one-time sign-in link every time it starts — open that link on this " +
                "phone, then come back here."

        // Rejected before authentication was considered. A different problem with
        // a different fix, and the one users are most likely to misdiagnose.
        403 -> FailureKind.TRUST_FENCE to
            "The host refused this address before checking who you are. That means " +
                "the address is not one it trusts — through a tunnel, the hostname " +
                "has to be added to the host's trusted hosts first. Opening the " +
                "sign-in link will not help with this."

        404 -> FailureKind.ADDRESS to
            "Something answered, but not the DeepSeek Harness interface. Check the " +
                "host address."

        in 500..599 -> FailureKind.SERVER to
            "The host is running but returned an error ($status). Its logs will say why."

        else -> null
    }

    /** Advice for an HTTP failure, for callers that only need the text. */
    fun forHttpStatus(status: Int): String? = classifyHttpStatus(status)?.second

    /** Build a failure from an HTTP status. */
    fun forStatus(status: Int): WebState.Failed? =
        classifyHttpStatus(status)?.let { (kind, message) -> WebState.Failed(kind, message) }

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

    /** Build a transport failure. */
    fun forTransport(description: String): WebState.Failed =
        WebState.Failed(FailureKind.TRANSPORT, forNetworkError(description))

    /**
     * The page arrived but the application inside it never started.
     *
     * This is the case the app used to miss entirely: `onPageFinished` fires for
     * a page whose scripts then fail, and reporting that as ready shows the user
     * a blank screen with nothing to act on.
     */
    fun forClientDidNotStart(): WebState.Failed = WebState.Failed(
        FailureKind.CLIENT,
        "The page loaded, but the DeepSeek Harness client inside it did not start. " +
            "Reloading often fixes it. If it keeps happening, the host's browser " +
            "console will say why.",
    )

    /**
     * Whether the last failure was the host being asleep rather than absent.
     *
     * Both look identical from the phone — a refused or timed-out connection —
     * so this cannot actually tell them apart, and does not pretend to. It
     * exists to point at the more likely cause given how the project is meant
     * to be deployed, and the banner says both.
     */
    fun suggestsWake(kind: FailureKind): Boolean = kind == FailureKind.TRANSPORT
}

/**
 * Tracks one main-frame load, so a late callback cannot erase an earlier
 * failure.
 *
 * The bug this exists for: `onPageFinished` fires after `onReceivedHttpError`,
 * unconditionally. A 403 therefore set the failure banner and then immediately
 * cleared it, leaving a user staring at the host's error page with the app
 * insisting everything was fine. The two callbacks describe different things —
 * "a response was received" and "the document finished" — and neither alone
 * means the session is usable.
 *
 * Pure on purpose: the ordering rules are the part worth testing, and they can
 * be tested without a WebView.
 */
internal class SessionLoad {

    private var failed = false

    /** A new main-frame navigation. Clears the previous outcome. */
    fun started() {
        failed = false
    }

    /** The main frame produced an HTTP or transport error. */
    fun failed() {
        failed = true
    }

    /**
     * Whether a finished page load may be reported as ready.
     *
     * @returns false when this load already failed — the failure stands, because
     *   the page that finished is the error page.
     */
    fun mayReportReady(): Boolean = !failed
}
