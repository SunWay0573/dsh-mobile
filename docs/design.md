# Design

Why this project is shaped the way it is. The decisions and the reasoning behind
them, including the ones that were reversed.

The full audit this is drawn from — every `pmset` reading, every source
reference, the complete teardown of a commercial remote-desktop tool — is in
[`design.zh.md`](design.zh.md), in Chinese. This document is the same design in
English and in a form meant to be read rather than referenced.

---

## 1. The short version

DSH's desktop app is, underneath, a local HTTP server plus a browser UI. So a
phone can talk to the **same host process** and see the **same session files**.
No sync layer, no conflict resolution, no replication — those problems do not
exist because there is only ever one copy of the data.

Building the phone client is therefore not the hard part. Four other things are,
and DSH has none of them:

| Gap | Evidence | Filled by |
|---|---|---|
| The host binds only `127.0.0.1`, and the CLI refuses `--host 0.0.0.0` | `packages/bundle/web-app/src/startup.ts:74` | a tunnel, plus a profile patch (§6) |
| Nothing stops the machine sleeping mid-task | no `caffeinate` anywhere in the tree | [`sleep-guard`](../plugins/sleep-guard/) (§7) |
| There is no outbound notification path at all | `packages/webhook` is **inbound** | [`mobile-bridge`](../plugins/mobile-bridge/) (§12) |
| No way to wake a sleeping machine | Operating-system physics | [`wol-bridge`](../wol-bridge/) (§9) |

There is a fifth, which is not a gap in DSH but a consequence of the design: the
**privacy curtain** (§8), and the reason it is done differently here than in
every remote-desktop tool.

### The one architectural constraint

A DSH session file is guarded by an **exclusive kernel lock** — `flock`, no
timeout, no preemption (`packages/session/session-persistence-jsonl/src/lease.ts`).

So exactly one host process may write a session at a time, and the phone and the
desktop must be **two clients of the same host process**, not two DSH instances.

Everything else follows from that.

---

## 2. What an audit of a commercial tool taught us

Before designing anything, a shipping remote-desktop product (NetEase's UU
Remote, which happened to be installed) was taken apart: its launch daemons, its
power assertions, its privacy modes, its wake implementation.

Three findings shaped this project.

### Wake-on-LAN cannot be solved in software

The product's own error strings settle it:

> "No auxiliary device is online on the LAN, so the wake command cannot be
> forwarded."

Wake-on-LAN packets are **broadcast**, and broadcast does not cross routers. A
phone on mobile data cannot wake a machine at home no matter how the packet is
built; something already awake on the same subnet has to send it.

**This is not a limitation to engineer around.** The commercial tool has exactly
the same one and says so. It is why `wol-bridge` exists as a separate component
with its own host, and why §9 is about *staying awake* rather than *waking up*.

### How to hold a machine awake without changing its settings

That product holds an `IOPMAssertion` for as long as it runs, which is visible
as `PreventUserIdleSystemSleep` in `pmset -g assertions`. It never touches the
user's `sleep` setting.

That is a better idea than editing power policy, and `sleep-guard` uses the same
mechanism — a child process rather than an in-process assertion, for the reason
in §7.

### The privacy curtain should not be copied

The tool draws a fake black overlay and protects its window level, because a real
lock screen would blind it: it needs the display to keep producing frames.

**DSH does not have that problem**, and §8 explains why the obvious approach is
the wrong one here.

It also runs a **root launch daemon** with a Mach service. Nothing this project
does needs root, and that is a deliberate property rather than an accident.

---

## 3. What DSH provides, and what it does not

Worth stating, because it is why this project is small.

**Provided, and reused rather than reimplemented:**

| Capability | Where |
|---|---|
| The web UI itself — desktop and phone load the same frontend | `apps/web` |
| Append-only session storage, JSONL in Zstandard frames, self-repairing | `packages/session/session-persistence-jsonl/` |
| Turns that outlive the client socket: `session.prompt` returns `{accepted:true}` immediately | `packages/api/session-controller/src/commands.ts:363` |
| Gap-free reconnect: `session.follow` opens with a snapshot and a cursor, then replays by sequence | `packages/api/session-controller/src/history.ts` |
| Several clients on one session, sharing one live agent | `packages/api/session-controller/src/agent.ts:187` |
| A slot system that lets a UI be replaced without forking | `packages/client/ui-slots/` |
| Goals that drive continuation rounds, and session-scoped schedules | `packages/goal/`, `packages/schedule/` |

**Absent, and therefore built here:** binding beyond loopback, outbound
notifications, approval timeouts, sleep awareness, waking, and anything mobile.

**One version caveat that matters.** DSH 0.1.x and 0.2.x differ in ways that
break plugins silently: `jobs.list()` with no argument returns only *unowned*
jobs, `onJobsChanged` was removed in favour of `jobs.events.subscribe`, and a
tool must declare an output schema or its return type infers as `never`. All
three were found by typechecking against the installed version rather than
trusting the source checkout.

---

## 4. Goals, and the constraints that shaped them

The goal is a machine you can leave at home and operate entirely from a phone: a
seven-by-seven agent, not a remote desktop.

Four constraints did most of the shaping.

1. **One writer per session.** Single-host architecture, no exceptions.
2. **Approvals fail closed with no timeout.** An unattended agent whose approval
   request reaches nobody does not wait — it does less than it was asked to,
   silently. Notifications are therefore not a nicety (§12).
3. **Apple Silicon cannot be woken from a full shutdown.** No Wake-on-LAN from
   S5, no boot-on-power-restore. Everything about waking assumes *asleep*.
4. **The intended host is often a machine someone else administers.** Corporate
   MDM, a VPN client in the way, and a policy about what may run.

---

## 5. Architecture

```
phone ──tunnel──► DSH host ──► ~/.dsh/sessions/<project>/<session>/
  │                 │                session.lock          ← exclusive
  │                 │                session.v4.jsonl.zstd ← the data
  │                 ├── sleep-guard    holds an assertion only while working
  │                 └── mobile-bridge  push, curtain, wake
  │
  └──► wol-bridge (always-on, same LAN) ──broadcast──► a sleeping machine
```

The phone holds **no state**. It renders the host's own UI in a WebView and
speaks to the host over its ordinary HTTP and WebSocket API. Nothing is
replicated, so nothing can diverge.

### Why the app is a hybrid

**The WebView owns everything that comes from the host. Native owns what the
host cannot do.**

DSH's client is not a standalone SPA: the host injects `window.__DSH_BOOT__` and
serves plugin bundles at runtime, so it cannot be bundled into an APK. Given
that, embedding it is strictly better than reimplementing it — the transcript,
tool-call cards, approval prompts and composer stay identical to the desktop and
keep working when DSH changes.

Native handles what a page cannot: remembering the host address, sending a magic
packet, gating access behind a biometric, and surviving the OS killing the app.

**One item was withdrawn from the original plan because it violated this.**
A native session list means reimplementing DSH's RPC envelope *and* the
one-time-token-to-cookie exchange, to draw a screen the web client already draws
correctly — a second implementation of the authentication path, hand-synced to
someone else's protocol. The sidebar in the loaded client is the session list.

---

## 6. Reaching the host from outside

DSH binds `127.0.0.1` and the CLI refuses `--host 0.0.0.0` with a good reason: it
would expose remote code execution to the network. That refusal is respected
here; a tunnel provides the path in.

### The trust fence, which is where most time is lost

DSH rejects a request unless `Host` is loopback or listed in `trustedHosts`, and
if an `Origin` header is present it must equal the `Host` authority exactly.

So a proxy that rewrites `Host` fails — and it fails with a **403**, which looks
like an authentication problem and is not. The distinction between 403 and 401
is the single most useful thing in
[`troubleshooting.md`](troubleshooting.md): **403 means the fence rejected the
request before authentication was considered.**

`trustedHosts` lives on the `connection` row, not on `webserver`, and a
deployment should append to the existing expression rather than replacing it.

### Authentication

A one-time launch token is printed on every start and exchanged for a signed
30-day cookie. Two consequences: restarting the host invalidates any URL you
copied, and the cookie is what makes the phone equivalent to the machine's keys.

---

## 7. Staying awake, and sleeping

The naive answer is `caffeinate` forever. That is a different product: a fan in
an empty room, power nobody asked for, and a machine that is awake to attackers
for no reason.

The interesting question is not "keep it awake", it is **"know when it is
working"**, and DSH already knows:

```ts
const busy = () =>
  ctx.agents.list().some(a => a.status === 'running') ||
  collectJobs(ctx).some(j => j.status === 'running' || j.status === 'stopping')
```

`agent/status` reports `idle ⇄ running`. Three details are easy to get wrong:

**`status === 'idle'` does not mean nothing is happening.** An idle agent has no
*driver* scheduled; a background `bash` job outlives the turn that started it.
Checking only agents produces a machine that sleeps in the middle of a build.

**`jobs.list()` with no argument returns only unowned jobs.** It has to be unioned
with `list(sessionId)` for every live agent, subagents included.

**`'stopping'` still occupies the machine.** The work has not finished.

### Why the assertion is a child process

macOS has `IOPMAssertionCreateWithName`; Linux has D-Bus inhibitors. Using
either directly would avoid spawning anything, and would mean a bug in this
plugin leaves the machine **permanently unable to sleep** — invisible, unloaded
with the plugin, fixed only by a reboot.

A child process inverts the risk. The assertion lives exactly as long as a
process we own, so a wedge, a crash, or a hot reload releases it automatically.

Every error path prefers "may sleep when it should not" over "can never sleep". A
missed wake-up delays a task; a leaked assertion runs a fan all night.

### Scheduled work is not covered

A session's schedule timers are in-process `setTimeout` calls and **cannot fire
while the machine sleeps**. The fix is an RTC wake event, which needs root, so it
is left to the operator rather than done silently by a plugin:

```sh
sudo pmset schedule wake "10/01/26 09:00:00"
```

That macOS schedules such events for itself is visible in `pmset -g sched` and is
the evidence the facility works.

---

## 8. The privacy curtain, done the other way round

Locks the screen while you are operating remotely, so nobody standing at the
machine can read the conversation.

**Every remote-desktop tool must fake this, and DSH does not have to.** A
pixel-streaming tool needs the display producing frames, so a real lock screen
would blind it; it draws an overlay and protects the window level instead, and an
overlay can be dismissed locally.

DSH drives the machine over HTTP and does not care about the display at all. So
it locks the screen — which is strictly stronger, and requires no code beyond one
command:

```sh
"/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession" -suspend
```

**There is no unlock, deliberately.** Unlocking needs the local user's
credentials, and a plugin that could unlock the machine for a remote caller would
defeat the point. Walking over and typing a password is the intended path.

### The trigger, which is the hard part

The action is one command; knowing *when* to run it is not. There is no "a remote
client just connected" signal available to a host plugin.

So it is a **tool the agent can call**. The alternatives were worse: locking on
every turn locks the screen while you are sitting in front of it, and a plugin
that guesses wrong here is actively hostile. You are already talking to the
agent; asking it to lock the screen is a trigger that cannot misfire.

---

## 9. Waking, in the right order

Physics first — see §2. **The strategy is to not need waking**, and to treat
waking as a fallback:

| Situation | Mechanism |
|---|---|
| Normal | Stay awake while working (§7). A machine that is working never needs waking. |
| Asleep, and something on the LAN is awake | `wol-bridge` sends a magic packet |
| A scheduled task is due | An RTC wake event, programmed before sleeping |
| Off | **Nothing works.** Not from here, not from a commercial tool. |

### The trap that costs an afternoon

Modern Wi-Fi randomises the MAC address per network, so **the hardware MAC is
often not the one the interface is using**, and magic packets must be addressed
to the real one:

```sh
networksetup -getMACAddress Wi-Fi    # hardware MAC
ifconfig en0 | grep ether            # what it is actually using
```

They differ, the setting looks correct, and nothing ever wakes.

### Two wake paths in the app, bridge first

A direct UDP broadcast reaches one subnet. The phone away from home is the
situation the feature exists for, so a bridge `POST` over the tunnel — which
works from anywhere — is preferred when configured, and the settings screen says
which path will be used. The wrong choice fails silently.

---

## 10. The phone interface

**The layout adapts, and an earlier version of this document said it did not.**

The columns have minimum widths — 264, 400 and 300 pixels — and reading only
those constants suggests three columns totalling 964 pixels cannot fit a phone.
It is wrong, and it was wrong because the constants were read without checking
how they are used. The actual solver is:

```ts
const available = viewport - sidebar - CENTER_MIN
const rightbar = rightbar === 0 || available < RIGHTBAR_MIN ? 0 : …
return { sidebar, center: max(0, viewport - sidebar - rightbar), rightbar }
```

Below 1024 pixels the sidebar collapses to a 56-pixel rail, and the right bar
**collapses to zero** when there is not room for a 400-pixel centre. Resolved at
real widths:

| Viewport | Rail | Centre | Right bar |
|---|---|---|---|
| 1680 | 280 | 1020 | 380 |
| 768 | 56 | 400 | 312 |
| 390 (a typical phone) | 56 | **334** | 0 |
| 320 | 56 | 264 | 0 |

So a phone gets a two-column layout — a sliver rail and the conversation — and it
works. Nothing was broken.

This is recorded rather than quietly deleted because the mistake is instructive:
a design document that asserts a constraint from reading constants, without
checking the code that consumes them, is how a project ends up building a
solution to a problem it does not have.

What *is* genuinely unadapted is smaller and was not verified on a device:
affordances that assume hover (`@media (hover: hover)` guards some of them, not
all), and a 56-pixel rail that is a desktop-shaped control on a touch screen. The
app sets `windowSoftInputMode="adjustResize"` so the keyboard pushes content
rather than covering it, and it does **not** request `viewport-fit=cover` — which
means the platform insets the viewport and no safe-area handling is needed. Both
statements are from the configuration, not from a phone, and that is the honest
status of every claim in this section.

Two UI decisions worth recording:

**Errors say what to do.** The likeliest first-run failure is not a network
problem — it is that DSH prints a one-time sign-in link on every start and the
phone has to open it once. `WebErrors` turns a status into advice, and returns
nothing where it has nothing specific to say, because a vague banner over a
working page is worse than none.

**Failures are reported for the main frame only.** DSH streams plugin bundles and
optional assets; a banner raised because one of them 404s would cover a page that
works perfectly.

---

## 11. Why there is no sync

Worth stating because it is the question everyone asks first, and the answer
removes most of the complexity a reader expects.

Sessions live on the host. The phone and the desktop are two views of one file.
There is no replication, no conflict resolution, and no consistency model,
because there is nothing to reconcile.

The cost of that simplicity is the constraint in §1: **one host**. Two DSH
processes on the same session produce a `SessionAlreadyOwnedError`, not
corruption — the append-only format plus an exclusive lease makes silent damage
impossible.

---

## 12. Notifications, which are not optional

DSH has no outbound notification path. `packages/webhook` is **inbound** — it
turns external deliveries into sessions — and nothing pushes events out.

Combined with an approval policy that **fails closed with no timeout**, that
means an unattended agent whose approval request reaches nobody silently does
less than it was asked to. From the phone, that looks like the agent got lazy. It
did not; nobody answered.

So `mobile-bridge` subscribes to `approval/request` and to turn completion, and
posts to a webhook (ntfy by default).

### The waterfall trap

`approval/request` is a **waterfall** event. A listener that returns without
calling `next()` *claims* the request — so an observer that forgets to delegate
is not merely failing to observe, it becomes a **silent auto-deny for every
approval-gated tool call**. The handler delegates first and notifies second, and a
test asserts the delegation happens.

### The tap target is an app scheme

A notification's `click` cannot be the host's URL: that opens a **browser**, and
the sign-in cookie lives in the app's WebView, so the tap lands on an
unauthenticated page. `dshmobile://session/<id>` is handled by the app, which
already knows the host address.

**The notification path deliberately does not go through our app.** ntfy's own
Android client delivers it, which is why there is no FCM dependency and no push
code to maintain.

---

## 13. Unattended operation and security

The recommended combination is **Auto review** — a model assesses each tool call
before it executes — plus push notifications, so a denial reaches a human.

Setting the policy to `never` makes an agent work unattended, and is the strict
headless stance. It also means approval-gated tools fail rather than wait, so
`never` **must** be paired with notifications.

Security posture, in priority order:

1. **Never expose the host to the public internet.** A tunnel, and a port-forward
   is not a mitigation when the thing behind it runs arbitrary shell commands.
2. **Scope the tunnel ACL to your own device.** Minutes of work, an entire class
   of problem removed.
3. **Treat the phone as the keys to the computer**, because that is what the
   30-day cookie makes it. The biometric lock exists for exactly this.

Not attempted: any privileged helper. The commercial tool in §2 installs a root
launch daemon; nothing here needs root, and keeping it that way is worth more
than any convenience it would buy.

---

## 14. What to distrust before you trust any of this

Recorded because a design document that only lists its strengths is not useful.
See [`troubleshooting.md`](troubleshooting.md) for the symptoms.

- **Wake-on-LAN delivery is unverified on real hardware.** The packet is verified
  byte for byte on a real socket; whether a given NIC, switch and subnet deliver
  it is not.
- **`wol-bridge` has never run on a Raspberry Pi.** All testing was macOS. Linux
  is where `SO_BROADCAST` is actually enforced, so CI running it there is real
  coverage rather than a formality.
- ~~The Android app has never run on a device.~~ **It has now.** Installed over
  ADB on a Redmi Note 15 Pro (Android 16) and driven through a real session
  against a real host. Two bugs came out of it that no amount of building or
  unit testing could have found — a WebView height collapse and content drawn
  under the status bar — and both are fixed. Still unverified on a device:
  background survival, the wake button against real hardware, and the biometric
  lock.
- **The systemd unit in the `wol-bridge` README is written from knowledge**, not
  started.
- **No release signing key exists.** The published APK is debug-signed.

---

## 15. What changed along the way

Three decisions were reversed, and the reversals are more instructive than the
originals.

**A permanent sleep assertion became a work-aware one.** Keeping the machine up
unconditionally is simpler to write and worse to live with. The behaviour that
was actually wanted is "sleep when idle, stay awake while working".

**A native session list was withdrawn** — §5.

**A lazy `import()` guard was abandoned.** It was written to make a missing
dependency degrade instead of aborting the plugin. It does not work: TypeScript's
`rewriteRelativeImportExtensions` rewrites static specifiers and leaves dynamic
ones alone, so the emitted code asks for a `.ts` file that is not there, the
build succeeds, and the host reports only "failed to import". The real fix was
the boring one — declaring the dependency.

---

## 16. See also

- [`troubleshooting.md`](troubleshooting.md) — organised by symptom, and says how
  to confirm each cause.
- [`setup-tunnel.md`](setup-tunnel.md) — reaching the host, including the fence.
- [`setup-power.md`](setup-power.md) — sleeping, waking, and the MAC trap.
- [`design.zh.md`](design.zh.md) — the full audit, in Chinese, with every source
  reference and every measured value.
- [`../SECURITY.md`](../SECURITY.md) — threat model and revocation.
