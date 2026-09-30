package io.github.sunway0573.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deep links arrive from another process, which is exactly why the parsing is
 * worth testing: a notification that opens the wrong session, or nothing at all,
 * is a confusing failure with no error message anywhere.
 */
class DeepLinksTest {

    @Test
    fun `parses the canonical form`() {
        assertEquals("abc", DeepLinks.sessionId("dshmobile://session/abc"))
    }

    @Test
    fun `tolerates a trailing slash`() {
        assertEquals("abc", DeepLinks.sessionId("dshmobile://session/abc/"))
    }

    // ntfy and Android both append things; the id must survive them.
    @Test
    fun `ignores a query string and a fragment`() {
        assertEquals("abc", DeepLinks.sessionId("dshmobile://session/abc?utm=ntfy"))
        assertEquals("abc", DeepLinks.sessionId("dshmobile://session/abc#top"))
    }

    @Test
    fun `the scheme match is case insensitive`() {
        // URI schemes are case insensitive by specification, and some handlers
        // normalise them.
        assertEquals("abc", DeepLinks.sessionId("DSHMobile://session/abc"))
    }

    @Test
    fun `accepts a path-only form`() {
        // The same string can be parsed either way depending on how it was
        // built, so both are handled rather than one being assumed.
        assertEquals("abc", DeepLinks.sessionId("dshmobile:///abc"))
    }

    @Test
    fun `decodes a percent-encoded id`() {
        assertEquals("a/b", DeepLinks.sessionId("dshmobile://session/a%2Fb"))
    }

    @Test
    fun `rejects anything that is not one of our links`() {
        assertNull(DeepLinks.sessionId(null))
        assertNull(DeepLinks.sessionId(""))
        assertNull(DeepLinks.sessionId("   "))
        assertNull(DeepLinks.sessionId("https://example.com/session/abc"))
        assertNull(DeepLinks.sessionId("dshmobilex://session/abc"))
    }

    @Test
    fun `rejects a link with no session id`() {
        assertNull(DeepLinks.sessionId("dshmobile://"))
        assertNull(DeepLinks.sessionId("dshmobile://session"))
        assertNull(DeepLinks.sessionId("dshmobile://session/"))
    }

    @Test
    fun `builds a session url from the configured host`() {
        assertEquals(
            "https://machine.tailnet.ts.net/#/session/abc",
            DeepLinks.sessionUrl("machine.tailnet.ts.net", "abc"),
        )
    }

    @Test
    fun `a host with a trailing slash does not produce a doubled one`() {
        assertEquals("https://h/#/session/abc", DeepLinks.sessionUrl("https://h/", "abc"))
    }

    // Without a host there is nowhere to point the WebView. Returning null lets
    // the app say so rather than loading something meaningless.
    @Test
    fun `no host means no url`() {
        assertNull(DeepLinks.sessionUrl("", "abc"))
        assertNull(DeepLinks.sessionUrl("   ", "abc"))
    }

    @Test
    fun `the id is encoded again on the way out`() {
        assertEquals("https://h/#/session/a%2Fb", DeepLinks.sessionUrl("https://h", "a/b"))
    }

    @Test
    fun `round-trips through both directions`() {
        val link = DeepLinks.sessionUrl("https://h", DeepLinks.sessionId("dshmobile://session/a%2Fb")!!)
        assertEquals("https://h/#/session/a%2Fb", link)
    }
}
