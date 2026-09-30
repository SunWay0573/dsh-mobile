# Troubleshooting

Organised by symptom, because that is what you have. Each entry says how to
**confirm** the cause, not just what it might be — a guess you cannot check is
not much better than no answer.

If nothing here fits, see [Reporting a bug](../CONTRIBUTING.md#reporting-bugs)
for what to include.

---

## The plugin installed, but nothing happens

By far the most likely category, and the most confusing, because every automated
check passes. The package is fine. It simply never runs.

Three distinct causes, all of which present identically.

### 1. The package declares no `dsh.bundle`

```sh
dsh plugin --profile web add ./plugins/sleep-guard
# dsh: warning: dsh-mobile-sleep-guard declares no dsh.bundle —
#      installed as a plain dependency, not a profile layer
```

That warning is the whole diagnosis, and it scrolls past easily. A package
without `dsh.bundle.patch` is installed and never mounted.

**Confirm:**

```sh
dsh --profile web --dump-config | grep -A1 "dsh-mobile"
```

No row means no mount.

### 2. A value import from a package that is not a dependency

The host reports:

```
dsh: warning: 1 entry did not activate
mobile-bridge (dsh-mobile-mobile-bridge): failed to import
```

`import type` is erased at runtime and harmless. A bare value import is not:
if the package is only a development dependency, it does not exist when the host
loads the plugin, and the **whole plugin** aborts — taking unrelated features
down with it.

### 3. The patch's `name:` does not match the package name

Same symptom, different cause. The loader imports that string verbatim, so a
value that is merely close produces the same nothing. Look closely at the
message:

```
mobile-bridge (dsh-mobile-mobile-bridge): failed to import
                ^^^^^^^^^^ the package is actually dsh-mobile-bridge
```

**Confirm all three at once:**

```sh
node scripts/check-plugins.mjs      # from a checkout
```

Or check that the plugin genuinely works, rather than loading:

```sh
scripts/verify-integration.sh
```

---

## It will not connect

### First: which error is it?

This distinction saves the most time of anything here.

| Response | Meaning |
|---|---|
| **401** | The fence passed and you are simply not authenticated. Expected until you sign in. |
| **403** | The request was rejected **before** authentication was even considered. |

A 403 is a `Host`/`Origin` problem, not a password problem. Do not go looking
for a token.

### 403: the trust fence

DSH rejects a request unless `Host` is loopback **or** listed in `trustedHosts`,
and if an `Origin` header is present it must equal the `Host` authority exactly.

This is why proxies that rewrite `Host` fail. `tailscale serve` is the usual
culprit because it terminates TLS and may rewrite the header.

**Confirm:** add the authority your proxy presents to the `connection` row.
Append to the existing expression rather than replacing it — see
[setup-tunnel.md](setup-tunnel.md#the-trust-fence).

### 401 forever: the one-time sign-in link

DSH prints a sign-in link on **every** start, and the phone has to open it once
to exchange a launch token for a 30-day cookie. Restart the host and the launch
token changes; an old link is dead.

The Android app says this in the failure banner rather than showing a bare 401,
but a browser does not.

**Confirm:** the host prints `dsh web: http://.../?token=...` at startup. Open
that exact URL on the phone. Run the host with `--no-open` and keep the line
somewhere you can reach it.

### The address is right and nothing answers

Check the host is up locally first, then through the tunnel:

```sh
curl -sI http://127.0.0.1:3080/          # expect 401 — up, unauthenticated
curl -sI https://your-hostname/          # expect 401, not 403
```

If the first fails, the problem is the host. If only the second fails, it is the
tunnel.

---

## The session screen is blank or shows a cleartext error

The Android **release** build refuses cleartext HTTP except on loopback.

`network security config` `<domain>` entries accept hostnames and literal IPs,
**not CIDR blocks**, so "allow cleartext on `192.168.0.0/16`" cannot be
expressed. Rather than permit cleartext globally for an app that holds a session
cookie, the release build takes the strict posture.

**Options:**

- Use HTTPS. `tailscale serve` issues a real certificate for your tailnet name,
  which is the recommended deployment and needs no app changes.
- Add that exact address as a `<domain>` entry and rebuild.
- Use a **debug** build, which permits cleartext already. See
  [`android/README.md`](../android/README.md#cleartext-http).

Do not set `cleartextTrafficPermitted="true"` on `<base-config>`. `scripts/verify.sh`
asserts that value stays `false`.

---

## The machine will not wake

### It is off, not asleep

Apple Silicon Macs **cannot be woken from a full shutdown**. There is no
Wake-on-LAN from S5 and no boot-on-power-restore. Everything about waking
assumes the machine is asleep.

### The magic packet is going to the wrong MAC

The single most frustrating failure here, because everything looks correct.

Modern Wi-Fi randomises the MAC per network, so the address the hardware reports
is often **not** the one the interface uses.

```sh
networksetup -getMACAddress Wi-Fi    # hardware MAC
ifconfig en0 | grep ether            # MAC actually in use
```

If they differ, the interface is using a private address and your magic packet
is addressed to something the machine no longer answers to.

**Fix:** turn off Private Wi-Fi Address for that network, reconnect, and record
the value from `ifconfig`. See
[setup-power.md](setup-power.md#3-fix-the-mac-address-do-not-skip-this).

### Nothing is on the LAN to send it

Wake-on-LAN packets are **broadcast** and do not cross routers, so waking from
outside the house requires something already awake on the same subnet. This is
physics, not a missing feature — commercial remote-desktop tools hit the same
wall and say so in their own error messages.

**Confirm:** check your router's admin page for a built-in Wake-on-LAN feature
before running anything extra. Many have one.

### It wakes, then sleeps again immediately

`womp` is per power source. `pmset -g custom` shows AC and battery separately,
and “Wake for network access” is frequently on for one and off for the other.

---

## The machine never sleeps, or never wakes

### Something else is holding an assertion

Check who:

```sh
pmset -g assertions
```

Each entry names its owning process. A `PreventUserIdleSystemSleep` held for
days by a tool you forgot about is a common finding — keep-awake agents, other
remote-desktop software, and `caffeinate` invocations all look alike here.

**Note that `pmset` counts lines, not assertions.** A single assertion produces
both a `pid …` line and a `Details: …` line, so a loose `grep -c caffeinate`
reports two per assertion. That is exactly why the integration check compares
against a **baseline** rather than an absolute count.

### sleep-guard is holding one when it should not

That is the failure mode it is designed around, and it should be impossible: the
assertion is a child process, so it cannot outlive the plugin.

If you see one anyway, the process name tells you who owns it. Note the pid
before concluding it is sleep-guard.

### sleep-guard is not holding one when it should

It treats a job as occupying in both `running` **and** `stopping`, and it counts
unowned jobs as well as session-owned ones. If it is missing work you can see,
that is a bug worth reporting — include `pmset -g assertions` and what the host
was doing.

### A scheduled task did not run

A session's `schedule` timers are in-process `setTimeout`s. **They do not fire
while the machine is asleep**, so an idle machine will sleep straight through a
scheduled task.

The fix is an RTC wake event, which needs root and is therefore left to you:

```sh
sudo pmset schedule wake "10/01/26 09:00:00"
pmset -g sched          # what is already scheduled
```

You can see the facility is real: `pmset -g sched` usually shows entries Apple
scheduled for itself.

---

## The notification never arrives

**First, is it being sent?** Point the plugin at something you control and watch:

```yaml
# in the profile patch layer
- id: mobile-bridge
  name: dsh-mobile-bridge
  config:
    url: http://127.0.0.1:8080/notify
```

`scripts/fixtures/notify-sink.mjs` is a throwaway receiver that records what it
gets. Then you know which half is broken.

**If it is being sent:** check the ntfy topic. A topic is a password — anyone who
knows it can push to your phone — so a typo produces a message delivered
somewhere you are not reading.

**If it is not being sent:** the approval path depends on `approval/request`,
which is a waterfall. If another listener claims the request before
`mobile-bridge` sees it, no notification is raised and the request never reaches
a human. That would be a bug; please report it.

---

## Telling "the plugin is not loaded" from "the plugin is not working"

These look identical from a phone. The distinction, in order:

```sh
dsh --profile web --dump-config | grep -A1 dsh-mobile   # is there a row?
dsh --profile web --no-open                             # did an entry fail to activate?
node scripts/check-plugins.mjs                          # is the package well-formed?
scripts/verify-integration.sh                           # does it work in a real host?
```

The last one is the answer to the actual question. It boots a real host, and it
checks behaviour rather than loading: that `sleep-guard` holds an assertion while
a job runs and releases it afterwards, and that `mobile-bridge` delivers a
notification with a usable body, urgent priority, a session deep link and the
configured token.
