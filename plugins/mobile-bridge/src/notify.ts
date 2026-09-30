/**
 * The notification channel.
 *
 * DSH has **no outbound notification path at all**. `packages/webhook` is
 * inbound — it turns external deliveries into sessions — and nothing pushes
 * events out. That gap is not cosmetic: DSH's approval policy has no timeout
 * and fails closed, so an unattended agent whose approval request reaches
 * nobody does not wait. It silently does less than it was asked to.
 *
 * The channel target is a plain HTTP POST, shaped for
 * [ntfy](https://ntfy.sh) (open source, self-hostable, Android app, action
 * buttons) but usable with anything that accepts a POST.
 *
 * @module dsh-mobile-mobile-bridge/notify
 */

/** A message to deliver to the phone. */
export interface NotifyMessage {
  /** Short headline, shown bold. */
  title: string
  /** Body text. */
  body: string
  /**
   * URL opened when the notification is tapped. Used to deep-link straight
   * back into the session that needs attention.
   */
  click?: string
  /** ntfy priority. `urgent` bypasses some do-not-disturb settings. */
  priority?: 'min' | 'low' | 'default' | 'high' | 'urgent'
  /** ntfy tags, rendered as an emoji or short label. */
  tags?: string[]
}

/**
 * The slice of `fetch` this module uses.
 *
 * Narrowed so tests can supply a fake without constructing a real `Response`.
 */
export type FetchLike = (
  url: string,
  init: {
    method: string
    headers: Record<string, string>
    body: string
    signal?: AbortSignal
  },
) => Promise<{ ok: boolean; status: number }>

/** How long to wait for the channel before giving up on one message. */
const DEFAULT_TIMEOUT_MS = 10_000

/** Delivery channel backed by one HTTP endpoint. */
export class Notifier {
  readonly #url: string
  readonly #token: string | undefined
  readonly #fetch: FetchLike
  readonly #onWarn: (message: string) => void
  readonly #timeoutMs: number

  /**
   * @param options.url - endpoint to POST to. For ntfy, `https://ntfy.sh/<topic>`.
   * @param options.token - bearer token, for a protected topic or a
   *   self-hosted instance. Omit for a public topic.
   * @param options.fetch - injectable HTTP client.
   * @param options.onWarn - receives delivery failures.
   * @param options.timeoutMs - per-message timeout.
   */
  constructor(options: {
    url: string
    token?: string | undefined
    fetch?: FetchLike
    onWarn?: (message: string) => void
    timeoutMs?: number
  }) {
    this.#url = options.url
    this.#token = options.token
    this.#fetch = options.fetch ?? (globalThis.fetch as unknown as FetchLike)
    this.#onWarn = options.onWarn ?? (() => {})
    this.#timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  }

  /**
   * Deliver one message.
   *
   * **Never throws.** A notification is a courtesy; a failed courtesy must not
   * take down the agent turn that triggered it. Callers get a boolean for
   * logging and otherwise carry on.
   *
   * @returns true when the channel accepted the message.
   */
  async send(message: NotifyMessage): Promise<boolean> {
    const controller = new AbortController()
    const timer = setTimeout(() => { controller.abort() }, this.#timeoutMs)
    try {
      const response = await this.#fetch(this.#url, {
        method: 'POST',
        headers: buildHeaders(message, this.#token),
        // ntfy takes the message as the raw request body; the metadata rides in
        // headers. That keeps this compatible with any endpoint that reads a
        // plain text body.
        body: message.body,
        signal: controller.signal,
      })
      if (!response.ok) {
        this.#onWarn(`channel rejected the message with HTTP ${String(response.status)}`)
        return false
      }
      return true
    } catch (error) {
      this.#onWarn(`could not reach the channel: ${describe(error)}`)
      return false
    } finally {
      clearTimeout(timer)
    }
  }
}

/**
 * Build the request headers for a message.
 *
 * Exported because the header names are the part that silently breaks against
 * a self-hosted ntfy or a different endpoint, and a unit test on them is
 * cheaper than a debugging session.
 *
 * @param message - the message to encode.
 * @param token - optional bearer token.
 * @returns headers, with non-ASCII left intact for the body only.
 */
export function buildHeaders(
  message: NotifyMessage,
  token?: string,
): Record<string, string> {
  const headers: Record<string, string> = {
    'content-type': 'text/plain; charset=utf-8',
    // ntfy requires headers to be ASCII; it documents RFC 2047 encoding for
    // non-ASCII titles. Titles here are deliberately kept ASCII by callers,
    // but encode anyway so a user-supplied label cannot corrupt the request.
    'title': encodeHeaderValue(message.title),
    'priority': message.priority ?? 'default',
  }
  if (message.click !== undefined) headers['click'] = message.click
  if (message.tags !== undefined && message.tags.length > 0) {
    headers['tags'] = message.tags.join(',')
  }
  if (token !== undefined && token !== '') {
    headers['authorization'] = `Bearer ${token}`
  }
  return headers
}

/**
 * Make a value safe for an HTTP header.
 *
 * Header values must be ASCII and free of control characters. A newline in a
 * header value is a request-splitting vulnerability, so this is a correctness
 * fix rather than a nicety — the title can contain a file name or a tool name
 * that came from the model.
 */
export function encodeHeaderValue(value: string): string {
  // Strip control characters first: they are never legitimate here, and
  // encoding them would hide an injection attempt rather than defeat it.
  // Collapsing a run to one space keeps a stripped CRLF from leaving a
  // double space in the middle of a title.
  const cleaned = value.replace(/[\u0000-\u001F\u007F]+/g, ' ').trim()
  if (/^[\x20-\x7E]*$/.test(cleaned)) return cleaned
  // RFC 2047 encoded-word, which ntfy understands.
  return `=?UTF-8?B?${Buffer.from(cleaned, 'utf8').toString('base64')}?=`
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
