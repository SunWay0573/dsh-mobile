import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import { MobileAdapter } from '../../src/mobile/adapter.ts'
import { CommandStore } from '../../src/mobile/command-store.ts'
import { PROTOCOL_VERSION, REFUSAL } from '../../src/mobile/protocol.ts'
import type { Operation, OperationHandler } from '../../src/mobile/adapter.ts'
import type { DeviceRecord } from '../../src/mobile/protocol.ts'

/** A handler that records what it was asked to do. */
function spyHandler(result: unknown = 'ok') {
  const calls: unknown[] = []
  const handler: OperationHandler = async (payload) => {
    calls.push(payload)
    return result
  }
  return { handler, calls }
}

/**
 * A device registry the test can change while the adapter is alive.
 *
 * This is the point of the fix: the adapter must ask this on every request, so
 * emptying a grant here takes effect on the next one.
 */
function registry(initial: Record<string, DeviceRecord> = {
  'dev-1': { deviceId: 'dev-1', grantedScopes: [...FULL], authorized: true },
}) {
  const devices = new Map(Object.entries(initial))
  return {
    devices,
    resolve: (deviceId: string) => devices.get(deviceId),
    grant: (deviceId: string, scopes: DeviceRecord['grantedScopes']) =>
      devices.set(deviceId, { deviceId, grantedScopes: scopes, authorized: true }),
    revoke: (deviceId: string) =>
      devices.set(deviceId, { deviceId, grantedScopes: [], authorized: false }),
    forget: (deviceId: string) => devices.delete(deviceId),
  }
}

const FULL = [
  'sessions.read', 'tasks.submit', 'tasks.cancel',
  'approvals.respond', 'questions.read', 'questions.respond', 'files.preview',
] as const

function build(options: {
  handlers?: Partial<Record<Operation, OperationHandler>>
  devices?: ReturnType<typeof registry>
  protocols?: number[]
  store?: CommandStore
} = {}) {
  const devices = options.devices ?? registry({ 'dev-1': { deviceId: 'dev-1', grantedScopes: [...FULL], authorized: true } })
  return new MobileAdapter({
    computerId: 'pc-1',
    computerName: '我的 Mac',
    adapterVersion: '1.0.0',
    hostVersion: '0.2.0-rc.2',
    handlers: options.handlers ?? { 'computer.status': async () => 'ok' },
    resolveDevice: devices.resolve,
    ...(options.protocols !== undefined ? { supportedProtocols: options.protocols } : {}),
    ...(options.store !== undefined ? { store: options.store } : {}),
  })
}

const envelope = (over: Record<string, unknown> = {}) => ({
  protocolVersion: PROTOCOL_VERSION,
  computerId: 'pc-1',
  deviceId: 'dev-1',
  commandId: 'c-1',
  operation: 'computer.status',
  payload: {},
  ...over,
})

describe('handshake', () => {
  test('reports what the build can actually do', () => {
    const status = build({
      handlers: { 'computer.status': async () => 'ok', 'session.list': async () => [] },
    }).status('dev-1')
    assert.deepEqual(status.capabilities, ['computer.status', 'session.list'])
  })

  test('an operation without a handler is not advertised', () => {
    assert.deepEqual(build().status('dev-1').capabilities, ['computer.status'])
  })

  test('host and adapter versions are reported separately', () => {
    const status = build().status('dev-1')
    assert.equal(status.hostVersion, '0.2.0-rc.2')
    assert.equal(status.adapterVersion, '1.0.0')
  })

  /**
   * The review's reproduction: an adapter configured for [2] reported version 1,
   * and a client that followed that report was then refused by the adapter that
   * had told it what to send.
   */
  test('the reported protocol is one the adapter actually accepts', async () => {
    const adapter = build({ protocols: [2] })
    const status = adapter.status('dev-1')
    assert.equal(status.protocolVersion, PROTOCOL_VERSION)
    // Everything the adapter claims to speak must be listed for the client to
    // choose from, so a client never has to trust the single reported value.
    assert.deepEqual(status.supportedProtocols, [2])
  })

  test('the supported list is reported so a client can negotiate', () => {
    assert.deepEqual(build({ protocols: [1, 2] }).status('dev-1').supportedProtocols, [1, 2])
  })

  test('scopes reported are this device, not the computer', () => {
    const devices = registry({
      'dev-1': { deviceId: 'dev-1', grantedScopes: ['sessions.read'], authorized: true },
      'dev-2': { deviceId: 'dev-2', grantedScopes: [...FULL], authorized: true },
    })
    const adapter = build({ devices })
    assert.deepEqual([...adapter.status('dev-1').grantedScopes], ['sessions.read'])
    assert.equal(adapter.status('dev-2').grantedScopes.length, 7)
  })

  test('an unknown device is reported as having no scopes', () => {
    assert.deepEqual([...build().status('nobody').grantedScopes], [])
  })

  test('a revoked device is reported as having no scopes', () => {
    const devices = registry()
    devices.revoke('dev-1')
    assert.deepEqual([...build({ devices }).status('dev-1').grantedScopes], [])
  })
})

describe('live permissions', () => {
  /**
   * The review's reproduction, as a test. Before the fix the adapter copied the
   * grant at construction, so a revocation mid-session was invisible and the
   * handler ran again.
   */
  test('revoking mid-session stops the next request', async () => {
    const spy = spyHandler(['one'])
    const devices = registry()
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'session.list': spy.handler },
      devices,
    })

    const first = await adapter.handle(envelope({ operation: 'session.list' }))
    assert.equal(first.status, 'accepted')
    assert.equal(spy.calls.length, 1)

    devices.revoke('dev-1')

    const second = await adapter.handle(envelope({ operation: 'session.list' }))
    assert.equal(second.status === 'rejected' ? second.code : '', REFUSAL.DEVICE_REVOKED)
    assert.equal(spy.calls.length, 1, 'the handler must not run again after revocation')
  })

  test('narrowing a grant mid-session stops the next request', async () => {
    const spy = spyHandler('submitted')
    const devices = registry()
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
      devices,
    })

    const first = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'a', payload: { text: 'x' } }),
    )
    assert.equal(first.status, 'accepted')

    devices.grant('dev-1', ['sessions.read'])

    const second = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'b', payload: { text: 'y' } }),
    )
    assert.equal(second.status === 'rejected' ? second.code : '', REFUSAL.FORBIDDEN_SCOPE)
    assert.equal(spy.calls.length, 1)
  })

  test('restoring a grant takes effect without a restart', async () => {
    const spy = spyHandler('submitted')
    const devices = registry()
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
      devices,
    })

    devices.grant('dev-1', ['sessions.read'])
    const refused = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'a', payload: { text: 'x' } }),
    )
    assert.equal(refused.status === 'rejected' ? refused.code : '', REFUSAL.FORBIDDEN_SCOPE)

    devices.grant('dev-1', ['sessions.read', 'tasks.submit'])
    const accepted = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'a', payload: { text: 'x' } }),
    )
    assert.equal(accepted.status, 'accepted')
  })

  test('a forgotten device is refused', async () => {
    const devices = registry()
    devices.forget('dev-1')
    const result = await build({ devices }).handle(envelope())
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.DEVICE_REVOKED)
  })

  test('two devices hold their own grants', async () => {
    const devices = registry()
    devices.grant('dev-1', ['sessions.read'])
    devices.grant('dev-2', ['sessions.read', 'tasks.submit'])
    const spy = spyHandler('submitted')
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
      devices,
    })

    const asOne = await adapter.handle(
      envelope({ deviceId: 'dev-1', operation: 'task.submit', commandId: 'a', payload: { text: 'x' } }),
    )
    assert.equal(asOne.status === 'rejected' ? asOne.code : '', REFUSAL.FORBIDDEN_SCOPE)

    const asTwo = await adapter.handle(
      envelope({ deviceId: 'dev-2', operation: 'task.submit', commandId: 'a', payload: { text: 'x' } }),
    )
    assert.equal(asTwo.status, 'accepted')
  })
})

describe('identity', () => {
  test('a request with no device is refused', async () => {
    const result = await build().handle(envelope({ deviceId: undefined }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNAUTHENTICATED)
  })

  test('an empty device id is refused', async () => {
    const result = await build().handle(envelope({ deviceId: '' }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNAUTHENTICATED)
  })

  // Identity is checked before capability, so an unauthenticated caller is not
  // told which operations this computer has.
  test('an unauthenticated request does not reveal capabilities', async () => {
    const result = await build({ handlers: { 'computer.status': async () => 'ok' } })
      .handle(envelope({ deviceId: undefined, operation: 'host.reboot' }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNAUTHENTICATED)
  })
})

describe('refusal classification', () => {
  test('a protocol this adapter does not speak is refused', async () => {
    const result = await build().handle(envelope({ protocolVersion: 99 }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNSUPPORTED_PROTOCOL)
    assert.match(result.status === 'rejected' ? result.message : '', /needs updating/)
  })

  test('a missing protocol version is refused, not assumed', async () => {
    const result = await build().handle(envelope({ protocolVersion: undefined }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNSUPPORTED_PROTOCOL)
  })

  test('an operation nobody implements is refused by name', async () => {
    const result = await build().handle(envelope({ operation: 'host.reboot' }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNKNOWN_OPERATION)
  })

  test('a real operation this build cannot serve says so', async () => {
    const result = await build().handle(envelope({ operation: 'session.list' }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.MISSING_CAPABILITY)
  })

  test('a command for another computer is refused', async () => {
    const result = await build().handle(envelope({ computerId: 'pc-2' }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.WRONG_COMPUTER)
  })

  // Updating the app does not restore a revoked grant, so these must not read
  // as version problems.
  test('a revoked device is not reported as a version problem', async () => {
    const devices = registry()
    devices.revoke('dev-1')
    const result = await build({ devices }).handle(envelope())
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.DEVICE_REVOKED)
    assert.doesNotMatch(result.status === 'rejected' ? result.message : '', /更新|update/)
  })
})

describe('payload validation', () => {
  /**
   * The review's reproduction: `task.submit` with no payload reached the
   * canonicaliser, which threw on `undefined.length` and escaped through a
   * method documented never to throw.
   */
  test('a write with no payload is refused, not thrown', async () => {
    const spy = spyHandler('submitted')
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
    })

    const result = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'a', payload: undefined }),
    )
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.INVALID_PAYLOAD)
    assert.equal(spy.calls.length, 0)
  })

  test('a null payload is refused too', async () => {
    const result = await build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': async () => 'x' },
    }).handle(envelope({ operation: 'task.submit', commandId: 'a', payload: null }))
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.INVALID_PAYLOAD)
  })

  // A refused request must not leave a record behind, or the id becomes
  // unusable and a corrected retry is answered with "unknown".
  test('a refused payload does not register a command', async () => {
    const store = new CommandStore()
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': async () => 'x' },
      store,
    })
    await adapter.handle(envelope({ operation: 'task.submit', commandId: 'a', payload: undefined }))
    assert.equal(store.size, 0)
  })

  test('a write with an empty command id is refused', async () => {
    const result = await build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': async () => 'x' },
    }).handle(envelope({ operation: 'task.submit', commandId: '', payload: { text: 'x' } }))
    assert.equal(result.status, 'rejected')
  })

  test('reads do not need a payload', async () => {
    const result = await build({
      handlers: { 'computer.status': async () => 'ok', 'session.list': async () => [] },
    }).handle(envelope({ operation: 'session.list' }))
    assert.equal(result.status, 'accepted')
  })

  test('a malformed envelope is refused rather than raising', async () => {
    const adapter = build()
    for (const bad of [undefined, null, {}, { protocolVersion: 'one' }]) {
      const result = await adapter.handle(bad as never)
      assert.equal(result.status, 'rejected')
    }
  })
})

describe('approvals', () => {
  const withApproval = () => {
    const spy = spyHandler('decided')
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'approval.decide': spy.handler },
    })
    return { adapter, spy }
  }

  /**
   * The answer could not be read. This is *not* the same as an unrecognised
   * question, and the earlier report conflated them.
   */
  test('no body at all is refused as a missing body', async () => {
    const { adapter, spy } = withApproval()
    for (const [index, payload] of [undefined, null].entries()) {
      const result = await adapter.handle(
        envelope({ operation: 'approval.decide', commandId: `none-${index}`, payload }),
      )
      assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.INVALID_PAYLOAD)
    }
    assert.equal(spy.calls.length, 0)
  })

  /**
   * A body arrived and its `decision` could not be read. Two refusals, both of
   * which mean "nothing happened": a missing body is a malformed envelope, an
   * unreadable decision is a malformed answer. Neither is ever an allow.
   */
  test('an unreadable decision is refused as a malformed decision', async () => {
    const { adapter } = withApproval()
    const cases: unknown[] = [
      {}, { decision: true }, { decision: false },
      { decision: 'allow' }, { decision: 'allow-always' }, { decision: 'ALLOW-ONCE' },
      { decision: '' }, { decision: { nested: 'allow-once' } }, { decision: ['allow-once'] },
      'allow-once', 42,
    ]
    for (const [index, payload] of cases.entries()) {
      const result = await adapter.handle(
        envelope({ operation: 'approval.decide', commandId: `bad-${index}`, payload }),
      )
      assert.equal(
        result.status === 'rejected' ? result.code : '',
        REFUSAL.MALFORMED_APPROVAL_DECISION,
        `payload ${JSON.stringify(payload)} must be refused as malformed`,
      )
    }
  })

  /**
   * The question could not be placed. There is no approval owner yet, so even a
   * well-formed decision is refused — an approval nobody can verify must not be
   * actionable. This is a deliberate refusal, not an unimplemented path that
   * happens to fall through.
   */
  test('a well-formed decision is still refused while there is no owner', async () => {
    const { adapter, spy } = withApproval()
    for (const decision of ['allow-once', 'deny']) {
      const result = await adapter.handle(
        envelope({ operation: 'approval.decide', commandId: `c-${decision}`, payload: { decision } }),
      )
      assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.UNKNOWN_APPROVAL_TYPE)
    }
    assert.equal(spy.calls.length, 0, 'the handler must never run without an owner')
  })

  test('the two approval refusals are distinguishable', async () => {
    const { adapter } = withApproval()
    const malformed = await adapter.handle(
      envelope({ operation: 'approval.decide', commandId: 'a', payload: { decision: 'yes' } }),
    )
    const unplaceable = await adapter.handle(
      envelope({ operation: 'approval.decide', commandId: 'b', payload: { decision: 'allow-once' } }),
    )
    assert.notEqual(
      malformed.status === 'rejected' ? malformed.code : '',
      unplaceable.status === 'rejected' ? unplaceable.code : '',
    )
  })
})

describe('idempotency', () => {
  test('a retry with the same id replays instead of running twice', async () => {
    const spy = spyHandler({ taskId: 't-1' })
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
    })

    const first = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'same', payload: { text: 'do it' } }),
    )
    const second = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'same', payload: { text: 'do it' } }),
    )

    assert.equal(first.status, 'accepted')
    assert.equal(second.status, 'replayed')
    assert.equal(spy.calls.length, 1)
  })

  /** The review's first idempotency reproduction, through the adapter. */
  test('the same id with a different operation is a conflict, not a replay', async () => {
    const submit = spyHandler('submitted')
    const cancel = spyHandler('cancelled')
    const adapter = build({
      handlers: {
        'computer.status': async () => 'ok',
        'task.submit': submit.handler,
        'task.cancel': cancel.handler,
      },
    })

    const first = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'same', payload: { taskId: 't-1' } }),
    )
    assert.equal(first.status, 'accepted')

    const second = await adapter.handle(
      envelope({ operation: 'task.cancel', commandId: 'same', payload: { taskId: 't-1' } }),
    )
    assert.equal(second.status === 'rejected' ? second.code : '', REFUSAL.COMMAND_CONFLICT)
    assert.equal(cancel.calls.length, 0, 'the cancel must not silently become a submit replay')
  })

  test('the same id with different content is a conflict', async () => {
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': async () => 'x' },
    })
    await adapter.handle(envelope({ operation: 'task.submit', commandId: 'same', payload: { text: 'a' } }))
    const second = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'same', payload: { text: 'b' } }),
    )
    assert.equal(second.status === 'rejected' ? second.code : '', REFUSAL.COMMAND_CONFLICT)
  })

  test('the same id from another device does not collide', async () => {
    const devices = registry()
    devices.grant('dev-1', ['sessions.read', 'tasks.submit'])
    devices.grant('dev-2', ['sessions.read', 'tasks.submit'])
    const spy = spyHandler('submitted')
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
      devices,
    })

    await adapter.handle(
      envelope({ deviceId: 'dev-1', operation: 'task.submit', commandId: 'same', payload: { text: 'x' } }),
    )
    const second = await adapter.handle(
      envelope({ deviceId: 'dev-2', operation: 'task.submit', commandId: 'same', payload: { text: 'x' } }),
    )
    assert.equal(second.status, 'accepted', 'another phone must not replay this one\'s result')
    assert.equal(spy.calls.length, 2)
  })

  test('a command whose outcome was lost is unknown, not replayed', async () => {
    const store = new CommandStore()
    const spy = spyHandler({ taskId: 't-1' })
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'task.submit': spy.handler },
      store,
    })

    store.begin({
      computerId: 'pc-1', deviceId: 'dev-1', commandId: 'lost',
      operation: 'task.submit', payload: { text: 'do it' },
    })

    const result = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'lost', payload: { text: 'do it' } }),
    )
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.COMMAND_STATE_UNKNOWN)
    assert.equal(spy.calls.length, 0)
  })

  test('a failing write reports unknown rather than failed', async () => {
    const adapter = build({
      handlers: {
        'computer.status': async () => 'ok',
        'task.submit': async () => { throw new Error('socket closed') },
      },
    })
    const result = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'c', payload: { text: 'x' } }),
    )
    assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.COMMAND_STATE_UNKNOWN)
    assert.match(result.status === 'rejected' ? result.message : '', /socket closed/)

    const retry = await adapter.handle(
      envelope({ operation: 'task.submit', commandId: 'c', payload: { text: 'x' } }),
    )
    assert.equal(retry.status === 'rejected' ? retry.code : '', REFUSAL.COMMAND_STATE_UNKNOWN)
  })

  test('a repeated read is served again, not replayed', async () => {
    const spy = spyHandler(['one'])
    const adapter = build({
      handlers: { 'computer.status': async () => 'ok', 'session.list': spy.handler },
    })
    await adapter.handle(envelope({ operation: 'session.list', commandId: 'r' }))
    await adapter.handle(envelope({ operation: 'session.list', commandId: 'r' }))
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
    })
    const result = await adapter.handle(envelope({ operation: 'session.list' }))
    assert.equal(result.status, 'rejected')
    assert.match(result.status === 'rejected' ? result.message : '', /host not ready/)
  })
})
