/**
 * What is worth waking someone up for, and what the message says.
 *
 * Kept separate from both the transport (`notify.ts`) and the wiring
 * (`index.ts`) so the wording and the routing decisions can be tested by
 * reading a returned value, with no HTTP and no Cordis context.
 *
 * @module dsh-mobile-mobile-bridge/events
 */

import type { NotifyMessage } from './notify.ts'

/** Input for an "the agent needs a decision" notification. */
export interface ApprovalNoticeInput {
  /** Tool awaiting a decision, e.g. `bash`. */
  toolName: string
  /** The asker's own explanation, when it supplied one. */
  reason?: string | undefined
  /** Session to deep-link back into. */
  sessionId?: string | undefined
  /** Host base URL, e.g. `https://machine.tailnet.ts.net`. */
  baseUrl?: string | undefined
}

/** Input for a "work finished" notification. */
export interface TurnFinishedInput {
  /** Session that finished. */
  sessionId?: string | undefined
  /** Host base URL. */
  baseUrl?: string | undefined
  /** Short label for the session, when one is known. */
  label?: string | undefined
}

/**
 * Build a deep link back into a session.
 *
 * Returns undefined when either half is missing rather than producing a link
 * that opens the app at a useless place — a notification that navigates
 * nowhere is worse than one that does not pretend to.
 */
export function sessionUrl(
  baseUrl: string | undefined,
  sessionId: string | undefined,
): string | undefined {
  if (baseUrl === undefined || baseUrl === '' || sessionId === undefined || sessionId === '') {
    return undefined
  }
  const trimmed = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return `${trimmed}/#/session/${encodeURIComponent(sessionId)}`
}

/**
 * The notification that matters most.
 *
 * An approval request with no client attached is answered "unavailable" and
 * the tool call is denied — silently, from the user's point of view. Urgent
 * priority is deliberate: this is the one message that, if missed, means the
 * agent quietly did less than it was asked to.
 */
export function approvalNeededMessage(input: ApprovalNoticeInput): NotifyMessage {
  const reason = input.reason?.trim()
  const body = reason !== undefined && reason !== ''
    ? reason
    : `The agent wants to run "${input.toolName}" and is waiting for you.`

  const message: NotifyMessage = {
    title: 'DSH needs your approval',
    body,
    priority: 'urgent',
    tags: ['warning'],
  }
  const click = sessionUrl(input.baseUrl, input.sessionId)
  if (click !== undefined) message.click = click
  return message
}

/**
 * The "I finished" notification.
 *
 * Default priority, not urgent: this is informational, and a phone that buzzes
 * loudly for every completed turn gets muted — which would defeat the approval
 * notification too.
 */
export function turnFinishedMessage(input: TurnFinishedInput): NotifyMessage {
  const message: NotifyMessage = {
    title: 'DSH finished a turn',
    body: input.label !== undefined && input.label !== ''
      ? input.label
      : 'A task completed on your machine.',
    priority: 'default',
    tags: ['white_check_mark'],
  }
  const click = sessionUrl(input.baseUrl, input.sessionId)
  if (click !== undefined) message.click = click
  return message
}

/**
 * Suppresses repeats so a noisy agent cannot flood the phone.
 *
 * A rate limit rather than deduplication: two genuinely different approvals a
 * second apart should both arrive, but the same storm reaching the channel
 * hundreds of times should not. The cap is per key, and the window is short
 * enough that normal use never notices it.
 */
export class RateLimiter {
  readonly #limit: number
  readonly #windowMs: number
  readonly #now: () => number
  readonly #seen = new Map<string, { count: number; resetAt: number }>()

  /**
   * @param options.limit - maximum messages per key per window.
   * @param options.windowMs - window length in milliseconds.
   * @param options.now - clock, injectable for tests.
   */
  constructor(options: { limit: number; windowMs: number; now?: () => number }) {
    if (options.limit <= 0) throw new RangeError('limit must be positive')
    if (options.windowMs <= 0) throw new RangeError('windowMs must be positive')
    this.#limit = options.limit
    this.#windowMs = options.windowMs
    this.#now = options.now ?? (() => Date.now())
  }

  /**
   * Whether a message under this key may be sent now.
   *
   * Records the attempt when it allows one, so callers should not call this
   * speculatively.
   *
   * @param key - usually the session id, or a constant for global limits.
   * @returns true when the message is within the limit.
   */
  allow(key: string): boolean {
    const now = this.#now()
    const entry = this.#seen.get(key)
    if (entry === undefined || now >= entry.resetAt) {
      this.#seen.set(key, { count: 1, resetAt: now + this.#windowMs })
      return true
    }
    if (entry.count >= this.#limit) return false
    entry.count += 1
    return true
  }

  /** Drop expired keys so a long-lived host does not grow this map forever. */
  prune(): void {
    const now = this.#now()
    for (const [key, entry] of this.#seen) {
      if (now >= entry.resetAt) this.#seen.delete(key)
    }
  }
}
