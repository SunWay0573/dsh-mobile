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
├── MainActivity.kt   Compose UI, both screens, back handling, web state
├── AppSettings.kt    SharedPreferences: host, MAC, broadcast, lock
├── Biometric.kt      the lock gate
├── WakeOnLan.kt      packet construction and the datagram send
├── Urls.kt           address normalisation          ┐ Android-free, so
└── WebState.kt       failure classification         ┘ unit testable
```

The two files with no Android imports are the two with judgement in them:
address handling and failure text. Everything that needs a device is in the
other four.

`SharedPreferences` rather than DataStore: there are four values, they are read
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

**For development, you do not have to edit anything.**
`app/src/debug/res/xml/network_security_config.xml` overrides the main config for
the debug variant only, so `assembleDebug` permits cleartext and
`assembleRelease` does not. Resource merging handles it; the relaxation is
scoped to a build type that never reaches a user.

Verified by reading the packaged resources back out of each variant:

```
[debug  ] base-config cleartext=true
[release] base-config cleartext=false   cleartext exceptions: 127.0.0.1, localhost
```

`scripts/verify.sh` asserts the release value from source, because copying the
debug config into `src/main` is the obvious way to "fix" a LAN connection that
will not load, and the consequence is an app that will talk plaintext anywhere.

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

## What the shell owns, and what it does not

The principle: **the WebView owns everything that comes from the host; native
owns what the host cannot do.** Two entries on the original plan violated it, and
one of them is now withdrawn.

### Withdrawn: a native session list

This was on the plan and should not have been. Rendering the session list
natively means reimplementing DSH's RPC envelope *and* the one-time-token to
cookie exchange, in order to draw a screen the web client already draws
correctly. It would be a second implementation of the authentication path, kept
in step with someone else's protocol by hand, in the part of this system where a
mistake is most expensive.

The sidebar in the loaded web client is the session list. If it is awkward to
reach on a phone, the fix belongs in the mobile layout, not in a parallel Kotlin
client.

### Implemented: biometric lock

Optional, off by default, explained in the settings card. It gates the whole UI
behind `BiometricPrompt`.

- `BIOMETRIC_WEAK`, not strong. A device with a weaker sensor is still far better
  protected than one with no prompt, and demanding a strong sensor would lock
  those users out of the protection entirely.
- **No device-credential fallback.** It could be offered, but it is the same PIN
  that unlocks the phone, so the gate would add nothing while appearing to. That
  is worse than not offering it.
- A cold start prompts immediately; a rotation does not, because the unlocked
  state was never lost.

This is why the activity is a `FragmentActivity`: `BiometricPrompt` requires one.

### Implemented: failures that say what to do

The most likely first-run failure is not a network problem. It is that DSH prints
a one-time sign-in link on **every** start and the phone has to open it once. A
user who just restarted their host otherwise gets a bare 401 page and no way
forward.

`WebErrors` (in `WebState.kt`, Android-free and unit tested) turns statuses into
advice:

| Status | What the banner says |
|---|---|
| 401, 403 | the host refused this device — open the sign-in link |
| 404 | something answered, but not DSH — check the address |
| 5xx | the host is running but errored; its logs will say why |
| anything else | nothing; an unclear banner over a working page is worse than none |

HTTP errors are reported for the **main frame only**. DSH streams plugin bundles
and optional assets, and a banner raised because one of them 404s would sit on
top of a page that is working perfectly.

### Implemented: process-level recovery

The web client owns its own transport recovery (500 ms → 10 s backoff, 15 s
readiness deadline), so the shell must not duplicate it. What the shell owes is
different: noticing that the page never loaded at all. That is a retry button,
plus an automatic reload when the app returns to the foreground while failed — a
tunnel that was down when you backgrounded the app is very often back by the
time you return.

Process death needs no special handling: the host URL lives in
`SharedPreferences`, so a fresh process loads it.

## Still not implemented

- **Push notification handling.** Needs FCM, which needs a project the app is
  registered with. That is a decision for whoever runs this, not something to
  guess at.
- **A foreground service** to survive aggressive background limits. MIUI is
  known for killing backgrounded apps, and this app is meant for exactly that
  audience, so it matters — but it also needs the FCM decision above to be worth
  building.
