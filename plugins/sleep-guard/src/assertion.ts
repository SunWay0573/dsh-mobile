/**
 * The effect half of sleep-guard: hold, and reliably release, a sleep
 * assertion implemented as a child process.
 *
 * ## Why a child process rather than an assertion API
 *
 * macOS exposes `IOPMAssertionCreateWithName` and Linux has D-Bus inhibitors.
 * Using either directly would avoid spawning anything, and would also mean that
 * a bug in *this* plugin leaves the machine permanently unable to sleep — the
 * single worst failure mode available here, because it is invisible, survives
 * the plugin being unloaded, and is only fixed by a reboot.
 *
 * A child process inverts that. The assertion lives exactly as long as a
 * process we own, so if the plugin wedges, crashes, or is hot-reloaded, the
 * operating system releases it for us. The cost is one short-lived process per
 * busy period, which is nothing next to the cost of getting it wrong.
 *
 * ## Failure direction
 *
 * Every error path here prefers "the machine may sleep when it should not" over
 * "the machine can never sleep". A missed wake-up costs a delayed task. A
 * leaked assertion costs a fan running all night and an unattended machine that
 * is awake for no reason.
 *
 * @module dsh-mobile-sleep-guard/assertion
 */

import { spawn } from 'node:child_process'

/**
 * The subset of `ChildProcess` this module uses.
 *
 * Narrowed deliberately: tests supply a fake, and a narrow contract makes it
 * obvious what a fake has to get right.
 */
export interface ChildLike {
  /**
   * Optional to match `ChildProcess`, where it is also optional — a child that
   * failed to spawn has no pid.
   */
  readonly pid?: number | undefined
  kill(signal?: NodeJS.Signals): boolean
  once(event: 'exit', listener: (code: number | null, signal: NodeJS.Signals | null) => void): this
  once(event: 'error', listener: (error: Error) => void): this
  removeAllListeners(event?: string): this
}

/** Injectable spawner, so tests never start a real process. */
export type SpawnFn = (command: string, args: readonly string[]) => ChildLike

/** Default spawner: a detached-free, stdio-ignoring child. */
const defaultSpawn: SpawnFn = (command, args) =>
  spawn(command, [...args], { stdio: 'ignore' })

/** Default grace period for a killed child to actually exit before escalating. */
const DEFAULT_KILL_GRACE_MS = 2_000

/**
 * A sleep assertion backed by one long-running child process.
 *
 * Not safe for concurrent `acquire`/`release` pairs from different callers —
 * the plugin drives it from a single {@link BusyTracker}, and that is the only
 * supported usage.
 */
export class SleepAssertion {
  readonly #command: string
  readonly #args: readonly string[]
  readonly #maxHoldMs: number
  readonly #killGraceMs: number
  readonly #spawn: SpawnFn
  readonly #onWarn: (message: string) => void
  readonly #onInfo: (message: string) => void

  #child: ChildLike | undefined
  #holdTimer: NodeJS.Timeout | undefined
  #releasing: Promise<void> | undefined
  /**
   * An acquire that arrived while a release was draining.
   *
   * Recorded rather than dropped. See {@link acquire} for why the difference
   * matters: dropping it can leave running work with no assertion at all.
   */
  #acquirePending = false
  /** Set by {@link dispose}. No further child is ever spawned after this. */
  #disposed = false

  /**
   * @param options.command - executable to run, e.g. `caffeinate`.
   * @param options.args - arguments, e.g. `['-i']`.
   * @param options.maxHoldMs - hard cap on how long an assertion may be held.
   *   A backstop against a missed "work finished" signal. Must be positive.
   * @param options.killGraceMs - how long to wait after SIGTERM before
   *   escalating to SIGKILL. Defaults to {@link DEFAULT_KILL_GRACE_MS}.
   * @param options.spawn - injectable process spawner; defaults to the real one.
   * @param options.onWarn - receives recoverable problems worth surfacing.
   * @param options.onInfo - receives state transitions, for debug logging.
   */
  constructor(options: {
    command: string
    args: readonly string[]
    maxHoldMs: number
    killGraceMs?: number
    spawn?: SpawnFn
    onWarn?: (message: string) => void
    onInfo?: (message: string) => void
  }) {
    if (!Number.isFinite(options.maxHoldMs) || options.maxHoldMs <= 0) {
      throw new RangeError('maxHoldMs must be a positive finite number')
    }
    const killGraceMs = options.killGraceMs ?? DEFAULT_KILL_GRACE_MS
    if (!Number.isFinite(killGraceMs) || killGraceMs < 0) {
      throw new RangeError('killGraceMs must be a non-negative finite number')
    }
    this.#command = options.command
    this.#args = [...options.args]
    this.#maxHoldMs = options.maxHoldMs
    this.#killGraceMs = killGraceMs
    this.#spawn = options.spawn ?? defaultSpawn
    this.#onWarn = options.onWarn ?? (() => {})
    this.#onInfo = options.onInfo ?? (() => {})
  }

  /** Whether an assertion is currently held. */
  get held(): boolean {
    return this.#child !== undefined
  }

  /**
   * Take the assertion. Idempotent: calling this while already held does
   * nothing, so a duplicated event cannot leak a second process.
   *
   * Never throws. A missing `caffeinate` — an unsupported platform, a stripped
   * image — degrades to a warning and a no-op, because a plugin that refuses to
   * load is worse than one that cannot hold an assertion.
   */
  acquire(): void {
    if (this.#disposed) return
    if (this.#child !== undefined) return
    if (this.#releasing !== undefined) {
      // A release is still draining. The old child is being killed, so a second
      // process must not be spawned yet -- but the request must not be thrown
      // away either.
      //
      // An earlier version simply returned, on the theory that "the next
      // update() from the host will re-acquire". There may not be a next
      // update: the host recomputes on events, and if work resumed during the
      // drain then stopped producing events, the busy state stays true and
      // nothing re-acquires. The machine then sleeps under running work, which
      // is the one outcome this whole component exists to prevent.
      this.#acquirePending = true
      this.#onInfo('acquire requested while a release is draining; deferring')
      return
    }

    let child: ChildLike
    try {
      child = this.#spawn(this.#command, this.#args)
    } catch (error) {
      this.#onWarn(`could not start ${this.#command}: ${describe(error)}`)
      return
    }

    this.#child = child
    this.#onInfo(`assertion held via ${this.#command} (pid ${String(child.pid)})`)

    // A spawn failure (ENOENT, EACCES) surfaces asynchronously. Without this
    // the plugin would believe it holds an assertion that does not exist.
    child.once('error', (error: Error) => {
      if (this.#child !== child) return
      this.#onWarn(`${this.#command} failed: ${describe(error)}; releasing`)
      this.#child = undefined
      this.#clearTimer()
    })

    // The assertion ends when the process does. If it exits while we still
    // believe we hold it, our model is wrong and the machine may sleep under
    // running work — say so rather than pretending.
    child.once('exit', (code, signal) => {
      if (this.#child !== child) return
      this.#onWarn(
        `${this.#command} exited early (code ${String(code)}, signal ${String(signal)});`
        + ' the machine may sleep while work is still running',
      )
      this.#child = undefined
      this.#clearTimer()
    })

    // Backstop for a missed "work finished" transition. Long by default: it
    // exists to bound the damage, not to enforce a policy.
    this.#holdTimer = setTimeout(() => {
      this.#onWarn(
        `assertion held longer than ${String(this.#maxHoldMs)}ms; forcing release`
        + ' (a work-finished signal was probably missed)',
      )
      void this.release()
    }, this.#maxHoldMs)
    this.#holdTimer.unref?.()
  }

  /**
   * Release the assertion, waiting for the child to actually exit.
   *
   * Idempotent and safe to call when not held. Resolves even if the child
   * ignores SIGTERM, so a wedged process cannot hang host shutdown.
   */
  async release(): Promise<void> {
    if (this.#releasing !== undefined) return this.#releasing
    const child = this.#child
    if (child === undefined) {
      this.#clearTimer()
      return
    }
    this.#releasing = this.#kill(child).finally(() => {
      this.#releasing = undefined
      // Work asked to be kept awake while the old child was still dying. Take
      // the assertion now, in the same turn the drain ends, so there is no
      // window where the machine is free to sleep.
      if (this.#acquirePending && !this.#disposed) {
        this.#acquirePending = false
        this.acquire()
      }
    })
    return this.#releasing
  }

  /**
   * Stop for good: drop anything pending and release what is held.
   *
   * Separate from {@link release} because a release is a pause -- work may
   * resume and the assertion comes back. A dispose is the end of the plugin,
   * and a deferred acquire that survived it would spawn a process nothing is
   * left to kill, which is precisely the leaked assertion this class is shaped
   * to make impossible.
   *
   * Idempotent, and safe to call while a release is draining: it clears the
   * pending flag, so the drain's completion check finds nothing to do.
   */
  async dispose(): Promise<void> {
    this.#disposed = true
    this.#acquirePending = false
    await this.release()
  }

  async #kill(child: ChildLike): Promise<void> {
    this.#clearTimer()
    this.#child = undefined

    const exited = new Promise<void>((resolve) => {
      child.once('exit', () => { resolve() })
      child.once('error', () => { resolve() })
    })

    try {
      child.kill('SIGTERM')
    } catch (error) {
      this.#onWarn(`could not signal ${this.#command}: ${describe(error)}`)
      return
    }

    const timedOut = await Promise.race([
      exited.then(() => false),
      delay(this.#killGraceMs).then(() => true),
    ])
    if (!timedOut) {
      this.#onInfo('assertion released')
      return
    }

    // SIGTERM was ignored. Escalate, then stop caring: the process is orphaned
    // but the OS still reclaims the assertion when it eventually dies, and a
    // wedged `caffeinate` is far less harmful than a host that will not exit.
    this.#onWarn(`${this.#command} ignored SIGTERM; sending SIGKILL`)
    try {
      child.kill('SIGKILL')
    } catch (error) {
      this.#onWarn(`SIGKILL failed: ${describe(error)}`)
    }
  }

  #clearTimer(): void {
    if (this.#holdTimer !== undefined) {
      clearTimeout(this.#holdTimer)
      this.#holdTimer = undefined
    }
  }
}

/**
 * The default assertion command for a platform.
 *
 * Returns `undefined` where no supported mechanism exists, which the caller
 * should report once rather than on every transition.
 *
 * @param platform - `process.platform`.
 * @returns command and args, or undefined when unsupported.
 */
export function defaultAssertionCommand(
  platform: NodeJS.Platform,
): { command: string; args: string[] } | undefined {
  if (platform === 'darwin') {
    // -i prevents idle system sleep. Deliberately not -s: -s also blocks
    // display sleep, which would keep the screen lit in an empty room.
    return { command: 'caffeinate', args: ['-i'] }
  }
  if (platform === 'linux') {
    return {
      command: 'systemd-inhibit',
      args: [
        '--what=idle:sleep',
        '--why=DSH is running a task',
        '--mode=block',
        'sleep',
        'infinity',
      ],
    }
  }
  return undefined
}

function delay(ms: number): Promise<void> {
  // Deliberately not unref'd: this timer is the only thing racing the child's
  // exit, so a process with nothing else pending must not be able to exit
  // before the grace period elapses.
  return new Promise((resolve) => { setTimeout(resolve, ms) })
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
