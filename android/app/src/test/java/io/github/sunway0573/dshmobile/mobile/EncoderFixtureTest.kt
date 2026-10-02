package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.protocol.MobileProtocol
import io.github.sunway0573.dshmobile.mobile.protocol.Operation
import io.github.sunway0573.dshmobile.mobile.protocol.encodeCommand
import io.github.sunway0573.dshmobile.mobile.protocol.parseStatus
import io.github.sunway0573.dshmobile.mobile.protocol.StatusParseResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes what this build actually encodes, for the other end to consume.
 *
 * ## Why a fixture file and not just assertions
 *
 * The earlier "cross-language contract test" hand-wrote five field names in
 * Kotlin and asserted the encoder contained them. That checks Kotlin against
 * Kotlin: the TypeScript side could add a required field tomorrow and the test
 * would still pass, because nothing on the TS side ever saw the output.
 *
 * So the encoder's real output is written here, and the TypeScript suite reads
 * this exact file and feeds it to the real adapter. If the two ends disagree,
 * one of the two suites fails — which is the only arrangement where a contract
 * test means anything.
 *
 * The file goes to a path both suites agree on:
 * `android/app/build/contract/`. It is build output and not committed.
 */
class EncoderFixtureTest {

    // Gradle runs tests with the module directory as the working directory, so
    // this is android/app/build/contract. The TS suite reads the same path.
    private val outputDir = File("build/contract").absoluteFile

    private fun write(name: String, json: JSONObject) {
        outputDir.mkdirs()
        File(outputDir, name).writeText(json.toString(2))
    }

    @Test
    fun write_the_commands_the_other_end_must_accept() {
        write(
            "session-list.json",
            encodeCommand(
                computerId = "pc-1",
                deviceId = "phone-7",
                commandId = "cmd-session-list",
                operation = Operation.SessionList,
                payload = null,
                protocolVersion = MobileProtocol.VERSION,
            ),
        )

        write(
            "session-page.json",
            encodeCommand(
                computerId = "pc-1",
                deviceId = "phone-7",
                commandId = "cmd-session-page",
                operation = Operation.SessionPage,
                payload = JSONObject().put("sessionId", "s-1").put("fromSeq", 0).put("limit", 50),
                protocolVersion = MobileProtocol.VERSION,
            ),
        )

        write(
            "task-submit.json",
            encodeCommand(
                computerId = "pc-1",
                deviceId = "phone-7",
                commandId = "cmd-task-submit",
                operation = Operation.TaskSubmit,
                payload = JSONObject().put("text", "整理下载目录"),
                protocolVersion = MobileProtocol.VERSION,
            ),
        )

        write(
            "approval-decide-malformed.json",
            encodeCommand(
                computerId = "pc-1",
                deviceId = "phone-7",
                commandId = "cmd-approval",
                operation = Operation.ApprovalDecide,
                payload = JSONObject().put("decision", "yes"),
                protocolVersion = MobileProtocol.VERSION,
            ),
        )

        assertTrue("the fixture directory must be written", outputDir.isDirectory)
    }

    /**
     * Writes a handshake reply in the shape this build produces, so the TS side
     * can check it round-trips through the Kotlin parser.
     *
     * Not the same direction as above: that one is Kotlin → TS, this one is
     * TS-shaped JSON → Kotlin. Both directions matter, because the two ends
     * disagreeing in the other direction is equally invisible.
     */
    @Test
    fun write_the_handshake_the_other_end_must_produce() {
        val reply = JSONObject()
            .put("protocolVersion", MobileProtocol.VERSION)
            .put("supportedProtocols", JSONArray(MobileProtocol.SUPPORTED_VERSIONS.toList()))
            .put("adapterVersion", "1.0.0")
            .put("hostVersion", "0.2.0-rc.2")
            .put("computerId", "pc-1")
            .put("computerName", "我的 Mac")
            .put("capabilities", JSONArray(listOf("computer.status", "session.list", "session.page")))
            .put("grantedScopes", JSONArray(listOf("sessions.read")))

        write("handshake.json", reply)

        // And this build must be able to read it back.
        assertTrue(parseStatus(reply) is StatusParseResult.Ok)
    }
}
