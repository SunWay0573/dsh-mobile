/**
 * sleep-guard — keep the machine awake while DSH is working, and let it sleep
 * the rest of the time.
 *
 * ## The behaviour
 *
 * | State | Machine |
 * |---|---|
 * | Idle, nothing running | sleeps |
 * | A turn or a background job is in flight | stays awake |
 *
 * ## What this plugin does not do
 *
 * It does not schedule RTC wake events for pending `schedule` timers. Those
 * timers are in-process `setTimeout`s and do not fire while the machine is
 * asleep, so a scheduled task can be missed. The workaround (`pmset schedule
 * wake`) needs root, so it is left to the operator rather than done silently
 * by a plugin — see the README.
 *
 * @module dsh-mobile-sleep-guard
 */

import type { Context } from '@deepseek-ai/cordis'
import z from '@deepseek-ai/schemastery'
// Type-only, and deliberately empty: these imports exist to pull in the
// declaration merges for `agent/*` events and the `agents` / `jobs` services.
// They are erased at runtime, so the plugin carries no dependency on the DSH
// packages themselves.
import type {} from '@deepseek-ai/dsh-agent'
import type {} from '@deepseek-ai/dsh-jobs'

import { SleepAssertion, defaultAssertionCommand } from './assertion.ts'
import { BusyTracker, type JobLike } from './busy.ts'

export const name = 'dsh-mobile-sleep-guard'

/**
 * Both services are required: agents supply turn state, jobs supply background
 * work. Declaring them means the host will not mount this plugin until both
 * exist, rather than letting it load and silently observe nothing.
 */
export const inject = ['agents', 'jobs']

/** How long an assertion may be held before it is force-released. */
const DEFAULT_MAX_HOLD_MS = 6 * 60 * 60 * 1_000

/** Plugin configuration. */
export interface Config {
  /**
   * Executable that holds the assertion. Defaults to `caffeinate` on macOS and
   * `systemd-inhibit` on Linux. Set this to override, e.g. a wrapper script.
   */
  command?: string
  /** Arguments for {@link Config.command}. Defaults depend on the platform. */
  args?: string[]
  /**
   * Hard cap on a single held period, in milliseconds.
   *
   * A backstop against a missed work-finished signal. It bounds the damage
   * rather than expressing a policy — a legitimate build can run for hours, so
   * the default is deliberately generous.
   * @default 21600000 (6 hours)
   */
  maxHoldMs?: number
  /** Log every acquire and release. Noisy; useful when diagnosing. */
  verbose?: boolean
}

export const Config: z<Config> = z.object({
  command: z.string().description('Executable that holds the sleep assertion'),
  args: z.array(z.string()).description('Arguments for the assertion command'),
  maxHoldMs: z.natural().default(DEFAULT_MAX_HOLD_MS)
    .description('Hard cap on a single held period, in milliseconds'),
  verbose: z.boolean().default(false)
    .description('Log every acquire and release'),
})

/**
 * Mount the plugin.
 *
 * @param ctx - host context; `agents` and `jobs` are guaranteed by `inject`.
 * @param config - validated plugin configuration.
 */
export function apply(ctx: Context, config: Config): void {
  const resolved = resolveCommand(config)
  if (resolved === undefined) {
    // Refusing to mount would be worse than doing nothing: the rest of the
    // host keeps working, and the operator gets one clear line rather than a
    // mysterious mount failure.
    ctx.logger.warn(
      `sleep-guard: no sleep-assertion command for platform "${process.platform}"`
      + ' and none configured; the plugin will not hold any assertion',
    )
    return
  }

  const verbose = config.verbose ?? false
  const maxHoldMs = config.maxHoldMs ?? DEFAULT_MAX_HOLD_MS

  ctx.logger.info(
    `sleep-guard: holding assertions via "${resolved.command} ${resolved.args.join(' ')}"`
    + ` (max ${String(maxHoldMs)}ms)`,
  )

  ctx.effect(() => {
    const assertion = new SleepAssertion({
      command: resolved.command,
      args: resolved.args,
      maxHoldMs,
      onWarn: (message) => { ctx.logger.warn(`sleep-guard: ${message}`) },
      onInfo: (message) => { if (verbose) ctx.logger.info(`sleep-guard: ${message}`) },
    })

    const tracker = new BusyTracker((busy) => {
      if (busy) {
        assertion.acquire()
        return
      }
      // Not awaited: this runs synchronously inside event dispatch, and
      // blocking the host's event loop on a process exit would be worse than
      // draining in the background. The disposer below does await it, so
      // shutdown stays orderly.
      void assertion.release().catch((error: unknown) => {
        ctx.logger.warn(`sleep-guard: release failed: ${describe(error)}`)
      })
    })

    /**
     * Recompute from a fresh snapshot.
     *
     * Returns `undefined` explicitly because the host types `agent/*`
     * listeners as `() => Promise<undefined> | undefined` — a `void`-returning
     * function is not assignable to that.
     */
    const recompute = (): undefined => {
      try {
        tracker.update(ctx.agents.list(), collectJobs(ctx))
      } catch (error) {
        // A throwing service must not take the host's event dispatch down.
        ctx.logger.warn(`sleep-guard: could not read host state: ${describe(error)}`)
      }
      return undefined
    }

    const disposers: Array<() => void> = [
      ctx.on('agent/status', recompute),
      ctx.on('agent/created', recompute),
      ctx.on('agent/disposed', recompute),
      // Background jobs settle without any agent event, so the job stream is
      // not optional here — without it a finished `bash` job would keep the
      // assertion held until the max-hold backstop fired.
      ctx.jobs.events.subscribe({ owners: 'all' }, recompute),
    ]

    // The host may already be busy when this plugin mounts — a hot reload
    // during a long build is the common case. Without this the assertion would
    // only appear at the *next* transition.
    recompute()

    return async () => {
      // Order matters: stop reacting before tearing down, so an event arriving
      // mid-teardown cannot re-acquire an assertion we are about to lose track
      // of. `BusyTracker.dispose` makes later updates inert for the same reason.
      tracker.dispose()
      for (const dispose of disposers) {
        try {
          dispose()
        } catch (error) {
          ctx.logger.warn(`sleep-guard: listener teardown failed: ${describe(error)}`)
        }
      }
      try {
        // `dispose` rather than `release`: a release is a pause that work may
        // end, and an acquire deferred during one is taken as soon as the drain
        // finishes. At teardown there is nothing left to take it back, so the
        // deferred request must be dropped -- otherwise a plugin that is
        // unloading spawns a `caffeinate` nobody owns, which is exactly the
        // leaked assertion this design exists to make impossible.
        await assertion.dispose()
      } catch (error) {
        ctx.logger.warn(`sleep-guard: final release failed: ${describe(error)}`)
      }
    }
  }, 'sleep-guard.assertion()')
}

/**
 * Collect every live background job.
 *
 * `jobs.list()` with no argument returns only **unowned** jobs — jobs owned by
 * a session are visible only to that session's id. Calling it once and calling
 * it done would silently miss every ordinary `bash` job, which is the whole
 * reason jobs are consulted here. So: unowned jobs, plus the jobs of every live
 * agent (subagents included, which is why this is `list()` and not `roots()`).
 *
 * Jobs are cancelled and awaited when their owning agent is disposed, so a live
 * job always has a live owner and this enumeration is complete.
 */
function collectJobs(ctx: Context): readonly JobLike[] {
  const collected: JobLike[] = ctx.jobs.list()
  for (const agent of ctx.agents.list()) {
    try {
      collected.push(...ctx.jobs.list(agent.session.id))
    } catch (error) {
      // A session-scoped read can fail for a session mid-teardown. One
      // unreadable session must not hide the others.
      ctx.logger.warn(`sleep-guard: could not list jobs for a session: ${describe(error)}`)
    }
  }
  return collected
}

/**
 * Resolve the configured command, falling back to the platform default.
 *
 * Explicit config always wins, including on a platform we do not know — a user
 * who sets `command` has a reason, and second-guessing them helps nobody.
 */
function resolveCommand(config: Config): { command: string; args: string[] } | undefined {
  if (config.command !== undefined) {
    return { command: config.command, args: config.args ?? [] }
  }
  return defaultAssertionCommand(process.platform)
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
