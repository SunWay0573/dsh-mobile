package io.github.sunway0573.dshmobile.mobile.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rebuilding a session from a list of events.
 *
 * ## Why an unknown event is not simply skipped
 *
 * History pages and live streams carry the same event types, so this has to be
 * right before `session.page` is wired to a real host — not only before live
 * subscription.
 *
 * If an event cannot be understood and is dropped, the result is a conversation
 * that renders, scrolls, and is wrong: missing a tool call, or showing an
 * assistant's answer with the question that prompted it gone. Nothing on screen
 * says anything is absent. That is worse than an empty screen, because the user
 * has no reason to doubt it.
 *
 * ## The rule
 *
 * - Known event → rebuild it.
 * - Unknown event marked `ignorable: true` → skip it. The computer is stating
 *   that this event carries nothing a reader needs.
 * - Unknown event without that mark → **refuse the whole rebuild** and say the
 *   two ends are incompatible.
 *
 * A phone must never decide for itself that an unrecognised event looks
 * unimportant. "I do not know what this is" is not evidence that it does not
 * matter, and the events most likely to be unrecognised are the ones from a
 * newer host — which are exactly the ones carrying something new.
 */

/** One event, as far as this build understands it. */
internal sealed interface SessionEvent {

    /** Position in the stream, for ordering and for resuming. */
    val sequence: Long

    data class Message(
        override val sequence: Long,
        val fromUser: Boolean,
        val text: String,
    ) : SessionEvent

    data class ToolRun(
        override val sequence: Long,
        val command: String,
        val output: String?,
    ) : SessionEvent

    data class Status(
        override val sequence: Long,
        val status: String,
    ) : SessionEvent
}

/**
 * The outcome of rebuilding.
 *
 * A refusal is a result rather than an exception, and there is deliberately no
 * "partial" case: a caller that can render half a conversation will render half
 * a conversation.
 */
internal sealed interface RebuildResult {

    data class Ok(val events: List<SessionEvent>) : RebuildResult

    /**
     * The stream contains something this build cannot place.
     *
     * @param types the unrecognised event types, so the message can name them —
     *   "this app is too old for this session" is actionable, "something went
     *   wrong" is not.
     */
    data class Incompatible(val types: Set<String>, val message: String) : RebuildResult

    /** The page itself could not be read. */
    data class Malformed(val detail: String) : RebuildResult
}

/** Event types this build knows how to render. */
private val KNOWN_TYPES = setOf("message", "tool_run", "status")

/**
 * Whether an unrecognised event may be skipped.
 *
 * Only the computer may say so, and only explicitly. Absent, malformed or
 * non-boolean means no.
 */
private fun isExplicitlyIgnorable(json: JSONObject): Boolean =
    json.has("ignorable") && json.opt("ignorable") == true

/**
 * Rebuild a session from a page of events.
 *
 * @param events the page, in any order; sorted by `sequence` on success.
 * @returns [RebuildResult.Ok] only when every event was either understood or
 *   explicitly ignorable.
 */
internal fun rebuildSession(events: List<JSONObject>): RebuildResult {
    val rebuilt = mutableListOf<SessionEvent>()
    val unknownRequired = mutableSetOf<String>()

    for (event in events) {
        val type = event.optString("type", "")
        if (type.isEmpty()) {
            return RebuildResult.Malformed("有一个事件没有 type 字段。")
        }
        val sequence = event.optLong("sequence", -1L)
        if (sequence < 0L) {
            return RebuildResult.Malformed("事件 \"$type\" 没有有效的 sequence。")
        }

        when (type) {
            "message" -> {
                val text = event.optString("text", "")
                if (text.isEmpty()) {
                    // A message with no text would render as a blank bubble and
                    // silently drop whatever it said.
                    return RebuildResult.Malformed("事件 $sequence 是消息但内容为空。")
                }
                rebuilt.add(SessionEvent.Message(sequence, event.optBoolean("fromUser", false), text))
            }

            "tool_run" -> {
                val command = event.optString("command", "")
                if (command.isEmpty()) {
                    return RebuildResult.Malformed("事件 $sequence 是工具调用但没有命令内容。")
                }
                rebuilt.add(
                    SessionEvent.ToolRun(
                        sequence = sequence,
                        command = command,
                        output = if (event.has("output")) event.optString("output") else null,
                    ),
                )
            }

            "status" -> rebuilt.add(
                SessionEvent.Status(sequence, event.optString("status", "")),
            )

            else -> if (!isExplicitlyIgnorable(event)) unknownRequired.add(type)
        }
    }

    if (unknownRequired.isNotEmpty()) {
        val names = unknownRequired.sorted().joinToString("、")
        return RebuildResult.Incompatible(
            types = unknownRequired,
            message = "这段会话里包含这个手机端不认识的必需事件（$names），" +
                "因此没有显示。显示出来会是残缺的，看起来却像完整的。" +
                "请更新手机上的 App，或在电脑上查看这段会话。",
        )
    }

    return RebuildResult.Ok(rebuilt.sortedBy { it.sequence })
}

/**
 * Rebuild from a `session.page` reply.
 *
 * @param json the reply object.
 */
internal fun rebuildSessionPage(json: JSONObject): RebuildResult {
    val array: JSONArray = json.optJSONArray("events")
        ?: return RebuildResult.Malformed("回复里没有 events 数组。")

    val events = mutableListOf<JSONObject>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index)
            ?: return RebuildResult.Malformed("events[$index] 不是对象。")
        events.add(item)
    }
    return rebuildSession(events)
}
