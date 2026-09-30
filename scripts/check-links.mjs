#!/usr/bin/env node
/**
 * Every relative link in every tracked markdown file must resolve to a real
 * file.
 *
 * Worth automating because a broken internal link is invisible in review and
 * invisible in a rendered diff — the text still reads correctly, and the reader
 * is the one who finds out. This project's documentation carries a lot of
 * cross-references on purpose, so the cost of a stale one is higher than usual.
 *
 * External links are not fetched. Checking those would make this depend on the
 * network and on other people's uptime, and a check that fails because someone
 * else's site is down is a check people learn to ignore.
 *
 * Anchors are not resolved either: GitHub's heading-slug algorithm has enough
 * edge cases that a checker would produce false failures, and a false failure is
 * worse than an unchecked anchor.
 *
 * @module scripts/check-links
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'

/** Every tracked file, so this can never disagree with what is pushed. */
const tracked = execFileSync('git', ['ls-files'], { encoding: 'utf8' })
  .split('\n')
  .filter((line) => line.endsWith('.md'))

const broken = []
let checked = 0

for (const file of tracked) {
  const text = readFileSync(file, 'utf8')
  // [label](target) or [label](target#anchor), skipping images with a leading !
  // only in the sense that they are checked too — an image path is just as
  // capable of being wrong.
  for (const match of text.matchAll(/\]\(([^)\s]+)\)/g)) {
    const raw = match[1]
    if (/^(https?:|mailto:|#)/.test(raw)) continue

    const target = raw.split('#')[0]
    if (target === '') continue

    checked += 1
    const resolved = resolve(dirname(file), target)
    if (!existsSync(resolved)) {
      broken.push(`${file} → ${raw}`)
    }
  }
}

// A markdown file that is not tracked would not be checked, which is a way for
// this to silently do nothing. Say so instead.
const untracked = []
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    if (entry === '.git' || entry === 'node_modules') continue
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      walk(full)
    } else if (entry.endsWith('.md') && !tracked.includes(full)) {
      untracked.push(full)
    }
  }
}
walk('.')

if (untracked.length > 0) {
  console.error(`  ${untracked.length} markdown file(s) are not tracked by git:`)
  for (const file of untracked) console.error(`    ${file}`)
  console.error('  Untracked documentation is not checked and not shipped.')
  process.exit(1)
}

if (broken.length > 0) {
  console.error(`  ${broken.length} relative link(s) do not resolve:`)
  for (const entry of broken) console.error(`    ${entry}`)
  process.exit(1)
}

console.log(`  ${checked} relative links across ${tracked.length} files, all resolve`)
process.exit(0)
