# wol-bridge

A tiny always-on service that sends Wake-on-LAN magic packets on your behalf,
for when the machine you want to wake is asleep and therefore cannot help.

## Why this has to be a separate machine

Wake-on-LAN packets are **broadcast**. They do not cross routers. So a phone on
mobile data cannot wake a machine at home no matter how the packet is
constructed — something already awake on the same subnet has to send it.

Commercial remote-desktop tools hit the same wall. One shipping product tells
its users, verbatim: *"no auxiliary device is online on the LAN, so the wake
command cannot be forwarded."* They did not solve it either. It is physics, not
software.

So the topology is:

```
phone ──tunnel──► wol-bridge ──broadcast──► sleeping machine
       (anywhere)   (your LAN)               (same subnet)
```

Run this on a Raspberry Pi, a NAS, an always-on mini PC, or anything else that
stays awake. If you would rather run nothing extra, check your router first —
many have a built-in Wake-on-LAN feature, and some can be triggered from a
vendor app.

## Before you start: the MAC address trap

Modern Wi-Fi uses a **randomised MAC address** per network. The MAC reported by
the hardware is often *not* the one the interface is actually using, and WoL
packets must be addressed to the real one.

```sh
networksetup -getMACAddress Wi-Fi   # e.g. a4:cf:99:5f:3e:af  ← hardware
ifconfig en0 | grep ether           # e.g. 7e:76:5e:29:e4:e1  ← actually in use
```

If these differ, the interface is using a private/randomised address. Turn off
"Private Wi-Fi Address" for that network (or set it to Fixed) before configuring
anything — otherwise you get the single most frustrating failure mode in this
whole project: every setting looks correct and nothing ever wakes.

## Usage

Stdlib only, no dependencies — it is meant to run somewhere you would rather not
install packages.

```sh
python3 wol.py --mac 7e:76:5e:29:e4:e1 --broadcast 192.168.10.255
```

## Status

🚧 Not implemented yet. Tracking issue: TBD.

The intended shape is a tiny HTTP endpoint (`POST /wake`), optionally guarded by
a shared secret, that `mobile-bridge` calls over the tunnel.
