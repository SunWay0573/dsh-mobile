/**
 * Tests for the assertion process lifecycle.
 *
 * The process is faked so these run in milliseconds and can drive the failure
 * paths that matter — a spawn that never happened, a child that dies on its
 * own, a child that ignores SIGTERM — none of which are reachable reliably
 * with a real `caffeinate`.
 *
 * What a fake cannot prove is that the real command actually holds an
 * assertion. That is verified out-of-band; see the README.
 */

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { SleepAssertion, defaultAssertionCommand, type ChildLike } from '../src/assertion.ts'

/** A child process we can drive by hand. */
class FakeChild {
  pid: number | undefined = 4242
  readonly signals: string[] = []
  /** When false, `kill` records the signal but never emits `exit`. */
  exitOnKill = true
  /** When false, `kill` returns false, mimicking an already-dead process. */
  killSucceeds = true

  #exitListeners: Array<(code: number | null, signal: NodeJS.Signals | null) => void> = []
  #errorListeners: Array<(error: Error) => void> = []

  kill(signal?: NodeJS.Signals): boolean {
    this.signals.push(signal ?? 'SIGTERM')
    if (this.exitOnKill) {
      queueMicrotask(() => { this.emitExit(0, signal ?? 'SIGTERM') })
    }
    return this.killSucceeds
  }

  once(event: 'exit', listener: (code: number | null, signal: NodeJS.Signals | null) => void): this
  once(event: 'error', listener: (error: Error) => void): this
  once(event: 'exit' | 'error', listener: (...args: never[]) => void): this {
    if (event === 'exit') {
      this.#exitListeners.push(listener as (code: number | null, signal: NodeJS.Signals | null) => void)
    } else {
      this.#errorListeners.push(listener as (error: Error) => void)
    }
    return this
  }

  removeAllListeners(): this {
    this.#exitListeners = []
    this.#errorListeners = []
    return this
  }

  emitExit(code: number | null, signal: NodeJS.Signals | null): void {
    const listeners = this.#exitListeners
    this.#exitListeners = []
    for (const listener of listeners) listener(code, signal)
  }

  emitError(error: Error): void {
    const listeners = this.#errorListeners
    this.#errorListeners = []
    for (const listener of listeners) listener(error)
  }
}

interface Harness {
  assertion: SleepAssertion
  children: FakeChild[]
  warnings: string[]
  infos: string[]
  spawnCount: () => number
}

function harness(options: {
  maxHoldMs?: number
  killGraceMs?: number
  spawnThrows?: Error
  child?: FakeChild
} = {}): Harness {
  const children: FakeChild[] = []
  const warnings: string[] = []
  const infos: string[] = []
  let spawns = 0

  const assertion = new SleepAssertion({
    command: 'caffeinate',
    args: ['-i'],
    maxHoldMs: options.maxHoldMs ?? 60_000,
    killGraceMs: options.killGraceMs ?? 2_000,
    onWarn: (message) => { warnings.push(message) },
    onInfo: (message) => { infos.push(message) },
    spawn: () => {
      spawns += 1
      if (options.spawnThrows !== undefined) throw options.spawnThrows
      const child = options.child ?? new FakeChild()
      children.push(child)
      return child as unknown as ChildLike
    },
  })

  return { assertion, children, warnings, infos, spawnCount: () => spawns }
}

const tick = (): Promise<void> => new Promise((resolve) => { setImmediate(resolve) })

describe('SleepAssertion construction', () => {
  test('rejects a non-positive maxHoldMs', () => {
    assert.throws(
      () => new SleepAssertion({ command: 'x', args: [], maxHoldMs: 0 }),
      RangeError,
    )
    assert.throws(
      () => new SleepAssertion({ command: 'x', args: [], maxHoldMs: -1 }),
      RangeError,
    )
  })

  test('rejects a negative killGraceMs', () => {
    assert.throws(
      () => new SleepAssertion({ command: 'x', args: [], maxHoldMs: 1000, killGraceMs: -1 }),
      RangeError,
    )
  })
})

describe('acquire', () => {
  test('spawns once and reports held', () => {
    const h = harness()
    h.assertion.acquire()
    assert.equal(h.assertion.held, true)
    assert.equal(h.spawnCount(), 1)
  })

  test('is idempotent, so a duplicated event cannot leak a second process', () => {
    const h = harness()
    h.assertion.acquire()
    h.assertion.acquire()
    h.assertion.acquire()
    assert.equal(h.spawnCount(), 1, 'three acquires must start one process')
  })

  test('warns and stays un-held when spawn throws synchronously', () => {
    const h = harness({ spawnThrows: new Error('spawn caffeinate ENOENT') })
    h.assertion.acquire()
    assert.equal(h.assertion.held, false)
    assert.equal(h.warnings.length, 1)
    assert.match(h.warnings[0] ?? '', /ENOENT/)
  })

  test('warns and releases when the child reports an async spawn failure', async () => {
    const child = new FakeChild()
    const h = harness({ child })
    h.assertion.acquire()
    assert.equal(h.assertion.held, true)

    child.emitError(new Error('EACCES'))
    assert.equal(h.assertion.held, false, 'a failed spawn must not look held')
    assert.match(h.warnings[0] ?? '', /EACCES/)
  })

  // If the assertion process dies while work continues, our model of the world
  // is wrong. Saying nothing would leave the user with a machine that sleeps
  // mid-task and no explanation.
  test('warns when the child exits while we still believe it is held', () => {
    const child = new FakeChild()
    const h = harness({ child })
    h.assertion.acquire()
    child.emitExit(0, null)
    assert.equal(h.assertion.held, false)
    assert.match(h.warnings[0] ?? '', /exited early/)
  })

  test('ignores late events from a previous child after re-acquire', async () => {
    const first = new FakeChild()
    const second = new FakeChild()
    const children = [first, second]
    let index = 0
    const warnings: string[] = []
    const assertion = new SleepAssertion({
      command: 'caffeinate',
      args: ['-i'],
      maxHoldMs: 60_000,
      killGraceMs: 50,
      onWarn: (m) => { warnings.push(m) },
      spawn: () => children[index++] as unknown as ChildLike,
    })

    assertion.acquire()
    await assertion.release()
    assertion.acquire()
    warnings.length = 0

    first.emitExit(0, null)
    assert.equal(assertion.held, true, 'the stale child must not clear the new assertion')
    assert.deepEqual(warnings, [])
  })
})

describe('release', () => {
  test('sends SIGTERM and waits for the child to exit', async () => {
    const child = new FakeChild()
    const h = harness({ child })
    h.assertion.acquire()
    await h.assertion.release()
    assert.deepEqual(child.signals, ['SIGTERM'])
    assert.equal(h.assertion.held, false)
  })

  test('is a no-op when nothing is held', async () => {
    const h = harness()
    await h.assertion.release()
    assert.equal(h.spawnCount(), 0)
    assert.deepEqual(h.warnings, [])
  })

  test('is idempotent across concurrent calls', async () => {
    const child = new FakeChild()
    const h = harness({ child })
    h.assertion.acquire()
    await Promise.all([h.assertion.release(), h.assertion.release(), h.assertion.release()])
    assert.deepEqual(child.signals, ['SIGTERM'], 'one kill, not three')
  })

  test('escalates to SIGKILL when the child ignores SIGTERM', async () => {
    const child = new FakeChild()
    child.exitOnKill = false
    const h = harness({ child, killGraceMs: 20 })
    h.assertion.acquire()
    await h.assertion.release()
    assert.deepEqual(child.signals, ['SIGTERM', 'SIGKILL'])
    assert.match(h.warnings.join('\n'), /ignored SIGTERM/)
  })

  test('does not hang when the child ignores both signals', async () => {
    const child = new FakeChild()
    child.exitOnKill = false
    child.killSucceeds = false
    const h = harness({ child, killGraceMs: 20 })
    h.assertion.acquire()
    await h.assertion.release()
    assert.equal(h.assertion.held, false)
  })

  test('warns but resolves when kill throws', async () => {
    const child = new FakeChild()
    child.kill = () => { throw new Error('ESRCH') }
    const h = harness({ child })
    h.assertion.acquire()
    await h.assertion.release()
    assert.equal(h.assertion.held, false)
    assert.match(h.warnings[0] ?? '', /ESRCH/)
  })
})

describe('max-hold backstop', () => {
  // Bounds the damage from a missed work-finished signal. Without it, one lost
  // event would keep the machine awake until the host restarts.
  test('force-releases after maxHoldMs and says why', async () => {
    const child = new FakeChild()
    const h = harness({ child, maxHoldMs: 30 })
    h.assertion.acquire()
    assert.equal(h.assertion.held, true)

    await new Promise((resolve) => { setTimeout(resolve, 80) })
    assert.equal(h.assertion.held, false, 'the backstop must have fired')
    assert.deepEqual(child.signals, ['SIGTERM'])
    assert.match(h.warnings.join('\n'), /forcing release/)
  })

  test('does not fire while the assertion is released normally', async () => {
    const h = harness({ maxHoldMs: 30 })
    h.assertion.acquire()
    await h.assertion.release()
    await new Promise((resolve) => { setTimeout(resolve, 60) })
    assert.deepEqual(h.warnings, [], 'a normal release must not trigger the backstop')
  })
})

describe('re-acquire during a draining release', () => {
  /**
   * The two halves of this have to be tested together.
   *
   * An earlier version of this suite checked only the first half -- no second
   * process while the old one is still alive -- and passed while the harmful
   * half was broken. `acquire()` during a drain returned without doing anything,
   * on the theory that "the next update() from the host will re-acquire". There
   * may not be a next update: the busy state can stay true with no further
   * events, and then the machine holds no assertion at all while work runs.
   *
   * Asserting only the safe half is how a test locks a bug in.
   */
  test('does not leak a second process while the old one is draining', async () => {
    const child = new FakeChild()
    child.exitOnKill = false
    const h = harness({ child, killGraceMs: 30 })
    h.assertion.acquire()

    const releasing = h.assertion.release()
    h.assertion.acquire()
    assert.equal(h.spawnCount(), 1, 'no second process while draining')

    await releasing
    await tick()
  })

  test('takes a new assertion once the drain finishes, because work is still running', async () => {
    const h = harness({ killGraceMs: 10_000 })
    h.assertion.acquire()
    const first = h.children[0]!
    first.exitOnKill = false

    const releasing = h.assertion.release()
    assert.equal(h.assertion.held, false, 'the old child is being killed')

    // Work resumes before the old child has gone.
    h.assertion.acquire()
    assert.equal(h.spawnCount(), 1, 'still only one process while draining')

    // The old child finally exits and the drain completes.
    first.emitExit(0, 'SIGTERM')
    await releasing
    await tick()

    assert.equal(
      h.assertion.held,
      true,
      'work is still running and no further event is coming; an assertion must be held',
    )
    assert.equal(h.spawnCount(), 2)
  })

  test('a deferred acquire does not survive dispose', async () => {
    const h = harness({ killGraceMs: 10_000 })
    h.assertion.acquire()
    const first = h.children[0]!
    first.exitOnKill = false

    const releasing = h.assertion.release()
    h.assertion.acquire() // deferred until the drain ends
    h.assertion.dispose() // ...but the plugin is shutting down

    first.emitExit(0, 'SIGTERM')
    await releasing
    await tick()

    assert.equal(h.assertion.held, false, 'a disposed assertion must not take a new one')
    assert.equal(h.spawnCount(), 1, 'dispose must not restart the process')
  })

  test('acquire after dispose is inert', () => {
    const h = harness()
    h.assertion.dispose()
    h.assertion.acquire()
    assert.equal(h.assertion.held, false)
    assert.equal(h.spawnCount(), 0)
  })

  test('dispose releases an assertion that is currently held', async () => {
    const h = harness({ killGraceMs: 30 })
    h.assertion.acquire()
    assert.equal(h.assertion.held, true)

    h.assertion.dispose()
    await h.assertion.release()
    assert.equal(h.assertion.held, false)
  })
})

describe('defaultAssertionCommand', () => {
  test('uses caffeinate -i on macOS', () => {
    assert.deepEqual(defaultAssertionCommand('darwin'), { command: 'caffeinate', args: ['-i'] })
  })

  // -s would also block display sleep, leaving the screen lit in an empty room.
  test('does not pass -s on macOS', () => {
    const result = defaultAssertionCommand('darwin')
    assert.ok(result !== undefined && !result.args.includes('-s'))
  })

  test('uses systemd-inhibit on linux', () => {
    const result = defaultAssertionCommand('linux')
    assert.equal(result?.command, 'systemd-inhibit')
    assert.ok(result?.args.includes('--mode=block'))
  })

  test('returns undefined on unsupported platforms rather than guessing', () => {
    assert.equal(defaultAssertionCommand('win32'), undefined)
    assert.equal(defaultAssertionCommand('freebsd'), undefined)
  })
})
