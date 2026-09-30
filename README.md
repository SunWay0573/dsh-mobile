# dsh-mobile

**Control your home machine's [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) agent from your phone.**

An Android app + a set of DSH host plugins that turn a desktop running DSH into a
7×24 remotely-operated agent: same conversation data as the desktop, the same
web UI for the transcript, plus the things a phone actually needs — remote wake,
sleep that respects whether work is running, push notifications when the agent
needs you, and a privacy curtain so nobody at home can read your screen.

> **Unofficial.** This is a community project. It is not affiliated with,
> endorsed by, or sponsored by DeepSeek. DSH itself is MIT-licensed, which is
> what makes this project possible — see [LICENSE](LICENSE).

---

## Why this exists

DSH's desktop app is, underneath, a local HTTP server plus a browser UI. That
means a phone can talk to the *same host process* and see the *same session
files* — no sync layer, no conflict resolution, no replication.

But four things are missing for real remote use, and this project supplies them:

| Gap | What dsh-mobile adds |
|---|---|
| The host only binds `127.0.0.1`, and the CLI refuses `--host 0.0.0.0` | Tunnel recipes + a profile patch |
| Nothing stops the machine sleeping mid-task — or keeps it sleeping when idle | **`sleep-guard`**: work-aware sleep inhibition |
| There is no way to wake a sleeping machine from outside | **Wake stack**: RTC self-wake + WoL bridge |
| DSH has no outbound notification path at all | **`mobile-bridge`**: push, privacy curtain, wake trigger |

---

## Architecture

```
┌──────────────────────────────────────────────┐
│  Android app (Kotlin + Compose)               │
│  · Native shell: sessions, settings, wake     │
│  · WebView: the conversation UI               │
│    (literally DSH's own web client)           │
│  · FCM push · WoL sender · biometric lock     │
└───────────────┬──────────────────────────────┘
                │ WireGuard tunnel / frp
                ▼
┌──────────────────────────────────────────────┐
│  Home machine — the single DSH host           │
│                                               │
│  dsh --profile web --port 3080                │
│    ├─ HTTP /api/*           (RPC)             │
│    ├─ WS  /api/remote.mux   (streams)         │
│    └─ static UI                               │
│                                               │
│  plugins/sleep-guard      work-aware sleep    │
│  plugins/mobile-bridge    push + curtain      │
│                                               │
│  ~/.dsh/sessions/<project>/<session-id>/      │
│      session.lock            ← exclusive lock │
│      session.v4.jsonl.zstd   ← the data       │
└──────────────────────────────────────────────┘
                ▲
                │ magic packet (same subnet only)
        wol-bridge (Raspberry Pi / NAS / router)
```

### The one architectural constraint

A DSH session file is guarded by an **exclusive kernel lock** (`flock`, no
timeout, no preemption). Exactly one host process may write a session at a time.

So the phone and the desktop must be **two clients of the same host process** —
not two DSH instances. This is why "sync" is a non-goal: there is only ever one
copy of the data.

---

## Components

### `android/` — the app

Native Kotlin + Jetpack Compose shell around a WebView.

The shell owns everything that benefits from being native: session list,
connection state, wake button, settings, biometric unlock, push handling.

The conversation view is **DSH's own web client** loaded from your host. That is
deliberate: the transcript, tool-call cards, approval prompts and composer stay
pixel-identical to the desktop, and they keep working when DSH updates — with no
reimplementation to maintain.

### `plugins/sleep-guard/` — sleep that follows the work

The behaviour everyone actually wants:

| State | Behaviour |
|---|---|
| Idle, nothing running | **Sleep.** Save power, stay quiet. |
| A turn or job is running | **Stay awake.** Never sleep mid-task. |
| A scheduled task is pending | **Self-wake on time** via RTC. |
| You want it | **Wake it from your phone.** |

DSH already emits exactly the events needed — `agent/status` (`idle ⇄ running`),
`agent/created` / `agent/disposed`, and `ctx.jobs`:

```ts
const busy = () =>
  ctx.agents.roots().some(a => a.status === 'running') ||
  ctx.jobs.list().some(j => j.status === 'running')
```

When `busy()` flips true we spawn `caffeinate -i`; when it flips false we kill it.
Using a child process rather than a native assertion API is intentional: the
assertion's lifetime is then bound to a process, so a crashed or wedged plugin
can never leave the machine permanently unable to sleep.

### `plugins/mobile-bridge/` — push, curtain, wake

- **Push** — DSH has no outbound notification path. This plugin subscribes to
  `approval/request`, `user-questions/request` and turn completion, and posts to
  [ntfy](https://ntfy.sh) (or any webhook). Without this, an unattended agent
  silently fails whenever a tool needs approval — DSH's approval policy has no
  timeout and fails closed.
- **Privacy curtain** — locks the screen while you are remote. Note this is
  *stronger* than what pixel-streaming remote-desktop tools can do: they must
  fake a black screen with an overlay because a real lock would blind them.
  DSH drives the machine over HTTP and does not care about the display at all,
  so it can just lock the screen.
- **Wake trigger** — a button in your phone's app that talks to `wol-bridge`.

### `wol-bridge/` — waking a sleeping machine

Wake-on-LAN packets are broadcast and **do not cross routers**, so waking from
outside the house requires *something already awake on the same subnet*.

This is a ~40-line script for a Raspberry Pi, NAS, or any always-on machine on
your LAN, plus a tiny HTTP endpoint your phone can reach over the tunnel.

If you would rather not run anything extra, check whether your router has a
built-in Wake-on-LAN feature first — many do.

---

## Quick start

> Detailed guides live in [`docs/`](docs/). This is the shape of it.

**1. Make the host reachable.** Run a dedicated DSH host and put a tunnel in
front of it:

```sh
dsh --profile web --no-open --port 3080
```

Then either a WireGuard/Tailscale tunnel, or `frp` to a small VPS. Full recipes,
including the trust-fence patch DSH needs before it will accept a non-loopback
`Host` header: [`docs/setup-tunnel.md`](docs/setup-tunnel.md).

**2. Install the plugins** into your DSH profile, and restart the host.

**3. Configure the wake stack.** Enable Wake-on-LAN on AC power, and — this one
bites everyone — **turn off the randomised Wi-Fi MAC address**, or your magic
packets will be addressed to a MAC the machine no longer uses:

```sh
networksetup -getMACAddress Wi-Fi   # hardware MAC
ifconfig en0 | grep ether           # what it is actually using
```

If these differ, turn off "Private Wi-Fi Address" for that network first.
See [`docs/setup-power.md`](docs/setup-power.md).

**4. Build and install the app.** Grab an APK from
[Releases](../../releases), or build it yourself — see [`android/`](android/).

---

## Status

Early. The design is settled and grounded in a hands-on audit of the DSH source
and of a shipping commercial remote-desktop tool; the implementation is being
built in the open.

| Component | State |
|---|---|
| Design & architecture | ✅ documented in [`docs/design.zh.md`](docs/design.zh.md) |
| `sleep-guard` | 🚧 in progress |
| `mobile-bridge` | 🚧 in progress |
| `wol-bridge` | 🚧 in progress |
| Android app | 🚧 in progress |

The design document is currently Chinese-only. An English translation is
welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

Two things worth knowing before you start:

- **Never commit credentials.** Tunnel configs, `~/.dsh/.credentials.yaml`,
  ntfy topics, and WoL scripts with hardcoded addresses are all easy to leak.
- **Set a per-repo git identity** if your global one is a work address. Your
  email is baked into public history permanently the moment you push:
  ```sh
  git config user.email "<id>+<username>@users.noreply.github.com"
  ```

## Security

This project exposes an agent that can execute code on your machine. Read
[SECURITY.md](SECURITY.md) before putting it on a network. The short version:
use a tunnel, never a public port-forward; scope your tunnel ACL to your own
device; and treat your phone as the keys to your computer.

## License

MIT — see [LICENSE](LICENSE).
