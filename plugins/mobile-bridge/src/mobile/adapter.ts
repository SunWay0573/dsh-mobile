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
 * what the build can actually do. When a real transport lands, the handlers are
 * the DSH calls — and an operation whose host support is missing simply has no
 * handler, so the phone is told it is unavailable rather than discovering it
 * through a failure.
 *
 * @module mobile/adapter
 */

import {
  APPROVAL_DECISIONS,
  OPERATIONS,
  SCOPES,
  PROTOCOL_VERSION,
  REFUSAL,
  availability,
  parseApprovalDecision,
  type AdapterContext,
  type CommandEnvelope,
  type Operation,
  type Scope,
  type StatusReport,
} from './protocol.ts'
import { CommandStore } from './command-store.ts'

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

/** What a handler is given beyond the payload. */
export interface HandlerContext {
  /** The scope the device was checked against, for the handler's own logging. */
  readonly scope: string
  readonly computerId: string
}

export type OperationHandler = (payload: unknown, context: HandlerContext) => Promise<unknown>

export interface AdapterOptions {
  readonly computerId: string
  readonly computerName: string
  readonly adapterVersion: string
  readonly hostVersion: string
  /** Handlers this build can actually serve. Their keys are the capabilities. */
  readonly handlers: Partial<Record<Operation, OperationHandler>>
  readonly grantedScopes: readonly Scope[]
  readonly store?: CommandStore
  /** Protocol versions this adapter accepts. Defaults to just the current one. */
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
  readonly #capabilities: readonly Operation[]
  readonly #protocols: readonly number[]
  readonly #scopes: readonly Scope[]

  constructor(options: AdapterOptions) {
    this.#options = options
    this.#store = options.store ?? new CommandStore()
    this.#protocols = options.supportedProtocols ?? [PROTOCOL_VERSION]
    // Iterating OPERATIONS rather than Object.keys keeps the reported order
    // stable, which makes the handshake response diffable between builds.
    this.#capabilities = OPERATIONS.filter(
      (operation) => options.handlers[operation] !== undefined,
    )
    // Intersected with what this adapter understands. A device record written
    // by a newer plugin may name a scope this build has never heard of, and
    // echoing it back would have the phone showing a permission that cannot be
    // exercised — and, worse, implying it had been checked.
    this.#scopes = options.grantedScopes.filter((scope): scope is Scope =>
      (SCOPES as readonly string[]).includes(scope))
  }

  /** The dedup table, exposed so a durable one can be substituted later. */
  get store(): CommandStore {
    return this.#store
  }

  #context(): AdapterContext {
    return {
      supportedProtocols: this.#protocols,
      capabilities: this.#capabilities,
      grantedScopes: this.#scopes,
      computerId: this.#options.computerId,
    }
  }

  /**
   * What the phone may do, and what it is talking to.
   *
   * The phone caches this and uses it to decide what to show. It is not a
   * permission: every command is checked again on arrival, because a phone that
   * was authorised an hour ago may have been revoked since, and because a
   * client is not a trustworthy narrator of its own rights.
   */
  status(): StatusReport {
    return {
      protocolVersion: PROTOCOL_VERSION,
      adapterVersion: this.#options.adapterVersion,
      hostVersion: this.#options.hostVersion,
      computerId: this.#options.computerId,
      computerName: this.#options.computerName,
      capabilities: this.#capabilities,
      grantedScopes: this.#scopes,
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

    const allowed = availability(
      {
        protocolVersion: envelope?.protocolVersion,
        operation: envelope?.operation,
        computerId: envelope?.computerId,
      },
      this.#context(),
    )
    if (!allowed.ok) {
      return { status: 'rejected', commandId, code: allowed.code, message: allowed.message }
    }

    const operation = envelope.operation as Operation

    // An approval whose decision cannot be read is refused, and this check runs
    // before anything else touches it. The default for an uninterpretable
    // approval must be "no": a request nobody could parse is not consent.
    if (operation === 'approval.decide' && parseApprovalDecision(envelope.payload) === undefined) {
      return {
        status: 'rejected',
        commandId,
        code: REFUSAL.UNKNOWN_APPROVAL_TYPE,
        message:
          'This approval decision could not be read, so it was not applied. ' +
          `Expected one of: ${APPROVAL_DECISIONS.join(', ')}.`,
      }
    }

    const handler = this.#options.handlers[operation]
    // Unreachable while `capabilities` is derived from `handlers`, and kept
    // because the two are computed separately and a future refactor could
    // decouple them.
    if (handler === undefined) {
      return {
        status: 'rejected',
        commandId,
        code: REFUSAL.MISSING_CAPABILITY,
        message: `This computer cannot do "${operation}".`,
      }
    }

    const isWrite = WRITE_OPERATIONS.has(operation)

    if (isWrite) {
      if (commandId === '') {
        return {
          status: 'rejected',
          commandId,
          code: REFUSAL.UNKNOWN_OPERATION,
          message: 'A command that changes something needs a commandId so a retry can be recognised.',
        }
      }
      const begun = this.#store.begin(commandId, envelope.payload)
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
    }

    try {
      const result = await handler(envelope.payload, {
        scope: allowed.scope,
        computerId: this.#options.computerId,
      })
      if (isWrite) this.#store.complete(commandId, result)
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
