/**
 * A test fixture, not a product.
 *
 * Raises one `approval/request` so that `mobile-bridge` has something to notify
 * about.
 *
 * Why this exists: `mobile-bridge`'s whole purpose is that a phone in someone's
 * pocket finds out the agent is blocked. Checking that it *loads* says nothing
 * about whether a notification is ever sent. The real path needs an agent turn
 * that hits an approval-gated tool, which needs an LLM call and a model willing
 * to call the right tool — a test that is slow, non-deterministic and costs
 * money.
 *
 * `approval/request` is an ordinary waterfall event, so a fixture can raise one
 * directly. Everything downstream is the production path: the same listener,
 * the same message builder, the same HTTP request. Only the reason the event
 * exists is synthetic.
 *
 * The payload is deliberately minimal. `mobile-bridge` reads
 * `req.agent.session.id`, `req.toolName` and `req.reason`, so those are what a
 * fixture has to supply — nothing more, because anything more would be the
 * fixture guessing at a shape the real asker owns.
 */

export const name = 'dsh-e2e-approval-emitter'

/**
 * No services. The waterfall is a plain event on the context, and requiring
 * `tools` or `agents` here would make the fixture fail to mount for reasons
 * unrelated to what it tests.
 */
export const inject = []

/**
 * @param ctx - host context.
 * @param config - `{ delayMs, sessionId }`.
 */
export function apply(ctx, config) {
  const delayMs = Number(config?.delayMs ?? 8_000)
  const sessionId = String(config?.sessionId ?? 'e2e-approval-session')

  // Delayed so the fixture fires after the host has finished mounting. Raising
  // it during mount would race the listener that is meant to receive it, and a
  // race in a test fixture is a source of flakiness that looks like a bug in
  // the thing under test.
  const timer = setTimeout(() => {
    try {
      const outcome = ctx.waterfall(
        'approval/request',
        {
          agent: { session: { id: sessionId } },
          toolName: 'bash',
          reason: 'e2e fixture: raised to check that a notification is delivered',
        },
        () => 'unavailable',
      )
      console.log(`dsh-e2e-approval-emitter: raised approval/request, outcome=${String(outcome)}`)
    } catch (error) {
      console.log(`dsh-e2e-approval-emitter: failed to raise the event: ${String(error)}`)
    }
  }, delayMs)
  timer.unref?.()

  ctx.effect(() => () => { clearTimeout(timer) })
}
