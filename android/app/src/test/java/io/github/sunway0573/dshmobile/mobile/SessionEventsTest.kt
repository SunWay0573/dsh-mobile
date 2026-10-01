package io.github.sunway0573.dshmobile.mobile

import io.github.sunway0573.dshmobile.mobile.protocol.RebuildResult
import io.github.sunway0573.dshmobile.mobile.protocol.SessionEvent
import io.github.sunway0573.dshmobile.mobile.protocol.rebuildSession
import io.github.sunway0573.dshmobile.mobile.protocol.rebuildSessionPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun event(type: String, sequence: Int, vararg extra: Pair<String, Any>): JSONObject =
    JSONObject().put("type", type).put("sequence", sequence).apply {
        for ((key, value) in extra) put(key, value)
    }

private fun page(vararg events: JSONObject): JSONObject =
    JSONObject().put("events", JSONArray(events.toList()))

/**
 * Rebuilding a session from events.
 *
 * The rule under test: an event this build cannot understand must stop the
 * rebuild unless the computer explicitly marked it ignorable. A dropped event
 * produces a conversation that renders, scrolls, and is wrong — with nothing on
 * screen to say anything is missing, which is worse than an empty screen because
 * the user has no reason to doubt it.
 */
class SessionEventsTest {

    @Test
    fun known_events_rebuild() {
        val result = rebuildSession(
            listOf(
                event("message", 1, "fromUser" to true, "text" to "你好"),
                event("tool_run", 2, "command" to "ls", "output" to "a.txt"),
                event("status", 3, "status" to "running"),
            ),
        )
        assertTrue(result is RebuildResult.Ok)
        assertEquals(3, (result as RebuildResult.Ok).events.size)
    }

    @Test
    fun events_come_back_in_sequence_order() {
        val result = rebuildSession(
            listOf(
                event("message", 30, "text" to "third"),
                event("message", 10, "text" to "first"),
                event("message", 20, "text" to "second"),
            ),
        ) as RebuildResult.Ok
        assertEquals(
            listOf("first", "second", "third"),
            result.events.map { (it as SessionEvent.Message).text },
        )
    }

    /**
     * The core rule. An unrecognised event with no `ignorable` mark stops the
     * whole rebuild — a partial conversation shown as if complete is the failure
     * this guards against.
     */
    @Test
    fun an_unknown_required_event_refuses_the_whole_rebuild() {
        val result = rebuildSession(
            listOf(
                event("message", 1, "text" to "hello"),
                event("plan_revision", 2, "plan" to "something new"),
                event("message", 3, "text" to "world"),
            ),
        )
        assertTrue(result is RebuildResult.Incompatible)
        result as RebuildResult.Incompatible
        assertTrue(result.types.contains("plan_revision"))
        assertTrue("should say the session is not shown", result.message.contains("没有显示"))
    }

    @Test
    fun an_explicitly_ignorable_event_is_skipped() {
        val result = rebuildSession(
            listOf(
                event("message", 1, "text" to "hello"),
                event("heartbeat", 2).put("ignorable", true),
            ),
        )
        assertTrue(result is RebuildResult.Ok)
        assertEquals(1, (result as RebuildResult.Ok).events.size)
    }

    /**
     * Only the computer may declare an event unimportant, and only explicitly.
     * Anything else — absent, false, a string, a number — means required.
     */
    @Test
    fun only_an_explicit_true_marks_an_event_ignorable() {
        val notIgnorable = listOf<JSONObject>(
            event("mystery_a", 1),
            event("mystery_b", 1).put("ignorable", false),
            event("mystery_c", 1).put("ignorable", "true"),
            event("mystery_d", 1).put("ignorable", 1),
            event("mystery_e", 1).put("ignorable", JSONObject.NULL),
        )
        for (candidate in notIgnorable) {
            val result = rebuildSession(listOf(candidate))
            assertTrue(
                "${candidate.getString("type")} must be treated as required",
                result is RebuildResult.Incompatible,
            )
        }
    }

    @Test
    fun several_unknown_types_are_all_named() {
        val result = rebuildSession(
            listOf(event("alpha", 1), event("beta", 2), event("alpha", 3)),
        ) as RebuildResult.Incompatible
        assertEquals(setOf("alpha", "beta"), result.types)
        assertTrue("the message names them", result.message.contains("alpha"))
        assertTrue(result.message.contains("beta"))
    }

    @Test
    fun a_message_with_no_text_is_malformed_rather_than_blank() {
        // An empty bubble silently drops whatever the message said.
        assertTrue(rebuildSession(listOf(event("message", 1))) is RebuildResult.Malformed)
    }

    @Test
    fun a_tool_run_with_no_command_is_malformed() {
        assertTrue(rebuildSession(listOf(event("tool_run", 1))) is RebuildResult.Malformed)
    }

    @Test
    fun a_tool_run_without_output_is_allowed() {
        val result = rebuildSession(listOf(event("tool_run", 1, "command" to "ls")))
        assertTrue(result is RebuildResult.Ok)
        assertEquals(null, ((result as RebuildResult.Ok).events[0] as SessionEvent.ToolRun).output)
    }

    @Test
    fun an_event_with_no_type_is_malformed() {
        assertTrue(
            rebuildSession(listOf(JSONObject().put("sequence", 1))) is RebuildResult.Malformed,
        )
    }

    @Test
    fun an_event_with_no_sequence_is_malformed() {
        assertTrue(
            rebuildSession(listOf(JSONObject().put("type", "message").put("text", "x")))
                is RebuildResult.Malformed,
        )
    }

    @Test
    fun an_empty_page_rebuilds_to_nothing() {
        val result = rebuildSession(emptyList())
        assertTrue(result is RebuildResult.Ok)
        assertTrue((result as RebuildResult.Ok).events.isEmpty())
    }

    // An unknown event is not ignorable just because nothing follows it: a
    // trailing event can still be the answer the user is waiting for.
    @Test
    fun an_unknown_event_at_the_end_still_refuses() {
        val result = rebuildSession(
            listOf(event("message", 1, "text" to "hi"), event("final_answer", 2)),
        )
        assertTrue(result is RebuildResult.Incompatible)
    }

    @Test
    fun a_page_reply_is_read_from_the_events_array() {
        val result = rebuildSessionPage(
            page(event("message", 1, "fromUser" to true, "text" to "hi")),
        )
        assertTrue(result is RebuildResult.Ok)
    }

    @Test
    fun a_reply_with_no_events_array_is_malformed() {
        assertTrue(rebuildSessionPage(JSONObject()) is RebuildResult.Malformed)
    }

    @Test
    fun a_non_object_entry_in_the_array_is_malformed() {
        val json = JSONObject().put("events", JSONArray(listOf<Any>("not an object")))
        assertTrue(rebuildSessionPage(json) is RebuildResult.Malformed)
    }
}
