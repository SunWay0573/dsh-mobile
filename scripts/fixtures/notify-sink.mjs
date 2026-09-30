#!/usr/bin/env node
/**
 * A webhook that records what it receives, for the integration check.
 *
 * `mobile-bridge` posts notifications to a URL. To find out whether a
 * notification was actually delivered — rather than trusting the plugin's own
 * account of itself — something has to be on the other end of that URL.
 *
 * Writes one JSON line per request to the file given as argv[2], and exits when
 * killed. Deliberately tiny: this is a probe, and a probe with its own bugs is
 * worse than no probe.
 *
 * Usage: notify-sink.mjs <port> <output-file>
 */

import { createServer } from 'node:http'
import { appendFileSync, writeFileSync } from 'node:fs'

const port = Number(process.argv[2])
const outFile = process.argv[3]

if (!Number.isInteger(port) || port <= 0 || !outFile) {
  console.error('usage: notify-sink.mjs <port> <output-file>')
  process.exit(2)
}

// Truncate, so a run that never delivers cannot pass by reading a previous
// run's file.
writeFileSync(outFile, '')

const server = createServer((request, response) => {
  let body = ''
  request.setEncoding('utf8')
  request.on('data', (chunk) => { body += chunk })
  request.on('end', () => {
    appendFileSync(outFile, `${JSON.stringify({
      method: request.method,
      url: request.url,
      headers: request.headers,
      body,
    })}\n`)
    response.writeHead(200, { 'content-type': 'text/plain' })
    response.end('ok')
  })
})

server.listen(port, '127.0.0.1', () => {
  console.log(`notify-sink listening on 127.0.0.1:${String(port)}, writing to ${outFile}`)
})

// The parent kills this; make sure the socket does not hold up the exit.
process.on('SIGTERM', () => { server.close(() => { process.exit(0) }) })
