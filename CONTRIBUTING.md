# Contributing to dsh-mobile

Thanks for wanting to help. This project is early, so the most useful
contributions right now are bug reports, platform-specific findings, and the
pieces listed under [Good first issues](#good-first-issues).

## Verify before you push

```sh
scripts/verify.sh            # everything available on this machine
scripts/verify.sh --strict   # what CI runs; missing toolchains are failures
scripts/verify.sh --only=plugins
scripts/verify.sh --without-integration   # skip the slow host boot
```

This is the same script CI calls, so a local pass means a CI pass. It degrades to
a skip when a toolchain is absent and says so loudly; `--strict` turns those
skips into failures, which is what CI passes, because a check that silently did
nothing looks exactly like a pass.

The Android section needs a JDK and an Android SDK. Without them it skips, and
you can still verify everything else.

The integration section boots a real DSH host with the plugins installed. It
downloads the DSH CLI on first run (~200 MB, cached afterwards) and takes a
couple of minutes. Do not skip it habitually: it is the only check that can see
a plugin which installs, typechecks, passes every unit test, and never runs.
That has happened here three times.

## Before you start

**Never commit secrets or private topology.** Specifically:

- `~/.dsh/.credentials.yaml` (contains the browser-session signing secret)
- Tunnel configs that encode your home network (`frpc.toml`, Tailscale state)
- ntfy topics — treat the topic string as a password
- WoL scripts with your real MAC address and subnet

The `.gitignore` covers the common cases, but check `git diff --staged` before
every commit.

**Set a per-repo git identity** if your global one is a work address:

```sh
git config user.name  "Your Name"
git config user.email "<id>+<username>@users.noreply.github.com"
```

Your email is written into public history permanently on first push. Rewriting
it later is painful and anyone who already fetched keeps the old value.

## Repository layout

| Path | What it is | Toolchain |
|---|---|---|
| `android/` | The Android app | Kotlin, Jetpack Compose, Gradle |
| `plugins/` | DSH host plugins | TypeScript, pnpm |
| `wol-bridge/` | LAN Wake-on-LAN sender | Python 3, stdlib only |
| `docs/` | Design and setup guides | Markdown |

Keep `wol-bridge` dependency-free — it is meant to run on a Raspberry Pi or a
NAS where installing packages is a chore.

## Plugin conventions

DSH plugins follow the host's conventions, and this repo matches them:

- A plugin declares what it needs with `export const inject = [...]`.
- Lifecycle work goes through `ctx.effect()` and returns a disposer. **If your
  plugin acquires a resource, release it in the disposer** — for `sleep-guard`
  that is the difference between "sleeps when idle" and "never sleeps again".
- Cross-plugin value imports are not allowed by the DSH client build. Reuse
  happens through slots and services, not imports.
- Use `ctx.settingsScope.bind(<schema>)` for user-facing configuration. It
  persists to the host settings document and syncs across clients. Do **not**
  reach for `cordis.patch.yml` for anything a user might want to change at
  runtime — client plugins cannot read it.

### Testing sleep-guard specifically

The failure mode that matters is a leaked assertion. Verify both directions:

```sh
# idle: no assertion from the plugin
pmset -g assertions | grep -i caffeinate

# start a long task, then re-check: an assertion must appear
# when the task finishes: it must disappear
```

If the assertion survives the task, the disposer is wrong. Please include both
observations in your PR description.

## Commit messages

Conventional Commits, scoped to the component:

```
feat(sleep-guard): hold assertion while background jobs run
fix(android): reconnect WebView after process death
docs(tunnel): document the trust-fence patch
```

## Pull requests

1. One logical change per PR.
2. Run `scripts/verify.sh --strict` and say what it reported. If a section
   skipped on your machine, say which — that is honest and useful, whereas a
   bare "tests pass" is neither.
3. Say what you actually tested, and on what hardware/OS. "Works on my machine"
   is fine as long as you say which machine.
4. If your change touches power management, networking, or authentication,
   explain the failure mode you considered.

## Good first issues

- **Test Wake-on-LAN on non-Apple hardware** and report what differs. The
  packet is verified byte for byte; delivery on a given NIC, switch and subnet is
  not.
- **Run `wol-bridge` on a Raspberry Pi** and report what differs from macOS.
  Linux is where `SO_BROADCAST` is actually enforced, so CI covers it, but nobody
  has run it on the hardware it is meant for.
- **Run the Android app on a device.** It builds and its unit tests pass, but
  anything needing a screen, a WebView or a network is unverified — as is MIUI's
  appetite for killing backgrounded apps.
- **Router wake support matrix.** If your router can send a magic packet from
  its admin UI or app, add a row to `docs/setup-power.md`.
- **Android: verify reconnect after the OS kills the process.**

## Reporting bugs

Include: OS and version, device model, DSH version, which component, and the
exact commands or steps. For power-management bugs, always include the output of
`pmset -g assertions`.

## Code of conduct

Be decent. Assume good faith. Technical disagreements are welcome; personal ones
are not.
