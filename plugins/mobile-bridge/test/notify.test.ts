/**
 * Tests for the notification channel and the message builders.
 *
 * The header encoding tests are not ceremony: a control character in a header
 * value is request splitting, and the title can carry a tool name or file name
 * that originated from the model.
 */

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

import {
  Notifier,
  buildHeaders,
  encodeHeaderValue,
  type FetchLike,
  type NotifyMessage,
} from '../src/notify.ts'

import {
  RateLimiter,
  approvalNeededMessage,
  sessionUrl,
  turnFinishedMessage,
} from '../src/events.ts'

interface Call {
  url: string
  method: string
  headers: Record<string, string>
  body: string
}

function fakeFetch(result: { ok?: boolean; status?: number; throws?: Error } = {}): {
  fetch: FetchLike
  calls: Call[]
} {
  const calls: Call[] = []
  const fetch: FetchLike = async (url, init) => {
    calls.push({ url, method: init.method, headers: init.headers, body: init.body })
    if (result.throws !== undefined) throw result.throws
    return { ok: result.ok ?? true, status: result.status ?? 200 }
  }
  return { fetch, calls }
}

const message: NotifyMessage = { title: 'Hi', body: 'There' }

describe('Notifier', () => {
  test('posts the body as plain text and reports success', async () => {
    const { fetch, calls } = fakeFetch()
    const notifier = new Notifier({ url: 'https://ntfy.sh/topic', fetch })
    assert.equal(await notifier.send(message), true)
    assert.equal(calls.length, 1)
    assert.equal(calls[0]?.url, 'https://ntfy.sh/topic')
    assert.equal(calls[0]?.method, 'POST')
    assert.equal(calls[0]?.body, 'There')
  })

  test('reports a rejected message rather than throwing', async () => {
    const warnings: string[] = []
    const { fetch } = fakeFetch({ ok: false, status: 429 })
    const notifier = new Notifier({
      url: 'https://ntfy.sh/topic',
      fetch,
      onWarn: (m) => { warnings.push(m) },
    })
    assert.equal(await notifier.send(message), false)
    assert.match(warnings.join('\n'), /HTTP 429/)
  })

  test('swallows a transport error, because a courtesy must not break the work', async () => {
    const warnings: string[] = []
    const { fetch } = fakeFetch({ throws: new Error('ECONNREFUSED') })
    const notifier = new Notifier({
      url: 'https://ntfy.sh/topic',
      fetch,
      onWarn: (m) => { warnings.push(m) },
    })
    await assert.doesNotReject(async () => { await notifier.send(message) })
    assert.match(warnings.join('\n'), /ECONNREFUSED/)
  })

  test('gives up on a hanging channel instead of hanging the caller', async () => {
    const warnings: string[] = []
    const fetch: FetchLike = (_url, init) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => { reject(new Error('aborted')) })
    })
    const notifier = new Notifier({
      url: 'https://ntfy.sh/topic',
      fetch,
      timeoutMs: 20,
      onWarn: (m) => { warnings.push(m) },
    })
    assert.equal(await notifier.send(message), false)
    assert.match(warnings.join('\n'), /aborted/)
  })

  test('sends a bearer token only when one is configured', async () => {
    const withToken = fakeFetch()
    await new Notifier({ url: 'u', token: 's3cret', fetch: withToken.fetch }).send(message)
    assert.equal(withToken.calls[0]?.headers['authorization'], 'Bearer s3cret')

    const without = fakeFetch()
    await new Notifier({ url: 'u', fetch: without.fetch }).send(message)
    assert.equal(without.calls[0]?.headers['authorization'], undefined)
  })
})

describe('buildHeaders', () => {
  test('sets the ntfy metadata headers', () => {
    const headers = buildHeaders({
      title: 'Hello',
      body: 'x',
      priority: 'urgent',
      tags: ['warning'],
      click: 'https://example.com/#/session/abc',
    })
    assert.equal(headers['title'], 'Hello')
    assert.equal(headers['priority'], 'urgent')
    assert.equal(headers['tags'], 'warning')
    assert.equal(headers['click'], 'https://example.com/#/session/abc')
  })

  test('defaults priority rather than omitting it', () => {
    assert.equal(buildHeaders(message)['priority'], 'default')
  })

  test('omits click and tags when absent', () => {
    const headers = buildHeaders(message)
    assert.equal(headers['click'], undefined)
    assert.equal(headers['tags'], undefined)
  })
})

describe('encodeHeaderValue', () => {
  // Header values must be ASCII. A tool name or file name from the model can
  // contain anything, including characters that would split the request.
  test('strips control characters instead of encoding them', () => {
    assert.equal(encodeHeaderValue('a\r\nX-Injected: 1'), 'a X-Injected: 1')
  })

  test('passes ASCII through unchanged', () => {
    assert.equal(encodeHeaderValue('bash'), 'bash')
  })

  test('RFC 2047 encodes non-ASCII, which ntfy understands', () => {
    const encoded = encodeHeaderValue('构建完成')
    assert.match(encoded, /^=\?UTF-8\?B\?/)
    const payload = encoded.slice('=?UTF-8?B?'.length, -'?='.length)
    assert.equal(Buffer.from(payload, 'base64').toString('utf8'), '构建完成')
  })

  test('trims surrounding whitespace', () => {
    assert.equal(encodeHeaderValue('  x  '), 'x')
  })
})

describe('sessionUrl', () => {
  test('builds a session deep link', () => {
    assert.equal(
      sessionUrl('https://host.ts.net', 'session-abc'),
      'https://host.ts.net/#/session/session-abc',
    )
  })

  test('tolerates a trailing slash', () => {
    assert.equal(
      sessionUrl('https://host.ts.net/', 'abc'),
      'https://host.ts.net/#/session/abc',
    )
  })

  // A notification that navigates nowhere is worse than one that does not
  // pretend to be tappable.
  test('returns undefined when either half is missing', () => {
    assert.equal(sessionUrl(undefined, 'abc'), undefined)
    assert.equal(sessionUrl('https://host', undefined), undefined)
    assert.equal(sessionUrl('', 'abc'), undefined)
    assert.equal(sessionUrl('https://host', ''), undefined)
  })

  test('escapes the session id', () => {
    assert.equal(sessionUrl('https://h', 'a/b'), 'https://h/#/session/a%2Fb')
  })
})

describe('message builders', () => {
  test('an approval request is urgent, because missing it means silent denial', () => {
    const result = approvalNeededMessage({ toolName: 'bash' })
    assert.equal(result.priority, 'urgent')
    assert.match(result.body, /bash/)
    assert.match(result.title, /approval/i)
  })

  test('an approval request prefers the asker explanation when given one', () => {
    const result = approvalNeededMessage({ toolName: 'bash', reason: 'writes outside the workspace' })
    assert.equal(result.body, 'writes outside the workspace')
  })

  test('a finished turn is default priority, so the urgent one stays loud', () => {
    assert.equal(turnFinishedMessage({}).priority, 'default')
  })

  test('both builders attach a deep link when one can be built', () => {
    const args = { sessionId: 's1', baseUrl: 'https://h' }
    assert.equal(approvalNeededMessage({ toolName: 't', ...args }).click, 'https://h/#/session/s1')
    assert.equal(turnFinishedMessage(args).click, 'https://h/#/session/s1')
  })

  test('neither builder attaches a link it cannot build', () => {
    assert.equal(approvalNeededMessage({ toolName: 't' }).click, undefined)
    assert.equal(turnFinishedMessage({}).click, undefined)
  })
})

describe('RateLimiter', () => {
  test('allows up to the limit then refuses', () => {
    const limiter = new RateLimiter({ limit: 2, windowMs: 1_000, now: () => 0 })
    assert.equal(limiter.allow('s'), true)
    assert.equal(limiter.allow('s'), true)
    assert.equal(limiter.allow('s'), false)
  })

  test('limits per key, so one noisy session cannot silence another', () => {
    const limiter = new RateLimiter({ limit: 1, windowMs: 1_000, now: () => 0 })
    assert.equal(limiter.allow('a'), true)
    assert.equal(limiter.allow('a'), false)
    assert.equal(limiter.allow('b'), true)
  })

  test('resets once the window passes', () => {
    let now = 0
    const limiter = new RateLimiter({ limit: 1, windowMs: 1_000, now: () => now })
    assert.equal(limiter.allow('s'), true)
    assert.equal(limiter.allow('s'), false)
    now = 1_000
    assert.equal(limiter.allow('s'), true)
  })

  test('prune drops expired keys so the map does not grow forever', () => {
    let now = 0
    const limiter = new RateLimiter({ limit: 1, windowMs: 1_000, now: () => now })
    limiter.allow('a')
    limiter.allow('b')
    now = 2_000
    limiter.prune()
    // Both keys are gone, so each gets a fresh window.
    assert.equal(limiter.allow('a'), true)
    assert.equal(limiter.allow('b'), true)
  })

  test('rejects a nonsensical configuration', () => {
    assert.throws(() => new RateLimiter({ limit: 0, windowMs: 1 }), RangeError)
    assert.throws(() => new RateLimiter({ limit: 1, windowMs: 0 }), RangeError)
  })
})
