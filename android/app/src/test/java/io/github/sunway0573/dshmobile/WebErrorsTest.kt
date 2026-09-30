package io.github.sunway0573.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure text is the part of this app with actual judgement in it, and the
 * part most likely to be wrong in a way that costs a user an afternoon.
 */
class WebErrorsTest {

    // The 401 case is the one that matters. DSH hands out a one-time sign-in
    // link on every start and there is no other way to authenticate, so a user
    // who has just restarted their host sees a bare 401 and has no idea the fix
    // is a URL that scrolled past in a terminal.
    @Test
    fun `an authentication failure points at the sign-in link`() {
        for (status in listOf(401, 403)) {
            val message = WebErrors.forHttpStatus(status)
            assertNotNull("status $status should explain itself", message)
            assertTrue(
                "status $status should mention the sign-in link, was: $message",
                message!!.contains("sign-in link", ignoreCase = true),
            )
        }
    }

    @Test
    fun `a not-found points at the address`() {
        val message = WebErrors.forHttpStatus(404)
        assertNotNull(message)
        assertTrue(
            "should suggest checking the address, was: $message",
            message!!.contains("address", ignoreCase = true),
        )
    }

    @Test
    fun `a server error says the host is up`() {
        for (status in listOf(500, 502, 503)) {
            val message = WebErrors.forHttpStatus(status)
            assertNotNull(message)
            assertTrue(
                "status $status should say the host answered, was: $message",
                message!!.contains("running", ignoreCase = true),
            )
        }
    }

    // An unclear banner over a page that works is worse than no banner. Anything
    // this function does not have something specific to say about must render
    // silently.
    @Test
    fun `statuses with nothing useful to say produce no banner`() {
        for (status in listOf(200, 204, 301, 302, 304, 400, 418)) {
            assertNull("status $status should not raise a banner", WebErrors.forHttpStatus(status))
        }
    }

    @Test
    fun `a network error keeps the platform wording`() {
        val message = WebErrors.forNetworkError("net::ERR_CONNECTION_REFUSED")
        assertTrue(
            "the platform wording is more specific than anything we could invent",
            message.contains("net::ERR_CONNECTION_REFUSED"),
        )
    }

    @Test
    fun `a blank network error still reads as a sentence`() {
        val message = WebErrors.forNetworkError("")
        assertTrue(message.startsWith("Could not reach the host"))
        assertTrue("should not contain a double space or empty clause", !message.contains(": ."))
    }

    // Both a sleeping machine and an absent one look identical from the phone.
    // The advice names both rather than guessing.
    @Test
    fun `a network error mentions both the machine being asleep and the tunnel`() {
        val message = WebErrors.forNetworkError("timeout")
        assertTrue("should mention waking the machine", message.contains("asleep", ignoreCase = true))
        assertTrue("should mention the tunnel", message.contains("tunnel", ignoreCase = true))
    }
}

class WebStateTest {

    @Test
    fun `failed carries the message it was given`() {
        assertEquals("boom", (WebState.Failed("boom") as WebState.Failed).message)
    }

    @Test
    fun `loading and ready are distinguishable`() {
        assertTrue(WebState.Loading != WebState.Ready)
        assertTrue(WebState.Ready != WebState.Failed("x"))
    }
}
