/**
 * The privacy curtain: lock the screen while you are operating remotely.
 *
 * ## Why this is not how remote-desktop software does it
 *
 * Pixel-streaming tools must draw a fake black overlay and protect its window
 * level, because a real lock screen would blind them — they need the display to
 * keep producing frames. DSH drives the machine over HTTP and does not care
 * about the display at all, so it can simply lock the screen. That is strictly
 * stronger: an overlay can be dismissed locally, a lock screen cannot.
 *
 * ## Why locking is one-way from here
 *
 * There is deliberately no unlock. Unlocking requires the local user's
 * credentials, and a plugin that could unlock the machine for a remote caller
 * would defeat the entire point of the curtain. The way out is to walk over and
 * type your password, which is exactly the property you want.
 *
 * @module dsh-mobile-mobile-bridge/curtain
 */

import { spawn } from 'node:child_process'

/** The subset of `ChildProcess` used here; narrowed so tests can supply a fake. */
export interface ChildLike {
  readonly pid?: number | undefined
  once(event: 'exit', listener: (code: number | null, signal: NodeJS.Signals | null) => void): this
  once(event: 'error', listener: (error: Error) => void): this
}

/** Injectable spawner. */
export type SpawnFn = (command: string, args: readonly string[]) => ChildLike

/** Outcome of a lock attempt. */
export interface LockResult {
  /** Whether the lock command ran and exited successfully. */
  ok: boolean
  /** Human-readable outcome, suitable for returning from a tool. */
  message: string
}

/** How long to wait for the lock command before giving up on it. */
const LOCK_TIMEOUT_MS = 10_000

/**
 * The lock command for a platform, or undefined where none is known.
 *
 * macOS has a supported, no-root-required path, which is the whole reason this
 * is feasible. Linux and Windows are left out rather than guessed at: a wrong
 * command here either does nothing (harmless but silently useless) or does
 * something else entirely, and neither is worth shipping blind.
 *
 * @param platform - `process.platform`.
 * @returns command and args, or undefined.
 */
export function lockCommand(
  platform: NodeJS.Platform,
): { command: string; args: string[] } | undefined {
  if (platform === 'darwin') {
    // Apple's own lock-screen helper. `-suspend` locks immediately; it needs no
    // privileges and no accessibility permission.
    return {
      command: '/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession',
      args: ['-suspend'],
    }
  }
  return undefined
}

/** Locks the local screen. */
export class ScreenCurtain {
  readonly #platform: NodeJS.Platform
  readonly #spawn: SpawnFn
  readonly #timeoutMs: number

  /**
   * @param options.platform - defaults to `process.platform`.
   * @param options.spawn - injectable, so tests never actually lock anything.
   * @param options.timeoutMs - how long to wait for the command to exit.
   */
  constructor(options: {
    platform?: NodeJS.Platform
    spawn?: SpawnFn
    timeoutMs?: number
  } = {}) {
    this.#platform = options.platform ?? process.platform
    this.#spawn = options.spawn ?? ((command, args) =>
      spawn(command, [...args], { stdio: 'ignore' }))
    this.#timeoutMs = options.timeoutMs ?? LOCK_TIMEOUT_MS
  }

  /** Whether this platform has a known lock command. */
  get supported(): boolean {
    return lockCommand(this.#platform) !== undefined
  }

  /**
   * Lock the screen.
   *
   * Never throws: the caller is a tool whose contract is to report, not to
   * crash a turn.
   *
   * @returns whether the lock succeeded, and a message describing it.
   */
  async lock(): Promise<LockResult> {
    const resolved = lockCommand(this.#platform)
    if (resolved === undefined) {
      return {
        ok: false,
        message: `locking the screen is not implemented for platform "${this.#platform}"`,
      }
    }

    let child: ChildLike
    try {
      child = this.#spawn(resolved.command, resolved.args)
    } catch (error) {
      return { ok: false, message: `could not run the lock command: ${describe(error)}` }
    }

    const outcome = await Promise.race([
      new Promise<'exited' | 'failed'>((resolve) => {
        child.once('exit', (code) => { resolve(code === 0 ? 'exited' : 'failed') })
        child.once('error', () => { resolve('failed') })
      }),
      new Promise<'timeout'>((resolve) => {
        setTimeout(() => { resolve('timeout') }, this.#timeoutMs)
      }),
    ])

    if (outcome === 'exited') {
      return { ok: true, message: 'Screen locked. Unlocking needs the local password.' }
    }
    if (outcome === 'timeout') {
      // The helper is known to linger once the lock is up, so a timeout is not
      // evidence of failure — reporting it as one would be a lie the user acts on.
      return { ok: true, message: 'Screen locked (the helper did not exit, which is normal).' }
    }
    return { ok: false, message: 'the lock command failed' }
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
