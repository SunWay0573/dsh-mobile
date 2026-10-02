import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { MobileAdapter } from '../../src/mobile/adapter.ts'
import { CommandStore } from '../../src/mobile/command-store.ts'
import { REFUSAL } from '../../src/mobile/protocol.ts'
import type { Operation, OperationHandler } from '../../src/mobile/adapter.ts'
import type { CommandEnvelope, DeviceRecord } from '../../src/mobile/protocol.ts'

/**
 * The real cross-language contract check.
 *
 * ## Why this exists
 *
 * An earlier "cross-language contract test" lived entirely in Kotlin: it
 * hand-wrote five field names and asserted the Kotlin encoder emitted them. That
 * checks Kotlin against Kotlin. If this side added a required field tomorrow,
 * that test would still pass, because nothing here ever saw the output.
 *
 * This reads the JSON that the Kotlin encoder actually produced — written by
 * `EncoderFixtureTest` into `android/app/build/contract/` — and feeds it to the
 * real adapter. A disagreement fails one of the two suites, which is the only
 * arrangement where "contract test" means anything.
 *
 * ## Running order
 *
 * The fixture is produced by the Android suite, so `verify.sh` runs Android
 * first. Standalone `npm test` skips these with a loud reason rather than
 * failing, and verify.sh asserts they were not skipped — otherwise a missing
 * fixture would look like a passing contract.
 */

const here = dirname(fileURLToPath(import.meta.url))
// here = plugins/mobile-bridge/test/mobile, so four levels up is the repo root.
const FIXTURE_DIR = join(here, '..', '..', '..', '..', 'android', 'app', 'build', 'contract')

const fixturePath = (name: string) => join(FIXTURE_DIR, name)

/** Read a fixture, or explain how to produce it. */
function fixture(name: string): CommandEnvelope | undefined {
  const path = fixturePath(name)
  if (!existsSync(path)) return undefined
  return JSON.parse(readFileSync(path, 'utf8')) as CommandEnvelope
}

const MISSING =
  'Kotlin encoder fixture not found. Run `cd android && ./gradlew testDebugUnitTest ' +
  "--tests '*EncoderFixtureTest*'` first; verify.sh does this automatically."

/** A device registry with one fully granted device. */
function devices(): (deviceId: string) => DeviceRecord | undefined {
  const table = new Map<string, DeviceRecord>([
    ['phone-7', {
      deviceId: 'phone-7',
      grantedScopes: ['sessions.read', 'tasks.submit', 'tasks.cancel', 'approvals.respond'],
      authorized: true,
    }],
  ])
  return (deviceId) => table.get(deviceId)
}

function build(handlers: Partial<Record<Operation, OperationHandler>>) {
  return new MobileAdapter({
    computerId: 'pc-1',
    computerName: '我的 Mac',
    adapterVersion: '1.0.0',
    hostVersion: '0.2.0-rc.2',
    handlers,
    resolveDevice: devices(),
  })
}

describe('the phone\'s own encoder output, consumed here', () => {
  test('a session.list the phone encoded is accepted', (t) => {
    const envelope = fixture('session-list.json')
    if (envelope === undefined) return t.skip(MISSING)

    return build({ 'session.list': async () => [] }).handle(envelope).then((result) => {
      assert.equal(result.status, 'accepted')
    })
  })

  test('a session.page the phone encoded is accepted', (t) => {
    const envelope = fixture('session-page.json')
    if (envelope === undefined) return t.skip(MISSING)

    return build({ 'session.page': async () => ({ events: [] }) })
      .handle(envelope)
      .then((result) => assert.equal(result.status, 'accepted'))
  })

  test('a task.submit the phone encoded is accepted', (t) => {
    const envelope = fixture('task-submit.json')
    if (envelope === undefined) return t.skip(MISSING)

    return build({ 'task.submit': async () => ({ taskId: 't-1' }) })
      .handle(envelope)
      .then((result) => assert.equal(result.status, 'accepted'))
  })

  /**
   * The field the encoder used to omit.
   *
   * Ran against the real output, this is the check that would have caught it:
   * without `deviceId` the adapter answers UNAUTHENTICATED, and the phone's own
   * suite would have been green.
   */
  test('the encoded envelope carries the device identity', (t) => {
    const envelope = fixture('session-list.json')
    if (envelope === undefined) return t.skip(MISSING)

    assert.equal(typeof envelope.deviceId, 'string')
    assert.notEqual(envelope.deviceId, '')
    assert.equal(envelope.deviceId, 'phone-7')
  })

  test('the encoded envelope carries the negotiated protocol version', (t) => {
    const envelope = fixture('session-list.json')
    if (envelope === undefined) return t.skip(MISSING)
    assert.equal(envelope.protocolVersion, 1)
  })

  test('the encoded operation is the wire name', (t) => {
    const envelope = fixture('session-page.json')
    if (envelope === undefined) return t.skip(MISSING)
    assert.equal(envelope.operation, 'session.page')
  })

  /**
   * A malformed decision, encoded by the phone, refused here.
   *
   * Checks the two ends agree on the refusal as well as on the request: the
   * Kotlin side has its own constant for this code, and a mismatch would leave
   * the phone unable to explain what happened.
   */
  test('a malformed approval decision from the phone is refused by name', (t) => {
    const envelope = fixture('approval-decide-malformed.json')
    if (envelope === undefined) return t.skip(MISSING)

    const spy: unknown[] = []
    return build({ 'approval.decide': async (payload) => { spy.push(payload); return 'decided' } })
      .handle(envelope)
      .then((result) => {
        assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.MALFORMED_APPROVAL_DECISION)
        assert.equal(spy.length, 0, 'the handler must not run for an unreadable decision')
      })
  })

  /**
   * The same encoder output, replayed with different content, is a conflict.
   *
   * This is where the two ends' notions of "the same request" have to agree:
   * the phone's `commandId` and the computer's fingerprint both derive from the
   * same fields, and if they disagreed a retry would run twice.
   */
  test('a retry of the encoded command replays rather than repeating', (t) => {
    const envelope = fixture('task-submit.json')
    if (envelope === undefined) return t.skip(MISSING)

    let calls = 0
    const adapter = build({
      'task.submit': async () => { calls += 1; return { taskId: 't-1' } },
    })

    return adapter.handle(envelope)
      .then(() => adapter.handle(envelope))
      .then((second) => {
        assert.equal(second.status, 'replayed')
        assert.equal(calls, 1)
      })
  })

  test('the same command id with a different body conflicts', (t) => {
    const envelope = fixture('task-submit.json')
    if (envelope === undefined) return t.skip(MISSING)

    const adapter = build({ 'task.submit': async () => ({ taskId: 't-1' }) })
    const changed: CommandEnvelope = { ...envelope, payload: { text: 'something else' } }

    return adapter.handle(envelope)
      .then(() => adapter.handle(changed))
      .then((second) => {
        assert.equal(second.status === 'rejected' ? second.code : '', REFUSAL.COMMAND_CONFLICT)
      })
  })

  /**
   * Impersonation: the same command with someone else's device id.
   *
   * `deviceId` is an identifier, not a credential, so this only shows that the
   * adapter looks the claiming device up rather than trusting it. Real
   * authentication is the connection's job and is not implemented.
   */
  test('a command claiming another device is judged as that device', (t) => {
    const envelope = fixture('task-submit.json')
    if (envelope === undefined) return t.skip(MISSING)

    const impersonating: CommandEnvelope = { ...envelope, deviceId: 'phone-does-not-exist' }
    return build({ 'task.submit': async () => ({ taskId: 't-1' }) })
      .handle(impersonating)
      .then((result) => {
        assert.equal(result.status === 'rejected' ? result.code : '', REFUSAL.DEVICE_REVOKED)
      })
  })
})

describe('the handshake the other end must be able to read', () => {
  test('the fixture the Kotlin parser accepted is one this adapter produces', (t) => {
    const path = fixturePath('handshake.json')
    if (!existsSync(path)) return t.skip(MISSING)

    const written = JSON.parse(readFileSync(path, 'utf8')) as Record<string, unknown>
    const produced = build({ 'session.list': async () => [] }).status('phone-7')

    // The shape, not the values: the fixture pins the field names both ends use.
    for (const field of Object.keys(written)) {
      assert.ok(
        field in produced,
        `this adapter's status must carry "${field}", which the phone's parser reads`,
      )
    }
  })

  test('the fixture does not advertise an operation this build cannot serve', (t) => {
    const path = fixturePath('handshake.json')
    if (!existsSync(path)) return t.skip(MISSING)

    const written = JSON.parse(readFileSync(path, 'utf8')) as { capabilities: string[] }
    const produced = build({
      'computer.status': async () => 'ok',
      'session.list': async () => [],
      'session.page': async () => ({ events: [] }),
      'approval.decide': async () => 'decided',
    }).status('phone-7')

    for (const operation of written.capabilities) {
      assert.ok(
        produced.capabilities.includes(operation as never),
        `the phone's fixture advertises "${operation}", which this adapter must be able to serve`,
      )
    }
  })
})
