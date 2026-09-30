/**
 * A test fixture, not a product.
 *
 * Holds one background job open so that `sleep-guard` has something to notice.
 *
 * Why this exists: `scripts/verify-integration.sh` used to prove only that the
 * plugins *load*. Loading is not working. `sleep-guard`'s entire behaviour is
 * "hold an assertion while work is in flight", and the only way to check that
 * inside a live host is to make some work be in flight — which normally means an
 * agent turn, which means an LLM call, which means a test that depends on a
 * network and a budget.
 *
 * `ctx.jobs.start` with no owner creates an unowned job, and sleep-guard counts
 * unowned jobs as busy. So this starts one, holds it for a configurable time,
 * and lets the script watch for the assertion appearing and then going away.
 *
 * Plain JavaScript on purpose: a fixture that needs its own build step is a
 * fixture that breaks independently of the thing it tests.
 */

export const name = 'dsh-e2e-job-holder'

/** `jobs` is the whole point; without it there is nothing to hold. */
export const inject = ['jobs']

/**
 * @param ctx - host context.
 * @param config - `{ holdMs }`, how long to keep the job running.
 */
export function apply(ctx, config) {
  const holdMs = Number(config?.holdMs ?? 25_000)
  if (!Number.isFinite(holdMs) || holdMs <= 0) {
    throw new Error(`dsh-e2e-job-holder: holdMs must be positive, got ${String(config?.holdMs)}`)
  }

  // A job controller has to serve the owner, or `start` refuses:
  //
  //   background jobs unavailable: no job controller serves this agent
  //     (load @deepseek-ai/dsh-tool-jobs in its composition)
  //
  // The tools package attaches one for the agent that calls the tool. This
  // fixture is not an agent and has no turn, so it attaches its own. That is
  // what `attachController` is for, and it is why the fixture can create a real
  // job without any model being involved.
  ctx.effect(() => ctx.jobs.attachController('dsh-e2e-job-holder'))

  let settle
  const done = new Promise((resolve) => { settle = resolve })

  const id = ctx.jobs.start({
    // 'bash' rather than a bespoke kind: the registry treats kinds as opaque id
    // namespaces but the producer for a kind has to exist, and bash is the one
    // every profile has.
    kind: 'bash',
    label: 'e2e fixture: holds a job open so sleep-guard has work to see',
    run() {
      return {
        cancel() {
          // Must be synchronous, idempotent, and eventually settle `done`.
          settle({ status: 'killed', detail: 'fixture cancelled' })
        },
        done,
      }
    },
  })

  // Printed to the host log so a failing run says which half went wrong: a job
  // that never started and an assertion that never appeared look identical from
  // the outside otherwise.
  console.log(`dsh-e2e-job-holder: holding job ${String(id)} for ${String(holdMs)}ms`)

  const timer = setTimeout(() => {
    settle({ status: 'completed' })
    console.log(`dsh-e2e-job-holder: released job ${String(id)}`)
  }, holdMs)
  timer.unref?.()

  ctx.effect(() => () => {
    clearTimeout(timer)
    settle({ status: 'killed', detail: 'host is shutting down' })
  })
}
