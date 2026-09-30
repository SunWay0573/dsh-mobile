# mobile-bridge

The outbound half of remote control: things DSH cannot tell a phone that is not
currently connected.

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

## 2. Privacy curtain — not implemented yet

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

No root required. What is missing is the **trigger**: the plugin needs a signal
for "a remote client is now driving this session", which is a different
subscription from anything above and has not been wired yet.

## 3. Wake trigger — not implemented yet

A command the phone calls to wake the machine, delegating to
[`wol-bridge`](../../wol-bridge/) — a sleeping machine cannot send its own wake
packet. Straightforward once there is a route for the phone to call; see the
`wol-bridge` README for the topology constraint.

## Tests

```sh
cd plugins/mobile-bridge
pnpm test     # 41 tests
pnpm build
```

The header-encoding tests are not ceremony. The notification title can carry a
tool name or file name that originated from the model, and a control character
in an HTTP header value is request splitting. The encoder strips control
characters rather than escaping them, so an injection attempt is destroyed
rather than hidden.
