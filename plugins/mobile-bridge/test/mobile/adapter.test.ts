import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { MobileAdapter } from '../../src/mobile/adapter.ts'
import { CommandStore } from '../../src/mobile/command-store.ts'
import { PROTOCOL_VERSION, REFUSAL } from '../../src/mobile/protocol.ts'
import type { Operation, OperationHandler } from '../../src/mobile/adapter.ts'

/** A handler that records what it was asked to do. */
function spyHandler(result: unknown = 'ok') {
  const calls: unknown[] = []
  const handler: OperationHandler = async (payload) => {
    calls.push(payload)
    return result
  }
  return { handler, calls }
}

function build(options: {
  handlers?: Partial<Record<Operation, OperationHandler>>
  scopes?: string[]
  protocols?: number[]
  store?: CommandStore
} = {}) {
  const handlers = options.handlers ?? { 'computer.status': async () => 'ok' }
  return new MobileAdapter({
    computerId: 'pc-1',
    computerName: '我的 Mac',
    adapterVersion: '1.0.0',
    hostVersion: '0.2.0-rc.2',
    handlers,
    grantedScopes: options.scopes ?? ['sessions.read'],
    ...(options.protocols !== undefined ? { supportedProtocols: options.protocols } : {}),
    ...(options.store !== undefined ? { store: options.store } : {}),
  })
}

const envelope = (over: Record<string, unknown> = {}) => ({
  protocolVersion: PROTOCOL_VERSION,
  computerId: 'pc-1',
  commandId: 'c-1',
  operation: 'computer.status',
  payload: {},
  ...over,
})

describe('handshake', () => {
  test('reports what the build can actually do', () => {
    const adapter = build({
      handlers: {
        'computer.status': async () => 'ok',
        'session.list': async () => [],
      },
      scopes: ['sessions.read'],
    })
    const status = adapter.status()
    assert.deepEqual(status.capabilities, ['computer.status', 'session.list'])
    assert.equal(status.protocolVersion, PROTOCOL_VERSION)
    assert.equal(status.computerId, 'pc-1')
  })

  // Capabilities come from the handlers, so an operation with no handler is
  // absent rather than present-and-failing. A phone that trusts a version table
  // instead finds out at call time, in front of a user.
  test('an operation without a handler is not advertised', () => {
    const adapter = build({ handlers: { 'computer.status': async () => 'ok' } })
    assert.deepEqual(adapter.status().capabilities, ['computer.status'])
  })

  test('host and adapter versions are reported separately', () => {
    const status = build().status()
    assert.equal(status.hostVersion, '0.2.0-rc.2')
    assert.equal(status.adapterVersion, '1.0.0')
  })

  test('granted scopes are reported verbatim', () => {
    const adapter = build({ scopes: ['sessions.read', 'tasks.submit'] })
    assert.deepEqual([...adapter.status().grantedScopes], ['sessions.read', 'tasks.submit'])
  })
})

describe('refusals', () => {
  test('a protocol this adapter does not speak is refused', () => {
    const outcome = build().handle(envelope({ protocolVersion: 99 })) as Promise<{
      code: string
      message: string
    }>
    return outcome.then((result) => {
      assert.equal(result.code, REFUSAL.UNSUPPORTED_PROTOCOL)
      // The message must distinguish "one of the two needs updating" from
      // "you are not allowed", because those have different fixes.
      assert.match(result.message, /needs updating/)
    })
  })

  test('a missing protocol version is refused, not assumed', () => {
    return build().handle(envelope({ protocolVersion: undefined })).then((result) => {
      assert.equal(result.status, 'rejected')
      assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNSUPPORTED_PROTOCOL)
    })
  })

  test('an operation nobody implements is refused by name', () => {
    return build().handle(envelope({ operation: 'host.reboot' })).then((result) => {
      assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNKNOWN_OPERATION)
    })
  })

  test('a real operation this build cannot serve says so', () => {
    return build().handle(envelope({ operation: 'session.list' })).then((result) => {
      assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.MISSING_CAPABILITY)
    })
  })

  test('an operation the device was not granted is refused', () => {
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': async () => 'submitted' },
      scopes: ['sessions.read'],
    })
    return adapter.handle(envelope({ operation: 'task.submit' })).then((result) => {
      assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.FORBIDDEN_SCOPE)
    })
  })

  // With two computers paired, this is how a stale notification or a mixed-up
  // draft would otherwise act on the wrong machine.
  test('a command addressed to another computer is refused', () => {
    return build().handle(envelope({ computerId: 'pc-2' })).then((result) => {
      assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.WRONG_COMPUTER)
    })
  })

  // Hiding a button is not access control. This runs per request, on the
  // computer, whatever the phone chose to display.
  test('every command is checked, not just the first', () => {
    const spy = spyHandler()
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
      scopes: ['sessions.read'], // deliberately missing tasks.submit
    })
    return Promise.all([
      adapter.handle(envelope({ operation: 'task.submit', commandId: 'a' })),
      adapter.handle(envelope({ operation: 'task.submit', commandId: 'b' })),
    ]).then((results) => {
      for (const result of results) {
        assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.FORBIDDEN_SCOPE)
      }
      assert.equal(spy.calls.length, 0, 'the handler must not run at all')
    })
  })
})

describe('approvals', () => {
  const withApproval = () =>
    build({
      handlers: { 'computer.status': async () => 'ok', 'approval.decide': spyHandler('decided').handler },
      scopes: ['sessions.read', 'approvals.respond'],
    })

  test('an explicit allow is accepted', () => {
    return withApproval()
      .handle(envelope({ operation: 'approval.decide', payload: { decision: 'allow-once' } }))
      .then((result) => assert.equal(result.status, 'accepted'))
  })

  test('an explicit deny is accepted', () => {
    return withApproval()
      .handle(envelope({ operation: 'approval.decide', payload: { decision: 'deny' } }))
      .then((result) => assert.equal(result.status, 'accepted'))
  })

  // The rule the plan states without qualification. Every one of these is a
  // request that could not be understood, and none of them may become consent.
  test('anything unreadable is refused rather than allowed', async () => {
    const cases: unknown[] = [
      undefined,
      null,
      {},
      { decision: true },
      { decision: false },
      { decision: 'allow' },
      { decision: 'allow-always' },
      { decision: 'ALLOW-ONCE' },
      { decision: '' },
      { decision: { nested: 'allow-once' } },
      'allow-once',
    ]
    for (const payload of cases) {
      const result = await withApproval().handle(
        envelope({ operation: 'approval.decide', commandId: `c-${String(cases.indexOf(payload))}`, payload }),
      )
      assert.equal(
        result.status === 'rejected' ? result.code : '',
        REFUSAL.UNKNOWN_APPROVAL_TYPE,
        `payload ${JSON.stringify(payload)} must not be accepted`,
      )
    }
  })

  test('a refused approval never reaches the handler', async () => {
    const spy = spyHandler('decided')
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'approval.decide': spy.handler },
      scopes: ['sessions.read', 'approvals.respond'],
    })
    await adapter.handle(envelope({ operation: 'approval.decide', payload: { decision: 'yes' } }))
    assert.equal(spy.calls.length, 0)
  })
})

describe('idempotency', () => {
  const writable = () =>
    build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spyHandler({ taskId: 't-1' }).handler },
      scopes: ['sessions.read', 'tasks.submit'],
    })

  test('a retry with the same id replays instead of running twice', async () => {
    const spy = spyHandler({ taskId: 't-1' })
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
      scopes: ['sessions.read', 'tasks.submit'],
    })

    const first = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'same', payload: { text: 'do it' } }),
    )
    const second = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'same', payload: { text: 'do it' } }),
    )

    assert.equal(first.status, 'accepted')
    assert.equal(second.status, 'replayed')
    assert.equal(spy.calls.length, 1, 'the task must be submitted exactly once')
  })

  test('the same id with different content is a conflict', async () => {
    const adapter = writable()
    await adapter.handle(envelope({ operation: 'task.submit', commandId: 'same', payload: { text: 'a' } }))
    const second = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'same', payload: { text: 'b' } }),
    )
    assert.equal(second.status === 'rejected' ? second.code : '', REFUSAL.COMMAND_CONFLICT)
  })

  // The crash window between registering and recording. Replaying could submit
  // twice; forgetting could make a submitted task look lost.
  test('a command whose outcome was lost is unknown, not replayed', async () => {
    const store = new CommandStore()
    const spy = spyHandler({ taskId: 't-1' })
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
      scopes: ['sessions.read', 'tasks.submit'],
      store,
    })

    // Simulate the crash: the record exists, no result was written.
    store.begin('lost', { text: 'do it' })

    const result = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'lost', payload: { text: 'do it' } }),
    )
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.COMMAND_STATE_UNKNOWN)
    assert.equal(spy.calls.length, 0, 'an unknown outcome must not be replayed')
  })

  test('a write without a command id is refused', async () => {
    const adapter = writable()
    const result = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: '', payload: { text: 'x' } }),
    )
    assert.equal(result.status, 'rejected')
  })

  // A handler that throws may have reached the host before it did. Recording a
  // failure would invite a retry that runs the work a second time.
  test('a failing write reports unknown rather than failed', async () => {
    const adapter = build({
      handlers: {
        'computer.status': async () => 'ok',
        'task.submit': async () => { throw new Error('socket closed') },
      },
      scopes: ['sessions.read', 'tasks.submit'],
    })
    const result = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'c', payload: { text: 'x' } }),
    )
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.COMMAND_STATE_UNKNOWN)
    assert.match(result.status === 'rejected' ? result.message : '', /socket closed/)

    // And the record stays unusable, so a retry cannot slip through.
    const retry = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'c', payload: { text: 'x' } }),
    )
    assert.equal(retry.status === 'rejected' ? retry.code : '', REFUSAL.COMMAND_STATE_UNKNOWN)
  })

  // Reads are not deduplicated: asking again about a task that is still growing
  // is a legitimate second look, not a duplicate.
  test('a repeated read is served again, not replayed', async () => {
    const spy = spyHandler(['one'])
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'session.list': spy.handler },
      scopes: ['sessions.read'],
    })
    const first = await adapter.handle(envelope({ operation: 'session.list', commandId: 'r' }))
    const second = await adapter.handle(envelope({ operation: 'session.list', commandId: 'r' }))
    assert.equal(first.status, 'accepted')
    assert.equal(second.status, 'accepted')
    assert.equal(spy.calls.length, 2)
  })
})

describe('handler failure', () => {
  test('a read that fails reports the reason', async () => {
    const adapter = build({
      handlers: {
        'computer.status': async () => 'ok',
        'session.list': async () => { throw new Error('host not ready') },
      },
      scopes: ['sessions.read'],
    })
    const result = await adapter.handle(envelope({ operation: 'session.list' }))
    assert.equal(result.status, 'rejected')
    assert.match(result.status === 'rejected' ? result.message : '', /host not ready/)
  })
})
