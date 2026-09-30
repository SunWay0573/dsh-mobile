/**
 * The wake trigger: ask a LAN device to send a magic packet.
 *
 * This does not send the packet itself, and it cannot. A sleeping machine has
 * no process to run this plugin, and Wake-on-LAN packets are broadcast so they
 * do not cross routers. The request therefore goes to a
 * [wol-bridge](../../../wol-bridge/) running on something already awake on the
 * same subnet. See that component's README for why this is physics rather than
 * a missing feature.
 *
 * @module dsh-mobile-mobile-bridge/wake
 */

import type { FetchLike } from './notify.ts'

/** Outcome of a wake attempt. */
export interface WakeResult {
  /** Whether the bridge accepted the request. */
  ok: boolean
  /** Human-readable outcome, suitable for returning from a tool. */
  message: string
}

/** How long to wait for the bridge. */
const DEFAULT_TIMEOUT_MS = 10_000

/** Calls a `wol-bridge` HTTP endpoint. */
export class WakeBridge {
  readonly #url: string
  readonly #mac: string | undefined
  readonly #token: string | undefined
  readonly #fetch: FetchLike
  readonly #timeoutMs: number

  /**
   * @param options.url - base URL of the bridge, e.g. `http://127.0.0.1:8787`.
   * @param options.mac - target MAC, when the bridge is not preconfigured for
   *   exactly one machine.
   * @param options.token - shared secret, when the bridge requires one.
   * @param options.fetch - injectable HTTP client.
   * @param options.timeoutMs - per-request timeout.
   */
  constructor(options: {
    url: string
    mac?: string | undefined
    token?: string | undefined
    fetch?: FetchLike
    timeoutMs?: number
  }) {
    this.#url = options.url.endsWith('/') ? options.url.slice(0, -1) : options.url
    this.#mac = options.mac
    this.#token = options.token
    this.#fetch = options.fetch ?? (globalThis.fetch as unknown as FetchLike)
    this.#timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  }

  /**
   * Ask the bridge to send a magic packet.
   *
   * Never throws, for the same reason notifications never throw: a tool call
   * reports outcomes, it does not crash a turn.
   *
   * @returns whether the request was accepted, and a message describing it.
   */
  async wake(): Promise<WakeResult> {
    const headers: Record<string, string> = { 'content-type': 'application/json' }
    if (this.#token !== undefined && this.#token !== '') {
      headers['authorization'] = `Bearer ${this.#token}`
    }
    const body = this.#mac === undefined ? '{}' : JSON.stringify({ mac: this.#mac })

    const controller = new AbortController()
    const timer = setTimeout(() => { controller.abort() }, this.#timeoutMs)
    try {
      const response = await this.#fetch(`${this.#url}/wake`, {
        method: 'POST',
        headers,
        body,
        signal: controller.signal,
      })
      if (!response.ok) {
        return {
          ok: false,
          message: `the wake bridge answered HTTP ${String(response.status)}`
            + (response.status === 401 ? ' (check the shared secret)' : ''),
        }
      }
      // `ok` means the kernel accepted the datagram, never that the machine is
      // awake. Saying otherwise would have the user conclude the setup is
      // broken when the machine is merely slow to resume.
      return {
        ok: true,
        message: 'Wake packet sent. The machine usually answers within a minute;'
          + ' a full resume from sleep can take longer.',
      }
    } catch (error) {
      return { ok: false, message: `could not reach the wake bridge: ${describe(error)}` }
    } finally {
      clearTimeout(timer)
    }
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
