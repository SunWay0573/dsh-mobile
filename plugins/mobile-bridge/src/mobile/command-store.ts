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
 * task twice and refusing could leave a task that did run looking like it did
 * not. The phone is told to go and look at the task list, which is the only
 * place the truth is available.
 *
 * @module mobile/command-store
 */

/**
 * How long a command's outcome is remembered.
 *
 * Long enough to cover a phone that was offline overnight and comes back with a
 * retry; short enough that the table does not grow without bound on a computer
 * that has been running for months.
 */
export const DEFAULT_RETENTION_MS = 24 * 60 * 60 * 1000

export interface CompletedRecord {
  readonly kind: 'completed'
  /** Hash of the request payload, so a reused id with new content is caught. */
  readonly payloadHash: string
  readonly result: unknown
  readonly at: number
}

export interface RunningRecord {
  readonly kind: 'running'
  readonly payloadHash: string
  readonly at: number
}

type CommandRecord = CompletedRecord | RunningRecord

export type BeginOutcome =
  /** First time this command id has been seen. The caller does the work. */
  | { readonly kind: 'new' }
  /** Seen before and finished; hand back the original answer. */
  | { readonly kind: 'replay'; readonly result: unknown }
  /** Seen before and still running, or its outcome was lost. */
  | { readonly kind: 'unknown' }
  /** The id was reused with a different payload. */
  | { readonly kind: 'conflict' }

/**
 * A stable hash of a request payload.
 *
 * Key order is normalised, because `{a:1,b:2}` and `{b:2,a:1}` are the same
 * request and a client that serialises differently must not be told it sent
 * something new. Not a cryptographic hash: this detects accidental reuse, and
 * an attacker who can choose the payload already holds the device credential.
 */
export function hashPayload(payload: unknown): string {
  const canonical = (value: unknown): string => {
    if (value === null) return 'null'
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
    if (typeof value === 'object') {
      const entries = Object.entries(value as Record<string, unknown>)
        .filter(([, v]) => v !== undefined)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(',')}}`
    }
    return JSON.stringify(value)
  }
  // FNV-1a. Short, stable, and no dependency.
  const text = canonical(payload)
  let hash = 0x811c9dc5
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash.toString(16).padStart(8, '0')
}

/**
 * The dedup table.
 *
 * In memory on purpose for this increment. The plan requires the record to
 * survive a plugin restart before the next one claims anything about
 * durability, and an in-memory table that silently forgets on restart would be
 * worse than one that admits it does — hence {@link CommandStore.isDurable}.
 */
export class CommandStore {
  readonly #records = new Map<string, CommandRecord>()
  readonly #retentionMs: number
  readonly #now: () => number

  constructor(options: { retentionMs?: number; now?: () => number } = {}) {
    this.#retentionMs = options.retentionMs ?? DEFAULT_RETENTION_MS
    this.#now = options.now ?? (() => Date.now())
  }

  /** False until the table is backed by storage that survives a restart. */
  get isDurable(): boolean {
    return false
  }

  /**
   * Claim a command id, or find out what happened to it before.
   *
   * A caller that receives `new` **must** eventually call {@link complete} or
   * {@link abandon}; a record left running blocks every later retry of that id
   * with `unknown`, which is the intended and safe outcome but a poor permanent
   * state.
   */
  begin(commandId: string, payload: unknown): BeginOutcome {
    this.#evictExpired()
    const hash = hashPayload(payload)
    const existing = this.#records.get(commandId)

    if (existing === undefined) {
      this.#records.set(commandId, { kind: 'running', payloadHash: hash, at: this.#now() })
      return { kind: 'new' }
    }

    // Same id, different content. Returning the old result would answer a
    // question nobody asked; running it would act on content the id does not
    // name. Neither is safe, so it is refused and the client is told why.
    if (existing.payloadHash !== hash) return { kind: 'conflict' }

    if (existing.kind === 'completed') return { kind: 'replay', result: existing.result }
    return { kind: 'unknown' }
  }

  /** Record an outcome so a retry replays it instead of repeating the work. */
  complete(commandId: string, result: unknown): void {
    const existing = this.#records.get(commandId)
    if (existing === undefined) return
    this.#records.set(commandId, {
      kind: 'completed',
      payloadHash: existing.payloadHash,
      result,
      at: this.#now(),
    })
  }

  /**
   * Drop a record whose work never reached the host.
   *
   * Only for the case where the caller knows nothing was attempted — a preflight
   * rejection, a validation failure. A command that may have reached the host
   * must not be abandoned, because forgetting it is exactly what would let a
   * retry run it twice.
   */
  abandon(commandId: string): void {
    const existing = this.#records.get(commandId)
    if (existing?.kind === 'running') this.#records.delete(commandId)
  }

  /** Number of live records. For diagnostics and tests. */
  get size(): number {
    this.#evictExpired()
    return this.#records.size
  }

  #evictExpired(): void {
    const cutoff = this.#now() - this.#retentionMs
    for (const [id, record] of this.#records) {
      if (record.at < cutoff) this.#records.delete(id)
    }
  }
}
