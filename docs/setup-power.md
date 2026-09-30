# Power: sleeping when idle, waking on demand

The behaviour this project aims for:

| State | Behaviour |
|---|---|
| Idle, nothing running | **Sleep** |
| Work is running | **Stay awake** |
| Scheduled task pending | **Self-wake on time** |
| You want it | **Wake from your phone** |

That first row is deliberate. Permanently preventing sleep is a different (and
worse) product: a fan spinning in an empty room, power you did not ask for, and
a machine that is awake for no reason.

## 1. Let it sleep

Leave the idle sleep timer alone. Do **not** set `sleep 0`. The
[`sleep-guard`](../plugins/sleep-guard/) plugin holds an assertion only while
work is running, so no system-level change is needed.

```sh
pmset -g custom | grep -E "sleep|womp"    # sleep should stay 1
```

> If you already run an always-on keep-awake agent (some tools install a
> permanent `caffeinate`), it will fight this. Check with
> `pmset -g assertions` — a `PreventUserIdleSystemSleep` assertion held for days
> by something you forgot about is a common finding.

## 2. Enable Wake-on-LAN

```sh
sudo pmset -c womp 1              # wake for network access, on AC power
sudo pmset -a tcpkeepalive 1
sudo pmset -a autorestart 1       # come back after a power failure
sudo pmset -c displaysleep 10     # the display may still sleep
```

Confirm the hardware supports it at all:

```sh
system_profiler SPAirPortDataType | grep -i "wake on"
# → "Wake On Wireless: Supported"
```

## 3. Fix the MAC address (do not skip this)

This is the failure mode that wastes an afternoon. Modern Wi-Fi randomises the
MAC per network, so the address the hardware reports is frequently **not** the
one the interface uses — and magic packets must be addressed to the real one.

```sh
networksetup -getMACAddress Wi-Fi    # hardware MAC
ifconfig en0 | grep ether            # MAC actually in use
```

If they differ, the interface is using a private address. Turn off
**Private Wi-Fi Address** for that network (Settings → Wi-Fi → network → Details)
or set it to Fixed, reconnect, and record the value from `ifconfig` — that is the
one that will stay put.

## 4. Waking it

### Self-wake (no extra hardware)

macOS can program the RTC to wake the machine at a specific time. This is what
makes scheduled tasks work: a session's timers are in-process `setTimeout`s and
**do not fire while the machine is asleep**.

```sh
sudo pmset schedule wake "10/01/26 09:00:00"
pmset -g sched                    # list pending events
sudo pmset schedule cancelall     # clear them
```

You can see the facility is real by looking at what the OS already schedules for
itself — `pmset -g sched` typically shows a few Apple-owned entries.

### Remote wake (needs something on the LAN)

Magic packets are broadcast and **do not cross routers**, so waking from outside
requires an awake device on the same subnet. See
[`wol-bridge`](../wol-bridge/).

Check your router first: many have a built-in Wake-on-LAN feature, and some can
be triggered from a vendor app. If yours can, you may not need anything else.

| Router / device | Can send magic packet? | Notes |
|---|---|---|
| _add yours_ | | PRs welcome — this table is meant to be filled in by users |

### From off

Apple Silicon Macs **cannot be woken from a full shutdown** — there is no
Wake-on-LAN from S5 and no boot-on-power-restore. Everything above assumes the
machine is asleep, not off. Size your expectations accordingly.

## 5. Verify

```sh
# hold an assertion, then check it appears
# (sleep-guard does this automatically when a turn starts)
pmset -g assertions | grep -i caffeinate

# let the machine go idle, then confirm it can actually sleep
pmset -g assertions        # no plugin-owned assertion should remain
```

Then the real test: let it sleep, send a magic packet, and time how long until
the host answers.
