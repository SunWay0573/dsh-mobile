package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.model.MobileTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The same `sessionId` exists on every computer — they are independent hosts
 * that happen to number sessions the same way. Anything keyed on the session
 * alone therefore lands on the wrong machine once a user owns two, and the bug
 * is invisible until exactly then.
 */
class MobileTargetTest {

    @Test
    fun the_same_session_on_two_computers_is_two_targets() {
        val onMac = MobileTarget("pc-mac", "session-1")
        val onMini = MobileTarget("pc-mini", "session-1")
        assertNotEquals(
            "the same sessionId on two computers must not compare equal",
            onMac,
            onMini,
        )
    }

    @Test
    fun identity_is_the_pair_not_the_session() {
        val a = MobileTarget("pc-1", "s-1")
        assertEquals(a, MobileTarget("pc-1", "s-1"))
        assertNotEquals(a, MobileTarget("pc-1", "s-2"))
        assertNotEquals(a, MobileTarget("pc-2", "s-1"))
    }

    // A blank id is always a bug upstream — an unparsed field, an empty
    // notification. Failing here means it surfaces at the boundary rather than
    // as a request sent to whatever the empty string resolves to.
    @Test
    fun blank_ids_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { MobileTarget("", "s-1") }
        assertThrows(IllegalArgumentException::class.java) { MobileTarget("pc-1", "") }
        assertThrows(IllegalArgumentException::class.java) { MobileTarget("   ", "s-1") }
    }

    // Usable as a map key, which is how drafts, caches and pending approvals are
    // scoped per computer without anyone remembering to add the computer id.
    @Test
    fun it_works_as_a_map_key() {
        val seen = mutableMapOf<MobileTarget, String>()
        seen[MobileTarget("pc-mac", "s-1")] = "from the Mac"
        seen[MobileTarget("pc-mini", "s-1")] = "from the mini"
        assertEquals(2, seen.size)
        assertEquals("from the mini", seen[MobileTarget("pc-mini", "s-1")])
    }
}
