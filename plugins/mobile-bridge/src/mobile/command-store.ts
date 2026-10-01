/**
 * Idempotency for write commands.
 *
 * ## The problem it solves
 *
 * A phone sends "submit this task", the network drops the reply, and the phone
 * retries. Without a record on the computer, the task runs twice. With one, the
 * second request returns the first one's answer.
 *
 * ## What it does not claim
 *
 * Exactly-once delivery is not achievable here and this file does not pretend
 * otherwise. Three crash windows exist between receiving a command and knowing
 * its outcome, and in each of them the honest answer is *unknown*:
 *
 *   - after registering, before calling the host
 *   - after calling the host, before it answers
 *   - after the host answers, before the result is recorded
 *
 * In all three the store holds a `running` record with no result. A retry gets
 * `COMMAND_STATE_UNKNOWN` rather than a replay, because replaying could run the
 * task twice and forgetting could make a task that did run look like it did not.
 *
 * @module mobile/command-store
 */

/**
 * How long a *finished* command's outcome is remembered.
 *
 * Long enough to cover a phone that was offline overnight and comes back with a
 * retry; short enough that the table does not grow without bound on a computer
 * that has been running for months.
 *
 * Applies to completed records only. See {@link CommandStore.begin} for why
 * unfinished ones are treated differently.
 */
export const DEFAULT_RETENTION_MS = 24 * 60 * 60 * 1000

/**
 * What makes two commands the same command.
 *
 * All five fields, not just the id. An earlier version keyed on `commandId`
 * alone and compared a payload hash, which meant that reusing one id for
 * `task.submit` and then `task.cancel` with the same body replayed the submit
 * result and never ran the cancel — two different requests treated as one.
 *
 * The device and computer are part of the identity so that a command id
 * generated on one phone, or addressed to one computer, can never collide with
 * another's. Ids come from clients and clients do not coordinate.
 */
export interface CommandIdentity {
  readonly computerId: string
  readonly deviceId: string
  readonly commandId: string
  readonly operation: string
  readonly payload: unknown
}

/**
 * A stable, injective encoding of a request body.
 *
 * ## Why this is a string and not a hash
 *
 * It used to be FNV-1a 32-bit, and 32 bits is not enough to tell two payloads
 * apart. The review found a concrete pair — `{text:'693xre1n2a0zo'}` and
 * `{text:'1i6ts9fmr0ouo'}` — that both hash to `3b7a2174`. The second request
 * was then replayed as the first, which is the exact failure the dedup table
 * exists to prevent, arriving through the table itself.
 *
 * A stronger digest would work too, but comparing the canonical form directly is
 * both simpler and correct by construction: equal strings mean equal requests,
 * with no collision probability to reason about. Command bodies are small.
 *
 * Key order is normalised so a client that serialises differently is not told it
 * sent something new.
 */
export function canonicalPayload(payload: unknown): string {
  const canonical = (value: unknown): string => {
    if (value === null) return 'null'
    if (value === undefined) return 'undefined'
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
    if (typeof value === 'object') {
      const entries = Object.entries(value as Record<string, unknown>)
        .filter(([, v]) => v !== undefined)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(',')}}`
    }
    if (typeof value === 'string') return JSON.stringify(value)
    return String(value)
  }
  return canonical(payload)
}

/**
 * The full identity of a request, as one comparable string.
 *
 * JSON-encoded rather than joined with a separator. Joining looks safe and is
 * not: a separator can appear *inside* a field, so `{computerId:'pc',
 * deviceId:'a\u0000b'}` and `{computerId:'pc\u0000a', deviceId:'b'}` produce
 * the same string. A test written for exactly that caught it here. JSON escapes
 * the delimiter, which makes the encoding injective by construction rather than
 * by hoping clients never send the character.
 */
export function fingerprint(identity: CommandIdentity): string {
  return JSON.stringify([
    identity.computerId,
    identity.deviceId,
    identity.commandId,
    identity.operation,
    canonicalPayload(identity.payload),
  ])
}

export interface CompletedRecord {
  readonly kind: 'completed'
  readonly fingerprint: string
  readonly generation: number
  readonly result: unknown
  readonly at: number
}

export interface RunningRecord {
  readonly kind: 'running'
  readonly fingerprint: string
  readonly generation: number
  readonly at: number
}

type CommandRecord = CompletedRecord | RunningRecord

/**
 * A claim on a command id.
 *
 * Returned only for `new`. It must be handed back to {@link CommandStore.complete}
 * so that a completion arriving late cannot be written into a record that
 * belongs to a different request.
 */
export interface CommandToken {
  readonly key: string
  readonly generation: number
}

export type BeginOutcome =
  /** First time this request has been seen. The caller does the work. */
  | { readonly kind: 'new'; readonly token: CommandToken }
  /** Seen before and finished; hand back the original answer. */
  | { readonly kind: 'replay'; readonly result: unknown }
  /** Seen before and still unresolved, or its outcome was lost. */
  | { readonly kind: 'unknown' }
  /** The id was reused for a different request. */
  | { readonly kind: 'conflict' }

/**
 * The dedup table.
 *
 * In memory on purpose for this increment. The plan requires the record to
 * survive a plugin restart before anything claims durability, and an in-memory
 * table that silently forgets on restart would be worse than one that admits it
 * does — hence {@link CommandStore.isDurable}.
 */
export class CommandStore {
  readonly #records = new Map<string, CommandRecord>()
  readonly #retentionMs: number
  readonly #now: () => number
  #generation = 0

  constructor(options: { retentionMs?: number; now?: () => number } = {}) {
    this.#retentionMs = options.retentionMs ?? DEFAULT_RETENTION_MS
    this.#now = options.now ?? (() => Date.now())
  }

  /** False until the table is backed by storage that survives a restart. */
  get isDurable(): boolean {
    return false
  }

  static #key(identity: CommandIdentity): string {
    // Keyed by the addressable part only. The operation and body go into the
    // fingerprint, so reusing an id for a different request finds the existing
    // record and reports a conflict instead of starting a second one.
    //
    // JSON for the same reason as the fingerprint: a separator can be typed
    // into a device id, and two devices sharing a record would mean one phone
    // replaying another's result.
    return JSON.stringify([identity.computerId, identity.deviceId, identity.commandId])
  }

  /**
   * Claim a request, or find out what happened to it before.
   *
   * A caller that receives `new` **must** eventually call {@link complete}; a
   * record left running blocks every later retry with `unknown`, which is the
   * intended and safe outcome but a poor permanent state.
   */
  begin(identity: CommandIdentity): BeginOutcome {
    this.#evictExpiredCompleted()

    const key = CommandStore.#key(identity)
    const print = fingerprint(identity)
    const existing = this.#records.get(key)

    if (existing === undefined) {
      const generation = ++this.#generation
      this.#records.set(key, { kind: 'running', fingerprint: print, generation, at: this.#now() })
      return { kind: 'new', token: { key, generation } }
    }

    // Same id, different request. Returning the old result would answer a
    // question nobody asked; running it would act on content the id does not
    // name. Neither is safe, so it is refused and the client is told why.
    if (existing.fingerprint !== print) return { kind: 'conflict' }

    if (existing.kind === 'completed') return { kind: 'replay', result: existing.result }
    return { kind: 'unknown' }
  }

  /**
   * Record an outcome so a retry replays it instead of repeating the work.
   *
   * @returns `true` when the result was recorded, `false` when the token no
   *   longer names the current record.
   *
   * The token check is the second half of the expiry fix. Without it, a command
   * that started long ago and finished late would write its result into whatever
   * record currently holds that id — so a retry of a *different* request would
   * be answered with a result that was never its own.
   */
  complete(token: CommandToken, result: unknown): boolean {
    const existing = this.#records.get(token.key)
    if (existing === undefined) return false
    if (existing.generation !== token.generation) return false

    this.#records.set(token.key, {
      kind: 'completed',
      fingerprint: existing.fingerprint,
      generation: existing.generation,
      result,
      at: this.#now(),
    })
    return true
  }

  /**
   * Drop a record whose work never reached the host.
   *
   * Only for the case where the caller knows nothing was attempted — a preflight
   * rejection, a validation failure. A command that may have reached the host
   * must not be abandoned, because forgetting it is exactly what would let a
   * retry run it twice.
   */
  abandon(token: CommandToken): void {
    const existing = this.#records.get(token.key)
    if (existing?.kind === 'running' && existing.generation === token.generation) {
      this.#records.delete(token.key)
    }
  }

  /** Number of live records. For diagnostics and tests. */
  get size(): number {
    this.#evictExpiredCompleted()
    return this.#records.size
  }

  /**
   * Expire finished records only.
   *
   * An earlier version expired everything, which meant a command whose outcome
   * was never recorded silently became "new" again after the window and would
   * run a second time. A record with no outcome is not stale — it is unresolved,
   * and the number of those is bounded by how many commands were ever in flight.
   */
  #evictExpiredCompleted(): void {
    const cutoff = this.#now() - this.#retentionMs
    for (const [key, record] of this.#records) {
      if (record.kind === 'completed' && record.at < cutoff) this.#records.delete(key)
    }
  }
}
