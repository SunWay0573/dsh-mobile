# mobile-bridge

The outbound half of remote control: things DSH cannot tell a phone that is not
currently connected.

## Install

```sh
dsh plugin --profile web add /path/to/plugins/mobile-bridge
```

Then restart the host. There is no separate enable step: adding the package and
restarting is the whole procedure.

### Three ways this silently does nothing

All three were found by booting a real host, and none of them is a type error or
a failing test — in every case the package is correct, it simply never runs.

| Symptom | Cause |
|---|---|
| `dsh plugin add` prints `declares no dsh.bundle — installed as a plain dependency` | The package needs `dsh.bundle.patch`, or the host never mounts it |
| `failed to import` at boot | A **value** import (not `import type`) from a package that is only a dev dependency; it does not exist at runtime |
| `failed to import`, package resolves fine by hand | The patch's `name:` does not exactly match the package's own name — the loader imports that string verbatim |

`scripts/check-plugins.mjs` checks all three from source, so they cannot come
back unnoticed.

## 1. Push notifications — implemented

DSH can tell a **connected** client that it needs a decision. It cannot reach a
client that is not there, and that gap is not cosmetic.

DSH's approval policy has **no timeout and fails closed**. An unattended agent
whose approval request reaches nobody does not wait for you — the tool call is
denied and the agent quietly does less than it was asked to. From the phone, it
looks like the agent got lazy. It did not; nobody answered.

`packages/webhook` does not help here: it is **inbound** (it turns external
deliveries into sessions). Nothing in DSH pushes events out.

So this plugin subscribes to two things and posts to a webhook:

| Trigger | Priority | Why |
|---|---|---|
| `approval/request` | **urgent** | Missing this means silent denial |
| `agent/status` running→idle | default | Informational |

[ntfy](https://ntfy.sh) is the recommended target — open source, self-hostable,
Android app, no account. Anything accepting a plain-text HTTP POST works.

### The waterfall trap

`approval/request` is a **waterfall** event. A listener that returns without
calling `next()` *claims* the request — so an observer that forgets to delegate
does not merely fail to observe, it becomes a silent auto-deny for every
approval-gated tool call.

The handler therefore delegates first and notifies second, and does not await
the notification. There is a test asserting the delegation happens, because
that failure mode is far worse than a missing push.

### Configuration

```yaml
- id: mobile-bridge
  name: dsh-mobile-mobile-bridge
  config:
    # Treat the topic string as a password: anyone who knows it can push to
    # your phone. Use a long random value, or self-host.
    url: https://ntfy.sh/<long-random-topic>
    # Only needed for a protected topic or a self-hosted instance.
    token: tk_xxx
    # Public base URL of this host, so notifications deep-link back into the
    # right session. Without it they arrive without a tap target.
    baseUrl: https://machine.tailnet.ts.net
    notifyOnApproval: true
    notifyOnTurnEnd: true
    rateLimitPerMinute: 20
```

### The tap target is an app scheme, and that is not cosmetic

A notification's `click` cannot be the host's own URL. That link opens a
**browser**, and the sign-in cookie lives in the Android app's WebView — so the
tap lands on an unauthenticated page and shows an error. Correct link, useless
destination.

`deepLinkScheme` (default `dshmobile`) makes the tap target
`dshmobile://session/<id>`, which the app handles. The app already knows the host
address from its own settings, so this is also the one link that works without
`baseUrl` being configured.

```yaml
    deepLinkScheme: dshmobile    # default; set to '' for the https form
```

## 2. Privacy curtain — implemented as `lock_screen`

Locks the screen while you are operating remotely, so nobody standing at the
machine can read your conversation or touch anything.

**This is deliberately not how remote-desktop software does it.** Pixel-
streaming tools must draw a fake black overlay and protect its window level,
because a real lock screen would blind them — they need the display to keep
producing frames. DSH drives the machine over HTTP and does not care about the
display at all, so it can simply lock the screen. That is strictly stronger: an
overlay can be dismissed locally, a lock screen cannot.

```sh
"/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession" -suspend
```

No root, no accessibility permission.

**There is no unlock, deliberately.** Unlocking needs the local user's
credentials, and a plugin that could unlock the machine for a remote caller
would defeat the entire point. The way out is to walk over and type your
password, which is exactly the property you want.

### On the trigger

The action is one command; knowing *when* to run it is the hard part. There is
no "a remote client just connected" subscription available to a host plugin, so
rather than guess at a heuristic — locking on every turn would lock the screen
while you are sitting in front of it — this is exposed as a **tool the agent can
call**. You are already talking to the agent; ask it to lock the screen.

`lock_screen` returns `{ ok, message }` and never throws. A timer that fires
while the helper is still running is treated as **success**, because the macOS
helper is known to linger once the lock is up and reporting failure there would
send you off to debug a working setup.

## 3. Wake trigger — implemented as `wake_computer`

Asks a [`wol-bridge`](../../wol-bridge/) on your LAN to send a magic packet.
This plugin cannot send it itself, and neither can anything else running on a
sleeping machine — see that component's README for why this is physics rather
than a missing feature.

Registered **only when `wakeBridgeUrl` is configured**. A tool that can only
fail is worse than no tool: the agent would call it and report a confusing
error instead of saying the feature is unconfigured.

The result never claims the machine is awake — `ok` means the bridge accepted
the request, and the message says so. Claiming more would have you debugging
working setup when the machine is merely slow to resume.

```yaml
    wakeBridgeUrl: http://127.0.0.1:8787   # behind the same tunnel as the host
    wakeBridgeToken: <shared secret>
    wakeMac: aa:bb:cc:dd:ee:ff             # optional; omit if the bridge knows
```

## Tests

```sh
cd plugins/mobile-bridge
pnpm test     # 60 tests
pnpm build
```

The header-encoding tests are not ceremony. The notification title can carry a
tool name or file name that originated from the model, and a control character
in an HTTP header value is request splitting. The encoder strips control
characters rather than escaping them, so an injection attempt is destroyed
rather than hidden.
