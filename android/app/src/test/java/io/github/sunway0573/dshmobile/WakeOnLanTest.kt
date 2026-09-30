package io.github.sunway0573.dshmobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packet layout is fixed by the Wake-on-LAN specification: six `0xFF` bytes
 * followed by the target MAC repeated sixteen times, 102 bytes total.
 *
 * This is worth testing byte for byte rather than by shape, because getting it
 * wrong fails completely silently — the datagram is sent, nothing complains,
 * and the machine simply never wakes. There is no error to read anywhere.
 */
class WakeOnLanTest {

    private val mac = "aa:bb:cc:dd:ee:ff"
    private val macBytes = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
        0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
    )

    @Test
    fun `packet is 102 bytes`() {
        assertEquals(102, WakeOnLan.buildPacket(mac).size)
        assertEquals(102, WakeOnLan.MAGIC_PACKET_LENGTH)
    }

    @Test
    fun `packet starts with six 0xFF bytes`() {
        val packet = WakeOnLan.buildPacket(mac)
        val header = packet.copyOfRange(0, 6)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, header)
    }

    @Test
    fun `packet contains the MAC repeated sixteen times`() {
        val packet = WakeOnLan.buildPacket(mac)
        val body = packet.copyOfRange(6, 102)
        val expected = ByteArray(96) { index -> macBytes[index % 6] }
        assertArrayEquals(expected, body)
    }

    @Test
    fun `packet matches a hand-built reference`() {
        val reference = ByteArray(102).also { packet ->
            for (index in 0 until 6) packet[index] = 0xFF.toByte()
            for (repeat in 0 until 16) {
                System.arraycopy(macBytes, 0, packet, 6 + repeat * 6, 6)
            }
        }
        assertArrayEquals(reference, WakeOnLan.buildPacket(mac))
    }

    @Test
    fun `all accepted MAC formats produce the same packet`() {
        val fromColons = WakeOnLan.buildPacket("aa:bb:cc:dd:ee:ff")
        val fromHyphens = WakeOnLan.buildPacket("aa-bb-cc-dd-ee-ff")
        val fromBare = WakeOnLan.buildPacket("aabbccddeeff")
        val fromUpper = WakeOnLan.buildPacket("AA:BB:CC:DD:EE:FF")

        assertArrayEquals(fromColons, fromHyphens)
        assertArrayEquals(fromColons, fromBare)
        assertArrayEquals(fromColons, fromUpper)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertArrayEquals(macBytes, WakeOnLan.parseMac("  aa:bb:cc:dd:ee:ff  "))
    }

    @Test
    fun `parseMac returns the address most significant byte first`() {
        assertArrayEquals(macBytes, WakeOnLan.parseMac(mac))
    }

    @Test
    fun `a leading zero byte is preserved`() {
        val parsed = WakeOnLan.parseMac("00:11:22:33:44:55")
        assertArrayEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55),
            parsed,
        )
    }

    @Test
    fun `rejects a MAC with too few digits`() {
        assertThrows { WakeOnLan.buildPacket("aa:bb:cc") }
    }

    @Test
    fun `rejects a MAC with too many digits`() {
        assertThrows { WakeOnLan.buildPacket("aa:bb:cc:dd:ee:ff:00") }
    }

    @Test
    fun `rejects an empty MAC`() {
        assertThrows { WakeOnLan.buildPacket("") }
    }

    @Test
    fun `rejects non-hex characters`() {
        assertThrows { WakeOnLan.buildPacket("zz:bb:cc:dd:ee:ff") }
    }

    @Test
    fun `rejects a wire-format address of the wrong length`() {
        assertThrows { WakeOnLan.buildPacket(ByteArray(4)) }
        assertThrows { WakeOnLan.buildPacket(ByteArray(8)) }
    }

    @Test
    fun `accepts a wire-format address of exactly six bytes`() {
        assertArrayEquals(
            WakeOnLan.buildPacket(mac),
            WakeOnLan.buildPacket(macBytes),
        )
    }

    @Test
    fun `a malformed address is rejected before anything is sent`() {
        // The point is not the exception type but that buildPacket is pure:
        // a bad address cannot reach the network.
        val error = try {
            WakeOnLan.buildPacket("not-a-mac")
            null
        } catch (caught: IllegalArgumentException) {
            caught
        }
        assertTrue("expected the address to be rejected", error != null)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
