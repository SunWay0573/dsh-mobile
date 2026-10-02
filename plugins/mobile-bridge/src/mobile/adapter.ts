/**
 * The host-side adapter: the only place that knows both vocabularies.
 *
 * A phone asks in Mobile terms; this translates to whatever the installed
 * Harness actually offers. Version differences are absorbed here so that one
 * phone can serve two computers running different Harness builds without
 * carrying a branch per version — which matters because the phone cannot know
 * how many computers it will be paired with.
 *
 * ## Capabilities come from handlers, not from a version number
 *
 * {@link MobileAdapter.status} reports an operation as available exactly when a
 * handler is registered for it. A version table would be a promise about
 * behaviour that drifts the moment the host changes; the set of handlers is
 * what the build can actually do.
 *
 * Registering a handler means the *method* exists, not that its semantics match
 * the installed host. When this is wired to a real DSH, only operations whose
 * behaviour has been verified against that host should have handlers.
 *
 * ## Permissions are read per request, never snapshotted
 *
 * An earlier version copied the device's scopes at construction. The review
 * reproduced the consequence: `session.list` succeeded, the grant was then
 * emptied, and the same adapter accepted a second request and ran the handler
 * again. A grant revoked a minute ago is not a grant, so there is nothing here
 * to go stale.
 *
 * @module mobile/adapter
 */

import {
  APPROVAL_DECISIONS,
  OPERATIONS,
  PROTOCOL_VERSION,
  REFUSAL,
  SCOPES,
  availability,
  parseApprovalDecision,
  type AdapterContext,
  type CommandEnvelope,
  type DeviceRecord,
  type Operation,
  type Scope,
  type StatusReport,
} from './protocol.ts'
import { CommandStore, type CommandToken } from './command-store.ts'

/**
 * Operations that change something.
 *
 * These go through the dedup table. Reads do not: repeating a read is harmless,
 * and putting reads through it would make a legitimate second look at a growing
 * task look like a duplicate.
 */
const WRITE_OPERATIONS: ReadonlySet<Operation> = new Set<Operation>([
  'task.submit',
  'task.cancel',
  'approval.decide',
  'question.answer',
])

/**
 * Operations this build always refuses, whatever handlers are registered.
 *
 * ## Why this exists
 *
 * Capabilities are derived from which handlers are registered, on the reasoning
 * that the set of handlers is what the build can actually do. That reasoning
 * breaks here: `approval.decide` has a handler and is still refused on every
 * request, because verifying *which* approval a decision answers needs an
 * authoritative record of pending approvals and there is no approval owner yet.
 *
 * The review's consequence: a stub with an approval handler made `status`
 * advertise `approval.decide`, while a well-formed decision was still refused.
 * A phone that trusts the handshake would show an approval control that cannot
 * work. Fail-closed was right; advertising it was not.
 *
 * So the rule is written down: **an operation is available only if the build can
 * complete it**, and an operation that is deliberately unimplemented is listed
 * here rather than left to be inferred from the absence of a handler.
 */
const NOT_IMPLEMENTED: ReadonlySet<Operation> = new Set<Operation>([
  // Needs the approval owner: an authoritative pending record, with ownership
  // and expiry, so a decision can be matched to one request and consumed once.
  'approval.decide',
])

/** What a handler is given beyond the payload. */
export interface HandlerContext {
  /** The scope the device was checked against, for the handler's own logging. */
  readonly scope: string
  readonly computerId: string
  readonly deviceId: string
}

export type OperationHandler = (payload: unknown, context: HandlerContext) => Promise<unknown>

export interface AdapterOptions {
  readonly computerId: string
  readonly computerName: string
  readonly adapterVersion: string
  readonly hostVersion: string
  /** Handlers this build can actually serve. Their keys are the capabilities. */
  readonly handlers: Partial<Record<Operation, OperationHandler>>
  /**
   * Look up a paired device as it is *now*.
   *
   * Called on every request. Returning `undefined` means the device is unknown
   * or was removed, and the request is refused. Implementations must not cache:
   * a stale answer here is the whole bug this replaced.
   */
  readonly resolveDevice: (deviceId: string) => DeviceRecord | undefined
  readonly store?: CommandStore
  /** Protocol versions this adapter accepts. Defaults to the one it speaks. */
  readonly supportedProtocols?: readonly number[]
}

export type CommandOutcome =
  | { readonly status: 'accepted'; readonly commandId: string; readonly result: unknown }
  | { readonly status: 'replayed'; readonly commandId: string; readonly result: unknown }
  | {
      readonly status: 'rejected'
      readonly commandId: string
      readonly code: string
      readonly message: string
    }

export class MobileAdapter {
  readonly #options: AdapterOptions
  readonly #store: CommandStore
  /** Advertised in the handshake: what a phone may offer. */
  readonly #capabilities: readonly Operation[]

  /**
   * What a request may reach: every registered handler.
   *
   * Deliberately a different set from `#capabilities`. An operation can be
   * reachable-but-not-offered — `approval.decide` is exactly that today — and
   * collapsing the two loses either the honesty of the handshake or the
   * accuracy of the refusal. If it were only the advertised set, a request for
   * an unimplemented operation would come back "update the plugin", which would
   * not help.
   */
  readonly #reachable: readonly Operation[]
  readonly #protocols: readonly number[]

  constructor(options: AdapterOptions) {
    this.#options = options
    this.#store = options.store ?? new CommandStore()
    this.#protocols = options.supportedProtocols ?? [PROTOCOL_VERSION]
    // Iterating OPERATIONS rather than Object.keys keeps the reported order
    // stable, which makes the handshake response diffable between builds.
    // Registered handlers minus the deliberately unimplemented ones. Both
    // halves matter: the handler set says what the code can reach, and
    // NOT_IMPLEMENTED says what it must not offer yet.
    this.#reachable = OPERATIONS.filter(
      (operation) => options.handlers[operation] !== undefined,
    )
    this.#capabilities = this.#reachable.filter((operation) => !NOT_IMPLEMENTED.has(operation))
  }

  /** The dedup table, exposed so a durable one can be substituted later. */
  get store(): CommandStore {
    return this.#store
  }

  #context(): AdapterContext {
    return {
      supportedProtocols: this.#protocols,
      // The reachable set, not the advertised one. See `#reachable`.
      capabilities: this.#reachable,
      computerId: this.#options.computerId,
    }
  }

  /**
   * What the phone may do, and what it is talking to.
   *
   * The phone caches this and uses it to decide what to show. It is not a
   * permission: every command is checked again on arrival, because a phone that
   * was authorised an hour ago may have been revoked since, and because a client
   * is not a trustworthy narrator of its own rights.
   *
   * @param deviceId who is asking. The reported scopes belong to that device — a
   *   handshake that reported the computer's grants rather than this phone's
   *   would have every phone showing every permission.
   */
  status(deviceId: string): StatusReport {
    const device = this.#options.resolveDevice(deviceId)
    const granted = (device?.authorized === true ? device.grantedScopes : [])
      .filter((scope): scope is Scope => (SCOPES as readonly string[]).includes(scope))

    return {
      // The version this reply is written in. A client sharing none of
      // `supportedProtocols` cannot read it, and says so rather than guessing.
      protocolVersion: PROTOCOL_VERSION,
      supportedProtocols: [...this.#protocols],
      adapterVersion: this.#options.adapterVersion,
      hostVersion: this.#options.hostVersion,
      computerId: this.#options.computerId,
      computerName: this.#options.computerName,
      capabilities: this.#capabilities,
      grantedScopes: granted,
    }
  }

  /**
   * Handle one command.
   *
   * Never throws: a refusal is a result, and a caller that has to catch
   * exceptions to find out it was not allowed will eventually forget to.
   */
  async handle(envelope: CommandEnvelope): Promise<CommandOutcome> {
    const commandId = typeof envelope?.commandId === 'string' ? envelope.commandId : ''
    const deviceId = typeof envelope?.deviceId === 'string' ? envelope.deviceId : ''

    // Resolved once per request and not held across requests: the next one gets
    // a fresh answer, which is what makes a mid-session revocation take effect.
    const device = deviceId === '' ? undefined : this.#options.resolveDevice(deviceId)

    const allowed = availability(
      {
        protocolVersion: envelope?.protocolVersion,
        operation: envelope?.operation,
        computerId: envelope?.computerId,
        deviceId: envelope?.deviceId,
        payload: envelope?.payload,
      },
      this.#context(),
      device,
    )
    if (!allowed.ok) {
      return { status: 'rejected', commandId, code: allowed.code, message: allowed.message }
    }

    const operation = envelope.operation as Operation

    // The *answer* could not be read. Distinct from an unrecognised *question*,
    // which needs the approval owner — see the next branch.
    if (operation === 'approval.decide' && parseApprovalDecision(envelope.payload) === undefined) {
      return {
        status: 'rejected',
        commandId,
        code: REFUSAL.MALFORMED_APPROVAL_DECISION,
        message:
          'This approval decision could not be read, so it was not applied. ' +
          `Expected one of: ${APPROVAL_DECISIONS.join(', ')}.`,
      }
    }

    // Whether the approval being answered exists, belongs to this device and
    // this computer, and is still pending is a separate question that needs an
    // authoritative record of pending approvals. There is no approval owner yet,
    // so this refuses rather than assuming the request is real: an approval
    // nobody can place must not be actionable.
    //
    // Unreachable while NOT_IMPLEMENTED excludes this operation from
    // capabilities — kept because the two are separate statements and a future
    // change to one should not silently open the other.
    // Refused with its own reason rather than the generic "missing capability".
    // That message says to update the plugin, and updating changes nothing here:
    // the owner does not exist in any released version either. Sending someone
    // to update software that will not help is the same mistake as reporting a
    // revoked grant as a version problem.
    if (operation === 'approval.decide') {
      return {
        status: 'rejected',
        commandId,
        code: REFUSAL.UNKNOWN_APPROVAL_TYPE,
        message:
          'This computer cannot yet verify which approval this answers, so it was not applied. ' +
          'Handle the request on the computer.',
      }
    }

    const handler = this.#options.handlers[operation]
    if (handler === undefined) {
      return {
        status: 'rejected',
        commandId,
        code: REFUSAL.MISSING_CAPABILITY,
        message: `This computer cannot do "${operation}".`,
      }
    }

    const isWrite = WRITE_OPERATIONS.has(operation)

    if (isWrite && commandId === '') {
      return {
        status: 'rejected',
        commandId,
        code: REFUSAL.INVALID_PAYLOAD,
        message: 'A command that changes something needs a commandId so a retry can be recognised.',
      }
    }

    let token: CommandToken | undefined
    if (isWrite) {
      const begun = this.#store.begin({
        computerId: envelope.computerId,
        deviceId,
        commandId,
        operation,
        payload: envelope.payload,
      })

      if (begun.kind === 'conflict') {
        return {
          status: 'rejected',
          commandId,
          code: REFUSAL.COMMAND_CONFLICT,
          message:
            'This command id was already used for a different request. ' +
            'Retrying with a new id is safe; reusing this one is not.',
        }
      }
      if (begun.kind === 'replay') {
        return { status: 'replayed', commandId, result: begun.result }
      }
      if (begun.kind === 'unknown') {
        return {
          status: 'rejected',
          commandId,
          code: REFUSAL.COMMAND_STATE_UNKNOWN,
          message:
            'This command was received but its outcome is not known — it may or may not have run. ' +
            'Check the task list before deciding whether to send it again.',
        }
      }
      token = begun.token
    }

    try {
      const result = await handler(envelope.payload, {
        scope: allowed.scope,
        computerId: this.#options.computerId,
        deviceId,
      })
      if (token !== undefined) this.#store.complete(token, result)
      return { status: 'accepted', commandId, result }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (isWrite) {
        // The handler ran and failed. The record stays `running` on purpose:
        // the handler may have reached the host before throwing, so this is
        // exactly the window where replaying would risk doing the work twice.
        return {
          status: 'rejected',
          commandId,
          code: REFUSAL.COMMAND_STATE_UNKNOWN,
          message: `The command failed and its outcome is not known: ${message}`,
        }
      }
      return { status: 'rejected', commandId, code: 'HANDLER_FAILED', message }
    }
  }
}
