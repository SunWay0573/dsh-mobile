import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import {
  CommandStore,
  canonicalPayload,
  fingerprint,
  DEFAULT_RETENTION_MS,
  type CommandIdentity,
} from '../../src/mobile/command-store.ts'

function fakeClock(start = 1_000_000) {
  let now = start
  return { now: () => now, advance: (ms: number) => { now += ms } }
}

const id = (over: Partial<CommandIdentity> = {}): CommandIdentity => ({
  computerId: 'pc-1',
  deviceId: 'dev-1',
  commandId: 'c-1',
  operation: 'task.submit',
  payload: { text: 'do it' },
  ...over,
})

describe('canonical form', () => {
  test('key order does not change it', () => {
    assert.equal(canonicalPayload({ a: 1, b: 2 }), canonicalPayload({ b: 2, a: 1 }))
  })

  test('nested key order does not change it either', () => {
    assert.equal(
      canonicalPayload({ outer: { x: 1, y: 2 }, list: [{ p: 1, q: 2 }] }),
      canonicalPayload({ list: [{ q: 2, p: 1 }], outer: { y: 2, x: 1 } }),
    )
  })

  test('different content differs', () => {
    assert.notEqual(canonicalPayload({ text: 'a' }), canonicalPayload({ text: 'b' }))
  })

  test('undefined fields are ignored, because JSON would drop them too', () => {
    assert.equal(canonicalPayload({ a: 1, b: undefined }), canonicalPayload({ a: 1 }))
  })

  test('null and absent are different', () => {
    assert.notEqual(canonicalPayload({ a: null }), canonicalPayload({}))
  })

  // An array of one string and a bare string must not look the same, or two
  // different requests share a fingerprint.
  test('structure is preserved, not flattened', () => {
    assert.notEqual(canonicalPayload(['a']), canonicalPayload('a'))
    assert.notEqual(canonicalPayload({ a: [1, 2] }), canonicalPayload({ a: '1,2' }))
  })

  // The separator between identity parts must not be forgeable by choosing ids.
  test('a separator inside a field cannot be used to forge a collision', () => {
    const a = fingerprint(id({ computerId: 'pc', deviceId: 'a\u0000b' }))
    const b = fingerprint(id({ computerId: 'pc\u0000a', deviceId: 'b' }))
    assert.notEqual(a, b)
  })
})

/**
 * The fixed counterexample from the review.
 *
 * These two bodies hashed to the same FNV-1a 32-bit value, so the second was
 * replayed as the first — the dedup table causing the duplicate it exists to
 * prevent. Comparing canonical content removes the possibility rather than
 * making it unlikely.
 */
describe('the reported hash collision', () => {
  test('the two colliding payloads are now told apart', () => {
    const first = { text: '693xre1n2a0zo' }
    const second = { text: '1i6ts9fmr0ouo' }
    assert.notEqual(canonicalPayload(first), canonicalPayload(second))
  })

  test('and the second is a conflict, not a replay', () => {
    const store = new CommandStore()
    const begun = store.begin(id({ payload: { text: '693xre1n2a0zo' } }))
    assert.equal(begun.kind, 'new')
    if (begun.kind === 'new') store.complete(begun.token, { taskId: 't-1' })

    const second = store.begin(id({ payload: { text: '1i6ts9fmr0ouo' } }))
    assert.equal(second.kind, 'conflict')
  })
})

describe('request identity', () => {
  test('a first request is new and gets a token', () => {
    const begun = new CommandStore().begin(id())
    assert.equal(begun.kind, 'new')
    assert.ok(begun.kind === 'new' && begun.token.generation > 0)
  })

  test('a completed request replays its result', () => {
    const store = new CommandStore()
    const begun = store.begin(id())
    if (begun.kind === 'new') store.complete(begun.token, { taskId: 't-9' })

    const again = store.begin(id())
    assert.equal(again.kind, 'replay')
    assert.deepEqual(again.kind === 'replay' ? again.result : null, { taskId: 't-9' })
  })

  /**
   * The review's reproduction: the same id and the same body, first as
   * task.submit and then as task.cancel, returned the submit result and never
   * ran the cancel. Two different requests, treated as one.
   */
  test('the same id with a different operation is a conflict', () => {
    const store = new CommandStore()
    const begun = store.begin(id({ operation: 'task.submit' }))
    if (begun.kind === 'new') store.complete(begun.token, 'submitted')

    const second = store.begin(id({ operation: 'task.cancel' }))
    assert.equal(second.kind, 'conflict')
  })

  test('the same id on another computer is a different request', () => {
    const store = new CommandStore()
    const begun = store.begin(id({ computerId: 'pc-1' }))
    if (begun.kind === 'new') store.complete(begun.token, 'pc1-result')

    assert.equal(store.begin(id({ computerId: 'pc-2' })).kind, 'new')
  })

  test('the same id from another device is a different request', () => {
    const store = new CommandStore()
    const begun = store.begin(id({ deviceId: 'phone-a' }))
    if (begun.kind === 'new') store.complete(begun.token, 'a-result')

    assert.equal(store.begin(id({ deviceId: 'phone-b' })).kind, 'new')
  })

  test('the same id with a different body is a conflict', () => {
    const store = new CommandStore()
    store.begin(id({ payload: { text: 'first' } }))
    assert.equal(store.begin(id({ payload: { text: 'second' } })).kind, 'conflict')
  })

  test('a conflict is detected after the first one completed', () => {
    const store = new CommandStore()
    const begun = store.begin(id({ payload: { text: 'first' } }))
    if (begun.kind === 'new') store.complete(begun.token, 'done')
    assert.equal(store.begin(id({ payload: { text: 'second' } })).kind, 'conflict')
  })
})

describe('unresolved commands', () => {
  // Registered, work started, nothing recorded. A replay could run it twice;
  // dropping the record could make a task that ran look like it did not.
  test('a request that started but never finished is unknown, not new', () => {
    const store = new CommandStore()
    store.begin(id())
    assert.equal(store.begin(id()).kind, 'unknown')
  })

  /**
   * The review's second reproduction. A record that expired while still running
   * was silently treated as new, so a second request took the id — and the first
   * one's late completion then wrote its result into the second one's record.
   * A retry of the second request would have been answered with the first's
   * result.
   */
  test('an unresolved record does not expire into a new command', () => {
    const clock = fakeClock()
    const store = new CommandStore({ retentionMs: 10, now: clock.now })

    const a = store.begin(id({ payload: { which: 'A' } }))
    assert.equal(a.kind, 'new')

    clock.advance(11)

    // Not `new`: the first request may have run, and starting a second one with
    // the same id is how the work happens twice.
    const b = store.begin(id({ payload: { which: 'A' } }))
    assert.equal(b.kind, 'unknown')
  })

  test('a late completion cannot write into another request record', () => {
    const clock = fakeClock()
    const store = new CommandStore({ retentionMs: 10, now: clock.now })

    const a = store.begin(id({ payload: { which: 'A' } }))
    assert.equal(a.kind, 'new')
    if (a.kind !== 'new') return

    clock.advance(11)

    // B is a different body under the same id, so it conflicts rather than
    // starting. Either way A's completion must not land on B.
    assert.equal(store.begin(id({ payload: { which: 'B' } })).kind, 'conflict')

    // A's result arrives far too late. It belongs to A; it must be recorded
    // against A's generation or not at all.
    const accepted = store.complete(a.token, { from: 'A' })
    assert.equal(accepted, true)

    const replay = store.begin(id({ payload: { which: 'A' } }))
    assert.equal(replay.kind, 'replay')
    assert.deepEqual(replay.kind === 'replay' ? replay.result : null, { from: 'A' })
  })

  test('a completion with a superseded token is refused', () => {
    const store = new CommandStore()
    const first = store.begin(id())
    assert.equal(first.kind, 'new')
    if (first.kind !== 'new') return

    store.abandon(first.token)

    // A second request takes the id and gets its own generation.
    const second = store.begin(id())
    assert.equal(second.kind, 'new')
    if (second.kind !== 'new') return
    assert.notEqual(second.token.generation, first.token.generation)

    // The first request's result arrives now. Writing it would answer the
    // second request with a result that was never its own.
    assert.equal(store.complete(first.token, 'stale'), false)

    const replay = store.begin(id())
    assert.equal(replay.kind, 'unknown')
  })

  test('abandoning frees an id that never reached the host', () => {
    const store = new CommandStore()
    const begun = store.begin(id())
    if (begun.kind === 'new') store.abandon(begun.token)
    assert.equal(store.begin(id()).kind, 'new')
  })

  // Abandoning is only safe before the host was called. A finished command must
  // stay finished, or a retry runs it again.
  test('abandoning a completed request does not erase it', () => {
    const store = new CommandStore()
    const begun = store.begin(id())
    if (begun.kind !== 'new') return
    store.complete(begun.token, 'result')
    store.abandon(begun.token)
    assert.equal(store.begin(id()).kind, 'replay')
  })
})

describe('retention', () => {
  test('a finished record older than the window is forgotten', () => {
    const clock = fakeClock()
    const store = new CommandStore({ now: clock.now })
    const begun = store.begin(id())
    if (begun.kind === 'new') store.complete(begun.token, 'r')

    clock.advance(DEFAULT_RETENTION_MS + 1)
    // Forgotten, so the id is usable again. That is a real trade: after a day a
    // retry is treated as new. The alternative is a table that grows forever.
    assert.equal(store.begin(id()).kind, 'new')
  })

  test('a finished record inside the window survives', () => {
    const clock = fakeClock()
    const store = new CommandStore({ now: clock.now })
    const begun = store.begin(id())
    if (begun.kind === 'new') store.complete(begun.token, 'r')

    clock.advance(DEFAULT_RETENTION_MS - 1000)
    assert.equal(store.begin(id()).kind, 'replay')
  })

  // Unresolved records are not stale, they are unresolved. Their count is
  // bounded by how many commands were ever in flight.
  test('unresolved records outlive the retention window', () => {
    const clock = fakeClock()
    const store = new CommandStore({ retentionMs: 10, now: clock.now })
    store.begin(id())

    clock.advance(DEFAULT_RETENTION_MS * 10)
    assert.equal(store.size, 1)
    assert.equal(store.begin(id()).kind, 'unknown')
  })
})

describe('durability', () => {
  // The honest bit. An in-memory table cannot promise anything across a plugin
  // restart, and the plan forbids claiming otherwise before it is backed by
  // storage. This reports the truth so no caller can assume it.
  test('the current store does not claim durability', () => {
    assert.equal(new CommandStore().isDurable, false)
  })
})
