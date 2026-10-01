# dsh-mobile

**English** | [中文](README.zh.md)

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
│  · Native shell: settings, wake, biometric    │
│  · WebView: the conversation UI               │
│    (literally DSH's own web client)           │
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

**The WebView owns everything that comes from the host. Native owns what the
host cannot do.**

The conversation view is **DSH's own web client** loaded from your host, and the
native shell handles only what a page cannot: remembering the host address,
sending a magic packet, gating access behind a biometric, and reconnecting after
the OS kills the process.

Reusing the web client is deliberate. The transcript, tool-call cards, approval
prompts and composer stay identical to the desktop, and they keep working when
DSH updates — with no reimplementation to maintain.

A **native session list was in the original plan and was withdrawn.** Rendering
it natively means reimplementing DSH's RPC envelope *and* its one-time-token-to-cookie
exchange, to draw a screen the web client already draws correctly — a second
implementation of the authentication path, hand-synced to someone else's
protocol. The sidebar in the loaded client is the session list.

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
  ctx.agents.list().some(a => a.status === 'running') ||
  collectJobs(ctx).some(j => j.status === 'running' || j.status === 'stopping')
```

Three details here are easy to get wrong, and all three are handled:

- **`status === 'idle'` does not mean nothing is happening.** An idle agent has
  no *driver* scheduled; a background `bash` job outlives the turn that started
  it. Checking only agents produces a machine that sleeps in the middle of a
  build.
- **`ctx.jobs.list()` with no argument returns only unowned jobs.** It has to be
  unioned with `list(sessionId)` for every live agent, subagents included, or
  every ordinary background job is invisible.
- **`'stopping'` still occupies the machine.** The work has not finished.

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

This is a dependency-free Python service for a Raspberry Pi, NAS, or any
always-on machine on your LAN, plus a small HTTP endpoint your phone can reach
over the tunnel.

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

**4. Install the app.** [`v0.1.0-pre`](https://github.com/SunWay0573/dsh-mobile/releases/tag/v0.1.0-pre)
has a debug-signed APK you can sideload today:

```sh
adb install app-debug.apk
```

Or build it yourself — see [`android/`](android/). A release signing key is a
decision for whoever runs this, and a secret, so the pre-release is debug-signed
and says so.

**Verify any of it** with the script CI itself calls:

```sh
scripts/verify.sh            # everything, including a real host boot
scripts/verify.sh --strict   # what CI runs; missing toolchains are failures
scripts/verify.sh --without-integration   # skip the slow one
```

A fresh `git clone` verifies green with that one command and no setup steps. It
installs the plugin dependencies, builds them, runs every test, builds the
Android APK, and boots a real DSH host with both plugins installed. That last
part is not decoration: it is the only check that can see a plugin which
installs cleanly, typechecks, passes every unit test, and never runs — which has
happened here three times.

The integration check is the interesting one. It installs the plugins into an
isolated `DSH_HOME`, boots a real DSH host, and asserts two things that nothing
else can:

- **that they load at all** — three bugs shipped here that installed cleanly,
  typechecked, passed every unit test, and never ran;
- **that `sleep-guard` works** — a fixture plugin opens a background job, and
  the script watches `pmset -g assertions` (or `systemd-inhibit` on Linux) for
  the assertion to appear while the work is in flight and disappear when it
  finishes. Observed from outside the process, because a plugin reporting its
  own state proves nothing.

---

## Status

Early, but complete enough to run. The design is settled and grounded in a
hands-on audit of the DSH source and of a shipping commercial remote-desktop
tool; everything in the table below is implemented, tested and pushed.

| Component | State | Tests |
|---|---|---|
| Design & architecture | ✅ [`docs/design.md`](docs/design.md) (English), [`docs/design.zh.md`](docs/design.zh.md) (full audit) | — |
| `plugins/sleep-guard` | ✅ work-aware sleep inhibition | 54 |
| `plugins/mobile-bridge` | ✅ push, privacy curtain, wake trigger | 68 |
| `wol-bridge` | ✅ magic packets + HTTP endpoint | 73 |
| Android app | ✅ debug **and release** build; APK + 55 unit tests verified locally | 55 |
| Releases | ✅ [`v0.1.0-pre`](https://github.com/SunWay0573/dsh-mobile/releases/tag/v0.1.0-pre), debug-signed APK | — |
| CI | ⏸ written and on `ci-workflow`; run [`scripts/enable-ci.sh`](scripts/enable-ci.sh) | — |

250 tests total, plus integration verification against a real DSH host. Both
plugins are installed into an isolated `DSH_HOME` and booted on every
`scripts/verify.sh` run, which is how three silent-non-load bugs were found that
no typecheck or unit test could see. Both are now checked automatically:
[`scripts/check-plugins.mjs`](scripts/check-plugins.mjs) from source, and
[`scripts/verify-integration.sh`](scripts/verify-integration.sh) by doing it.

The Android app has been built against a real SDK —
`BUILD SUCCESSFUL` for both variants, a 10.7 MB debug APK, a 7.5 MB release APK,
and its 55 unit tests passing — so it is verified rather than merely written.

The release APK is checked **as an artifact**, not as source. Release builds
rename resources, so the network security config has to be resolved from the
manifest attribute through the resource table to whatever path the packager
chose — in this project's own release APK that is `res/8G.xml`. Reading a named
file out of `src/main/res/` proves what was written; this proves what ships.

The CI workflow is written and lives on the `ci-workflow` branch. GitHub refuses
to let an OAuth app push `.github/workflows/` without the `workflow` scope — a
control that exists so a compromised token cannot silently add CI that
exfiltrates secrets — so the file waits there.

To finish it:

```sh
scripts/enable-ci.sh --check    # says exactly what is missing
gh auth refresh -h github.com -s workflow   # you authorise in a browser
scripts/enable-ci.sh            # installs and pushes
```

Five jobs: hygiene, plugins, wol-bridge, integration, android. The integration
job boots a real DSH host, because that is the only thing that can see a plugin
which installs, typechecks, passes every unit test and never runs.

[`docs/design.md`](docs/design.md) is the English design document: why the
project is shaped this way, including the decisions that were reversed.
[`docs/design.zh.md`](docs/design.zh.md) is the longer original, in Chinese,
with every source reference and measured value behind it.

---

## When something does not work

[`docs/troubleshooting.md`](docs/troubleshooting.md) is organised by symptom and
says how to *confirm* each cause rather than only what it might be. The entries
worth knowing about before they happen:

- **A plugin is installed and does nothing.** Three distinct causes, all of
  which present identically and all of which pass every automated check.
- **403 vs 401.** A 403 is a `Host`/`Origin` problem and has nothing to do with
  authentication. Knowing which one you have saves the most time of anything in
  that document.
- **The machine will not wake.** Usually the randomised Wi-Fi MAC address, and
  the hardware MAC is the wrong one to configure.
- **A scheduled task did not run.** It will not, if the machine slept through
  it — the timers are in-process.

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

DSH itself is MIT (Copyright (c) 2026 DeepSeek); this project builds against its
public plugin API and ships none of its code. Details, plus the
not-affiliated statement, are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
