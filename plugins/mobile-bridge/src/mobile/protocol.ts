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
  /** The operation belongs to a different computer. */
  WRONG_COMPUTER: 'WRONG_COMPUTER',
  /** Same commandId, different payload. */
  COMMAND_CONFLICT: 'COMMAND_CONFLICT',
  /** The command was registered but its outcome is not knowable. */
  COMMAND_STATE_UNKNOWN: 'COMMAND_STATE_UNKNOWN',
  /** An approval whose type this adapter does not recognise. */
  UNKNOWN_APPROVAL_TYPE: 'UNKNOWN_APPROVAL_TYPE',
} as const

export type RefusalCode = (typeof REFUSAL)[keyof typeof REFUSAL]

export type Availability =
  | { readonly ok: true; readonly scope: Scope }
  | {
      readonly ok: false
      readonly code: RefusalCode
      readonly message: string
    }

/** What the adapter can serve right now, and what this device may use. */
export interface AdapterContext {
  /** Protocol versions this adapter implements. */
  readonly supportedProtocols: readonly number[]
  /** Operations this build can actually execute against the installed host. */
  readonly capabilities: readonly Operation[]
  /** Scopes the paired device holds. */
  readonly grantedScopes: readonly Scope[]
  /** The computer this adapter serves; a command for another one is refused. */
  readonly computerId: string
}

export function isOperation(value: unknown): value is Operation {
  return typeof value === 'string' && (OPERATIONS as readonly string[]).includes(value)
}

export function isScope(value: unknown): value is Scope {
  return typeof value === 'string' && (SCOPES as readonly string[]).includes(value)
}

/**
 * Whether an operation may run, given the protocol, the build and the device.
 *
 * Three independent conditions, all required:
 *
 * 1. **Protocol** — the client and this adapter agree on the envelope.
 * 2. **Capability** — this build can actually do it against the installed host.
 *    A newer adapter on an older Harness implements fewer operations, and must
 *    say so rather than failing at call time.
 * 3. **Permission** — the device holds the scope.
 *
 * The order matters because the messages differ. "Your app is too new" and "this
 * computer has not been updated" and "you were not granted this" have three
 * different fixes, and a client that cannot tell them apart sends the user to
 * the wrong one.
 *
 * Note what this is not: hiding the button in the phone's UI. The plan is
 * explicit that a hidden control is not access control. This runs on the
 * computer, per request, every time.
 */
export function availability(
  request: { readonly protocolVersion: unknown; readonly operation: unknown; readonly computerId?: unknown },
  context: AdapterContext,
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

  if (!isOperation(request.operation)) {
    return {
      ok: false,
      code: REFUSAL.UNKNOWN_OPERATION,
      message: `"${String(request.operation)}" is not an operation this computer knows.`,
    }
  }

  // A command naming another computer is refused even when everything else
  // checks out. With more than one computer paired this is how a stale
  // notification or a mixed-up draft would otherwise act on the wrong machine.
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

  const scope = OPERATION_SCOPE[operation]
  if (!context.grantedScopes.includes(scope)) {
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
  readonly protocolVersion: number
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
  readonly commandId: string
  readonly operation: string
  readonly payload?: unknown
}

/**
 * The one field an approval decision may carry.
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
 */
export function parseApprovalDecision(payload: unknown): ApprovalDecision | undefined {
  if (payload === null || typeof payload !== 'object') return undefined
  const value = (payload as { decision?: unknown }).decision
  return isApprovalDecision(value) ? value : undefined
}
