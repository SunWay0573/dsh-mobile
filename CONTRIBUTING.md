# Contributing to dsh-mobile

Thanks for wanting to help. This project is early, so the most useful
contributions right now are bug reports, platform-specific findings, and the
pieces listed under [Good first issues](#good-first-issues).

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
2. Say what you actually tested, and on what hardware/OS. "Works on my machine"
   is fine as long as you say which machine.
3. If your change touches power management, networking, or authentication,
   explain the failure mode you considered.

## Good first issues

- **Translate `docs/design.zh.md` to English.** It is the single highest-value
  contribution available right now.
- **Test Wake-on-LAN on non-Apple hardware** and report what differs.
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
