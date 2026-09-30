/**
 * Wiring tests for mobile-bridge.
 *
 * The single most important assertion in this file is that the approval
 * handler calls `next()`. `approval/request` is a waterfall: a listener that
 * returns without delegating *claims* the request, so an observer that forgets
 * to delegate does not merely fail to observe — it turns into a silent
 * auto-deny for every approval-gated tool call. That is the kind of bug that
 * looks like "the agent got lazy" rather than "the plugin is broken".
 */

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import type { Context } from '@deepseek-ai/cordis'

import { apply, Config, name, inject, type Config as PluginConfig } from '../src/index.ts'

type Listener = (...args: never[]) => unknown

interface Call {
  url: string
  headers: Record<string, string>
  body: string
}

function fakeContext(): {
  ctx: Context
  infos: string[]
  warnings: string[]
  emit(event: string, ...args: unknown[]): void
  teardown(): Promise<void>
  listenerCount(event: string): number
} {
  const infos: string[] = []
  const warnings: string[] = []
  const handlers = new Map<string, Listener[]>()
  let effectDisposer: (() => unknown) | undefined

  const ctx = {
    logger: {
      info: (m: string) => { infos.push(m) },
      warn: (m: string) => { warnings.push(m) },
      error: (m: string) => { warnings.push(m) },
    },
    on: (event: string, handler: Listener) => {
      const list = handlers.get(event) ?? []
      list.push(handler)
      handlers.set(event, list)
      return () => {
        handlers.set(event, (handlers.get(event) ?? []).filter((h) => h !== handler))
      }
    },
    effect: (callback: () => unknown) => {
      effectDisposer = callback() as () => unknown
      return () => {}
    },
    // The tools registry is injected optionally, so a host without it still
    // gets notifications. The fake supplies it eagerly; a separate test covers
    // the host that does not have it.
    inject: (_deps: string[], callback: (scoped: unknown) => void) => {
      callback({
        logger: {
          info: (m: string) => { infos.push(m) },
          warn: (m: string) => { warnings.push(m) },
        },
        effect: (cb: () => unknown) => { cb(); return () => {} },
        tools: { register: () => () => {} },
      })
    },
    agents: { list: () => [], roots: () => [] },
  }

  return {
    ctx: ctx as unknown as Context,
    infos,
    warnings,
    emit: (event, ...args) => {
      for (const handler of handlers.get(event) ?? []) handler(...(args as never[]))
    },
    teardown: async () => {
      if (effectDisposer !== undefined) await effectDisposer()
    },
    listenerCount: (event) => (handlers.get(event) ?? []).length,
  }
}

/** Install a recording fetch and return a restorer plus the recorded calls. */
function stubFetch(): { calls: Call[]; restore: () => void } {
  const calls: Call[] = []
  const original = globalThis.fetch
  globalThis.fetch = (async (url: string, init: { headers: Record<string, string>; body: string }) => {
    calls.push({ url, headers: init.headers, body: init.body })
    return { ok: true, status: 200 }
  }) as unknown as typeof globalThis.fetch
  return { calls, restore: () => { globalThis.fetch = original } }
}

// Built through the schema rather than written as a literal, so the defaults a
// real host applies are the ones under test. A plain object would silently skip
// `deepLinkScheme`, and the deep-link assertions below would then be checking a
// path production never takes.
const baseConfig: PluginConfig = Config({
  url: 'https://ntfy.sh/topic',
  baseUrl: 'https://host.ts.net',
  notifyOnApproval: true,
  notifyOnTurnEnd: true,
  rateLimitPerMinute: 20,
})

/** Let queued microtasks (the fire-and-forget send) run. */
const flush = (): Promise<void> => new Promise((resolve) => { setImmediate(resolve) })

const agent = (id = 'session-1'): unknown => ({ session: { id } })

describe('plugin metadata', () => {
  test('declares the agents service', () => {
    assert.deepEqual([...inject], ['agents'])
    assert.equal(name, 'dsh-mobile-mobile-bridge')
  })

  test('defaults both notification kinds on and the rate limit to 20', () => {
    const parsed = Config({})
    assert.equal(parsed.notifyOnApproval, true)
    assert.equal(parsed.notifyOnTurnEnd, true)
    assert.equal(parsed.rateLimitPerMinute, 20)
  })
})

describe('without a configured url', () => {
  // A host that has not been set up for remote use should load cleanly and do
  // nothing, not fail to mount.
  test('mounts, says so, and subscribes to nothing', () => {
    const h = fakeContext()
    apply(h.ctx, {})
    assert.match(h.infos.join('\n'), /no notification url configured/)
    assert.equal(h.listenerCount('approval/request'), 0)
    assert.equal(h.listenerCount('agent/status'), 0)
  })
})

describe('approval notifications', () => {
  test('sends a notification and delegates to the next answerer', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, baseConfig)

      let delegated = 0
      const next = async (): Promise<'unavailable'> => { delegated += 1; return 'unavailable' }

      h.emit('approval/request', {
        agent: agent(),
        toolName: 'bash',
        reason: 'writes outside the workspace',
      }, next)
      await flush()

      // The delegation assertion comes first on purpose: if this regresses,
      // every approval-gated tool call is silently denied, and that failure is
      // far more damaging than a missing notification.
      assert.equal(delegated, 1, 'the waterfall must be delegated, or this becomes an auto-deny')

      assert.equal(stub.calls.length, 1)
      assert.equal(stub.calls[0]?.body, 'writes outside the workspace')
      assert.equal(stub.calls[0]?.headers['priority'], 'urgent')
      assert.equal(stub.calls[0]?.headers['click'], 'dshmobile://session/session-1')
    } finally {
      stub.restore()
    }
  })

  test('still delegates when the notification channel is unreachable', async () => {
    const original = globalThis.fetch
    globalThis.fetch = (async () => { throw new Error('ENETUNREACH') }) as unknown as typeof globalThis.fetch
    try {
      const h = fakeContext()
      apply(h.ctx, baseConfig)
      let delegated = 0
      const next = async (): Promise<'unavailable'> => { delegated += 1; return 'unavailable' }

      h.emit('approval/request', { agent: agent(), toolName: 'bash' }, next)
      await flush()

      assert.equal(delegated, 1, 'a failed courtesy must not block the decision')
      assert.match(h.warnings.join('\n'), /ENETUNREACH/)
    } finally {
      globalThis.fetch = original
    }
  })

  test('notifies a second request rather than swallowing it', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, baseConfig)
      let delegated = 0
      const next = async (): Promise<'unavailable'> => { delegated += 1; return 'unavailable' }

      h.emit('approval/request', { agent: agent('a'), toolName: 'bash' }, next)
      h.emit('approval/request', { agent: agent('b'), toolName: 'bash' }, next)
      await flush()

      assert.equal(delegated, 2)
      assert.equal(stub.calls.length, 2)
    } finally {
      stub.restore()
    }
  })

  test('respects the rate limit without ever dropping the delegation', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, { ...baseConfig, rateLimitPerMinute: 1 })
      let delegated = 0
      const next = async (): Promise<'unavailable'> => { delegated += 1; return 'unavailable' }

      h.emit('approval/request', { agent: agent('same'), toolName: 'bash' }, next)
      h.emit('approval/request', { agent: agent('same'), toolName: 'bash' }, next)
      await flush()

      assert.equal(stub.calls.length, 1, 'the second notification is rate limited')
      assert.equal(delegated, 2, 'but both requests are still delegated')
      assert.match(h.warnings.join('\n'), /rate limited/)
    } finally {
      stub.restore()
    }
  })

  // With the watch off the plugin is simply not in the waterfall, so the real
  // answerer is unaffected. Asserting that a fake `next` was called here would
  // be asserting that a listener which was never registered still ran.
  test('can be turned off without joining the waterfall at all', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, { ...baseConfig, notifyOnApproval: false })

      assert.equal(h.listenerCount('approval/request'), 0, 'not in the chain means cannot interfere')

      h.emit('approval/request', { agent: agent(), toolName: 'bash' }, async () => 'unavailable')
      await flush()

      assert.equal(stub.calls.length, 0)
      assert.deepEqual(h.warnings, [])
    } finally {
      stub.restore()
    }
  })
})

describe('turn-end notifications', () => {
  test('fires on the running to idle edge', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, baseConfig)
      const a = agent('s1')

      h.emit('agent/status', { agent: a, status: 'running' })
      assert.equal(stub.calls.length, 0, 'starting work is not worth a notification')
      h.emit('agent/status', { agent: a, status: 'idle' })
      await flush()

      assert.equal(stub.calls.length, 1)
      assert.equal(stub.calls[0]?.headers['priority'], 'default')
      assert.equal(stub.calls[0]?.headers['click'], 'dshmobile://session/s1')
    } finally {
      stub.restore()
    }
  })

  // `agent/status` reports the state just entered, so a naive listener would
  // notify on every idle event, including the many where nothing ever ran.
  test('does not fire on an idle event that was not preceded by running', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, baseConfig)
      const a = agent('s1')

      h.emit('agent/status', { agent: a, status: 'idle' })
      h.emit('agent/status', { agent: a, status: 'idle' })
      await flush()
      assert.equal(stub.calls.length, 0)
    } finally {
      stub.restore()
    }
  })

  test('does not fire when work starts', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, baseConfig)
      h.emit('agent/status', { agent: agent(), status: 'running' })
      await flush()
      assert.equal(stub.calls.length, 0)
    } finally {
      stub.restore()
    }
  })

  test('tracks each agent independently', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, baseConfig)
      const a = agent('a')
      const b = agent('b')

      h.emit('agent/status', { agent: a, status: 'running' })
      h.emit('agent/status', { agent: b, status: 'running' })
      h.emit('agent/status', { agent: a, status: 'idle' })
      await flush()

      assert.equal(stub.calls.length, 1)
      assert.equal(stub.calls[0]?.headers['click'], 'dshmobile://session/a')
      assert.equal(stub.calls[0]?.headers['click']?.includes('/b'), false)
    } finally {
      stub.restore()
    }
  })

  // The default matters: a HTTPS link opens a browser, and the sign-in cookie
  // lives in the app's WebView, so that tap lands on an unauthenticated page.
  test('links a tap to the app scheme by default', () => {
    const h = fakeContext()
    apply(h.ctx, baseConfig)
    assert.equal(baseConfig.deepLinkScheme, 'dshmobile')
  })

  test('falls back to the https form when the scheme is cleared', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, Config({ ...baseConfig, deepLinkScheme: '' }))
      // One agent object, reused: the turn-end watch keys on the agent, so two
      // calls to agent('s1') would be two different agents and no edge.
      const watcher = agent('s1')
      h.emit('agent/status', { agent: watcher, status: 'running' })
      h.emit('agent/status', { agent: watcher, status: 'idle' })
      await flush()
      assert.equal(stub.calls[0]?.headers['click'], 'https://host.ts.net/#/session/s1')
    } finally {
      stub.restore()
    }
  })

  test('can be turned off independently of approvals', () => {
    const h = fakeContext()
    apply(h.ctx, { ...baseConfig, notifyOnTurnEnd: false })
    assert.equal(h.listenerCount('agent/status'), 0)
    assert.equal(h.listenerCount('approval/request'), 1)
  })
})

describe('teardown', () => {
  test('unsubscribes both watches', async () => {
    const h = fakeContext()
    apply(h.ctx, baseConfig)
    assert.equal(h.listenerCount('approval/request'), 1)
    assert.equal(h.listenerCount('agent/status'), 1)

    await h.teardown()
    assert.equal(h.listenerCount('approval/request'), 0)
    assert.equal(h.listenerCount('agent/status'), 0)
  })

  test('stops notifying after teardown', async () => {
    const stub = stubFetch()
    try {
      const h = fakeContext()
      apply(h.ctx, baseConfig)
      await h.teardown()

      let delegated = 0
      const next = async (): Promise<'unavailable'> => { delegated += 1; return 'unavailable' }
      h.emit('approval/request', { agent: agent(), toolName: 'bash' }, next)
      await flush()

      assert.equal(stub.calls.length, 0)
      assert.equal(delegated, 0, 'an unsubscribed listener cannot delegate, and must not be called')
    } finally {
      stub.restore()
    }
  })
})
