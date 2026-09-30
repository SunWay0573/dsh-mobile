# wol-bridge

A tiny always-on service that sends Wake-on-LAN magic packets on your behalf,
for when the machine you want to wake is asleep and therefore cannot help.

Two Python files, Python 3.12+, **standard library only**. No pip, no venv, no
requirements.txt — it is meant to run somewhere you would rather not install
packages: a Raspberry Pi, a NAS, a router with a shell.

| File | What it is |
|---|---|
| `wol.py` | packet builder + sender, importable and runnable as a CLI |
| `server.py` | the two HTTP routes the tunnel exposes |
| `config.example.toml` | deployment config to copy to a gitignored local name |
| `test_wol.py` | `unittest` suite, 73 tests |

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

## Run it

```sh
cp config.example.toml wol-bridge.local.toml   # gitignored via `wol-*.local.*`
$EDITOR wol-bridge.local.toml                  # at minimum: mac, broadcast
export WOL_BRIDGE_SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"

python3 server.py --config wol-bridge.local.toml
```

You should see the effective configuration, and the service refuses to start on
a MAC or broadcast address that could never work — a typo here is the thing that
otherwise looks fine until the night you need it.

Send a wake:

```sh
curl -s -X POST http://127.0.0.1:8787/wake -H "Authorization: Bearer $WOL_BRIDGE_SECRET"
```

The phone never talks to this directly. The intended deployment is the same
tunnel the DSH host already uses, so the default bind is `127.0.0.1` and there
is nothing to firewall. `--listen 0.0.0.0` is possible and logs a warning; do it
only if something else is filtering (see [SECURITY.md](../SECURITY.md)).

### As a systemd service

```ini
[Unit]
Description=wol-bridge (Wake-on-LAN sender for dsh-mobile)
After=network-online.target
Wants=network-online.target

[Service]
Type=exec
WorkingDirectory=/home/pi/wol-bridge
Environment=WOL_BRIDGE_SECRET=<paste a long random string>
ExecStart=/usr/bin/python3 /home/pi/wol-bridge/server.py --config wol-bridge.local.toml
Restart=on-failure
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=read-only

[Install]
WantedBy=multi-user.target
```

## HTTP API

| Route | Auth | Purpose |
|---|---|---|
| `GET /health` | never | Liveness. Returns `{"ok": true, "service": "wol-bridge", "authenticated": bool, "targets": n, "version": ...}`. Deliberately does **not** echo the MAC or the broadcast addresses: a health check should not hand out your LAN topology. |
| `POST /wake` | if a secret is set | Sends the packet. Body may be empty, or `{"mac": "...", "broadcast": ["..."]}` to override the configured target for one call. |

Status codes: `200` at least one target accepted the datagram, `400` unusable
request (bad MAC, bad address, bad JSON), `401` missing/wrong bearer token,
`404` no such route, `405` wrong method, `413` body over 64 KiB, `502` every
target failed at the socket level (nothing was sent), `500` unexpected.

The `200` body is the same shape as `wol.py --json` below, so one parser covers
both:

```json
{
  "ok": true,
  "mac": "7e:76:5e:29:e4:e1",
  "port": 9,
  "repeat": 3,
  "datagrams": 3,
  "bytes": 306,
  "targets": [
    {"broadcast": "192.168.10.255", "port": 9, "ok": true, "datagrams": 3, "bytes": 306, "error": null}
  ]
}
```

Auth, when enabled: `Authorization: Bearer <secret>`. The comparison is
constant-time (`hmac.compare_digest`), and the secret is never logged or echoed
back — including in access logs, where anything matching the secret is redacted.

## Command line

```sh
python3 wol.py --mac 7e:76:5e:29:e4:e1 --broadcast 192.168.10.255 \
               --broadcast 100.64.0.255 --port 9 --repeat 3 --json
```

Accepts a MAC as `aa:bb:cc:dd:ee:ff`, `aa-bb-cc-dd-ee-ff` or `aabbccddeeff`
(case-insensitive). Exit codes: `0` at least one target accepted the packet,
`1` every target failed, `2` unusable arguments. With `--json`, failures also
print JSON on stdout instead of prose on stderr.

`wol.py` is importable too:

```python
import wol

wol.send_magic_packet("aa:bb:cc:dd:ee:ff", "192.168.10.255")   # raises on failure
report = wol.wake("aa:bb:cc:dd:ee:ff", ["192.168.10.255", "100.64.0.255"], repeat=3)
print(report.ok, report.datagrams, [t.error for t in report.failures])
```

## Configuration

Flag > environment variable > TOML file > built-in default. There is no implicit
config discovery; a file is read only when `--config` / `WOL_BRIDGE_CONFIG`
names one.

| Flag | Env | TOML key | Default | Notes |
|---|---|---|---|---|
| `--mac` | `WOL_BRIDGE_MAC` | `mac` | — | required |
| `--broadcast` | `WOL_BRIDGE_BROADCAST` | `broadcast` | — | required; repeat the flag, or comma/space-separate the env var, or use a TOML list |
| `--port` | `WOL_BRIDGE_PORT` | `port` | `9` | UDP port of the *magic packet*; try `7` if nothing wakes |
| `--repeat` | `WOL_BRIDGE_REPEAT` | `repeat` | `3` | datagrams per target |
| `--listen` | `WOL_BRIDGE_LISTEN` | `listen` | `127.0.0.1` | bind address of the HTTP endpoint |
| `--http-port` | `WOL_BRIDGE_HTTP_PORT` | `http_port` | `8787` | port of the HTTP endpoint |
| `--secret` | `WOL_BRIDGE_SECRET` | `secret` | unset | unset means open, with a loud warning at startup |
| `--config` | `WOL_BRIDGE_CONFIG` | — | unset | TOML file to read |

## Tests

```sh
python3 -m unittest discover -s . -p 'test_*.py'
```

They use real UDP sockets wherever a real socket can tell the difference:
packets are received and compared byte for byte, and a partially unreachable
target list is exercised for real. Two things are deliberately not tested — see
the notes at the end of this file.

## What this does not do

Honest list, because a wake button that silently does nothing is worse than no
wake button.

- **It cannot confirm the machine woke.** Magic packets are unacknowledged UDP.
  `ok: true` means *the kernel accepted the datagram* — not that the NIC saw it,
  and certainly not that the machine is up. Confirm by polling the host
  afterwards; the phone app should show "wake sent", never "awake".
- **It cannot wake a machine that is switched off.** Apple Silicon Macs have no
  Wake-on-LAN from S5 and no boot-on-power-restore, so this works from sleep,
  not from shutdown. See [`docs/setup-power.md`](../docs/setup-power.md).
- **Broadcast does not cross routers**, including a Tailscale-style overlay: a
  tailnet is a point-to-point L3 mesh that does not forward L2 broadcast, so a
  "subnet broadcast" address there will usually be accepted by the kernel and
  then go nowhere. Passing several `--broadcast` addresses is for a bridge that
  really is attached to (or routed into) more than one subnet.
- **IPv4 only.** WoL is an IPv4 broadcast; an IPv6 target address is refused at
  startup rather than silently doing nothing.
- **No TLS.** Put it behind the tunnel, or terminate TLS in front of it, rather
  than exposing `0.0.0.0` on a hostile network.
- **No lockout or rate limiting.** A wrong secret costs nothing but a 401. The
  secret is the only thing standing between a caller and "wake that computer".
- **Only the target MAC is checked, not the sender.** Any host that can reach
  the port may send a magic packet; that is the whole point of a wake button,
  and also the whole risk.

## Verification notes

- Developed and tested on macOS with Python 3.14 (the repo's CI compiles the
  files under Python 3.12). `SO_BROADCAST` is enforced by Linux — without it
  `sendto` to a broadcast address fails with `EACCES` — but macOS accepts
  broadcast sends regardless, so the option is set unconditionally and a fake
  socket asserts that it is set *before* the first `sendto`.
- No test sends a packet to a physical network's broadcast address; the
  broadcast-path test uses `127.255.255.255`, the broadcast of the loopback /8,
  which cannot leave the machine.
- Nobody has yet woken a real sleeping machine with this. The packet layout is
  verified byte for byte, which is the part that fails silently; whether your
  particular NIC, switch and subnet deliver it is a question only your LAN can
  answer.
