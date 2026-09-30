package io.github.sunway0573.dshmobile

/**
 * Address handling, kept free of Android imports so it is testable on the JVM
 * without a device or an emulator. A unit test that needs Robolectric to check
 * string handling is a unit test nobody runs.
 */
internal object Urls {

    /**
     * Make a user-typed address loadable by a WebView.
     *
     * WebView requires an absolute URL, and nobody types a scheme for a tailnet
     * name or a LAN address.
     *
     * Defaulting to `https` rather than `http` is deliberate: the recommended
     * deployment has a real certificate (tailscale serve issues one for your
     * tailnet name), and silently downgrading to cleartext is not a guess worth
     * making on someone else's behalf.
     *
     * @param raw the address as typed.
     * @return an absolute URL, with a scheme prepended when one was missing.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }
}
