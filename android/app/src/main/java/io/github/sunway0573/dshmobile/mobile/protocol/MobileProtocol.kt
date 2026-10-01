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

    /**
     * 这个手机端会说的协议版本。
     *
     * 是列表而不是单个常量：协商需要两边各拿一个列表求交集。
     * 只说一个值、却在别处接受另一个值，就会出现"按握手报告发过去的请求被握手方自己拒绝"——
     * 这正是复核复现的那个缺陷。
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
    val protocolVersion: Int,
    /** 电脑端能说的全部版本，供手机自行选择，而不是只能相信单个值。 */
    val supportedProtocols: Set<Int>,
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
        /** 双方共同选定的版本。界面和请求编码都用它，不是各自写死的常量。 */
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

    /**
     * 两边没有任何共同版本。
     *
     * 与"电脑太新"和"电脑太旧"都不同：那两种能指出该更新哪一端，
     * 而这种只能说明两端都落后于对方，无法从手机单方面判断谁该动。
     */
    NO_COMMON_VERSION,
}

/**
 * 选出双方都支持的最高协议版本。
 *
 * 取最高而不是最低：两个列表都按能力递增，共享的最高版本能力最多。
 *
 * @return 选中的版本；没有共同版本时返回 null。调用方必须把 null 当作不兼容，
 *   绝不能"先用我们自己的试试"——那正是把不兼容变成运行期失败的做法。
 */
internal fun selectProtocolVersion(
    serverVersions: Set<Int>,
    clientVersions: Set<Int> = MobileProtocol.SUPPORTED_VERSIONS,
): Int? = serverVersions.intersect(clientVersions).maxOrNull()

/**
 * Decide whether a status reply is usable, and what it permits.
 *
 * @param raw the decoded reply.
 * @returns the negotiation outcome; never null, so a caller cannot forget the
 *   failure case and treat an empty result as success.
 */
internal fun negotiate(raw: ComputerStatus): Negotiation {
    // 用两边各自声明的列表求共同版本，而不是拿单个数字和常量比。
    // 后者在电脑端支持多个版本时会得出错误结论。
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

/** Encode a command for the wire. */
internal fun encodeCommand(
    computerId: String,
    commandId: String,
    operation: Operation,
    payload: JSONObject?,
    protocolVersion: Int = MobileProtocol.VERSION,
): JSONObject = JSONObject().apply {
    put("protocolVersion", protocolVersion)
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
        supportedProtocols = json.optJSONArray("supportedProtocols")
            .toIntSet()
            .ifEmpty { setOf(protocolVersion) },
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

private fun JSONArray?.toIntSet(): Set<Int> {
    if (this == null) return emptySet()
    val out = mutableSetOf<Int>()
    for (index in 0 until length()) {
        val value = optInt(index, -1)
        if (value > 0) out.add(value)
    }
    return out
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
    Unauthenticated("UNAUTHENTICATED"),
    DeviceRevoked("DEVICE_REVOKED"),
    InvalidPayload("INVALID_PAYLOAD"),
    /**
     * decision 字段读了但读不懂。与 UnknownApprovalType 不同：
     * 那个说的是"问题认不出来"，这个说的是"答案认不出来"。
     */
    MalformedApprovalDecision("MALFORMED_APPROVAL_DECISION"),
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
    Refusal.Unauthenticated ->
        "这个请求没有说明是哪台手机发的，因此被拒绝。请重新配对。"
    Refusal.DeviceRevoked ->
        "这台手机在电脑上的授权已经被移除。**更新 App 不会恢复它**，请在电脑上重新配对。"
    Refusal.InvalidPayload ->
        "这个请求缺少必要内容，因此没有执行。"
    Refusal.MalformedApprovalDecision ->
        "这个审批决定无法识别，因此没有被执行。任务会停在这一步等待处理。"
    Refusal.UnknownApprovalType ->
        "电脑无法确认这个审批对应哪一条待处理请求，因此没有执行。请在电脑上处理。"
    Refusal.HandlerFailed ->
        "电脑处理这条命令时出错了。电脑上的日志会说明原因。"
    Refusal.Unknown ->
        "电脑拒绝了这个请求，但没有说明原因。"
}
