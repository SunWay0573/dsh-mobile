package io.github.sunway0573.dshmobile.mobile.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Mobile protocol, as the phone sees it.
 *
 * ## The one rule this file exists to enforce
 *
 * The phone never parses a DSH internal field, a desktop page structure or a
 * plugin's own UI. It speaks this vocabulary and nothing else. Everything about
 * how a particular Harness build does something is the computer's problem, and
 * keeping it there is what lets one phone serve two computers running different
 * Harness versions without a branch per version.
 *
 * Pure Kotlin with no Android imports, so all of it is JVM testable. The parts
 * worth testing are the ones that go wrong quietly: version negotiation, and
 * which operations are genuinely available.
 */
internal object MobileProtocol {

    /**
     * The version this build speaks.
     *
     * Bumped only for a breaking change. Additive operations and fields do not
     * need one: a client that does not recognise a capability simply does not
     * use it, and an operation it does not know is refused by name.
     */
    const val VERSION = 1

    /** Every operation this build implements. A subset of these may be available. */
    val IMPLEMENTED: Set<Operation> = Operation.entries.toSet()
}

/** An operation the phone may ask for. */
internal enum class Operation(val wire: String) {
    ComputerStatus("computer.status"),
    SessionList("session.list"),
    SessionPage("session.page"),
    SessionFollow("session.follow"),
    TaskSubmit("task.submit"),
    TaskCancel("task.cancel"),
    ApprovalList("approval.list"),
    ApprovalDecide("approval.decide"),
    QuestionList("question.list"),
    QuestionAnswer("question.answer"),
    FilePreview("file.preview"),
    ;

    companion object {
        fun fromWire(value: String?): Operation? = entries.firstOrNull { it.wire == value }
    }
}

/** What a paired device may be granted. */
internal enum class Scope(val wire: String) {
    SessionsRead("sessions.read"),
    TasksSubmit("tasks.submit"),
    TasksCancel("tasks.cancel"),
    ApprovalsRespond("approvals.respond"),
    QuestionsRead("questions.read"),
    QuestionsRespond("questions.respond"),
    FilesPreview("files.preview"),
    ;

    companion object {
        fun fromWire(value: String?): Scope? = entries.firstOrNull { it.wire == value }
    }
}

/** The scope each operation needs. Read and write are separate throughout. */
internal val OPERATION_SCOPE: Map<Operation, Scope> = mapOf(
    Operation.ComputerStatus to Scope.SessionsRead,
    Operation.SessionList to Scope.SessionsRead,
    Operation.SessionPage to Scope.SessionsRead,
    Operation.SessionFollow to Scope.SessionsRead,
    Operation.TaskSubmit to Scope.TasksSubmit,
    Operation.TaskCancel to Scope.TasksCancel,
    Operation.ApprovalList to Scope.ApprovalsRespond,
    Operation.ApprovalDecide to Scope.ApprovalsRespond,
    Operation.QuestionList to Scope.QuestionsRead,
    Operation.QuestionAnswer to Scope.QuestionsRespond,
    Operation.FilePreview to Scope.FilesPreview,
)

/** What the computer says about itself and what this phone may do. */
internal data class ComputerStatus(
    val protocolVersion: Int,
    val adapterVersion: String,
    val hostVersion: String,
    val computerId: String,
    val computerName: String,
    val capabilities: Set<Operation>,
    val grantedScopes: Set<Scope>,
)

/**
 * The outcome of connecting.
 *
 * A successful connection is not simply "we got a status": it is a status whose
 * protocol this build understands. Modelling the failure as a first-class result
 * means the screen has something specific to say instead of showing an empty
 * task list that looks like the computer has no tasks.
 */
internal sealed interface Negotiation {

    /**
     * Usable, possibly with fewer operations than this build implements.
     *
     * @param available the intersection of what the phone implements, what the
     *   computer advertises, and what this device was granted. Everything the UI
     *   offers comes from here — and is still checked again on the computer,
     *   because a phone's idea of its own rights is not evidence.
     * @param unavailableKnown operations the computer or the grant rules out, so
     *   the UI can explain an absent control rather than omitting it silently.
     */
    data class Ready(
        val status: ComputerStatus,
        val available: Set<Operation>,
        val unavailableKnown: Set<Operation>,
    ) : Negotiation

    /** Cannot be used until one of the two ends is updated. */
    data class Incompatible(
        val kind: Incompatibility,
        val message: String,
    ) : Negotiation

    /** The reply could not be understood at all. */
    data class Malformed(val detail: String) : Negotiation
}

/**
 * Which end needs updating.
 *
 * Four cases with four different fixes, and telling a user the wrong one wastes
 * their afternoon. Note that none of them is "retry": a version mismatch does
 * not resolve itself, and presenting it as a network problem is the specific
 * mistake this type exists to prevent.
 */
internal enum class Incompatibility {
    /** The computer speaks a newer protocol than this app. The app needs updating. */
    CLIENT_TOO_OLD,

    /** The computer speaks an older protocol. Its plugin needs updating. */
    COMPUTER_TOO_OLD,

    /** The protocol matches but the computer offers nothing phone and grant allow. */
    NO_USABLE_OPERATIONS,
}

/**
 * Decide whether a status reply is usable, and what it permits.
 *
 * @param raw the decoded reply.
 * @returns the negotiation outcome; never null, so a caller cannot forget the
 *   failure case and treat an empty result as success.
 */
internal fun negotiate(raw: ComputerStatus): Negotiation {
    if (raw.protocolVersion > MobileProtocol.VERSION) {
        return Negotiation.Incompatible(
            Incompatibility.CLIENT_TOO_OLD,
            "这台电脑使用的是更新的协议（${raw.protocolVersion}），" +
                "而这个手机端只会说版本 ${MobileProtocol.VERSION}。" +
                "请更新手机上的 App。重试不会有帮助。",
        )
    }
    if (raw.protocolVersion < MobileProtocol.VERSION) {
        return Negotiation.Incompatible(
            Incompatibility.COMPUTER_TOO_OLD,
            "这台电脑使用的是较旧的协议（${raw.protocolVersion}），" +
                "而这个手机端需要版本 ${MobileProtocol.VERSION}。" +
                "请在这台电脑上更新插件。重试不会有帮助。",
        )
    }

    // All three conditions, not just the computer's word. The phone's own
    // implementation matters too: a capability the computer offers that this
    // build cannot render is not available, whatever the handshake says.
    val available = raw.capabilities
        .intersect(MobileProtocol.IMPLEMENTED)
        .filter { operation ->
            val scope = OPERATION_SCOPE[operation]
            scope != null && raw.grantedScopes.contains(scope)
        }
        .toSet()

    val known = MobileProtocol.IMPLEMENTED - available

    if (available.isEmpty()) {
        return Negotiation.Incompatible(
            Incompatibility.NO_USABLE_OPERATIONS,
            "和这台电脑连上了，但没有任何可用操作。可能是插件版本过旧，" +
                "或者这台手机上授权被撤销了。请在电脑上检查手机连接设置。",
        )
    }

    return Negotiation.Ready(status = raw, available = available, unavailableKnown = known)
}

/** Encode a command for the wire. */
internal fun encodeCommand(
    computerId: String,
    commandId: String,
    operation: Operation,
    payload: JSONObject?,
): JSONObject = JSONObject().apply {
    put("protocolVersion", MobileProtocol.VERSION)
    put("computerId", computerId)
    put("commandId", commandId)
    put("operation", operation.wire)
    if (payload != null) put("payload", payload)
}

/**
 * Read a status reply.
 *
 * @returns the status, or null when a required field is missing. A partial
 *   status is not repaired with defaults: an absent `protocolVersion` cannot be
 *   assumed to be this build's, and guessing would turn "the computer said
 *   something we do not understand" into "the computer is fine".
 */
internal fun parseStatus(json: JSONObject): ComputerStatus? {
    if (!json.has("protocolVersion")) return null
    val protocolVersion = json.optInt("protocolVersion", -1)
    if (protocolVersion <= 0) return null

    val computerId = json.optString("computerId").takeIf { it.isNotBlank() } ?: return null

    return ComputerStatus(
        protocolVersion = protocolVersion,
        adapterVersion = json.optString("adapterVersion", ""),
        hostVersion = json.optString("hostVersion", ""),
        computerId = computerId,
        computerName = json.optString("computerName", computerId),
        capabilities = json.optJSONArray("capabilities").toStringSet()
            .mapNotNull(Operation::fromWire)
            .toSet(),
        grantedScopes = json.optJSONArray("grantedScopes").toStringSet()
            .mapNotNull(Scope::fromWire)
            .toSet(),
    )
}

private fun JSONArray?.toStringSet(): Set<String> {
    if (this == null) return emptySet()
    val out = mutableSetOf<String>()
    for (index in 0 until length()) {
        val value = optString(index, "")
        if (value.isNotEmpty()) out.add(value)
    }
    return out
}

/** Why the computer refused a command. Stable strings; the UI branches on them. */
internal enum class Refusal(val wire: String) {
    UnsupportedProtocol("UNSUPPORTED_PROTOCOL"),
    UnknownOperation("UNKNOWN_OPERATION"),
    MissingCapability("MISSING_CAPABILITY"),
    ForbiddenScope("FORBIDDEN_SCOPE"),
    WrongComputer("WRONG_COMPUTER"),
    CommandConflict("COMMAND_CONFLICT"),
    CommandStateUnknown("COMMAND_STATE_UNKNOWN"),
    UnknownApprovalType("UNKNOWN_APPROVAL_TYPE"),
    HandlerFailed("HANDLER_FAILED"),
    Unknown(""),
    ;

    companion object {
        fun fromWire(value: String?): Refusal =
            entries.firstOrNull { it.wire == value && it != Unknown } ?: Unknown
    }
}

/**
 * Turn a refusal into something a person can act on.
 *
 * Two of these are deliberately emphatic about retrying being useless. A user
 * who reads "try again" on a version mismatch will try again, and again, and
 * conclude the app is broken — which, from where they are standing, it is.
 */
internal fun refusalMessage(refusal: Refusal, computerName: String): String = when (refusal) {
    Refusal.UnsupportedProtocol ->
        "这台电脑和手机端的协议版本不一致。请先更新两端中的一端；重试不会有帮助。"
    Refusal.UnknownOperation ->
        "这台电脑不认识这个操作。手机端可能比电脑端新。"
    Refusal.MissingCapability ->
        "这台电脑安装的 Harness 版本做不到这个操作。请在电脑上更新插件，或在电脑上直接处理。"
    Refusal.ForbiddenScope ->
        "这台手机没有获得执行该操作的授权。请在「$computerName」上检查手机连接设置。"
    Refusal.WrongComputer ->
        "这条命令是发给另一台电脑的。请确认当前选中的电脑。"
    Refusal.CommandConflict ->
        "这个命令编号已经用于另一个请求。请重新发起一次。"
    Refusal.CommandStateUnknown ->
        "这条命令已经送达，但结果未知——它可能执行了，也可能没有。" +
            "请先到任务列表确认，再决定是否重发。"
    Refusal.UnknownApprovalType ->
        "这个审批请求无法识别，因此没有被执行。任务会停在这一步等待处理。"
    Refusal.HandlerFailed ->
        "电脑处理这条命令时出错了。电脑上的日志会说明原因。"
    Refusal.Unknown ->
        "电脑拒绝了这个请求，但没有说明原因。"
}
