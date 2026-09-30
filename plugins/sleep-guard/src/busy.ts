/**
 * The decision half of sleep-guard: is the host busy right now?
 *
 * This is deliberately separated from the process management in
 * `assertion.ts` and from the Cordis wiring in `index.ts`. The question
 * "should the machine be allowed to sleep?" is the part that can be wrong in
 * interesting ways, so it is written as pure functions over plain data and
 * tested directly, with no DSH host and no child processes involved.
 *
 * @module dsh-mobile-sleep-guard/busy
 */

/**
 * Mirror of DSH's `AgentStatus`. Declared structurally rather than imported so
 * that this module stays usable — and testable — without the DSH packages
 * present, and so a version skew cannot turn into a type error here.
 */
export type AgentStatus = 'idle' | 'running'

/**
 * Mirror of DSH's `JobStatus`.
 *
 * The full set matters. An earlier draft of this plugin only treated
 * `'running'` as occupying, which would have let the machine sleep while a job
 * was still shutting down. `'stopping'` means the work has not finished yet.
 */
export type JobStatus = 'running' | 'stopping' | 'completed' | 'killed' | 'failed'

/** Minimal shape of a live agent. */
export interface AgentLike {
  readonly status: AgentStatus
}

/** Minimal shape of a background job. */
export interface JobLike {
  readonly status: JobStatus
}

/**
 * Job states in which work is still in flight.
 *
 * `'stopping'` is included on purpose: a cancellation that has been requested
 * has not yet completed, and the process being cancelled is still doing work.
 */
const OCCUPYING_JOB_STATUSES: ReadonlySet<string> = new Set<JobStatus>(['running', 'stopping'])

/** Whether a job in this state still occupies the machine. */
export function isJobOccupying(status: JobStatus): boolean {
  return OCCUPYING_JOB_STATUSES.has(status)
}

/**
 * Whether any work is in flight.
 *
 * Both signals are required. `agent.status === 'idle'` means no *driver* is
 * scheduled — it does not mean nothing is happening, because a background job
 * (a long `bash`, a `subagent`) outlives the turn that started it. Checking
 * only agents produces a machine that sleeps in the middle of a build.
 *
 * @param agents - live agents, typically `ctx.agents.roots()`.
 * @param jobs - background jobs, typically `ctx.jobs.list()`.
 * @returns true when the machine must stay awake.
 */
export function isBusy(
  agents: readonly AgentLike[],
  jobs: readonly JobLike[],
): boolean {
  for (const agent of agents) {
    if (agent.status === 'running') return true
  }
  for (const job of jobs) {
    if (isJobOccupying(job.status)) return true
  }
  return false
}

/**
 * Edge detector over {@link isBusy}.
 *
 * Emits only on transitions, because the caller's job — spawning or killing a
 * process — is expensive and non-idempotent in the sense that matters: a
 * second `acquire()` while already held must not leak a second process.
 *
 * Keeping this as an explicit object rather than a boolean captured in a
 * closure makes the transition logic testable without a host.
 */
export class BusyTracker {
  #busy = false
  #disposed = false
  readonly #onChange: (busy: boolean) => void

  /**
   * @param onChange - invoked only when the busy state flips.
   */
  constructor(onChange: (busy: boolean) => void) {
    this.#onChange = onChange
  }

  /** Current state, as of the last {@link update}. */
  get busy(): boolean {
    return this.#busy
  }

  /**
   * Recompute from a fresh snapshot and fire `onChange` if the state flipped.
   *
   * A throw from `onChange` propagates, but the internal state has already
   * been updated, so a retry does not double-fire. That is the safer failure
   * direction: a missed callback leaves the assertion in whatever state the
   * failure handler put it, whereas a duplicated callback leaks a process.
   *
   * @returns the new busy state.
   */
  update(agents: readonly AgentLike[], jobs: readonly JobLike[]): boolean {
    if (this.#disposed) return this.#busy
    const next = isBusy(agents, jobs)
    if (next === this.#busy) return this.#busy
    this.#busy = next
    this.#onChange(next)
    return next
  }

  /**
   * Stop reacting. After disposal {@link update} is inert, so a late event
   * from a torn-down host cannot resurrect a process we are about to lose
   * track of.
   */
  dispose(): void {
    this.#disposed = true
  }
}
