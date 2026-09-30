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

**Why native for everything else.** Things a WebView cannot do well, or at all:

| Feature | Why native |
|---|---|
| Session list & connection state | Needs to render before the tunnel is up |
| Wake-on-LAN sender | UDP broadcast; not available to page JS |
| Push notifications | FCM, and reliable delivery when the app is backgrounded |
| Biometric unlock | The app is the key to your computer |
| Reconnect after process death | Android kills backgrounded processes; the shell owns recovery |

## Screens

- **Sessions** — the entry point. Cards with a running/needs-attention badge.
  This is the highest-frequency screen by far, so it is native and fast.
- **Conversation** — the WebView, scoped to one session.
- **Wake** — shown when the host is unreachable; sends the magic packet and
  polls until it comes up, then jumps into sessions.
- **Settings** — host URL, wake bridge URL, notification topic, biometric lock.

## Connectivity notes

The DSH web client derives its WebSocket URL from `location.origin` and has its
own reconnect policy (500 ms → 10 s backoff, 15 s readiness deadline). The shell
should not duplicate that — let the web client own its transport, and have the
shell own *process-level* recovery: reload the WebView when the app returns to
the foreground after being killed, and surface a clear banner rather than a
white screen when the tunnel is down.

Prefer HTTPS for the host origin — `tailscale serve` provides a real certificate
for your tailnet name. Cleartext works but requires an explicit network security
config, and the app holds credentials worth protecting.

## Status

🚧 Not implemented yet. Tracking issue: TBD.

Toolchain: Kotlin, Jetpack Compose, Gradle (Kotlin DSL). The release workflow
builds a debug APK on every push and attaches a signed release APK to tagged
releases.
