#!/usr/bin/env node
/**
 * Structural checks on the plugin packages.
 *
 * These exist because of three bugs that shipped and were only caught by booting
 * a real DSH host. None of them is a type error, and none is visible to a unit
 * test, because in every case the package itself is fine — it simply never runs.
 *
 *   1. No `dsh.bundle` declaration. `dsh plugin add` installs the package as a
 *      plain dependency, warns once, and the host never mounts it.
 *   2. `defineTool` imported from `@deepseek-ai/dsh-tools` while that package
 *      was only a development-time dependency. The value import fails at
 *      runtime and the whole plugin aborts.
 *   3. The patch's `name:` not matching the package's own name. It is a free
 *      string the loader imports verbatim, so a value that is merely close
 *      yields the same silent nothing.
 *
 * A check written as an inline shell one-liner was tried first and became an
 * unreadable nest of escaping, which is the other reason this is a file.
 *
 * @module scripts/check-plugins
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const ROOT = 'plugins'
const problems = []

/** Strip a leading `./` so `files[]` and `dsh.bundle.patch` compare equal. */
const bare = (path) => path.replace(/^\.\//, '')

for (const dir of readdirSync(ROOT)) {
  const packageDir = join(ROOT, dir)
  const manifestPath = join(packageDir, 'package.json')
  if (!existsSync(manifestPath) || !statSync(packageDir).isDirectory()) continue

  const pkg = JSON.parse(readFileSync(manifestPath, 'utf8'))
  if (pkg.private === true) continue

  // ── 1. dsh.bundle, with real patch files that actually ship ──────────────
  const declared = pkg.dsh?.bundle?.patch
  const patches = Array.isArray(declared) ? declared : declared ? [declared] : []

  if (patches.length === 0) {
    problems.push(`${dir}: no dsh.bundle.patch — the host will install it and never mount it`)
    continue
  }

  for (const patch of patches) {
    if (!existsSync(join(packageDir, patch))) {
      problems.push(`${dir}: dsh.bundle.patch points at a missing file: ${patch}`)
    }
    if (!(pkg.files ?? []).some((f) => bare(f) === bare(patch))) {
      problems.push(`${dir}: ${patch} is not listed in files[], so it would not be published`)
    }
  }

  // ── 2. Every value import must be a real dependency ─────────────────────
  // `import type` is erased and safe; a bare value import is not. This catches
  // the class of mistake that killed mobile-bridge at load time.
  const srcDir = join(packageDir, 'src')
  if (existsSync(srcDir)) {
    for (const file of readdirSync(srcDir)) {
      if (!file.endsWith('.ts')) continue
      const source = readFileSync(join(srcDir, file), 'utf8')
      for (const line of source.split('\n')) {
        if (!/^\s*import\s+(?!type\b)/.test(line)) continue
        const match = /from\s+['"](@[^'"]+)['"]/.exec(line)
        if (match === null) continue
        const specifier = match[1]
        // Split a possible subpath: only the package name is a dependency key.
        const packageName = specifier.startsWith('@')
          ? specifier.split('/').slice(0, 2).join('/')
          : specifier.split('/')[0]
        const declaredAsDependency = Object.hasOwn(pkg.dependencies ?? {}, packageName)
        const providedByHost = Object.hasOwn(pkg.peerDependencies ?? {}, packageName)
        if (!declaredAsDependency && !providedByHost) {
          problems.push(
            `${dir}: ${file} value-imports ${packageName}, which is neither a dependency`
            + ' nor a peer dependency — the import will fail at load time',
          )
        }
      }
    }
  }

  // ── 3. The patch's insert name must be the package's own name ───────────
  for (const patch of patches) {
    const patchPath = join(packageDir, patch)
    if (!existsSync(patchPath)) continue
    const text = readFileSync(patchPath, 'utf8')
    const names = [...text.matchAll(/^\s*name:\s*['"]?([^'"\s]+)['"]?\s*$/gm)].map((m) => m[1])
    if (names.length === 0) {
      problems.push(`${dir}: ${patch} has no name: entry to insert`)
    }
    for (const name of names) {
      if (name !== pkg.name) {
        problems.push(
          `${dir}: ${patch} declares name "${name}" but the package is "${pkg.name}"`
          + ' — the loader imports that string verbatim',
        )
      }
    }
  }
}

if (problems.length > 0) {
  for (const problem of problems) console.error(`  ${problem}`)
  process.exit(1)
}
process.exit(0)
