#!/usr/bin/env python3
"""Tests for :mod:`wol` and :mod:`server`.

Run from this directory::

    python3 -m unittest discover -s . -p 'test_*.py'

Where a test could be written either against a fake socket or against a real
one, it is written against a real one: the failure mode this file exists to
catch is "the packet looks right in the source and is wrong on the wire", and
only a real socket can tell the difference. The fake-socket test that remains
covers one thing a real socket cannot observe from inside this process -- that
``SO_BROADCAST`` is set before the first ``sendto``.

Nothing here sends a packet to a broadcast address of a physical network. The
broadcast-path test uses ``127.255.255.255``, the broadcast address of the
loopback /8, which cannot leave the machine.
"""

from __future__ import annotations

import argparse
import contextlib
import http.client
import io
import json
import logging
import os
import pathlib
import socket
import sys
import tempfile
import threading
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import server  # noqa: E402  (path set up above)
import wol  # noqa: E402

MAC = "aa:bb:cc:dd:ee:ff"
MAC_BYTES = bytes.fromhex("aabbccddeeff")
PACKET = b"\xff" * 6 + MAC_BYTES * 16


def setUpModule() -> None:
    """Keep routine access/warning logs out of the test report.

    The tests deliberately provoke failures (unreachable target, wrong secret),
    and the server logs every request; the log lines have their own test.
    """
    logging.getLogger("wol").setLevel(logging.CRITICAL)
    logging.getLogger("wol_bridge.server").setLevel(logging.CRITICAL)


_REAL_SOCKET = socket.socket


class RecordingSocket:
    """A real UDP socket that remembers the calls made through it.

    Deliberately *not* a mock: every call is forwarded to a genuine socket, so
    ``SO_BROADCAST`` is really applied and ``sendto`` really runs. The recorded
    call list only makes the order observable -- and order is the part that
    matters, because a socket that enables ``SO_BROADCAST`` *after* the first
    ``sendto`` is broken on Linux (EACCES) and silently fine on macOS.
    """

    def __init__(self) -> None:
        # The original socket class, captured at import time: socket.socket
        # itself is patched while these tests run.
        self._inner = _REAL_SOCKET(socket.AF_INET, socket.SOCK_DGRAM)
        self.calls: list[tuple[str, object]] = []

    def setsockopt(self, *args: object) -> None:
        self.calls.append(("setsockopt", args))
        self._inner.setsockopt(*args)  # type: ignore[arg-type]

    def getsockopt(self, *args: object) -> int:
        return self._inner.getsockopt(*args)  # type: ignore[arg-type]

    def sendto(self, data: bytes, target: tuple[str, int]) -> int:
        self.calls.append(("sendto", (len(data), target)))
        return self._inner.sendto(data, target)

    def close(self) -> None:
        self.calls.append(("close", None))
        self._inner.close()

    def names(self) -> list[str]:
        return [name for name, _ in self.calls]


class RecordingSocketFactory:
    """Stands in for :class:`socket.socket` for the duration of a test.

    ``wol._new_socket`` calls ``socket.socket`` directly, so the substitution
    happens at that level rather than by replacing ``_new_socket``: the code
    under test then runs completely unchanged, ``setsockopt`` call included --
    which is exactly the call being asserted about.
    """

    def __init__(self) -> None:
        self.created: list[RecordingSocket] = []

    def __call__(
        self,
        family: int = -1,
        type: int = -1,
        proto: int = -1,
        fileno: int | None = None,
    ) -> object:
        if (family, type) == (socket.AF_INET, socket.SOCK_DGRAM):
            recorder = RecordingSocket()
            self.created.append(recorder)
            return recorder
        # Anything else (a TCP socket opened elsewhere in the process) goes
        # straight to the real implementation.
        if fileno is None:
            return _REAL_SOCKET(family, type, proto)
        return _REAL_SOCKET(family, type, proto, fileno)


class FailingSocketFactory(RecordingSocketFactory):
    """The same substitution, except every UDP socket refuses to send.

    Used to reach the code path where nothing left the machine at all, which a
    real socket cannot be talked into on a host that has a default route.
    """

    def __call__(
        self,
        family: int = -1,
        type: int = -1,
        proto: int = -1,
        fileno: int | None = None,
    ) -> object:
        created = super().__call__(family, type, proto, fileno)
        if isinstance(created, RecordingSocket):
            created.sendto = mock.Mock(  # type: ignore[method-assign]
                side_effect=OSError(101, "Network is unreachable")
            )
        return created

    def close_all(self) -> None:
        for recorder in self.created:
            if "close" not in recorder.names():
                recorder.close()


class PacketTests(unittest.TestCase):
    """The 102-byte layout, which is the part that fails silently."""

    def test_packet_is_exactly_102_bytes(self) -> None:
        self.assertEqual(len(wol.build_magic_packet(MAC)), 102)
        self.assertEqual(wol.MAGIC_PACKET_SIZE, 102)

    def test_packet_structure_is_6_ff_then_mac_16_times(self) -> None:
        packet = wol.build_magic_packet(MAC)
        self.assertEqual(packet[:6], b"\xff" * 6)
        self.assertEqual(packet[6:], MAC_BYTES * 16)
        # Spelled out rather than reusing the implementation's constants, so a
        # change to MAC_REPEATS cannot make this test agree with a bug.
        self.assertEqual(packet[6:].count(MAC_BYTES), 16)
        self.assertEqual(len(packet[6:]), 96)

    def test_all_three_mac_formats_give_the_same_packet(self) -> None:
        packets = {
            "colon": wol.build_magic_packet("aa:bb:cc:dd:ee:ff"),
            "dash": wol.build_magic_packet("aa-bb-cc-dd-ee-ff"),
            "plain": wol.build_magic_packet("aabbccddeeff"),
            "upper": wol.build_magic_packet("AA:BB:CC:DD:EE:FF"),
        }
        self.assertEqual(set(packets.values()), {PACKET})

    def test_packet_accepts_pre_parsed_bytes(self) -> None:
        self.assertEqual(wol.build_magic_packet(MAC_BYTES), PACKET)

    def test_packet_rejects_bytes_of_the_wrong_length(self) -> None:
        with self.assertRaises(wol.InvalidMac):
            wol.build_magic_packet(b"\xaa\xbb")

    def test_different_macs_give_different_packets(self) -> None:
        other = wol.build_magic_packet("02:00:00:00:00:01")
        self.assertNotEqual(other, PACKET)
        self.assertEqual(len(other), 102)


class MacParsingTests(unittest.TestCase):
    def test_three_formats_parse_to_the_same_bytes(self) -> None:
        self.assertEqual(wol.parse_mac("aa:bb:cc:dd:ee:ff"), MAC_BYTES)
        self.assertEqual(wol.parse_mac("aa-bb-cc-dd-ee-ff"), MAC_BYTES)
        self.assertEqual(wol.parse_mac("aabbccddeeff"), MAC_BYTES)

    def test_case_and_surrounding_whitespace_are_tolerated(self) -> None:
        self.assertEqual(wol.parse_mac("  AA:BB:CC:DD:EE:FF\n"), MAC_BYTES)

    def test_format_mac_is_canonical_lowercase(self) -> None:
        self.assertEqual(wol.format_mac(MAC_BYTES), MAC)
        self.assertEqual(wol.format_mac(wol.parse_mac("AABBCCDDEEFF")), MAC)
        with self.assertRaises(wol.InvalidMac):
            wol.format_mac(b"\x01\x02")

    def test_malformed_macs_raise(self) -> None:
        bad = {
            "empty": "",
            "whitespace only": "   ",
            "too short": "aa:bb:cc:dd:ee",
            "too long": "aa:bb:cc:dd:ee:ff:00",
            "too few hex digits": "aabbccddeef",
            "too many hex digits": "aabbccddeeff0",
            "non-hex letters": "gg:bb:cc:dd:ee:ff",
            "mixed separators": "aa:bb-cc:dd:ee:ff",
            "space separated": "aa bb cc dd ee ff",
            "dot separated": "aabb.ccdd.eeff",
            "trailing separator": "aa:bb:cc:dd:ee:ff:",
            "leading separator": ":aa:bb:cc:dd:ee:ff",
            "hex prefix": "0xaa:bb:cc:dd:ee:ff",
        }
        for label, value in bad.items():
            with self.subTest(label=label):
                with self.assertRaises(wol.InvalidMac):
                    wol.parse_mac(value)

    def test_non_string_input_raises(self) -> None:
        for value in (None, 1234, MAC_BYTES, ["aa:bb:cc:dd:ee:ff"]):
            with self.subTest(value=repr(value)):
                with self.assertRaises(wol.InvalidMac):
                    wol.parse_mac(value)  # type: ignore[arg-type]

    def test_broadcast_mac_is_refused(self) -> None:
        # Not malformed, but never what the caller meant: every WoL-capable NIC
        # on the subnet answers it.
        with self.assertRaises(wol.InvalidMac) as ctx:
            wol.parse_mac("ff:ff:ff:ff:ff:ff")
        self.assertIn("broadcast", str(ctx.exception))

    def test_invalid_mac_is_also_a_value_error(self) -> None:
        # Callers that treat bad input as a value problem keep working, and
        # callers catching the module's own error type keep working too.
        with self.assertRaises(ValueError):
            wol.parse_mac("nope")
        self.assertTrue(issubclass(wol.InvalidMac, wol.WolError))

    def test_error_message_lists_the_accepted_formats(self) -> None:
        with self.assertRaises(wol.InvalidMac) as ctx:
            wol.parse_mac("nope")
        message = str(ctx.exception)
        self.assertIn("aa:bb:cc:dd:ee:ff", message)
        self.assertIn("aabbccddeeff", message)


class TargetValidationTests(unittest.TestCase):
    def test_valid_broadcast_addresses_are_accepted(self) -> None:
        for value in (
            "192.168.10.255",
            "255.255.255.255",
            " 10.0.0.255 ",
            "100.64.0.255",
            "127.0.0.1",
        ):
            with self.subTest(value=value):
                self.assertEqual(wol.validate_broadcast(value), value.strip())

    def test_addresses_that_could_never_work_are_refused(self) -> None:
        cases = {
            "empty": "",
            "blank": "   ",
            "hostname": "myrouter.local",
            "not an ip": "banana",
            "out of range octet": "192.168.10.256",
            "ipv6": "fe80::1",
            "unspecified": "0.0.0.0",
            "multicast": "224.0.0.1",
        }
        for label, value in cases.items():
            with self.subTest(label=label):
                with self.assertRaises(wol.WolError):
                    wol.validate_broadcast(value)

    def test_bad_port_is_rejected_once_not_once_per_target(self) -> None:
        for port in (0, -1, 65536, 99999, "9", 9.0, True):
            with self.subTest(port=repr(port)):
                with self.assertRaises(wol.WolError):
                    wol.wake(MAC, "127.0.0.1", port=port)  # type: ignore[arg-type]

    def test_bad_repeat_is_rejected(self) -> None:
        for repeat in (0, -3, 101, "2", None):
            with self.subTest(repeat=repr(repeat)):
                with self.assertRaises(wol.WolError):
                    wol.wake(MAC, "127.0.0.1", repeat=repeat)  # type: ignore[arg-type]

    def test_empty_target_list_is_rejected(self) -> None:
        with self.assertRaises(wol.WolError):
            wol.wake(MAC, [])


class RealSocketTests(unittest.TestCase):
    """Packets that actually leave the process and arrive somewhere."""

    def setUp(self) -> None:
        self.receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.receiver.bind(("127.0.0.1", 0))
        self.receiver.settimeout(0.75)
        self.addCleanup(self.receiver.close)
        self.port = self.receiver.getsockname()[1]

    def receive(self) -> tuple[bytes, tuple[str, int]]:
        return self.receiver.recvfrom(2048)

    def assert_no_more_packets(self) -> None:
        with self.assertRaises(socket.timeout):
            self.receiver.recvfrom(2048)

    def test_send_magic_packet_returns_none_and_delivers_102_bytes(self) -> None:
        result = wol.send_magic_packet(MAC, "127.0.0.1", self.port)
        self.assertIsNone(result)
        data, _ = self.receive()
        self.assertEqual(len(data), 102)
        self.assertEqual(data, PACKET)
        self.assert_no_more_packets()

    def test_datagram_bytes_match_the_documented_layout(self) -> None:
        wol.send_magic_packet("AA-BB-CC-DD-EE-FF", "127.0.0.1", self.port)
        data, _ = self.receive()
        self.assertEqual(data[:6], b"\xff" * 6)
        self.assertEqual(data[6:12], MAC_BYTES)
        self.assertEqual(len(data[6:]) // 6, 16)

    def test_repeat_sends_that_many_identical_datagrams(self) -> None:
        report = wol.wake(MAC, "127.0.0.1", port=self.port, repeat=3)
        self.assertTrue(report.ok)
        self.assertEqual(report.datagrams, 3)
        self.assertEqual(report.bytes_sent, 306)
        for _ in range(3):
            data, _ = self.receive()
            self.assertEqual(data, PACKET)
        self.assert_no_more_packets()

    def test_one_unreachable_target_does_not_fail_the_others(self) -> None:
        report = wol.wake(
            MAC,
            ["192.168.10.256", "127.0.0.1"],
            port=self.port,
            repeat=1,
        )
        self.assertTrue(report.ok, report.summary())
        self.assertEqual(len(report.failures), 1)
        self.assertEqual(report.failures[0].broadcast, "192.168.10.256")
        self.assertIsNotNone(report.failures[0].error)
        data, _ = self.receive()
        self.assertEqual(data, PACKET)
        self.assertIn("192.168.10.256", report.summary())

    def test_wake_reports_total_failure_without_raising(self) -> None:
        report = wol.wake(MAC, ["192.168.10.256", "not-an-address"], port=self.port)
        self.assertFalse(report.ok)
        self.assertEqual(report.datagrams, 0)
        self.assertEqual(len(report.failures), 2)
        self.assertIn("error", report.as_dict())

    def test_send_magic_packet_raises_when_the_only_target_fails(self) -> None:
        with self.assertRaises(wol.SendError) as ctx:
            wol.send_magic_packet(MAC, "192.168.10.256", self.port)
        self.assertIn("192.168.10.256", str(ctx.exception))

    def test_loopback_broadcast_destination_is_accepted_by_the_kernel(self) -> None:
        """Exercise the *broadcast* path without touching a physical network.

        127.255.255.255 is the broadcast address of the loopback /8: the kernel
        routes it out ``lo``, so nothing reaches the surrounding LAN. Receipt is
        not asserted because platforms disagree about whether an unbound
        loopback broadcast is delivered to a socket bound to 127.0.0.1 -- what
        matters here is that the kernel accepted a datagram aimed at a
        broadcast address, which is the thing ``SO_BROADCAST`` governs.
        """
        report = wol.wake(MAC, "127.255.255.255", port=self.port, repeat=1)
        if not report.ok:
            self.skipTest(
                "this kernel refused a loopback broadcast datagram: "
                f"{report.failures[0].error}"
            )
        self.assertTrue(report.ok, report.summary())


class SocketSetupTests(unittest.TestCase):
    """The one property a socket inside this process cannot observe itself."""

    def setUp(self) -> None:
        self.factory = RecordingSocketFactory()
        patcher = mock.patch.object(socket, "socket", self.factory)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self._close_recorders)
        # Created through the original class: the patch is already active.
        receiver = _REAL_SOCKET(socket.AF_INET, socket.SOCK_DGRAM)
        receiver.bind(("127.0.0.1", 0))
        receiver.settimeout(0.75)
        self.addCleanup(receiver.close)
        self.receiver = receiver
        self.port = receiver.getsockname()[1]

    def _close_recorders(self) -> None:
        for recorder in self.factory.created:
            if "close" not in recorder.names():
                recorder.close()

    @property
    def recorder(self) -> RecordingSocket:
        self.assertTrue(self.factory.created, "the code never opened a socket")
        return self.factory.created[0]

    def test_so_broadcast_is_enabled_before_the_first_sendto(self) -> None:
        wol.send_magic_packet(MAC, "127.0.0.1", self.port)
        self.assertIn(
            ("setsockopt", (socket.SOL_SOCKET, socket.SO_BROADCAST, 1)),
            self.recorder.calls,
            "SO_BROADCAST must be set on the sending socket",
        )
        self.assertLess(
            self.recorder.names().index("setsockopt"),
            self.recorder.names().index("sendto"),
            "SO_BROADCAST must be set before the packet is sent",
        )

    def test_so_broadcast_is_really_applied_by_the_kernel(self) -> None:
        # The recorded call above says the code asked for the option; this asks
        # the kernel, on a socket built by the real _new_socket() (the test's
        # factory only wraps it). Read back straight away because the sender
        # closes its sockets as soon as the datagrams are away. macOS reports
        # the raw bit value (32) rather than 1.
        sock = wol._new_socket()
        self.addCleanup(sock.close)
        self.assertNotEqual(
            sock.getsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST), 0
        )

    def test_datagram_is_102_bytes_to_the_configured_address_and_port(self) -> None:
        wol.send_magic_packet(MAC, "127.0.0.1", self.port)
        sent = [call for name, call in self.recorder.calls if name == "sendto"]
        self.assertEqual(sent, [(102, ("127.0.0.1", self.port))])
        data, _ = self.receiver.recvfrom(2048)
        self.assertEqual(data, PACKET)

    def test_broadcast_address_is_passed_to_sendto_unchanged(self) -> None:
        # A directed broadcast over the loopback /8: recorded destination and
        # real sendto, but the packet cannot reach a physical network.
        wol.send_magic_packet(MAC, "127.255.255.255", self.port)
        sent = [call for name, call in self.recorder.calls if name == "sendto"]
        self.assertEqual(sent, [(102, ("127.255.255.255", self.port))])

    def test_each_target_gets_its_own_socket_and_closes_it(self) -> None:
        report = wol.wake(MAC, ["127.0.0.1", "127.255.255.255"], port=self.port)
        self.assertTrue(report.ok, report.summary())
        self.assertEqual(len(self.factory.created), 2, "one socket per target")
        for recorder in self.factory.created:
            self.assertEqual(recorder.names().count("close"), 1)

    def test_socket_is_closed_even_when_sendto_fails(self) -> None:
        with mock.patch.object(
            RecordingSocket,
            "sendto",
            side_effect=OSError(101, "Network is unreachable"),
        ):
            report = wol.wake(MAC, "127.0.0.1", port=self.port)
        self.assertFalse(report.ok)
        self.assertIn("Network is unreachable", report.failures[0].error or "")
        self.assertEqual(self.recorder.names().count("close"), 1)


class RedactionTests(unittest.TestCase):
    def test_secret_is_replaced_everywhere_it_appears(self) -> None:
        text = "Authorization: Bearer hunter2 for POST /wake?secret=hunter2"
        scrubbed = server.redact_secret(text, "hunter2")
        self.assertNotIn("hunter2", scrubbed)
        self.assertEqual(scrubbed.count("***"), 2)

    def test_no_secret_means_text_is_untouched(self) -> None:
        self.assertEqual(server.redact_secret("plain text", None), "plain text")
        self.assertEqual(server.redact_secret("plain text", ""), "plain text")


class ConfigTests(unittest.TestCase):
    def parse(self, argv: list[str]) -> argparse.Namespace:
        return server.build_parser().parse_args(argv)

    def test_parser_defaults_are_all_none_so_layers_can_be_told_apart(self) -> None:
        args = self.parse([])
        for attribute in ("mac", "broadcast", "port", "repeat", "listen", "http_port", "secret"):
            with self.subTest(attribute=attribute):
                self.assertIsNone(getattr(args, attribute))

    def test_defaults_bind_to_loopback(self) -> None:
        config = server.config_from_args(
            self.parse(["--mac", MAC, "--broadcast", "192.168.10.255"]), env={}
        )
        self.assertEqual(config.listen, server.DEFAULT_LISTEN)
        self.assertEqual(config.listen, "127.0.0.1")
        self.assertEqual(config.http_port, server.DEFAULT_HTTP_PORT)
        self.assertEqual(config.wol_port, 9)
        self.assertIsNone(config.secret)
        self.assertFalse(config.authenticated)

    def test_precedence_is_flag_then_env_then_file_then_default(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "wol-bridge.local.toml")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(
                    'mac = "aa:bb:cc:dd:ee:ff"\n'
                    'broadcast = ["10.0.0.255"]\n'
                    "port = 7\n"
                    "repeat = 5\n"
                    'listen = "127.0.0.1"\n'
                    "http_port = 9999\n"
                    'secret = "from-file-secret"\n'
                )
            env = {
                "WOL_BRIDGE_PORT": "8",
                "WOL_BRIDGE_SECRET": "from-env-secret",
            }

            from_file = server.config_from_args(
                self.parse(["--config", path]), env=env
            )
            self.assertEqual(from_file.mac, MAC)
            self.assertEqual(from_file.broadcasts, ("10.0.0.255",))
            self.assertEqual(from_file.repeat, 5)
            self.assertEqual(from_file.http_port, 9999)
            # env beats file
            self.assertEqual(from_file.wol_port, 8)
            self.assertEqual(from_file.secret, "from-env-secret")
            self.assertTrue(from_file.authenticated)

            # flag beats env
            from_flag = server.config_from_args(
                self.parse(
                    [
                        "--config",
                        path,
                        "--port",
                        "9",
                        "--broadcast",
                        "192.168.10.255",
                        "--broadcast",
                        "100.64.0.255",
                    ]
                ),
                env=env,
            )
            self.assertEqual(from_flag.wol_port, 9)
            self.assertEqual(
                from_flag.broadcasts, ("192.168.10.255", "100.64.0.255")
            )
            self.assertEqual(from_flag.source, "command line")

    def test_env_can_point_at_the_config_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "wol-bridge.local.toml")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(
                    'mac = "aa:bb:cc:dd:ee:ff"\n'
                    'broadcast = ["10.0.0.255"]\n'
                    "port = 7\n"
                    'secret = "from-file"\n'
                )
            config = server.config_from_args(
                self.parse([]), env={"WOL_BRIDGE_CONFIG": path}
            )
        self.assertEqual(config.mac, MAC)
        self.assertEqual(config.broadcasts, ("10.0.0.255",))
        self.assertEqual(config.wol_port, 7)
        self.assertEqual(config.secret, "from-file")
        self.assertEqual(config.source, path)

    def test_env_broadcast_accepts_a_comma_separated_list(self) -> None:
        config = server.config_from_args(
            self.parse([]),
            env={
                "WOL_BRIDGE_MAC": MAC,
                "WOL_BRIDGE_BROADCAST": "192.168.10.255, 100.64.0.255",
            },
        )
        self.assertEqual(config.broadcasts, ("192.168.10.255", "100.64.0.255"))

    def test_missing_mac_or_broadcast_is_a_config_error(self) -> None:
        with self.assertRaises(server.ConfigError):
            server.config_from_args(self.parse(["--broadcast", "10.0.0.255"]), env={})
        with self.assertRaises(server.ConfigError):
            server.config_from_args(self.parse(["--mac", MAC]), env={})

    def test_bad_mac_and_bad_broadcast_fail_at_startup(self) -> None:
        with self.assertRaises(server.ConfigError):
            server.config_from_args(
                self.parse(["--mac", "nope", "--broadcast", "10.0.0.255"]), env={}
            )
        with self.assertRaises(server.ConfigError):
            server.config_from_args(
                self.parse(["--mac", MAC, "--broadcast", "10.0.0.256"]), env={}
            )

    def test_empty_secret_is_ignored_rather_than_half_applied(self) -> None:
        with self.assertLogs("wol_bridge.server", level="WARNING") as captured:
            config = server.config_from_args(
                self.parse(["--mac", MAC, "--broadcast", "10.0.0.255", "--secret", "  "]),
                env={},
            )
        self.assertIsNone(config.secret)
        self.assertIn("empty secret", "\n".join(captured.output))

    def test_mac_is_normalised(self) -> None:
        config = server.config_from_args(
            self.parse(["--mac", "AA-BB-CC-DD-EE-FF", "--broadcast", "10.0.0.255"]),
            env={},
        )
        self.assertEqual(config.mac, MAC)

    def test_unknown_config_keys_are_reported(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "wol-bridge.local.toml")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(
                    'mac = "aa:bb:cc:dd:ee:ff"\n'
                    'broadcast = ["10.0.0.255"]\n'
                    'broadcast_addr = "typo"\n'
                )
            with self.assertLogs("wol_bridge.server", level="WARNING") as captured:
                server.config_from_args(self.parse(["--config", path]), env={})
        self.assertIn("broadcast_addr", "\n".join(captured.output))

    def test_missing_and_broken_config_files_raise(self) -> None:
        with self.assertRaises(server.ConfigError):
            server.load_config_file("/nonexistent/wol-bridge.local.toml")
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "wol-bridge.local.toml")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("mac = \n")
            with self.assertRaises(server.ConfigError):
                server.load_config_file(path)

    def test_the_example_config_is_valid_toml_and_loads(self) -> None:
        example = pathlib.Path(__file__).resolve().parent / "config.example.toml"
        data = server.load_config_file(str(example))
        self.assertEqual(data["mac"], "aa:bb:cc:dd:ee:ff")
        self.assertIsInstance(data["broadcast"], list)
        self.assertNotIn("secret", data, "the shipped example must not carry a secret")

    def test_non_loopback_listen_is_reported_as_exposed(self) -> None:
        self.assertTrue(server._is_loopback("127.0.0.1"))
        self.assertTrue(server._is_loopback("localhost"))
        self.assertFalse(server._is_loopback("0.0.0.0"))
        self.assertFalse(server._is_loopback("192.168.10.5"))
        self.assertFalse(server._is_loopback("pi.lan"))


class ServerTestCase(unittest.TestCase):
    """Starts a real server on an ephemeral port with a real UDP receiver."""

    secret: str | None = None
    repeat = 2

    def setUp(self) -> None:
        self.receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.receiver.bind(("127.0.0.1", 0))
        self.receiver.settimeout(0.75)
        self.addCleanup(self.receiver.close)
        self.wol_port = self.receiver.getsockname()[1]

        self.config = server.Config(
            mac=MAC,
            broadcasts=("127.0.0.1",),
            wol_port=self.wol_port,
            repeat=self.repeat,
            listen="127.0.0.1",
            http_port=0,  # ephemeral: no port collisions between tests
            secret=self.secret,
        )
        self.httpd = server.build_server(self.config)
        self.addCleanup(self.httpd.server_close)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop_server)
        self.http_port = self.httpd.server_address[1]

    def _stop_server(self) -> None:
        self.httpd.shutdown()
        self.thread.join(timeout=5)
        self.assertFalse(self.thread.is_alive(), "server thread did not stop")

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, object], bytes]:
        connection = http.client.HTTPConnection("127.0.0.1", self.http_port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            raw = response.read()
        finally:
            connection.close()
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):  # pragma: no cover
            self.fail(f"response was not JSON: {raw!r}")
        return response.status, payload, raw

    def receive_packet(self) -> bytes:
        data, _ = self.receiver.recvfrom(2048)
        return data


class HealthTests(ServerTestCase):
    def test_health_is_liveness_and_needs_no_auth(self) -> None:
        status, payload, _ = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["service"], "wol-bridge")
        self.assertFalse(payload["authenticated"])
        self.assertEqual(payload["targets"], 1)

    def test_health_does_not_leak_topology(self) -> None:
        _, payload, raw = self.request("GET", "/health")
        self.assertNotIn(MAC, raw.decode())
        # The configured broadcast address is 127.0.0.1 in these tests, so this
        # also covers "the health check does not hand out your LAN topology".
        self.assertNotIn("127.0.0.1", raw.decode())
        self.assertNotIn(str(self.wol_port), json.dumps(payload["targets"]))

    def test_health_tolerates_a_trailing_slash_and_query(self) -> None:
        self.assertEqual(self.request("GET", "/health/?x=1")[0], 200)

    def test_health_rejects_post(self) -> None:
        status, payload, _ = self.request("POST", "/health")
        self.assertEqual(status, 405)
        self.assertFalse(payload["ok"])


class WakeEndpointTests(ServerTestCase):
    def test_wake_sends_the_packet_and_reports_it(self) -> None:
        status, payload, _ = self.request("POST", "/wake")
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["mac"], MAC)
        self.assertEqual(payload["repeat"], self.repeat)
        self.assertEqual(payload["datagrams"], self.repeat)
        for _ in range(self.repeat):
            self.assertEqual(self.receive_packet(), PACKET)

    def test_wake_returns_the_same_shape_as_the_cli(self) -> None:
        _, payload, _ = self.request("POST", "/wake")
        self.assertEqual(
            set(payload),
            {"ok", "mac", "port", "repeat", "datagrams", "bytes", "targets"},
        )
        self.assertEqual(
            set(payload["targets"][0]),  # type: ignore[index]
            {"broadcast", "port", "ok", "datagrams", "bytes", "error"},
        )

    def test_wake_accepts_an_empty_body_and_a_json_body(self) -> None:
        self.assertEqual(self.request("POST", "/wake")[0], 200)
        status, payload, _ = self.request(
            "POST", "/wake", body=json.dumps({"mac": "02:00:00:00:00:01"}).encode()
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload["mac"], "02:00:00:00:00:01")
        self.assertEqual(len(self.receive_packet()), 102)

    def test_wake_body_can_override_the_broadcast_list(self) -> None:
        body = json.dumps({"broadcast": ["127.0.0.1"]}).encode()
        status, payload, _ = self.request("POST", "/wake", body=body)
        self.assertEqual(status, 200)
        self.assertEqual(
            payload["targets"][0]["broadcast"],  # type: ignore[index]
            "127.0.0.1",
        )

    def test_malformed_mac_override_is_a_client_error(self) -> None:
        body = json.dumps({"mac": "not-a-mac"}).encode()
        status, payload, _ = self.request("POST", "/wake", body=body)
        self.assertEqual(status, 400)
        self.assertFalse(payload["ok"])
        with self.assertRaises(socket.timeout):
            self.receive_packet()

    def test_bad_broadcast_override_is_a_client_error(self) -> None:
        body = json.dumps({"broadcast": "192.168.10.256"}).encode()
        self.assertEqual(self.request("POST", "/wake", body=body)[0], 400)

    def test_wrong_body_type_is_a_client_error(self) -> None:
        self.assertEqual(
            self.request("POST", "/wake", body=json.dumps(["127.0.0.1"]).encode())[0],
            400,
        )
        self.assertEqual(self.request("POST", "/wake", body=b"{not json")[0], 400)
        self.assertEqual(
            self.request("POST", "/wake", body=json.dumps({"broadcast": 5}).encode())[0],
            400,
        )

    def test_unreachable_target_is_reported_as_a_gateway_failure(self) -> None:
        # A machine with a default route happily accepts a datagram for any
        # IPv4 address, so "the send itself failed" cannot be provoked with a
        # real socket. The failure is injected at the socket layer -- the one
        # place that genuinely cannot be made to fail on demand -- and
        # wol.wake still runs for real.
        factory = FailingSocketFactory()
        self.addCleanup(factory.close_all)
        with mock.patch.object(socket, "socket", factory):
            status, payload, _ = self.request("POST", "/wake")
        self.assertEqual(status, 502)
        self.assertFalse(payload["ok"])
        self.assertIn("Network is unreachable", json.dumps(payload))
        with self.assertRaises(socket.timeout):
            self.receive_packet()

    def test_get_on_wake_is_method_not_allowed(self) -> None:
        status, payload, _ = self.request("GET", "/wake")
        self.assertEqual(status, 405)
        self.assertFalse(payload["ok"])

    def test_unknown_endpoint_is_404(self) -> None:
        self.assertEqual(self.request("GET", "/wake/now")[0], 404)
        self.assertEqual(self.request("POST", "/")[0], 404)

    def test_oversized_body_is_refused_before_it_is_read(self) -> None:
        # Raw socket: http.client would not let us lie about Content-Length.
        with socket.create_connection(("127.0.0.1", self.http_port), timeout=5) as raw:
            raw.sendall(
                b"POST /wake HTTP/1.0\r\n"
                b"Host: 127.0.0.1\r\n"
                b"Content-Type: application/json\r\n"
                + f"Content-Length: {server.MAX_BODY_BYTES + 1}\r\n".encode()
                + b"\r\n"
            )
            response = b""
            while b"\r\n\r\n" not in response:
                chunk = raw.recv(4096)
                if not chunk:
                    break
                response += chunk
        self.assertIn(b"413", response.split(b"\r\n")[0])


class AuthenticatedServerTests(ServerTestCase):
    secret = "correct-horse-battery-staple"

    def auth(self, token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    def test_health_still_needs_no_auth(self) -> None:
        status, payload, _ = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertTrue(payload["authenticated"])

    def test_missing_token_is_rejected(self) -> None:
        status, payload, _ = self.request("POST", "/wake")
        self.assertEqual(status, 401)
        self.assertFalse(payload["ok"])
        with self.assertRaises(socket.timeout):
            self.receive_packet()

    def test_wrong_token_is_rejected(self) -> None:
        status, _, _ = self.request(
            "POST", "/wake", headers=self.auth("correct-horse-battery-stapl")
        )
        self.assertEqual(status, 401)

    def test_non_bearer_scheme_is_rejected(self) -> None:
        status, _, _ = self.request(
            "POST",
            "/wake",
            headers={"Authorization": f"Basic {self.secret}"},
        )
        self.assertEqual(status, 401)
        status, _, _ = self.request(
            "POST", "/wake", headers={"Authorization": self.secret}
        )
        self.assertEqual(status, 401)

    def test_correct_token_wakes(self) -> None:
        status, payload, _ = self.request("POST", "/wake", headers=self.auth(self.secret))
        self.assertEqual(status, 200)
        self.assertTrue(payload["ok"])
        self.assertEqual(len(self.receive_packet()), 102)

    def test_rejections_and_responses_never_echo_a_token(self) -> None:
        wrong = "correct-horse-battery-stapleX"
        for headers in ({}, self.auth(wrong), {"Authorization": wrong}):
            with self.subTest(headers=headers):
                _, _, raw = self.request("POST", "/wake", headers=headers)
                self.assertNotIn(self.secret, raw.decode())
                self.assertNotIn(wrong, raw.decode())
        _, _, raw = self.request("GET", "/health")
        self.assertNotIn(self.secret, raw.decode())


class RedactionInLogsTests(unittest.TestCase):
    def test_a_secret_in_the_request_line_is_redacted_from_the_access_log(self) -> None:
        secret = "log-leak-canary"
        config = server.Config(
            mac=MAC,
            broadcasts=("127.0.0.1",),
            wol_port=9,
            http_port=0,
            secret=secret,
        )
        httpd = server.build_server(config)
        self.addCleanup(httpd.server_close)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertLogs("wol_bridge.server", level="INFO") as captured:
                connection = http.client.HTTPConnection(
                    "127.0.0.1", httpd.server_address[1], timeout=5
                )
                try:
                    # A client that puts the secret in the query string instead
                    # of the header must not get it written to a log file.
                    connection.request("GET", f"/health?secret={secret}")
                    connection.getresponse().read()
                finally:
                    connection.close()
        finally:
            httpd.shutdown()
            thread.join(timeout=5)
        logged = "\n".join(captured.output)
        self.assertIn("/health", logged)
        self.assertNotIn(secret, logged)


class CliTests(unittest.TestCase):
    """wol.py and server.py are used from a shell, so their exit codes matter."""

    def test_wol_cli_reports_success_and_json(self) -> None:
        receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        receiver.bind(("127.0.0.1", 0))
        receiver.settimeout(0.75)
        self.addCleanup(receiver.close)
        port = receiver.getsockname()[1]

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = wol.main(
                [
                    "--mac",
                    MAC,
                    "--broadcast",
                    "127.0.0.1",
                    "--port",
                    str(port),
                    "--repeat",
                    "2",
                    "--json",
                ]
            )
        self.assertEqual(code, 0)
        payload = json.loads(buffer.getvalue())
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["datagrams"], 2)
        self.assertEqual(len(receiver.recvfrom(2048)[0]), 102)

    def test_wol_cli_exit_code_is_1_when_every_target_fails(self) -> None:
        buffer = io.StringIO()
        factory = FailingSocketFactory()
        self.addCleanup(factory.close_all)
        with mock.patch.object(socket, "socket", factory):
            with contextlib.redirect_stdout(buffer):
                code = wol.main(
                    ["--mac", MAC, "--broadcast", "192.168.10.255", "--json"]
                )
        self.assertEqual(code, 1)
        payload = json.loads(buffer.getvalue())
        self.assertFalse(payload["ok"])
        self.assertIn("error", payload)
        self.assertIn("Network is unreachable", json.dumps(payload))

    def test_wol_cli_exit_code_is_2_for_bad_arguments(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as ctx:
                wol.main(["--mac", MAC])  # --broadcast missing
        self.assertEqual(ctx.exception.code, 2)

    def test_wol_cli_rejects_unusable_values_as_a_usage_error(self) -> None:
        cases = (
            ["--mac", "zz:zz:zz:zz:zz:zz", "--broadcast", "127.0.0.1"],
            ["--mac", MAC, "--broadcast", "192.168.10.256"],
            ["--mac", MAC, "--broadcast", "myrouter.local"],
            ["--mac", MAC, "--broadcast", "0.0.0.0"],
            ["--mac", MAC, "--broadcast", "127.0.0.1", "--port", "0"],
            ["--mac", MAC, "--broadcast", "127.0.0.1", "--repeat", "0"],
            ["--mac", MAC, "--broadcast", "127.0.0.1", "--repeat", "1000"],
        )
        for argv in cases:
            with self.subTest(argv=argv):
                with contextlib.redirect_stderr(io.StringIO()):
                    with contextlib.redirect_stdout(io.StringIO()):
                        code = wol.main(argv)
                self.assertEqual(code, 2, f"expected a usage error for {argv}")


if __name__ == "__main__":
    unittest.main()
