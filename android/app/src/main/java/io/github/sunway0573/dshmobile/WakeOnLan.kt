package io.github.sunway0573.dshmobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Wake-on-LAN, sent from the phone.
 *
 * A sleeping machine cannot send its own wake packet, and magic packets are
 * broadcast so they do not cross routers. When the phone is on the same network
 * as the machine this works directly; otherwise it needs a
 * [wol-bridge](https://github.com/SunWay0573/dsh-mobile/tree/main/wol-bridge)
 * on the LAN to send it instead.
 *
 * The packet layout is fixed by the specification: six `0xFF` bytes followed by
 * the target MAC repeated sixteen times, 102 bytes in total. Getting it wrong
 * fails silently — the machine simply never wakes — so [buildPacket] is a pure
 * function and is unit tested byte for byte.
 */
object WakeOnLan {

    /** Length of a well-formed magic packet: 6 + 6×16. */
    const val MAGIC_PACKET_LENGTH = 102

    /** The conventional discard port for Wake-on-LAN. */
    const val DEFAULT_PORT = 9

    private const val MAC_BYTES = 6
    private const val REPEATS = 16

    /**
     * Parse a MAC address into six bytes.
     *
     * Accepts the three forms people actually type: `aa:bb:cc:dd:ee:ff`,
     * `aa-bb-cc-dd-ee-ff`, and `aabbccddeeff`, in either case.
     *
     * @param mac the address to parse.
     * @return six bytes, most significant first.
     * @throws IllegalArgumentException if the input is not a MAC address.
     */
    fun parseMac(mac: String): ByteArray {
        val hex = mac.trim()
            .replace(":", "")
            .replace("-", "")
            .replace(".", "")
            .replace(" ", "")

        require(hex.length == MAC_BYTES * 2) {
            "a MAC address needs ${MAC_BYTES * 2} hex digits, got ${hex.length}"
        }
        require(hex.all { it.isAsciiHexDigit() }) {
            "a MAC address may contain only hex digits, got \"$mac\""
        }
        return ByteArray(MAC_BYTES) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Build the magic packet for a MAC address.
     *
     * @param mac the target address, in any accepted form.
     * @return the 102-byte packet.
     * @throws IllegalArgumentException if the address is malformed.
     */
    fun buildPacket(mac: String): ByteArray = buildPacket(parseMac(mac))

    /**
     * Build the magic packet for an already-parsed address.
     *
     * @param address six bytes, most significant first.
     * @return the 102-byte packet.
     */
    fun buildPacket(address: ByteArray): ByteArray {
        require(address.size == MAC_BYTES) {
            "a MAC address is $MAC_BYTES bytes, got ${address.size}"
        }
        val packet = ByteArray(MAGIC_PACKET_LENGTH)
        for (index in 0 until MAC_BYTES) packet[index] = 0xFF.toByte()
        for (repeat in 0 until REPEATS) {
            System.arraycopy(address, 0, packet, MAC_BYTES + repeat * MAC_BYTES, MAC_BYTES)
        }
        return packet
    }

    /**
     * Send a magic packet.
     *
     * Runs on the IO dispatcher; callers do not need to move off the main
     * thread themselves.
     *
     * @param mac the target address, in any accepted form.
     * @param broadcast the broadcast address of the target's subnet, e.g.
     *   `192.168.1.255`. A directed broadcast is required: sending to the
     *   machine's unicast address does not wake it.
     * @param port the target port; [DEFAULT_PORT] is conventional.
     * @throws IllegalArgumentException if the address or broadcast is malformed.
     * @throws java.io.IOException if the datagram cannot be sent.
     */
    suspend fun wake(
        mac: String,
        broadcast: String,
        port: Int = DEFAULT_PORT,
    ) = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "port must be between 1 and 65535, got $port" }
        val packet = buildPacket(mac)
        // A broadcast address must be given as one; InetAddress.getByName on a
        // unicast address would produce a packet the target's NIC ignores.
        val target = InetAddress.getByName(broadcast)
        DatagramSocket().use { socket ->
            // Without SO_BROADCAST the OS refuses to send to a broadcast
            // address, and the failure is silent from the caller's point of view.
            socket.broadcast = true
            socket.send(DatagramPacket(packet, packet.size, target, port))
        }
    }

    /**
     * Carry out a wake plan and describe what happened.
     *
     * Never throws: the result is a sentence shown to the user, and a failed
     * wake attempt is a normal outcome rather than an error. It also never
     * claims the machine is awake — sending a packet and a machine resuming are
     * different events, and saying otherwise sends people off to debug a
     * working setup.
     *
     * @param plan what [Wake.plan] chose.
     * @param bridgeTimeoutMs how long to wait on a bridge before giving up.
     * @returns a sentence for the user.
     */
    internal suspend fun attempt(plan: WakePlan, bridgeTimeoutMs: Int = 10_000): String =
        withContext(Dispatchers.IO) {
            when (plan) {
                is WakePlan.NotConfigured ->
                    "Nothing is configured to wake. Set a bridge address, or a MAC and a " +
                        "broadcast address."

                is WakePlan.ViaBroadcast ->
                    try {
                        wake(plan.mac, plan.broadcast)
                        "Wake packet sent on the local network. Give it up to a minute."
                    } catch (error: IllegalArgumentException) {
                        "Check the address: ${error.message}"
                    }

                is WakePlan.ViaBridge -> attemptBridge(plan, bridgeTimeoutMs)
            }
        }

    /**
     * POST to a wol-bridge.
     *
     * `HttpURLConnection` rather than `java.net.http.HttpClient`: the latter is a
     * JDK 11 API that Android does not ship, so it would compile here and fail
     * on the device.
     */
    private fun attemptBridge(plan: WakePlan.ViaBridge, timeoutMs: Int): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(plan.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("content-type", "application/json")
                plan.token?.let { setRequestProperty("authorization", "Bearer $it") }
            }
            val body = Wake.bridgeBody(plan.mac)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            when (val code = connection.responseCode) {
                in 200..299 ->
                    "Wake packet requested from the bridge. The machine usually answers " +
                        "within a minute; a full resume from sleep can take longer."

                401, 403 ->
                    "The bridge refused the request ($code). Check the shared secret."

                else ->
                    "The bridge answered $code. Its logs will say why."
            }
        } catch (error: IOException) {
            "Could not reach the bridge: ${error.message ?: error::class.java.simpleName}"
        } catch (error: Exception) {
            "Could not reach the bridge: ${error.message ?: error::class.java.simpleName}"
        } finally {
            connection?.disconnect()
        }
    }
}

private fun Char.isAsciiHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
