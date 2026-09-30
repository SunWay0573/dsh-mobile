# Android app

A native Kotlin + Jetpack Compose shell around a WebView.

## Why a hybrid, and not one or the other

**Why a WebView for the conversation.** DSH's web client is not a standalone
SPA — the host injects `window.__DSH_BOOT__` and serves plugin bundles from
`/plugins/...`, so it cannot be bundled into the APK and must be loaded from your
host. Given that, embedding it means the transcript, tool-call cards, approval
prompts and composer stay identical to the desktop, and keep working when DSH
updates. A native reimplementation would be a permanent maintenance tax chasing
someone else's UI.

**Why native for everything else.**

| Feature | Why native |
|---|---|
| Host address & settings | Must work before any page loads |
| Wake-on-LAN sender | UDP broadcast; not available to page JavaScript |
| Back navigation | Has to reconcile WebView history with the system back gesture |
| Session list, push, biometrics | Planned; all need the shell, not the page |

## Screens

Two, in one activity, with no navigation library — a graph would add a
dependency and a second place for back handling to go wrong.

- **Home** — host address, wake configuration, and the wake button.
- **Session** — the WebView, scoped to the host.

Back is handled in exactly one place, so precedence never depends on composition
order: walk the WebView's history first, and only leave the screen when there is
nowhere back to go.

## Layout

```
app/src/main/java/io/github/sunway0573/dshmobile/
├── MainActivity.kt   Compose UI, both screens, back handling
├── AppSettings.kt    SharedPreferences: host, MAC, broadcast
├── WakeOnLan.kt      packet construction and the datagram send
└── Urls.kt           address normalisation, Android-free so it is unit testable
```

`SharedPreferences` rather than DataStore: there are three values, they are read
once, and a synchronous read is what the UI wants.

## Version choices

| Component | Version | Why |
|---|---|---|
| Gradle | 8.9 | Required floor for AGP 8.7 |
| AGP | 8.7.3 | Current stable at time of writing |
| Kotlin | 2.0.21 | Compose compiler plugin must match exactly |
| Compose BOM | 2024.10.01 | Pins all Compose artifacts consistently |
| compileSdk / targetSdk | 35 | |
| minSdk | 26 | Vector launcher icon and modern WebView without compat shims |

Kotlin 2.x moved the Compose compiler into its own Gradle plugin
(`org.jetbrains.kotlin.plugin.compose`), and **it must match the Kotlin version
exactly**. Mismatching them is the most common way a fresh Compose project fails
to configure, which is why both come from a single `kotlin` version in the
catalog.

## Cleartext HTTP

`network_security_config.xml` refuses cleartext everywhere except loopback.

The wish — "allow cleartext on private ranges like `192.168.0.0/16`" — **cannot
be expressed**: `<domain>` entries are hostnames or literal IPs, never CIDR
blocks. Rather than work around that by permitting cleartext globally, this
takes the posture the project actually recommends: reach the host over HTTPS
(`tailscale serve` issues a real certificate for your tailnet name). Loopback
stays open only because `adb reverse` needs it during development.

To reach a plain-HTTP host on your LAN, add that exact address as a `<domain>`.
Do not set `cleartextTrafficPermitted="true"` on `<base-config>` — this app
holds a session cookie for a machine that can run arbitrary code.

## Building

```sh
cd android
./gradlew assembleDebug     # APK at app/build/outputs/apk/debug/
./gradlew test              # JVM unit tests
```

The Gradle wrapper is complete and checked in. `gradle-wrapper.jar` is the
official file from the Gradle 8.9.0 tag, SHA-256:

```
498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17
```

The wrapper jar is a binary that conventionally gets committed; verifying the
hash is how you check it has not been swapped.

### Verified

This project has been built and tested locally, against a real Android SDK:

```
BUILD SUCCESSFUL in 58s
41 actionable tasks: 21 executed, 20 up-to-date
```

```
app/build/outputs/apk/debug/app-debug.apk   9,528,992 bytes
  classes.dex, AndroidManifest.xml, resources.arsc all present

testDebugUnitTest
  UrlsTest        tests=6  failures=0 errors=0
  WakeOnLanTest   tests=15 failures=0 errors=0
```

SDK used: `platforms;android-35`, `build-tools;35.0.0`, JDK 17.

Keep `android/local.properties` (gitignored) pointing at your SDK:

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

### If dependency resolution fails

Gradle pulls from `repo.maven.apache.org`, `dl.google.com` and
`plugins.gradle.org`, and **which of those is reachable varies by network**. Two
things that were needed here, neither of which should be committed:

- A proxy, in `~/.gradle/gradle.properties`:
  ```properties
  systemProp.https.proxyHost=127.0.0.1
  systemProp.https.proxyPort=7897
  ```
- Or a distribution mirror, for a network where `services.gradle.org` is
  unreachable — edit `distributionUrl` in
  `gradle/wrapper/gradle-wrapper.properties` locally. Prefer a mirror you trust;
  the committed URL stays official so CI and everyone else gets the real thing.

A single `Remote host terminated the handshake` failure is usually transient
rather than a real block — retry before concluding anything.

## Not implemented yet

- Session list on the home screen (currently the WebView is the only entry)
- Push notification handling
- Biometric unlock
- Foreground-service reconnect after the OS kills the process

Reconnect is worth a note: the DSH web client already owns its own transport
recovery (500 ms → 10 s backoff, 15 s readiness deadline), so the shell should
not duplicate it. What the shell owes is *process-level* recovery — reload the
WebView when the app returns to the foreground after being killed, and show a
clear banner rather than a white screen when the tunnel is down.
