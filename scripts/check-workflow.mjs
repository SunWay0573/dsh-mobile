#!/usr/bin/env node
/**
 * Checks on the CI workflow that cannot be made from inside it.
 *
 * Two things fail a workflow before a single job step runs, and neither is
 * visible from a passing local `verify.sh`:
 *
 * 1. **An action reference that does not resolve.** `uses: owner/repo@v9` when
 *    only v4 exists fails the job instantly, and nothing local can tell.
 * 2. **A floating ref.** `@main` means the workflow can start failing because
 *    somebody else pushed, on a day nobody touched this repository.
 *
 * Deliberately no YAML dependency. Every other script here imports node builtins
 * only, which is why they run in the hygiene job with no install step, and
 * trading that for a parser to read six lines of simple values is a bad deal.
 * The values extracted below cannot contain anything ambiguous, and the pattern
 * is anchored so a mention inside a comment is not mistaken for a step.
 *
 * The section names the workflow passes to `verify.sh` are NOT checked here.
 * They do not need to be: `verify.sh` rejects an unknown `--only` value and
 * exits 2, so a typo fails the job loudly instead of passing while running
 * nothing. That rejection exists because this script's absence let exactly that
 * happen once.
 *
 * Usage:
 *   node scripts/check-workflow.mjs            offline: pinning and structure
 *   node scripts/check-workflow.mjs --online   also resolve every ref (network)
 *   node scripts/check-workflow.mjs <path>     check a different file
 *
 * The path argument exists so the checks below can be tested against deliberately
 * broken files. A checker that has never been seen to fail is not evidence of
 * anything.
 */

import { readFileSync, existsSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

const ONLINE = process.argv.includes('--online')
const WORKFLOW = process.argv.slice(2).find((arg) => !arg.startsWith('--')) ?? 'ci/github-workflow.yml'

if (!existsSync(WORKFLOW)) {
  console.error(`  ${WORKFLOW} is missing`)
  process.exit(1)
}

const text = readFileSync(WORKFLOW, 'utf8')
const problems = []

/**
 * Every `uses:` value, taken from lines that begin a step.
 *
 * Anchoring on a leading `-` or whitespace before `uses:` keeps a `uses:` inside
 * a comment or a string from being read as a step.
 */
const refs = [...text.matchAll(/^\s*(?:-\s*)?uses:\s*(\S+)\s*$/gm)].map((m) => m[1])

if (refs.length === 0) {
  problems.push('no action references found — the pattern has drifted from the file')
}

/** Refs that float: a branch name, or no ref at all. */
const FLOATING = /@(main|master|HEAD|latest)$/

for (const ref of refs) {
  const [spec, at, version] = ref.split(/(@)/)
  if (!at || !version) {
    problems.push(`${ref}: no version pinned`)
    continue
  }
  if (FLOATING.test(ref)) {
    problems.push(`${ref}: floats on a branch; pin a major version`)
  }
  const parts = spec.split('/')
  if (parts.length < 2 || !parts[0] || !parts[1]) {
    problems.push(`${ref}: not an owner/repo reference`)
  }
}

// A job with no `runs-on` cannot be scheduled. Cheap to check, and the failure
// is a wall of red with no useful message.
const jobsBlock = text.split(/^jobs:\s*$/m)[1]
if (jobsBlock === undefined) {
  problems.push('no jobs: block')
} else {
  const jobNames = [...jobsBlock.matchAll(/^ {2}([A-Za-z0-9_-]+):\s*$/gm)].map((m) => m[1])
  if (jobNames.length === 0) problems.push('no jobs defined')
  for (const name of jobNames) {
    const body = jobsBlock.split(new RegExp(`^ {2}${name}:\\s*$`, 'm'))[1] ?? ''
    const next = body.search(/^ {2}[A-Za-z0-9_-]+:\s*$/m)
    const scope = next === -1 ? body : body.slice(0, next)
    if (!/^\s{4}runs-on:/m.test(scope)) problems.push(`job "${name}" has no runs-on`)
    if (!/^\s{4}steps:/m.test(scope)) problems.push(`job "${name}" has no steps`)
  }
}

// ── Online: does each ref actually exist? ───────────────────────────────────
if (ONLINE) {
  /**
   * Distinguishing "does not exist" from "could not ask" matters more than it
   * looks. Treating a network failure as a missing action reports a bug that is
   * not there — which happened while this check was being written, and nearly
   * led to "fixing" a correct `uses:` line.
   */
  const lookup = (repo, kind, ref) => {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        execFileSync('gh', ['api', `repos/${repo}/git/ref/${kind}/${ref}`], { stdio: 'pipe' })
        return 'ok'
      } catch (error) {
        const message = `${error.stderr ?? ''}${error.stdout ?? ''}`
        if (/HTTP 404|Not Found/.test(message)) return 'missing'
        if (attempt === 2) return 'unreachable'
      }
    }
    return 'unreachable'
  }

  for (const ref of refs) {
    const [spec, version] = ref.split('@')
    // owner/repo/subdir@ref — the repository is always the first two segments;
    // a subdirectory action is not a nested repository.
    const repo = spec.split('/').slice(0, 2).join('/')
    let state = lookup(repo, 'tags', version)
    if (state === 'missing') state = lookup(repo, 'heads', version)
    if (state === 'missing') problems.push(`${ref}: no such tag or branch on ${repo}`)
    if (state === 'unreachable') {
      console.log(`  ${ref}: not checked — could not reach the API`)
    }
  }
}

if (problems.length > 0) {
  console.error(`  ${problems.length} problem(s) in ${WORKFLOW}:`)
  for (const problem of problems) console.error(`    ${problem}`)
  process.exit(1)
}

console.log(`  ${refs.length} action references, all pinned and well-formed`)
process.exit(0)
