# Changelog

Notable changes to this project. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project aims to
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once there is
something to version.

The repository is pre-release: nothing here has been published to a registry.
Entries under Unreleased describe what is on `main` today.

## [v0.1.0-pre] — first installable build

A GitHub pre-release with a **debug-signed** APK attached. It installs by
sideloading and is fine for trying this out; it is not something to distribute,
and a release signing key is a decision for whoever runs this rather than one
made on their behalf.

Everything below this heading is what that build contains.

## [Unreleased]

### Added

- **`plugins/sleep-guard`** — holds a sleep assertion while DSH is working and
  releases it when it is not. Uses `ctx.agents` and `ctx.jobs` rather than a
  permanent `caffeinate`, because permanently preventing sleep is a different
  product: a fan in an empty room and a machine that is awake for no reason.
  The assertion is a child process rather than a native assertion API, so a bug
  in the plugin cannot leave a machine permanently unable to sleep.
  54 tests.

- **`plugins/mobile-bridge`** — the outbound half. Push notifications for
  approval requests and finished turns (`approval/request` is a waterfall, so
  the observer delegates before notifying, and a test asserts that). A
  `lock_screen` tool, which is stronger than the overlay a pixel-streaming
  remote-desktop tool must fake, because DSH drives the machine over HTTP and
  does not care about the display. A `wake_computer` tool delegating to
  `wol-bridge`. 60 tests.

- **`wol-bridge`** — sends Wake-on-LAN magic packets from an always-on device on
  the LAN, with an HTTP endpoint and optional bearer auth. Standard library
  only, because the target is a Raspberry Pi or a NAS. 73 tests.

- **Android app** — a Kotlin and Jetpack Compose shell around a WebView. Native
  for what the host cannot do (host address, wake sender, biometric lock,
  process-level recovery), WebView for the conversation, because DSH's client
  cannot be bundled into an APK. 30 unit tests; a debug APK builds.

- **`scripts/verify.sh`** — one definition of what passing means, called by CI
  per section so the two cannot drift. Checks degrade to a skip when a toolchain
  is absent, and `--strict` turns skips into failures.

- **`scripts/verify-integration.sh`** — installs the plugins into an isolated
  `DSH_HOME`, boots a real DSH host, and asserts that they load. The only check
  that can see a plugin which installs, typechecks, passes every unit test and
  never runs.

- **`scripts/check-plugins.mjs`** — structural checks for the three ways a
  plugin silently never loads.

- Documentation: a design document (Chinese), tunnel and power setup guides, a
  security policy with a threat model, and contributing guidance.

### Fixed

Three bugs, all found by booting a real host, all of which meant a plugin never
ran while every automated check passed:

- Plugins shipped without a `dsh.bundle` declaration, so `dsh plugin add`
  installed them as plain dependencies and the host never mounted them.
- `defineTool` was imported from a package that was only a development-time
  dependency. It is a value import, so it did not exist at runtime and
  `mobile-bridge` aborted at load.
- One patch declared `dsh-mobile-mobile-bridge` for a package called
  `dsh-mobile-bridge`. The loader imports that string verbatim, and the message
  reads like an import bug rather than a typo.

### Notes

- The release configuration of the Android app refuses cleartext HTTP except on
  loopback. `network security config` domain entries accept literal IPs, not
  CIDR blocks, so "allow cleartext on private ranges" cannot be expressed — and
  the alternative, permitting it globally, is not a trade this project will
  make for an app holding a session cookie. A debug-only override covers LAN
  testing.
