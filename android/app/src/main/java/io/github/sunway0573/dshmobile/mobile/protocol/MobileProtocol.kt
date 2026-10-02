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
 * worth testing are the ones that go wrong quietly: version negotiation,
 * contract parsing, and which operations are genuinely available.
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

    /**
     * Every version this phone can speak.
     *
     * A set rather than a constant, because negotiation needs two lists to
     * intersect. Reporting one value while accepting another is how a client
     * ends up being refused by the end that told it what to send.
     */
    val SUPPORTED_VERSIONS: Set<Int> = setOf(1)

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
    /** The version this reply is written in. */
    val protocolVersion: Int,
    /** Every version the computer says it can speak. */
    val supportedProtocols: Set<Int>,
    val adapterVersion: String,
    val hostVersion: String,
    val computerId: String,
    val computerName: String,
    val capabilities: Set<Operation>,
    val grantedScopes: Set<Scope>,
)

/**
 * The outcome of parsing a handshake reply.
 *
 * A result type rather than a nullable value: `null` cannot distinguish "field
 * missing" from "wrong type" from "malformed version", and those tell a user
 * different things. More importantly, an earlier version *repaired* malformed
 * values into compatibility — the review demonstrated exactly that.
 */
internal sealed interface StatusParseResult {
    data class Ok(val status: ComputerStatus) : StatusParseResult
    data class Malformed(val detail: String) : StatusParseResult
}

/**
 * Read a strictly positive integer.
 *
 * `optInt` truncates `1.9` to `1` and converts `"1"` to `1`. For a version
 * number that is not convenience, it is fabrication: a computer saying `1.9`
 * becomes a computer saying `1`, and both ends then believe they agree. Every
 * type is checked and nothing is coerced.
 */
private fun JSONObject.strictPositiveInt(key: String): Int? {
    val raw = opt(key) ?: return null
    val value = when (raw) {
        is Int -> raw
        is Long -> if (raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt() else return null
        else -> return null
    }
    return if (value > 0) value else null
}

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
     * @param protocolVersion the version both ends agreed on. Requests are
     *   encoded with this, not with a constant.
     * @param available the intersection of what the phone implements, what the
     *   computer advertises, and what this device was granted. Everything the UI
     *   offers comes from here — and is still checked again on the computer,
     *   because a phone's idea of its own rights is not evidence.
     * @param unavailableKnown operations the computer or the grant rules out, so
     *   the UI can explain an absent control rather than omitting it silently.
     */
    data class Ready(
        val status: ComputerStatus,
        val protocolVersion: Int,
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
 * their afternoon. None of them is "retry": a version mismatch does not resolve
 * itself, and presenting it as a network problem is the specific mistake this
 * type exists to prevent.
 */
internal enum class Incompatibility {
    /** The computer speaks newer versions than this app. The app needs updating. */
    CLIENT_TOO_OLD,

    /** The computer speaks only older versions. Its plugin needs updating. */
    COMPUTER_TOO_OLD,

    /** Neither end is strictly newer; there is simply no overlap. */
    NO_COMMON_VERSION,

    /** The protocol matches but the computer offers nothing phone and grant allow. */
    NO_USABLE_OPERATIONS,
}

/**
 * Choose the highest protocol version both ends speak.
 *
 * Highest rather than lowest: both lists are ordered by capability, so the
 * newest shared version is the one with the most of it.
 *
 * @return the chosen version, or null when there is none. The caller must treat
 *   null as incompatible — never as "try ours and see", which is what turns a
 *   version mismatch into a runtime failure.
 */
internal fun selectProtocolVersion(
    serverVersions: Set<Int>,
    clientVersions: Set<Int> = MobileProtocol.SUPPORTED_VERSIONS,
): Int? = serverVersions.intersect(clientVersions).maxOrNull()

/**
 * Decide whether a status reply is usable, and what it permits.
 *
 * @returns the negotiation outcome; never null, so a caller cannot forget the
 *   failure case and treat an empty result as success.
 */
internal fun negotiate(raw: ComputerStatus): Negotiation {
    // Intersect two declared lists rather than comparing one number against a
    // constant. The latter gives the wrong answer as soon as a computer speaks
    // more than one version.
    val agreed = selectProtocolVersion(raw.supportedProtocols)

    if (agreed == null) {
        val serverMax = raw.supportedProtocols.maxOrNull()
        val clientMax = MobileProtocol.SUPPORTED_VERSIONS.maxOrNull() ?: 0
        val kind = when {
            serverMax == null -> Incompatibility.NO_COMMON_VERSION
            serverMax > clientMax -> Incompatibility.CLIENT_TOO_OLD
            serverMax < clientMax -> Incompatibility.COMPUTER_TOO_OLD
            else -> Incompatibility.NO_COMMON_VERSION
        }
        val blame = when (kind) {
            Incompatibility.CLIENT_TOO_OLD -> "请更新手机上的 App。"
            Incompatibility.COMPUTER_TOO_OLD -> "请在这台电脑上更新插件。"
            else -> "请把两端都更新到较新版本。"
        }
        return Negotiation.Incompatible(
            kind,
            "没有共同的协议版本：电脑端支持 " +
                "${raw.supportedProtocols.sorted().joinToString(", ").ifEmpty { "（未声明）" }}，" +
                "手机端支持 ${MobileProtocol.SUPPORTED_VERSIONS.sorted().joinToString(", ")}。" +
                "$blame 重试不会有帮助。",
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

    return Negotiation.Ready(
        status = raw,
        protocolVersion = agreed,
        available = available,
        unavailableKnown = known,
    )
}

/**
 * Encode a command for the wire.
 *
 * ## Why `deviceId` is a required parameter
 *
 * An earlier version neither accepted nor encoded it, while the computer lists
 * it as required and answers `UNAUTHENTICATED` without it. Both ends passed
 * their own tests and did not fit together; the review proved it with one
 * counterexample. There is no default, so forgetting it is a compile error
 * rather than a rejected request.
 *
 * ## `deviceId` is an identifier, not a credential
 *
 * It says which paired device this command *claims* to come from. The actual
 * authentication is the connection's credential, and the computer passes the
 * device identity it authenticated into the adapter as trusted context. Adding
 * a self-declared field is **not** authentication: anyone who can send a request
 * can put someone else's id in it. The computer must check that the claimed id
 * matches the authenticated connection.
 *
 * `protocolVersion` is required for the same reason: the negotiated version has
 * to be the one on the wire, and a default would silently send a version the two
 * ends did not agree on.
 */
internal fun encodeCommand(
    computerId: String,
    deviceId: String,
    commandId: String,
    operation: Operation,
    payload: JSONObject?,
    protocolVersion: Int,
): JSONObject = JSONObject().apply {
    put("protocolVersion", protocolVersion)
    put("computerId", computerId)
    put("deviceId", deviceId)
    put("commandId", commandId)
    put("operation", operation.wire)
    if (payload != null) put("payload", payload)
}

/**
 * Read a status reply.
 *
 * See {@link StatusParseResult} for why this is not nullable.
 */
internal fun parseStatus(json: JSONObject): StatusParseResult {
    val protocolVersion = json.strictPositiveInt("protocolVersion")
        ?: return StatusParseResult.Malformed(
            "回复里的 protocolVersion 缺失、不是正整数、或不是数字类型。" +
                "不能假设它是本机支持的版本。",
        )

    val computerId = json.optString("computerId").takeIf { it.isNotBlank() }
        ?: return StatusParseResult.Malformed("回复里没有 computerId。")

    val supported = parseSupportedProtocols(json, protocolVersion)
    if (supported is SupportedProtocols.Malformed) {
        return StatusParseResult.Malformed(supported.detail)
    }

    return StatusParseResult.Ok(
        ComputerStatus(
            protocolVersion = protocolVersion,
            supportedProtocols = (supported as SupportedProtocols.Valid).versions,
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
        ),
    )
}

/** The outcome of reading `supportedProtocols`. */
private sealed interface SupportedProtocols {
    data class Valid(val versions: Set<Int>) : SupportedProtocols
    data class Malformed(val detail: String) : SupportedProtocols
}

/**
 * Strictly read `supportedProtocols`.
 *
 * Three rules, matching the three malformed inputs the review demonstrated:
 *
 * - Present but not an array → contract error (`"invalid"`).
 * - An element that is not a positive integer → contract error (`[1.9]`). No
 *   truncation.
 * - An empty array → contract error (`[]`). A computer that supports nothing is
 *   broken, not compatible, and inferring the current version from an empty list
 *   is precisely the bug that was reproduced.
 *
 * **The one permitted compatibility branch**: the field is entirely absent and
 * `protocolVersion` is valid. That is a legacy single-version handshake. It is
 * written as its own rule because letting it share a branch with the three
 * errors above is how an error becomes "compatible".
 */
private fun parseSupportedProtocols(json: JSONObject, protocolVersion: Int): SupportedProtocols {
    // Only a genuinely absent field. An explicit `null` is a value the computer
    // chose to send, and treating it as "absent" made the documented
    // compatibility rule wider than the documentation said. The review caught
    // that the implementation and the stated rule disagreed; the test that
    // pinned the old behaviour was pinning the wrong thing.
    if (!json.has("supportedProtocols")) {
        return SupportedProtocols.Valid(setOf(protocolVersion))
    }
    if (json.isNull("supportedProtocols")) {
        return SupportedProtocols.Malformed(
            "supportedProtocols 是显式 null。字段缺失才是旧式单版本握手，" +
                "显式 null 表示电脑发了这个字段但值为空，两者不能混为一谈。",
        )
    }

    val array = json.optJSONArray("supportedProtocols")
        ?: return SupportedProtocols.Malformed(
            "supportedProtocols 存在但不是数组。" +
                "不能把它当成单版本握手——字段类型错误意味着两端对协议的理解已经不一致。",
        )

    if (array.length() == 0) {
        return SupportedProtocols.Malformed(
            "supportedProtocols 是空数组。一台不声明任何协议版本的电脑无法协商，" +
                "不能推断成支持当前版本。",
        )
    }

    val versions = mutableSetOf<Int>()
    for (index in 0 until array.length()) {
        val item = array.opt(index)
        val value = when (item) {
            is Int -> item
            is Long -> if (item in Int.MIN_VALUE..Int.MAX_VALUE) item.toInt() else null
            else -> null
        }
        if (value == null || value <= 0) {
            return SupportedProtocols.Malformed(
                "supportedProtocols[$index] 不是正整数。版本号不做截断或类型转换。",
            )
        }
        versions.add(value)
    }
    return SupportedProtocols.Valid(versions)
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
    Unauthenticated("UNAUTHENTICATED"),
    DeviceRevoked("DEVICE_REVOKED"),
    WrongComputer("WRONG_COMPUTER"),
    InvalidPayload("INVALID_PAYLOAD"),
    CommandConflict("COMMAND_CONFLICT"),
    CommandStateUnknown("COMMAND_STATE_UNKNOWN"),

    /**
     * The decision field was present but unreadable. Distinct from
     * {@link UnknownApprovalType}: this says the *answer* could not be read, not
     * that the *question* was unrecognised.
     */
    MalformedApprovalDecision("MALFORMED_APPROVAL_DECISION"),

    /**
     * The approval being answered could not be placed. Requires an authoritative
     * record of pending approvals, which does not exist yet.
     */
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
    Refusal.Unauthenticated ->
        "这个请求没有说明是哪台手机发的，因此被拒绝。请重新配对。"
    Refusal.DeviceRevoked ->
        "这台手机在电脑上的授权已经被移除。更新 App 不会恢复它，请在电脑上重新配对。"
    Refusal.WrongComputer ->
        "这条命令是发给另一台电脑的。请确认当前选中的电脑。"
    Refusal.InvalidPayload ->
        "这个请求缺少必要内容，因此没有执行。"
    Refusal.CommandConflict ->
        "这个命令编号已经用于另一个请求。请重新发起一次。"
    Refusal.CommandStateUnknown ->
        "这条命令已经送达，但结果未知——它可能执行了，也可能没有。" +
            "请先到任务列表确认，再决定是否重发。"
    Refusal.MalformedApprovalDecision ->
        "这个审批决定无法识别，因此没有被执行。任务会停在这一步等待处理。"
    Refusal.UnknownApprovalType ->
        "电脑无法确认这个审批对应哪一条待处理请求，因此没有执行。请在电脑上处理。"
    Refusal.HandlerFailed ->
        "电脑处理这条命令时出错了。电脑上的日志会说明原因。"
    Refusal.Unknown ->
        "电脑拒绝了这个请求，但没有说明原因。"
}
