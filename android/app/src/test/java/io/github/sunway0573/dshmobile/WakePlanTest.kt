package io.github.sunway0573.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing a transport is the part of waking that a user gets stuck on, because
 * the wrong choice fails silently: the packet is sent, nothing complains, and
 * the machine simply never wakes.
 */
class WakePlanTest {

    private val mac = "aa:bb:cc:dd:ee:ff"
    private val broadcast = "192.168.1.255"
    private val bridge = "http://127.0.0.1:8787"

    // The only path that works when the phone is away from home, which is the
    // situation the feature exists for. A direct broadcast reaches one subnet.
    @Test
    fun `the bridge wins when both are configured`() {
        val plan = Wake.plan(bridge, "secret", mac, broadcast)
        assertTrue("expected the bridge path, got $plan", plan is WakePlan.ViaBridge)
    }

    @Test
    fun `a direct broadcast is used when no bridge is configured`() {
        val plan = Wake.plan("", "", mac, broadcast)
        assertTrue(plan is WakePlan.ViaBroadcast)
        plan as WakePlan.ViaBroadcast
        assertEquals(mac, plan.mac)
        assertEquals(broadcast, plan.broadcast)
    }

    @Test
    fun `nothing configured produces nothing to attempt`() {
        assertEquals(WakePlan.NotConfigured, Wake.plan("", "", "", ""))
    }

    // A bridge alone is enough: it can hold its own target, so requiring a MAC
    // in the app would be asking for something the bridge does not need.
    @Test
    fun `a bridge without a MAC is still a usable plan`() {
        val plan = Wake.plan(bridge, "", "", "")
        assertTrue(plan is WakePlan.ViaBridge)
        assertNull((plan as WakePlan.ViaBridge).mac)
    }

    @Test
    fun `a MAC without a broadcast cannot be used directly`() {
        // A packet needs somewhere to go. Falling back to NotConfigured is more
        // honest than sending a broadcast to a guessed address.
        assertEquals(WakePlan.NotConfigured, Wake.plan("", "", mac, ""))
    }

    @Test
    fun `whitespace is not configuration`() {
        assertEquals(WakePlan.NotConfigured, Wake.plan("   ", "  ", "  ", "  "))
        assertTrue(Wake.plan("  $bridge  ", "", "", "") is WakePlan.ViaBridge)
    }

    @Test
    fun `a trailing slash does not produce a doubled path`() {
        // People paste both forms. `//wake` works on some servers and not
        // others, which is the kind of bug that appears on one deployment only.
        assertEquals("http://host:8787/wake", Wake.bridgeEndpoint("http://host:8787/"))
        assertEquals("http://host:8787/wake", Wake.bridgeEndpoint("http://host:8787"))
        assertEquals("http://host:8787/wake", Wake.bridgeEndpoint("  http://host:8787//  "))
    }

    @Test
    fun `the plan carries the endpoint, not the base url`() {
        val plan = Wake.plan("http://host:8787/", "", "", "") as WakePlan.ViaBridge
        assertEquals("http://host:8787/wake", plan.url)
    }

    @Test
    fun `a blank token is absent rather than empty`() {
        // Sending `Authorization: Bearer ` is not the same as sending no header,
        // and some servers reject the former.
        val plan = Wake.plan(bridge, "   ", "", "") as WakePlan.ViaBridge
        assertNull(plan.token)
    }

    @Test
    fun `a configured token is carried through`() {
        val plan = Wake.plan(bridge, " s3cret ", "", "") as WakePlan.ViaBridge
        assertEquals("s3cret", plan.token)
    }

    // An absent `mac` means "use your own target". An empty string would be read
    // as a malformed address instead.
    @Test
    fun `the body omits the MAC when there is none`() {
        assertEquals("{}", Wake.bridgeBody(null))
    }

    @Test
    fun `the body carries the MAC when there is one`() {
        assertEquals("""{"mac":"aa:bb:cc:dd:ee:ff"}""", Wake.bridgeBody(mac))
    }
}
