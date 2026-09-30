#!/usr/bin/env python3
"""Wake-on-LAN: build a magic packet and get it onto the wire.

Why this is a separate file from the HTTP front end: the packet layout is the
part that fails *silently*. A malformed magic packet raises nothing, logs
nothing, and is not rejected by anything -- the target simply never wakes, and
you go looking in the wrong place for an hour. So the builder lives here, on
its own, where a test can check it byte for byte.

The constraint this module cannot code around: a magic packet is a *broadcast*,
and broadcasts do not cross routers. Whatever runs this code must share a
subnet with the machine it is waking. That is the entire reason a bridge
process like this has to exist on your LAN.

CLI exit codes: ``0`` if at least one target accepted the datagram, ``1`` if
every target failed, ``2`` if the arguments themselves were unusable.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import logging
import re
import socket
import sys
from dataclasses import dataclass
from typing import Sequence

LOG = logging.getLogger("wol")

__all__ = [
    "BROADCAST_MAC",
    "DEFAULT_PORT",
    "MAGIC_PACKET_SIZE",
    "InvalidMac",
    "SendError",
    "SendResult",
    "WakeReport",
    "WolError",
    "build_magic_packet",
    "build_parser",
    "format_mac",
    "main",
    "parse_mac",
    "send_magic_packet",
    "validate_broadcast",
    "wake",
]

# Port 9 is the "discard" port and what essentially every WoL tool defaults to.
# A minority of NICs/BIOSes only listen on 7 (echo) or 0, so --port is not
# decoration: it is the second thing to try when nothing wakes.
DEFAULT_PORT = 9

# 6 x 0xFF followed by the target MAC repeated 16 times = 6 + 96 = 102 bytes.
MAGIC_PACKET_SIZE = 102

# The 16 repetitions are not arbitrary and not negotiable: the NIC firmware
# matches this exact pattern while the rest of the machine is powered down.
# Sending fewer/fewer repeats does not make a "compact" packet, it makes a
# packet no NIC recognises. There is no length field for the hardware to read.
MAC_REPEATS = 16

BROADCAST_MAC = "ff:ff:ff:ff:ff:ff"

_HEX_PLAIN = re.compile(r"\A[0-9A-Fa-f]{12}\Z")
_HEX_PAIRS = re.compile(r"\A[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\Z")
_SEPARATOR = re.compile(r"[:-]")

# Refusing a handful of targets that can only ever be a mistake. Each of these
# would otherwise "succeed" (the datagram leaves the machine) while being
# incapable of waking the intended host -- the worst possible outcome, since
# the caller sees exit code 0 and concludes WoL is broken.
_MAX_REPEAT = 100


class WolError(Exception):
    """Base class for every error this module raises deliberately."""


class InvalidMac(WolError, ValueError):
    """A MAC address that cannot be turned into 6 bytes.

    Also a :class:`ValueError`, so callers that treat bad input as a value
    problem can keep doing that.
    """


class SendError(WolError):
    """No target accepted the datagram."""


@dataclass(frozen=True, slots=True)
class SendResult:
    """What happened when we tried to send to one broadcast address."""

    broadcast: str
    port: int
    ok: bool
    datagrams: int = 0
    error: str | None = None

    @property
    def bytes_sent(self) -> int:
        return self.datagrams * MAGIC_PACKET_SIZE

    def as_dict(self) -> dict[str, object]:
        return {
            "broadcast": self.broadcast,
            "port": self.port,
            "ok": self.ok,
            "datagrams": self.datagrams,
            "bytes": self.bytes_sent,
            "error": self.error,
        }


@dataclass(frozen=True, slots=True)
class WakeReport:
    """Outcome of one wake attempt across every configured target.

    Partial success is success: a machine may be reachable over two subnets and
    only one of them may currently be routable, so :attr:`ok` is true if *any*
    target accepted the packet.
    """

    mac: str
    port: int
    repeat: int
    results: tuple[SendResult, ...]

    @property
    def ok(self) -> bool:
        return any(result.ok for result in self.results)

    @property
    def datagrams(self) -> int:
        return sum(result.datagrams for result in self.results)

    @property
    def bytes_sent(self) -> int:
        return self.datagrams * MAGIC_PACKET_SIZE

    @property
    def failures(self) -> tuple[SendResult, ...]:
        return tuple(result for result in self.results if not result.ok)

    def as_dict(self) -> dict[str, object]:
        """Return the JSON shape shared by ``wol.py --json`` and ``POST /wake``."""
        payload: dict[str, object] = {
            "ok": self.ok,
            "mac": self.mac,
            "port": self.port,
            "repeat": self.repeat,
            "datagrams": self.datagrams,
            "bytes": self.bytes_sent,
            "targets": [result.as_dict() for result in self.results],
        }
        if not self.ok:
            payload["error"] = self.summary()
        return payload

    def summary(self) -> str:
        """One line explaining the result, safe to show a user or log."""
        if self.ok:
            reached = ", ".join(
                result.broadcast for result in self.results if result.ok
            )
            summary = (
                f"sent {self.datagrams} magic packet(s) for {self.mac} to {reached}"
            )
            if self.failures:
                missed = ", ".join(
                    f"{result.broadcast} ({result.error})"
                    for result in self.failures
                )
                summary += f"; unreachable: {missed}"
            return summary
        reasons = "; ".join(
            f"{result.broadcast}: {result.error}" for result in self.failures
        )
        return f"no magic packet was sent for {self.mac} ({reasons})"


def parse_mac(mac: str) -> bytes:
    """Parse a MAC address into 6 bytes.

    Accepts the three formats people actually paste: ``aa:bb:cc:dd:ee:ff``,
    ``aa-bb-cc-dd-ee-ff`` and ``aabbccddeeff``, in either case, with
    surrounding whitespace. Separators may not be mixed within one address.
    """
    if not isinstance(mac, str):
        raise InvalidMac(f"MAC address must be a string, got {type(mac).__name__}")

    candidate = mac.strip()
    if _HEX_PLAIN.match(candidate):
        octets = [candidate[i : i + 2] for i in range(0, 12, 2)]
    elif _HEX_PAIRS.match(candidate):
        if ":" in candidate and "-" in candidate:
            raise InvalidMac(
                f"{mac!r} mixes ':' and '-' separators; pick one"
            )
        octets = _SEPARATOR.split(candidate)
    else:
        raise InvalidMac(
            f"{mac!r} is not a MAC address; expected 12 hex digits as "
            "'aa:bb:cc:dd:ee:ff', 'aa-bb-cc-dd-ee-ff' or 'aabbccddeeff'"
        )

    raw = bytes(int(octet, 16) for octet in octets)
    if raw == b"\xff" * 6:
        # A unicast MAC is required. Sending a magic packet addressed to the
        # broadcast MAC wakes every WoL-capable machine on the subnet, which
        # has never been what the caller meant.
        raise InvalidMac(
            f"{mac!r} is the broadcast MAC; a magic packet needs the unicast "
            "MAC of the machine you want to wake"
        )
    return raw


def format_mac(raw: bytes) -> str:
    """Render 6 MAC bytes as the canonical lowercase ``aa:bb:...`` form."""
    if len(raw) != 6:
        raise InvalidMac(f"a MAC address is 6 bytes, got {len(raw)}")
    return ":".join(f"{octet:02x}" for octet in raw)


def build_magic_packet(mac: str | bytes) -> bytes:
    """Build the 102-byte magic packet for ``mac``.

    Layout (this is the whole format, there is no header or checksum):
    ``FF FF FF FF FF FF`` followed by the target MAC repeated 16 times.
    """
    mac_bytes = parse_mac(mac) if isinstance(mac, str) else mac
    if len(mac_bytes) != 6:
        raise InvalidMac(f"a MAC address is 6 bytes, got {len(mac_bytes)}")
    return b"\xff" * 6 + mac_bytes * MAC_REPEATS


def validate_broadcast(broadcast: str) -> str:
    """Return ``broadcast`` unchanged, or explain why it can never work."""
    if not isinstance(broadcast, str) or not broadcast.strip():
        raise WolError("broadcast address must be a non-empty string")
    candidate = broadcast.strip()
    try:
        address = ipaddress.IPv4Address(candidate)
    except ipaddress.AddressValueError as exc:
        # Only literal IPv4 addresses are accepted here. A hostname would have
        # to resolve to a broadcast address to be useful, which is vanishingly
        # rare, and a DNS lookup at wake time is a bad trade for that rarity.
        raise WolError(
            f"{broadcast!r} is not an IPv4 address; expected a literal "
            "broadcast address such as 192.168.10.255 or 255.255.255.255"
        ) from exc
    if address.is_unspecified:
        raise WolError(
            "0.0.0.0 is not a broadcast address; use 255.255.255.255 or the "
            "broadcast address of the target's subnet (e.g. 192.168.10.255)"
        )
    if address.is_multicast:
        raise WolError(
            f"{candidate} is a multicast group; Wake-on-LAN needs a broadcast "
            "address"
        )
    return candidate


def _validate_port(port: int) -> int:
    # Validate here rather than letting sendto() raise OverflowError/TypeError
    # per target, so a typo is one clear message instead of one per target.
    if not isinstance(port, int) or isinstance(port, bool):
        raise WolError(f"port must be an integer, got {type(port).__name__}")
    if not 0 < port < 65536:
        raise WolError(f"port {port} is out of range (1-65535)")
    return port


def _validate_repeat(repeat: int) -> int:
    if not isinstance(repeat, int) or isinstance(repeat, bool):
        raise WolError(f"repeat must be an integer, got {type(repeat).__name__}")
    if not 1 <= repeat <= _MAX_REPEAT:
        raise WolError(f"repeat must be between 1 and {_MAX_REPEAT}, got {repeat}")
    return repeat


def _new_socket() -> socket.socket:
    """Open the UDP socket used for broadcast.

    ``SO_BROADCAST`` is not optional: without it the kernel refuses to send a
    datagram to a broadcast destination at all and ``sendto`` fails with
    ``EACCES`` ("Permission denied"). It grants no privilege -- it is the
    socket-level way of saying "yes, I really do mean to shout", which the
    kernel requires because a broadcast wakes every host on the segment.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    return sock


def _send_to_target(
    packet: bytes, broadcast: str, port: int, repeat: int
) -> SendResult:
    """Send ``packet`` to one target, converting any failure into a result.

    One socket per target, so a target that cannot be reached (no route,
    unroutable subnet) does not affect the others, and a fresh socket cannot
    carry stale state between attempts.
    """
    try:
        validated = validate_broadcast(broadcast)
    except WolError as exc:
        return SendResult(broadcast=broadcast, port=port, ok=False, error=str(exc))

    sock = _new_socket()
    sent = 0
    try:
        for _ in range(repeat):
            # UDP is fire-and-forget: sendto() returning means the kernel
            # accepted the datagram, not that the target NIC saw it. That is
            # why --repeat exists and why "success" here is a weak claim.
            sock.sendto(packet, (validated, port))
            sent += 1
    except OSError as exc:
        return SendResult(
            broadcast=validated,
            port=port,
            ok=False,
            datagrams=sent,
            error=f"{exc.strerror or exc} ({exc.__class__.__name__})",
        )
    finally:
        sock.close()

    LOG.debug("sent %d magic packet(s) to %s:%d", sent, validated, port)
    return SendResult(broadcast=validated, port=port, ok=True, datagrams=sent)


def wake(
    mac: str,
    broadcasts: str | Sequence[str],
    port: int = DEFAULT_PORT,
    repeat: int = 1,
) -> WakeReport:
    """Send a magic packet for ``mac`` to every address in ``broadcasts``.

    Never raises because one target failed -- that is the point of accepting a
    list. A machine may be on two subnets (wired plus a VPN-style overlay), and
    the bridge usually cannot tell which one is currently routable, so it
    shouts on all of them and reports per-target detail.

    Raises :class:`InvalidMac` for a bad MAC and :class:`WolError` for bad
    configuration (unknown port, empty target list, ...).
    """
    mac_bytes = parse_mac(mac)
    targets = [broadcasts] if isinstance(broadcasts, str) else list(broadcasts)
    if not targets:
        raise WolError("no broadcast address given")
    port = _validate_port(port)
    repeat = _validate_repeat(repeat)

    packet = build_magic_packet(mac_bytes)
    canonical = format_mac(mac_bytes)

    results = tuple(
        _send_to_target(packet, target, port, repeat) for target in targets
    )
    report = WakeReport(mac=canonical, port=port, repeat=repeat, results=results)

    for failure in report.failures:
        LOG.warning("wake target %s failed: %s", failure.broadcast, failure.error)
    return report


def send_magic_packet(
    mac: str, broadcast: str, port: int = DEFAULT_PORT
) -> None:
    """Send one magic packet to one broadcast address, or raise.

    Thin wrapper over :func:`wake` for callers that have exactly one target and
    would rather get an exception than inspect a report.
    """
    report = wake(mac, broadcast, port=port, repeat=1)
    if not report.ok:
        failure = report.results[0]
        raise SendError(
            f"could not send a magic packet for {report.mac} to "
            f"{failure.broadcast}:{port}: {failure.error}"
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="wol.py",
        description=(
            "Send a Wake-on-LAN magic packet. Works only from a machine that "
            "shares a subnet with the target -- broadcasts do not cross routers."
        ),
        epilog=(
            "exit codes: 0 = at least one target accepted the packet, "
            "1 = every target failed, 2 = bad arguments"
        ),
    )
    parser.add_argument(
        "--mac",
        required=True,
        help="target MAC: aa:bb:cc:dd:ee:ff, aa-bb-cc-dd-ee-ff or aabbccddeeff",
    )
    parser.add_argument(
        "--broadcast",
        required=True,
        action="append",
        metavar="ADDR",
        help=(
            "broadcast address to send to; repeat the flag for several subnets "
            "(e.g. --broadcast 192.168.10.255 --broadcast 100.64.0.255)"
        ),
    )
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"UDP port for the magic packet (default {DEFAULT_PORT}; try 7 if "
        "nothing wakes)",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=3,
        help="datagrams per target (default 3; UDP drops are real and "
        "repetition is cheap)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="print the machine-readable result on stdout (same shape as the "
        "HTTP endpoint returns)",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="log every send on stderr",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """CLI entry point. Returns the process exit code."""
    args = build_parser().parse_args(argv)

    # Logging goes to stderr only, so --json keeps stdout parseable.
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )

    try:
        # Validate the targets before sending anything, instead of letting
        # wake() record a typo as a per-target send failure. That is what keeps
        # the exit codes meaningful: an unusable argument is 2, a datagram the
        # kernel refused is 1.
        targets = [validate_broadcast(item) for item in args.broadcast]
        report = wake(args.mac, targets, port=args.port, repeat=args.repeat)
    except (WolError, ValueError) as exc:
        if args.json:
            json.dump(
                {"ok": False, "error": str(exc), "mac": args.mac}, sys.stdout, indent=2
            )
            sys.stdout.write("\n")
        else:
            print(f"error: {exc}", file=sys.stderr)
        return 2

    if args.json:
        json.dump(report.as_dict(), sys.stdout, indent=2)
        sys.stdout.write("\n")
    elif report.ok:
        for result in report.results:
            state = "sent" if result.ok else "FAILED"
            detail = (
                f"{result.datagrams} datagram(s), {result.bytes_sent} bytes"
                if result.ok
                else result.error
            )
            print(f"{state} {result.broadcast}:{result.port} - {detail}")
    else:
        for failure in report.failures:
            print(f"error: {failure.broadcast}:{failure.port}: {failure.error}", file=sys.stderr)

    return 0 if report.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
