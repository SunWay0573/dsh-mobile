/**
 * The Mobile protocol contract.
 *
 * ## What this file is
 *
 * The one place that defines what a phone may ask a computer to do, and what
 * the computer guarantees back. Both ends are written against it, and the tests
 * here are the part that can be checked without either end running.
 *
 * ## Why the operations are not DSH's RPC names
 *
 * These are the Mobile contract, not the host's internal API. A phone that
 * speaks `session/page` is coupled to DSH's session controller and breaks when
 * DSH renames something — which it has, three times in this project's short
 * history. A phone that speaks `session.page` breaks only when this file
 * changes, and this file changes on purpose.
 *
 * The host-side adapter is the only place that knows both vocabularies. That is
 * the point: version differences are absorbed on the computer, so one phone can
 * talk to two computers running different Harness versions without carrying a
 * branch per version.
 *
 * @module mobile/protocol
 */

/** Bumped when a change would break an existing client. */
export const PROTOCOL_VERSION = 1

/**
 * Protocol versions this adapter can speak.
 *
 * A list rather than a constant, because negotiation needs two lists to
 * intersect. Reporting `1` while accepting something else — which an earlier
 * version did — means a client that follows the handshake is refused by the
 * very adapter that told it what to send.
 *
 * Additive operations do not need a new version: a client that does not know an
 * operation is refused by name, and one that does not know a capability simply
 * does not use it.
 */
export const SUPPORTED_PROTOCOL_VERSIONS: readonly number[] = [1]

/**
 * Every operation a phone may request.
 *
 * Adding one is a protocol change; removing one is a breaking protocol change.
 */
export const OPERATIONS = [
  'computer.status',
  'session.list',
  'session.page',
  'session.follow',
  'task.submit',
  'task.cancel',
  'approval.list',
  'approval.decide',
  'question.list',
  'question.answer',
  'file.preview',
] as const

export type Operation = (typeof OPERATIONS)[number]

/** What a paired device may be granted. A device holds a subset. */
export const SCOPES = [
  'sessions.read',
  'tasks.submit',
  'tasks.cancel',
  'approvals.respond',
  'questions.read',
  'questions.respond',
  'files.preview',
] as const

export type Scope = (typeof SCOPES)[number]

/**
 * The scope each operation needs.
 *
 * Read and write are separate scopes throughout. Pairing someone to watch a
 * build should not also let them cancel it, and a single `sessions` scope would
 * make that distinction impossible to express.
 */
export const OPERATION_SCOPE: Readonly<Record<Operation, Scope>> = Object.freeze({
  'computer.status': 'sessions.read',
  'session.list': 'sessions.read',
  'session.page': 'sessions.read',
  'session.follow': 'sessions.read',
  'task.submit': 'tasks.submit',
  'task.cancel': 'tasks.cancel',
  'approval.list': 'approvals.respond',
  'approval.decide': 'approvals.respond',
  'question.list': 'questions.read',
  'question.answer': 'questions.respond',
  'file.preview': 'files.preview',
})

/**
 * Operations that change something and therefore need a payload.
 *
 * A write with no body is not a valid request, and an earlier version let it
 * through to `canonicalPayload`, which threw on `undefined.length` and escaped
 * as an unhandled exception — in a method documented never to throw. The phone
 * got a crash-shaped failure instead of a refusal it could show.
 */
const REQUIRED_PAYLOAD: ReadonlySet<Operation> = new Set<Operation>([
  'task.submit',
  'task.cancel',
  'approval.decide',
  'question.answer',
])

/** Why a request was refused. Stable strings: clients branch on them. */
export const REFUSAL = {
  /** The client speaks a protocol this adapter does not. */
  UNSUPPORTED_PROTOCOL: 'UNSUPPORTED_PROTOCOL',
  /** Not an operation this adapter implements. */
  UNKNOWN_OPERATION: 'UNKNOWN_OPERATION',
  /** A real operation, but this build cannot serve it. */
  MISSING_CAPABILITY: 'MISSING_CAPABILITY',
  /** The device is not granted the scope this operation needs. */
  FORBIDDEN_SCOPE: 'FORBIDDEN_SCOPE',
  /** No device identity on the request, so no grant could be checked. */
  UNAUTHENTICATED: 'UNAUTHENTICATED',
  /** The device is known but its authorisation was revoked. */
  DEVICE_REVOKED: 'DEVICE_REVOKED',
  /** The operation belongs to a different computer. */
  WRONG_COMPUTER: 'WRONG_COMPUTER',
  /** The envelope is malformed or a required field is missing. */
  INVALID_PAYLOAD: 'INVALID_PAYLOAD',
  /** Same commandId, different request. */
  COMMAND_CONFLICT: 'COMMAND_CONFLICT',
  /** The command was registered but its outcome is not knowable. */
  COMMAND_STATE_UNKNOWN: 'COMMAND_STATE_UNKNOWN',
  /**
   * The decision field was present but not one of the allowed values.
   *
   * Distinct from {@link UNKNOWN_APPROVAL_TYPE}: this says the *answer* could
   * not be read, not that the *question* was unrecognised. Collapsing them made
   * the earlier report claim more coverage than it had.
   */
  MALFORMED_APPROVAL_DECISION: 'MALFORMED_APPROVAL_DECISION',
  /**
   * The approval being answered is not one this adapter knows about.
   *
   * Requires an authoritative record of pending approvals. Not implemented:
   * there is no approval owner yet, and this code is reserved for it.
   */
  UNKNOWN_APPROVAL_TYPE: 'UNKNOWN_APPROVAL_TYPE',
} as const

export type RefusalCode = (typeof REFUSAL)[keyof typeof REFUSAL]

export type Availability =
  | { readonly ok: true; readonly scope: Scope }
  | { readonly ok: false; readonly code: RefusalCode; readonly message: string }

/**
 * What the adapter can serve right now, and what this device may use.
 *
 * `grantedScopes` is deliberately absent. It used to be copied in at
 * construction, which meant a device whose authorisation was revoked mid-session
 * kept working until the plugin restarted — the review reproduced exactly that.
 * Permissions now come from {@link AdapterOptions.resolveDevice} on each
 * request.
 */
export interface AdapterContext {
  /** Protocol versions this adapter implements. */
  readonly supportedProtocols: readonly number[]
  /** Operations this build can actually execute against the installed host. */
  readonly capabilities: readonly Operation[]
  /** The computer this adapter serves; a command for another one is refused. */
  readonly computerId: string
}

/** A paired device, as the computer currently records it. */
export interface DeviceRecord {
  readonly deviceId: string
  readonly grantedScopes: readonly Scope[]
  /** False once the user has removed this device from the computer. */
  readonly authorized: boolean
}

export function isOperation(value: unknown): value is Operation {
  return typeof value === 'string' && (OPERATIONS as readonly string[]).includes(value)
}

export function isScope(value: unknown): value is Scope {
  return typeof value === 'string' && (SCOPES as readonly string[]).includes(value)
}

/**
 * The highest protocol version both ends speak.
 *
 * @returns the version to use, or `undefined` when there is none. The caller
 *   must treat `undefined` as incompatible — never as "use ours and hope",
 *   which is how a client ends up sending a version the server refuses.
 *
 * Highest rather than lowest: both lists are ordered by capability, and the
 * newest shared version is the one with the most of it.
 */
export function selectProtocolVersion(
  clientVersions: readonly unknown[],
  serverVersions: readonly number[] = SUPPORTED_PROTOCOL_VERSIONS,
): number | undefined {
  const usable = serverVersions.filter(
    (version) => Number.isInteger(version) && version > 0,
  )
  if (usable.length === 0) return undefined

  const shared = usable.filter((version) =>
    clientVersions.some((client) => client === version),
  )
  if (shared.length === 0) return undefined

  return Math.max(...shared)
}

/** Whether a command carries a body at all. */
export function hasPayload(operation: Operation, payload: unknown): boolean {
  if (!REQUIRED_PAYLOAD.has(operation)) return true
  return payload !== null && payload !== undefined
}

/**
 * Whether an operation may run, given the protocol, the build and the device.
 *
 * Four independent conditions, all required:
 *
 * 1. **Protocol** — the two ends share a version.
 * 2. **Identity** — the request names a device, and that device is known here.
 * 3. **Capability** — this build can actually do it against the installed host.
 * 4. **Permission** — the device holds the scope *right now*.
 *
 * The order matters because the messages differ. "Your app is too new", "this
 * computer has not been updated", "you were not granted this" and "this device
 * was removed" have four different fixes, and a client that cannot tell them
 * apart sends the user to the wrong one. Updating the app does not restore a
 * revoked grant.
 *
 * Note what this is not: hiding a button in the phone's UI. The plan is explicit
 * that a hidden control is not access control. This runs on the computer, per
 * request, every time.
 */
export function availability(
  request: {
    readonly protocolVersion: unknown
    readonly operation: unknown
    readonly computerId?: unknown
    readonly deviceId?: unknown
    readonly payload?: unknown
  },
  context: AdapterContext,
  device: DeviceRecord | undefined,
): Availability {
  const version = request.protocolVersion
  if (typeof version !== 'number' || !context.supportedProtocols.includes(version)) {
    return {
      ok: false,
      code: REFUSAL.UNSUPPORTED_PROTOCOL,
      message:
        `This computer speaks protocol ${context.supportedProtocols.join(', ')}, ` +
        `and the phone asked with ${String(version)}. One of the two needs updating.`,
    }
  }

  // Identity before the operation, not after. An unauthenticated caller should
  // not get operation-level answers at all: "unknown operation" and "you cannot
  // do that" are different facts about this computer, and telling them apart is
  // free reconnaissance.
  if (typeof request.deviceId !== 'string' || request.deviceId === '') {
    return {
      ok: false,
      code: REFUSAL.UNAUTHENTICATED,
      message: 'This request did not identify the device that sent it.',
    }
  }
  if (device === undefined) {
    return {
      ok: false,
      code: REFUSAL.DEVICE_REVOKED,
      message:
        'This phone is no longer paired with the computer. ' +
        'Pair it again from the computer to restore access.',
    }
  }
  if (!device.authorized) {
    return {
      ok: false,
      code: REFUSAL.DEVICE_REVOKED,
      message:
        'This device was removed on the computer. ' +
        'Updating the app will not restore it; pair again from the computer.',
    }
  }

  if (!isOperation(request.operation)) {
    return {
      ok: false,
      code: REFUSAL.UNKNOWN_OPERATION,
      message: `"${String(request.operation)}" is not an operation this computer knows.`,
    }
  }

  if (request.computerId !== undefined && request.computerId !== context.computerId) {
    return {
      ok: false,
      code: REFUSAL.WRONG_COMPUTER,
      message: 'That command is addressed to a different computer.',
    }
  }

  const operation = request.operation
  if (!context.capabilities.includes(operation)) {
    return {
      ok: false,
      code: REFUSAL.MISSING_CAPABILITY,
      message:
        `This computer cannot do "${operation}" with the Harness version installed. ` +
        'Update the plugin on the computer, or do this from the computer itself.',
    }
  }

  if (!hasPayload(operation, request.payload)) {
    return {
      ok: false,
      code: REFUSAL.INVALID_PAYLOAD,
      message: `"${operation}" needs a request body, and none was sent.`,
    }
  }

  // Read live, never from a snapshot taken at construction. A grant that was
  // revoked a minute ago is not a grant.
  const scope = OPERATION_SCOPE[operation]
  if (!device.grantedScopes.includes(scope)) {
    return {
      ok: false,
      code: REFUSAL.FORBIDDEN_SCOPE,
      message: `This phone was not granted "${scope}".`,
    }
  }

  return { ok: true, scope }
}

/**
 * The handshake reply: everything a phone needs to know what it may do.
 *
 * Sent as the answer to `computer.status`, and the reason the phone never has to
 * guess. `hostVersion` and `adapterVersion` are here for diagnostics — they are
 * deliberately *not* what the phone branches on. The phone branches on
 * `capabilities`, which the adapter computes from whatever the installed host
 * actually offers.
 */
export interface StatusReport {
  /** The version this reply is written in, and the one to use from now on. */
  readonly protocolVersion: number
  /** Everything this adapter can speak, so the client can pick for itself. */
  readonly supportedProtocols: readonly number[]
  readonly adapterVersion: string
  readonly hostVersion: string
  readonly computerId: string
  readonly computerName: string
  readonly capabilities: readonly Operation[]
  readonly grantedScopes: readonly Scope[]
}

/** A command, as it arrives. */
export interface CommandEnvelope {
  readonly protocolVersion: number
  readonly computerId: string
  /** Which paired device sent this. Required: grants are per device. */
  readonly deviceId: string
  readonly commandId: string
  readonly operation: string
  readonly payload?: unknown
}

/**
 * The values an approval decision may carry.
 *
 * A closed union rather than a boolean, because `false` has to mean "deny" and
 * there is no safe default for a missing field. An unrecognised value is denied
 * — never allowed. The plan is unambiguous that an approval nobody could
 * interpret must not become a permission.
 */
export const APPROVAL_DECISIONS = ['allow-once', 'deny'] as const
export type ApprovalDecision = (typeof APPROVAL_DECISIONS)[number]

export function isApprovalDecision(value: unknown): value is ApprovalDecision {
  return typeof value === 'string' && (APPROVAL_DECISIONS as readonly string[]).includes(value)
}

/**
 * Read a decision out of an approval payload.
 *
 * @returns the decision, or `undefined` when it is missing or unrecognised.
 *   The caller must treat `undefined` as a refusal and say why — never as
 *   "allow", and never as "no answer, carry on".
 *
 * This checks the *answer*. Whether the *question* refers to a real pending
 * approval is a separate check that needs the approval owner, and is not
 * implemented — see {@link REFUSAL.UNKNOWN_APPROVAL_TYPE}.
 */
export function parseApprovalDecision(payload: unknown): ApprovalDecision | undefined {
  if (payload === null || typeof payload !== 'object') return undefined
  const value = (payload as { decision?: unknown }).decision
  return isApprovalDecision(value) ? value : undefined
}
