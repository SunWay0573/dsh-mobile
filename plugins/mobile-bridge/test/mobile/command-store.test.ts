import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { CommandStore, hashPayload, DEFAULT_RETENTION_MS } from '../../src/mobile/command-store.ts'

/** A clock the test drives, so nothing depends on wall time. */
function fakeClock(start = 1_000_000) {
  let now = start
  return { now: () => now, advance: (ms: number) => { now += ms } }
}

describe('payload hashing', () => {
  // A client that serialises keys in a different order sent the same request.
  // Treating it as new would defeat the whole point of the dedup table.
  test('key order does not change the hash', () => {
    assert.equal(hashPayload({ a: 1, b: 2 }), hashPayload({ b: 2, a: 1 }))
  })

  test('nested key order does not change the hash either', () => {
    assert.equal(
      hashPayload({ outer: { x: 1, y: 2 }, list: [{ p: 1, q: 2 }] }),
      hashPayload({ list: [{ q: 2, p: 1 }], outer: { y: 2, x: 1 } }),
    )
  })

  test('different content hashes differently', () => {
    assert.notEqual(hashPayload({ text: 'a' }), hashPayload({ text: 'b' }))
  })

  test('undefined fields are ignored, because JSON would drop them too', () => {
    assert.equal(hashPayload({ a: 1, b: undefined }), hashPayload({ a: 1 }))
  })

  test('null and absent are different', () => {
    assert.notEqual(hashPayload({ a: null }), hashPayload({}))
  })
})

describe('command dedup', () => {
  test('a first command is new', () => {
    const store = new CommandStore()
    assert.equal(store.begin('c1', { x: 1 }).kind, 'new')
  })

  test('a completed command replays its result', () => {
    const store = new CommandStore()
    store.begin('c1', { x: 1 })
    store.complete('c1', { taskId: 't-9' })

    const again = store.begin('c1', { x: 1 })
    assert.equal(again.kind, 'replay')
    assert.deepEqual(again.kind === 'replay' ? again.result : null, { taskId: 't-9' })
  })

  // The failure that matters: the phone retries with the same id but different
  // content. Returning the old answer answers a question nobody asked; running
  // it acts on content the id does not name.
  test('the same id with different content conflicts', () => {
    const store = new CommandStore()
    store.begin('c1', { text: 'first' })
    assert.equal(store.begin('c1', { text: 'second' }).kind, 'conflict')
  })

  test('a conflict is detected even after the first one completed', () => {
    const store = new CommandStore()
    store.begin('c1', { text: 'first' })
    store.complete('c1', 'done')
    assert.equal(store.begin('c1', { text: 'second' }).kind, 'conflict')
  })

  // The crash window: registered, work started, nothing recorded. A replay could
  // run the task twice; dropping the record could make a task that ran look like
  // it did not. Unknown is the only honest answer.
  test('a command that started but never finished is unknown, not new', () => {
    const store = new CommandStore()
    store.begin('c1', { x: 1 })
    assert.equal(store.begin('c1', { x: 1 }).kind, 'unknown')
  })

  test('abandoning a command that never reached the host frees its id', () => {
    const store = new CommandStore()
    store.begin('c1', { x: 1 })
    store.abandon('c1')
    assert.equal(store.begin('c1', { x: 1 }).kind, 'new')
  })

  // Abandoning is only safe before the host was called. A completed command must
  // stay completed, or a retry would run it again.
  test('abandoning a completed command does not erase it', () => {
    const store = new CommandStore()
    store.begin('c1', { x: 1 })
    store.complete('c1', 'result')
    store.abandon('c1')
    assert.equal(store.begin('c1', { x: 1 }).kind, 'replay')
  })

  test('completing an unknown id is inert', () => {
    const store = new CommandStore()
    store.complete('never-seen', 'result')
    assert.equal(store.size, 0)
  })
})

describe('retention', () => {
  test('a record older than the window is forgotten', () => {
    const clock = fakeClock()
    const store = new CommandStore({ now: clock.now })
    store.begin('c1', { x: 1 })
    store.complete('c1', 'r')

    clock.advance(DEFAULT_RETENTION_MS + 1)
    // Forgotten, so the id is usable again. That is a real trade: after a day, a
    // retry of the same id is treated as a new command. The alternative is a
    // table that grows forever on a machine left running.
    assert.equal(store.begin('c1', { x: 1 }).kind, 'new')
  })

  test('a record inside the window survives', () => {
    const clock = fakeClock()
    const store = new CommandStore({ now: clock.now })
    store.begin('c1', { x: 1 })
    store.complete('c1', 'r')

    clock.advance(DEFAULT_RETENTION_MS - 1000)
    assert.equal(store.begin('c1', { x: 1 }).kind, 'replay')
  })

  test('expiry is swept on access rather than on a timer', () => {
    const clock = fakeClock()
    const store = new CommandStore({ now: clock.now })
    for (let i = 0; i < 50; i += 1) store.begin(`c${i}`, { i })
    assert.equal(store.size, 50)

    clock.advance(DEFAULT_RETENTION_MS + 1)
    assert.equal(store.size, 0)
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
