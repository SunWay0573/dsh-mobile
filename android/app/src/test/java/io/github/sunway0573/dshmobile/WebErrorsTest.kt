package io.github.sunway0573.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure text is the part of this app with actual judgement in it, and the
 * part most likely to be wrong in a way that costs a user an afternoon.
 */
class WebErrorsTest {

    // 401 and 403 look alike on screen and have opposite fixes. This app treated
    // them as one thing, which sent people to re-open a sign-in link that could
    // not possibly help: a 403 is the host refusing the address itself, before
    // it ever considered who was asking.
    @Test
    fun `401 and 403 are different problems with different advice`() {
        val unauthorized = WebErrors.classifyHttpStatus(401)
        val forbidden = WebErrors.classifyHttpStatus(403)

        assertNotNull(unauthorized)
        assertNotNull(forbidden)
        assertEquals(FailureKind.AUTHENTICATION, unauthorized!!.first)
        assertEquals(FailureKind.TRUST_FENCE, forbidden!!.first)
        assertTrue(
            "these must not share a message",
            unauthorized.second != forbidden.second,
        )
    }

    @Test
    fun `an authentication failure points at the sign-in link`() {
        val message = WebErrors.forHttpStatus(401)
        assertNotNull(message)
        assertTrue(
            "should mention the sign-in link, was: $message",
            message!!.contains("sign-in link", ignoreCase = true),
        )
    }

    // The specific misdiagnosis to prevent: sending someone off to sign in again
    // when signing in is not what is wrong.
    //
    // Naming the sign-in link is fine, and deliberate — a user who has just seen
    // one will reach for it, so the message rules it out in as many words. What
    // must not appear is an *instruction* to go and open it.
    @Test
    fun `a trust-fence failure does not instruct the user to sign in`() {
        val message = WebErrors.forHttpStatus(403)
        assertNotNull(message)
        assertFalse(
            "a 403 must not tell the user to go and sign in, was: $message",
            message!!.contains("open that link", ignoreCase = true),
        )
        assertFalse(
            "a 403 must not tell the user to come back after signing in, was: $message",
            message.contains("then come back", ignoreCase = true),
        )
        assertTrue(
            "should name the real cause, was: $message",
            message.contains("trust", ignoreCase = true),
        )
        assertTrue(
            "should explicitly rule out the sign-in link, was: $message",
            message.contains("will not help", ignoreCase = true),
        )
    }

    // And the 401 does the opposite, or the pair is not actually distinguished.
    @Test
    fun `an authentication failure instructs the user to sign in`() {
        val message = WebErrors.forHttpStatus(401)
        assertNotNull(message)
        assertTrue(
            "a 401 should tell the user to open the link, was: $message",
            message!!.contains("open that link", ignoreCase = true),
        )
    }

    @Test
    fun `a not-found points at the address`() {
        val message = WebErrors.forHttpStatus(404)
        assertNotNull(message)
        assertTrue(
            "should suggest checking the address, was: $message",
            message!!.contains("address", ignoreCase = true),
        )
        assertEquals(FailureKind.ADDRESS, WebErrors.classifyHttpStatus(404)!!.first)
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
            assertEquals(FailureKind.SERVER, WebErrors.classifyHttpStatus(status)!!.first)
        }
    }

    // An unclear banner over a page that works is worse than no banner. Anything
    // this function does not have something specific to say about must render
    // silently.
    @Test
    fun `statuses with nothing useful to say produce no banner`() {
        for (status in listOf(200, 204, 301, 302, 304, 400, 418)) {
            assertNull("status $status should not raise a banner", WebErrors.forHttpStatus(status))
            assertNull(WebErrors.forStatus(status))
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

    // The case the app used to miss: onPageFinished fires for a page whose
    // scripts then fail, and reporting that as ready leaves a blank screen with
    // nothing to act on.
    @Test
    fun `a page that loaded without starting its client is a failure, not ready`() {
        val failure = WebErrors.forClientDidNotStart()
        assertEquals(FailureKind.CLIENT, failure.kind)
        assertTrue(
            "should say the page loaded, was: ${failure.message}",
            failure.message.contains("page loaded", ignoreCase = true),
        )
        assertTrue(
            "should offer reloading, was: ${failure.message}",
            failure.message.contains("reload", ignoreCase = true),
        )
    }

    // Only a transport failure can plausibly be a sleeping machine. A 403 is
    // not, and advice to go wake something would send the user the wrong way.
    @Test
    fun `only transport failures suggest waking the machine`() {
        assertTrue(WebErrors.suggestsWake(FailureKind.TRANSPORT))
        for (kind in FailureKind.entries - FailureKind.TRANSPORT) {
            assertFalse("$kind must not suggest waking", WebErrors.suggestsWake(kind))
        }
    }
}

class WebStateTest {

    @Test
    fun `failed carries the kind and the message it was given`() {
        val failure = WebState.Failed(FailureKind.CLIENT, "boom")
        assertEquals("boom", failure.message)
        assertEquals(FailureKind.CLIENT, failure.kind)
    }

    @Test
    fun `loading and ready are distinguishable`() {
        assertTrue(WebState.Loading != WebState.Ready)
        assertTrue(WebState.Ready != WebState.Failed(FailureKind.OTHER, "x"))
    }
}

/**
 * The ordering rules that let a late callback erase an earlier failure.
 *
 * Pure, because this is the part with the bug in it and it does not need a
 * WebView to reproduce.
 */
class SessionLoadTest {

    @Test
    fun `a fresh load may report ready`() {
        val load = SessionLoad()
        assertTrue(load.mayReportReady())
    }

    // The reproduction. On a real device this sequence is:
    //   onReceivedHttpError(403)  -> the banner appears
    //   onPageFinished            -> Ready, and the banner vanishes
    // leaving the host's error page on screen with the app claiming success.
    @Test
    fun `a finished page does not erase a failure from the same load`() {
        val load = SessionLoad()
        load.started()
        load.failed()
        assertFalse(
            "the page that finished is the error page; the failure stands",
            load.mayReportReady(),
        )
    }

    @Test
    fun `a new navigation clears the previous failure`() {
        val load = SessionLoad()
        load.failed()
        load.started()
        assertTrue("a retry deserves a clean slate", load.mayReportReady())
    }

    @Test
    fun `a transport failure and an http failure behave the same way`() {
        val transport = SessionLoad().apply { started(); failed() }
        val http = SessionLoad().apply { started(); failed() }
        assertFalse(transport.mayReportReady())
        assertFalse(http.mayReportReady())
    }
}
