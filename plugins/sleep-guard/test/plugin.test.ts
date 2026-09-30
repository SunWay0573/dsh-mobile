/**
 * Wiring tests for the plugin entry point.
 *
 * These drive a fake Cordis context, so they verify the part the unit tests
 * cannot: that the plugin subscribes to the right events, reacts to them,
 * sees jobs it would otherwise miss, and releases everything on teardown.
 *
 * The assertion command is a real `sleep`, deliberately. Faking it here would
 * mean this file no longer proves the acquire/release chain actually reaches a
 * process. `sleep 5` is long enough not to exit mid-test and short enough to
 * clean itself up if the test dies badly.
 */

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import type { Context } from '@deepseek-ai/cordis'

import { apply, Config, name, inject, type Config as PluginConfig } from '../src/index.ts'
import type { JobStatus } from '../src/busy.ts'

type Listener = (...args: never[]) => unknown

interface FakeAgent {
  status: 'idle' | 'running'
  session: { id: string }
}

interface FakeJob {
  status: JobStatus
}

interface FakeContext {
  ctx: Context
  infos: string[]
  warnings: string[]
  /** Fire every listener registered for an event. */
  emit(event: string): void
  /** Run the disposer returned by the plugin's `ctx.effect` callback. */
  teardown(): Promise<void>
  /** Whether the job-event subscription was disposed. */
  jobSubDisposed(): boolean
  setAgents(agents: FakeAgent[]): void
  setJobsBySession(jobs: Record<string, FakeJob[]>): void
  setUnownedJobs(jobs: FakeJob[]): void
}

function fakeContext(options: { listThrowsForSession?: string } = {}): FakeContext {
  const infos: string[] = []
  const warnings: string[] = []
  const handlers = new Map<string, Listener[]>()
  let jobSubDisposed = false
  let effectDisposer: (() => unknown) | undefined

  let agents: FakeAgent[] = []
  let jobsBySession: Record<string, FakeJob[]> = {}
  let unownedJobs: FakeJob[] = []

  const ctx = {
    logger: {
      info: (message: string) => { infos.push(message) },
      warn: (message: string) => { warnings.push(message) },
      error: (message: string) => { warnings.push(message) },
    },
    on: (event: string, handler: Listener) => {
      const list = handlers.get(event) ?? []
      list.push(handler)
      handlers.set(event, list)
      return () => {
        const current = handlers.get(event) ?? []
        handlers.set(event, current.filter((h) => h !== handler))
      }
    },
    effect: (callback: () => unknown) => {
      effectDisposer = callback() as () => unknown
      return () => {}
    },
    agents: {
      list: () => agents,
      roots: () => agents,
    },
    jobs: {
      list: (caller?: string) => {
        if (caller === undefined) return unownedJobs
        if (caller === options.listThrowsForSession) throw new Error('session is tearing down')
        return jobsBySession[caller] ?? []
      },
      events: {
        subscribe: (_filter: unknown, _listener: Listener) => () => { jobSubDisposed = true },
      },
    },
  }

  return {
    ctx: ctx as unknown as Context,
    infos,
    warnings,
    emit: (event) => {
      for (const handler of handlers.get(event) ?? []) handler()
    },
    teardown: async () => {
      if (effectDisposer !== undefined) await effectDisposer()
    },
    jobSubDisposed: () => jobSubDisposed,
    setAgents: (next) => { agents = next },
    setJobsBySession: (next) => { jobsBySession = next },
    setUnownedJobs: (next) => { unownedJobs = next },
  }
}

const config: PluginConfig = {
  command: 'sleep',
  args: ['5'],
  maxHoldMs: 60_000,
  verbose: true,
}

const agent = (status: 'idle' | 'running', id = 's1'): FakeAgent => ({ status, session: { id } })
const job = (status: JobStatus): FakeJob => ({ status })

/** Did the plugin log that it took an assertion? */
const acquired = (h: FakeContext): boolean =>
  h.infos.some((m) => m.includes('assertion held'))

/** Did the plugin log that it released one? */
const released = (h: FakeContext): boolean =>
  h.infos.some((m) => m.includes('assertion released'))

/**
 * Wait for a condition that becomes true asynchronously.
 *
 * Release is deliberately not awaited inside the event handler — blocking the
 * host's dispatch on a process exit would be worse than draining in the
 * background — so acquire is observable synchronously and release is not.
 */
async function waitFor(predicate: () => boolean, timeoutMs = 5_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return
    await new Promise((resolve) => { setTimeout(resolve, 10) })
  }
  throw new Error(`condition not met within ${String(timeoutMs)}ms`)
}

describe('plugin metadata', () => {
  test('declares both required services so it cannot mount half-blind', () => {
    assert.deepEqual([...inject].sort(), ['agents', 'jobs'])
    assert.equal(name, 'dsh-mobile-sleep-guard')
  })

  test('defaults maxHoldMs to six hours', () => {
    const parsed = Config({})
    assert.equal(parsed.maxHoldMs, 6 * 60 * 60 * 1_000)
  })

  test('defaults verbose to false', () => {
    assert.equal(Config({}).verbose, false)
  })
})

describe('mounting', () => {
  test('holds nothing when the host is idle', () => {
    const h = fakeContext()
    h.setAgents([agent('idle')])
    apply(h.ctx, config)
    assert.equal(acquired(h), false)
  })

  // The hot-reload case: mounting during a long build must not wait for the
  // next transition, or the machine sleeps mid-task.
  test('acquires immediately when the host is already busy at mount', () => {
    const h = fakeContext()
    h.setAgents([agent('running')])
    apply(h.ctx, config)
    assert.equal(acquired(h), true)
  })

  test('acquires immediately when only a background job is running at mount', () => {
    const h = fakeContext()
    h.setAgents([agent('idle')])
    h.setUnownedJobs([job('running')])
    apply(h.ctx, config)
    assert.equal(acquired(h), true)
  })

  test('warns and does nothing when no command resolves', () => {
    const h = fakeContext()
    const original = Object.getOwnPropertyDescriptor(process, 'platform')
    // `apply` reads process.platform to choose a default; win32 has none.
    Object.defineProperty(process, 'platform', { value: 'win32', configurable: true })
    try {
      apply(h.ctx, { maxHoldMs: 60_000 })
    } finally {
      if (original !== undefined) Object.defineProperty(process, 'platform', original)
    }
    assert.match(h.warnings.join('\n'), /no sleep-assertion command/)
    assert.equal(acquired(h), false)
  })
})

describe('reacting to work', () => {
  test('acquires on the idle to busy edge and releases on the way back', async () => {
    const h = fakeContext()
    h.setAgents([agent('idle')])
    apply(h.ctx, config)
    assert.equal(acquired(h), false)

    h.setAgents([agent('running')])
    h.emit('agent/status')
    assert.equal(acquired(h), true)

    h.setAgents([agent('idle')])
    h.emit('agent/status')
    await waitFor(() => released(h))
  })

  test('keeps holding across a handoff from a turn to a background job', () => {
    const h = fakeContext()
    h.setAgents([agent('running')])
    apply(h.ctx, config)

    // The turn ends, but a job it started continues.
    h.setAgents([agent('idle')])
    h.setUnownedJobs([job('running')])
    h.emit('agent/status')
    h.emit('agent/disposed')

    assert.equal(acquired(h), true)
    assert.equal(released(h), false, 'the machine must not be allowed to sleep here')
  })

  test('subscribes to the job stream, which is the only signal for a settling job', async () => {
    const h = fakeContext()
    apply(h.ctx, config)
    h.setAgents([agent('idle')])
    h.setUnownedJobs([job('running')])
    h.emit('agent/status')
    assert.equal(acquired(h), true)

    h.setUnownedJobs([job('completed')])
    // No agent event fires when a background job settles.
    h.emit('agent/status')
    await waitFor(() => released(h))
  })

  // `jobs.list()` with no argument returns only *unowned* jobs. Missing this
  // would mean every ordinary session-owned `bash` job is invisible.
  test('sees jobs owned by a live session, not just unowned ones', () => {
    const h = fakeContext()
    h.setAgents([agent('idle', 'session-a')])
    h.setJobsBySession({ 'session-a': [job('running')] })
    apply(h.ctx, config)
    assert.equal(acquired(h), true, 'a session-owned job must keep the machine awake')
  })

  test('sees jobs on non-root agents such as subagents', () => {
    const h = fakeContext()
    h.setAgents([agent('idle', 'root'), agent('idle', 'sub')])
    h.setJobsBySession({ sub: [job('stopping')] })
    apply(h.ctx, config)
    assert.equal(acquired(h), true, 'a stopping job has not finished')
  })

  test('survives one session failing to list its jobs', () => {
    const h = fakeContext({ listThrowsForSession: 'broken' })
    h.setAgents([agent('idle', 'broken'), agent('idle', 'fine')])
    h.setJobsBySession({ fine: [job('running')] })
    apply(h.ctx, config)
    assert.equal(acquired(h), true, 'one unreadable session must not hide the others')
    assert.match(h.warnings.join('\n'), /could not list jobs for a session/)
  })

  test('survives a service that throws on every read', () => {
    const h = fakeContext()
    const thrower = {
      list: () => { throw new Error('registry is gone') },
      roots: () => { throw new Error('registry is gone') },
    }
    Object.assign(h.ctx, { agents: thrower })
    assert.doesNotThrow(() => { apply(h.ctx, config) })
    assert.match(h.warnings.join('\n'), /could not read host state/)
  })
})

describe('teardown', () => {
  test('releases the assertion and unsubscribes', async () => {
    const h = fakeContext()
    h.setAgents([agent('running')])
    apply(h.ctx, config)
    assert.equal(acquired(h), true)

    await h.teardown()
    assert.equal(released(h), true, 'unloading must not leak an assertion')
    assert.equal(h.jobSubDisposed(), true, 'the job subscription must be disposed')
  })

  test('tears down cleanly when nothing was ever held', async () => {
    const h = fakeContext()
    h.setAgents([agent('idle')])
    apply(h.ctx, config)
    await assert.doesNotReject(() => h.teardown())
  })

  test('ignores events that arrive after teardown', async () => {
    const h = fakeContext()
    h.setAgents([agent('idle')])
    apply(h.ctx, config)
    await h.teardown()

    const before = h.infos.length
    h.setAgents([agent('running')])
    h.emit('agent/status')
    assert.equal(h.infos.length, before, 'a late event must not resurrect an assertion')
  })
})
