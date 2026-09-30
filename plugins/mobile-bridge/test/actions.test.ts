/**
 * Tests for the two actions a remote operator triggers: locking the screen and
 * asking a LAN bridge to wake the machine.
 *
 * Both fakes are deliberate. A test that actually locks the screen would lock
 * the machine running the tests, and one that actually broadcasts a magic
 * packet proves nothing about this code.
 */

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { ScreenCurtain, lockCommand, type ChildLike } from '../src/curtain.ts'
import { WakeBridge } from '../src/wake.ts'
import type { FetchLike } from '../src/notify.ts'

/** A child process that exits however the test says it does. */
function fakeChild(behaviour: 'exit-0' | 'exit-1' | 'error' | 'silent'): ChildLike {
  return {
    pid: 1234,
    once(event: string, listener: (...args: never[]) => void) {
      if (behaviour === 'silent') return this
      queueMicrotask(() => {
        if (event === 'exit') {
          ;(listener as unknown as (code: number) => void)(behaviour === 'exit-0' ? 0 : 1)
        } else if (event === 'error' && behaviour === 'error') {
          ;(listener as unknown as (e: Error) => void)(new Error('boom'))
        }
      })
      return this
    },
  }
}

describe('lockCommand', () => {
  test('uses the supported no-privilege helper on macOS', () => {
    const resolved = lockCommand('darwin')
    assert.equal(resolved?.args[0], '-suspend')
    assert.match(resolved?.command ?? '', /CGSession$/)
  })

  // Guessing a lock command for another platform is how you get a plugin that
  // silently does nothing, or does something else entirely.
  test('returns undefined on platforms it has not verified', () => {
    assert.equal(lockCommand('win32'), undefined)
    assert.equal(lockCommand('linux'), undefined)
  })
})

describe('ScreenCurtain', () => {
  test('reports success when the helper exits cleanly', async () => {
    const curtain = new ScreenCurtain({
      platform: 'darwin',
      spawn: () => fakeChild('exit-0'),
    })
    const result = await curtain.lock()
    assert.equal(result.ok, true)
    assert.match(result.message, /locked/i)
  })

  test('reports failure when the helper exits non-zero', async () => {
    const curtain = new ScreenCurtain({
      platform: 'darwin',
      spawn: () => fakeChild('exit-1'),
    })
    assert.equal((await curtain.lock()).ok, false)
  })

  test('reports failure when the helper cannot start', async () => {
    const curtain = new ScreenCurtain({
      platform: 'darwin',
      spawn: () => { throw new Error('ENOENT') },
    })
    const result = await curtain.lock()
    assert.equal(result.ok, false)
    assert.match(result.message, /ENOENT/)
  })

  test('reports failure when the helper errors asynchronously', async () => {
    const curtain = new ScreenCurtain({
      platform: 'darwin',
      spawn: () => fakeChild('error'),
    })
    assert.equal((await curtain.lock()).ok, false)
  })

  // The macOS helper is known to linger once the lock is up, so a timeout is
  // not evidence of failure. Reporting failure here would be a lie the user
  // acts on -- they would go and "fix" a working setup.
  test('treats a lingering helper as success, and says why', async () => {
    const curtain = new ScreenCurtain({
      platform: 'darwin',
      spawn: () => fakeChild('silent'),
      timeoutMs: 20,
    })
    const result = await curtain.lock()
    assert.equal(result.ok, true)
    assert.match(result.message, /normal|did not exit/)
  })

  test('explains itself on an unsupported platform instead of pretending', async () => {
    const curtain = new ScreenCurtain({ platform: 'linux', spawn: () => fakeChild('exit-0') })
    const result = await curtain.lock()
    assert.equal(result.ok, false)
    assert.match(result.message, /not implemented/)
  })

  test('reports whether it can work at all', () => {
    assert.equal(new ScreenCurtain({ platform: 'darwin' }).supported, true)
    assert.equal(new ScreenCurtain({ platform: 'win32' }).supported, false)
  })

  test('never throws, whatever the helper does', async () => {
    for (const behaviour of ['exit-0', 'exit-1', 'error'] as const) {
      const curtain = new ScreenCurtain({
        platform: 'darwin',
        spawn: () => fakeChild(behaviour),
      })
      await assert.doesNotReject(async () => { await curtain.lock() })
    }
  })
})

interface Call {
  url: string
  headers: Record<string, string>
  body: string
}

function fakeFetch(result: { ok?: boolean; status?: number; throws?: Error } = {}): {
  fetch: FetchLike
  calls: Call[]
} {
  const calls: Call[] = []
  const fetch: FetchLike = async (url, init) => {
    calls.push({ url, headers: init.headers, body: init.body })
    if (result.throws !== undefined) throw result.throws
    return { ok: result.ok ?? true, status: result.status ?? 200 }
  }
  return { fetch, calls }
}

describe('WakeBridge', () => {
  test('posts to the bridge wake endpoint', async () => {
    const { fetch, calls } = fakeFetch()
    const result = await new WakeBridge({ url: 'http://127.0.0.1:8787', fetch }).wake()
    assert.equal(result.ok, true)
    assert.equal(calls[0]?.url, 'http://127.0.0.1:8787/wake')
  })

  test('tolerates a trailing slash on the configured url', async () => {
    const { fetch, calls } = fakeFetch()
    await new WakeBridge({ url: 'http://host:8787/', fetch }).wake()
    assert.equal(calls[0]?.url, 'http://host:8787/wake')
  })

  test('sends the target MAC when one is configured', async () => {
    const { fetch, calls } = fakeFetch()
    await new WakeBridge({ url: 'http://h', mac: 'aa:bb:cc:dd:ee:ff', fetch }).wake()
    assert.deepEqual(JSON.parse(calls[0]?.body ?? '{}'), { mac: 'aa:bb:cc:dd:ee:ff' })
  })

  test('sends an empty body when the bridge holds the MAC', async () => {
    const { fetch, calls } = fakeFetch()
    await new WakeBridge({ url: 'http://h', fetch }).wake()
    assert.equal(calls[0]?.body, '{}')
  })

  test('sends a bearer token only when one is configured', async () => {
    const withToken = fakeFetch()
    await new WakeBridge({ url: 'http://h', token: 's3cret', fetch: withToken.fetch }).wake()
    assert.equal(withToken.calls[0]?.headers['authorization'], 'Bearer s3cret')

    const without = fakeFetch()
    await new WakeBridge({ url: 'http://h', fetch: without.fetch }).wake()
    assert.equal(without.calls[0]?.headers['authorization'], undefined)
  })

  test('names the likely cause on a 401 rather than just the status', async () => {
    const { fetch } = fakeFetch({ ok: false, status: 401 })
    const result = await new WakeBridge({ url: 'http://h', fetch }).wake()
    assert.equal(result.ok, false)
    assert.match(result.message, /shared secret/)
  })

  test('reports a non-ok response with its status', async () => {
    const { fetch } = fakeFetch({ ok: false, status: 502 })
    const result = await new WakeBridge({ url: 'http://h', fetch }).wake()
    assert.match(result.message, /502/)
  })

  test('reports an unreachable bridge without throwing', async () => {
    const { fetch } = fakeFetch({ throws: new Error('ECONNREFUSED') })
    const result = await new WakeBridge({ url: 'http://h', fetch }).wake()
    assert.equal(result.ok, false)
    assert.match(result.message, /ECONNREFUSED/)
  })

  // `ok` means the kernel accepted the datagram, never that the machine is
  // awake. Claiming otherwise sends the user off to debug working setup.
  test('never claims the machine is awake', async () => {
    const { fetch } = fakeFetch()
    const result = await new WakeBridge({ url: 'http://h', fetch }).wake()
    assert.doesNotMatch(result.message, /\bawake\b|\bawake\.|is up|has resumed/i)
    assert.match(result.message, /packet sent/i)
  })
})
