package io.github.sunway0573.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlsTest {

    @Test
    fun `prepends https when no scheme is given`() {
        // Defaulting to https rather than http is the security-relevant choice:
        // the recommended deployment has a real certificate, and guessing
        // cleartext on the user's behalf would be the wrong guess.
        assertEquals("https://machine.tailnet.ts.net", Urls.normalize("machine.tailnet.ts.net"))
    }

    @Test
    fun `preserves an explicitly given scheme`() {
        assertEquals("http://192.168.1.10:3080", Urls.normalize("http://192.168.1.10:3080"))
    }

    @Test
    fun `preserves an explicit https scheme`() {
        assertEquals("https://host/#/session/abc", Urls.normalize("https://host/#/session/abc"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("https://host", Urls.normalize("  host  "))
    }

    @Test
    fun `leaves an empty address empty rather than inventing a host`() {
        assertEquals("", Urls.normalize(""))
        assertEquals("", Urls.normalize("   "))
    }

    @Test
    fun `does not double up on a scheme-like substring`() {
        assertEquals("https://host/path?x=a://b", Urls.normalize("https://host/path?x=a://b"))
    }
}
