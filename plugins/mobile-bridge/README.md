# mobile-bridge

Three small things a phone needs that DSH does not provide out of the box.

## 1. Push notifications

DSH has **no outbound notification path**. `packages/webhook` is inbound (it
turns external deliveries into sessions); nothing pushes events out. The only
existing escape hatch is a local command hook.

That is a problem, because DSH's approval policy has **no timeout and fails
closed**: if the agent needs approval and no client is connected to answer, the
tool is simply denied. An unattended agent that cannot reach you does not wait —
it quietly does less than you asked.

So this plugin subscribes to:

- `approval/request`
- `user-questions/request`
- turn completion

and posts to a webhook. [ntfy](https://ntfy.sh) is the recommended target
(open source, self-hostable, Android app, supports action buttons), but anything
that accepts an HTTP POST will do — a WeCom/WeChat bot, Server Chan, or your own
endpoint.

Payloads carry the `sessionId` so the notification is tappable and lands you
directly in the right conversation.

> Treat the ntfy topic string as a password. Anyone who knows it can push to your
> phone. Use a long random value, or self-host.

## 2. Privacy curtain

Locks the screen while you are connected remotely, so nobody standing at the
machine can read your conversation or touch anything.

**This is deliberately not implemented the way remote-desktop software does it.**
Pixel-streaming tools must draw a fake black overlay and protect its window
level, because a real lock screen would blind them — they need the display to
keep producing frames. DSH drives the machine over HTTP and does not care about
the display at all, so it can just lock the screen, which is strictly stronger:
an overlay can be dismissed locally, a lock screen cannot.

```sh
"/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession" -suspend
```

No root required.

Trigger policy is configurable: lock on connect, lock on idle timeout, or manual
only. Unlocking is local by design — the plugin will not unlock the machine for
a remote client.

## 3. Wake trigger

A command the Android app calls to wake the machine. It delegates to
[`wol-bridge`](../../wol-bridge/) rather than sending the packet itself, because
a sleeping machine cannot send its own wake packet.

## Status

🚧 Not implemented yet. Tracking issue: TBD.
