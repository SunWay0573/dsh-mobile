#!/usr/bin/env python3
"""HTTP front end for :mod:`wol`, small enough to leave running forever.

The phone cannot send a magic packet itself: broadcasts do not cross routers,
and the machine to be woken is asleep anyway. So the call chain is
phone -> DSH host -> the tunnel it already has -> this process, which is on a
subnet that can actually reach the target.

Two endpoints, and deliberately nothing else:

``GET /health``
    Liveness. Never requires the secret, and deliberately does not echo the
    configured MAC or broadcast addresses -- anyone who can reach the port
    should not learn your LAN topology from a health check.

``POST /wake``
    Sends the packet. Returns the same JSON shape as ``python3 wol.py --json``.
    An optional JSON body may override ``mac`` and/or ``broadcast``; everything
    else comes from configuration.

Security posture, stated plainly because it is easy to get wrong:

* Default bind is ``127.0.0.1``. The intended deployment puts this behind the
  same tunnel as the DSH host, so there is nothing to gain from listening on
  every interface, and a great deal to lose if the tunnel is not the only path
  in. Binding wider is possible (``--listen 0.0.0.0``) and always logged loudly.
* If ``WOL_BRIDGE_SECRET`` / ``--secret`` is set, every ``POST /wake`` requires
  ``Authorization: Bearer <secret>``. Otherwise the endpoint runs open and says
  so at startup. The blast radius of an open endpoint is "a stranger on your
  network can wake your computer", which is small but not nothing.
* The secret is never logged and never echoed back, including in error messages
  and access logs.
"""

from __future__ import annotations

import argparse
import hmac
import ipaddress
import json
import logging
import os
import signal
import sys
import threading
import time
import tomllib
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Mapping, Sequence
from urllib.parse import urlsplit

import wol

LOG = logging.getLogger("wol_bridge.server")

__all__ = [
    "DEFAULT_HTTP_PORT",
    "DEFAULT_LISTEN",
    "Config",
    "ConfigError",
    "WolBridgeHandler",
    "WolBridgeServer",
    "build_parser",
    "build_server",
    "config_from_args",
    "load_config_file",
    "main",
    "redact_secret",
    "run",
]

DEFAULT_LISTEN = "127.0.0.1"
DEFAULT_HTTP_PORT = 8787
DEFAULT_REPEAT = 3

# A wake request body is a MAC and a couple of addresses. Anything larger is a
# mistake or an attempt to make this process allocate on someone else's behalf.
MAX_BODY_BYTES = 64 * 1024

ENV_PREFIX = "WOL_BRIDGE_"
_ENV_NAMES = (
    "MAC",
    "BROADCAST",
    "PORT",
    "REPEAT",
    "LISTEN",
    "HTTP_PORT",
    "SECRET",
    "CONFIG",
)
_FLAG_ATTRS = ("mac", "broadcast", "port", "repeat", "listen", "http_port", "secret", "config")

#: Every key understood in a TOML config file. Names mirror the CLI flags so
#: that there is only one vocabulary to remember: ``--http-port`` <->
#: ``http_port``.
_CONFIG_KEYS = frozenset(
    {"mac", "broadcast", "port", "repeat", "listen", "http_port", "secret"}
)


class ConfigError(Exception):
    """Configuration that cannot be turned into a running server."""


def redact_secret(text: str, secret: str | None) -> str:
    """Replace ``secret`` with ``***`` anywhere it appears in ``text``.

    Belt and braces: nothing in this server is supposed to log the secret, but
    a client can always put it somewhere unexpected (a query string, a header
    this code does not know about) and the default :mod:`http.server` access
    log is happy to print whatever it is given.
    """
    if not secret:
        return text
    return text.replace(secret, "***")


@dataclass(frozen=True, slots=True)
class Config:
    """Everything the server needs to know, already validated."""

    mac: str
    broadcasts: tuple[str, ...]
    wol_port: int = wol.DEFAULT_PORT
    repeat: int = DEFAULT_REPEAT
    listen: str = DEFAULT_LISTEN
    http_port: int = DEFAULT_HTTP_PORT
    secret: str | None = None
    source: str = "defaults"

    @property
    def authenticated(self) -> bool:
        return self.secret is not None


def _as_int(value: Any, what: str) -> int:
    if isinstance(value, bool):
        raise ConfigError(f"{what} must be an integer, got a boolean")
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value.strip(), 10)
        except ValueError as exc:
            raise ConfigError(f"{what} must be an integer, got {value!r}") from exc
    raise ConfigError(f"{what} must be an integer, got {type(value).__name__}")


def _as_broadcasts(value: Any, what: str) -> tuple[str, ...]:
    if isinstance(value, str):
        items = [chunk for chunk in value.replace(",", " ").split()]
    elif isinstance(value, (list, tuple)):
        items = [str(item) for item in value]
    else:
        raise ConfigError(
            f"{what} must be a string or a list of strings, got "
            f"{type(value).__name__}"
        )
    if not items:
        raise ConfigError(f"{what} is empty; at least one broadcast address is needed")
    try:
        return tuple(wol.validate_broadcast(item) for item in items)
    except wol.WolError as exc:
        # Fail at startup, not on the first wake request at 2am: a typo in a
        # broadcast address is exactly the thing that otherwise looks fine
        # until the moment it matters.
        raise ConfigError(str(exc)) from exc


def load_config_file(path: str) -> dict[str, Any]:
    """Read a TOML config file. Raises :class:`ConfigError` on anything odd."""
    try:
        with open(path, "rb") as handle:
            # tomllib is read-only and stdlib from 3.11, which is exactly what
            # is wanted here: no dependency, and this process never writes
            # config back.
            data = tomllib.load(handle)
    except FileNotFoundError as exc:
        raise ConfigError(f"config file not found: {path}") from exc
    except tomllib.TOMLDecodeError as exc:
        raise ConfigError(f"{path} is not valid TOML: {exc}") from exc
    except OSError as exc:
        raise ConfigError(f"cannot read {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise ConfigError(f"{path} must contain a TOML table")
    return data


def config_from_args(
    args: argparse.Namespace, env: Mapping[str, str] | None = None
) -> Config:
    """Resolve configuration as CLI flag > environment > TOML file > default.

    Every layer is optional. Nothing is discovered implicitly -- a config file
    is used only when ``--config`` / ``WOL_BRIDGE_CONFIG`` names one, because a
    server that silently picks up a file from the current directory is a
    surprise waiting to happen under systemd.
    """
    env = os.environ if env is None else env

    config_path = args.config or env.get(f"{ENV_PREFIX}CONFIG")
    file_data: dict[str, Any] = load_config_file(config_path) if config_path else {}
    unknown = sorted(set(file_data) - set(_CONFIG_KEYS))
    if unknown:
        # A typo like `broadcast_addr` would otherwise mean "the value I set is
        # not the value in use", which is the hardest kind of bug to see.
        LOG.warning(
            "%s has unrecognised key(s): %s (known: %s)",
            config_path,
            ", ".join(unknown),
            ", ".join(sorted(_CONFIG_KEYS)),
        )

    def pick(flag: Any, name: str) -> Any:
        if flag is not None:
            return flag
        env_value = env.get(f"{ENV_PREFIX}{name}")
        if env_value is not None and env_value.strip() != "":
            return env_value
        return file_data.get(name.lower())

    source = "defaults"
    if any(env.get(f"{ENV_PREFIX}{name}") for name in _ENV_NAMES):
        source = "environment"
    if file_data:
        source = config_path or "config file"
    if any(getattr(args, attr, None) is not None for attr in _FLAG_ATTRS):
        source = "command line"

    raw_mac = pick(args.mac, "MAC")
    if raw_mac is None:
        raise ConfigError(
            "no target MAC configured; pass --mac, set WOL_BRIDGE_MAC, or put "
            "mac = \"aa:bb:cc:dd:ee:ff\" in a config file"
        )
    try:
        mac = wol.format_mac(wol.parse_mac(str(raw_mac)))
    except wol.WolError as exc:
        raise ConfigError(str(exc)) from exc

    raw_broadcast = pick(args.broadcast, "BROADCAST")
    if raw_broadcast is None:
        raise ConfigError(
            "no broadcast address configured; pass --broadcast (repeat the flag "
            "for several subnets), set WOL_BRIDGE_BROADCAST, or put "
            "broadcast = [\"192.168.10.255\"] in a config file"
        )
    broadcasts = _as_broadcasts(raw_broadcast, "broadcast")

    # `is None` checks, not `or`, so that an explicit-but-invalid 0 in a config
    # file is reported instead of being silently replaced by the default.
    raw_wol_port = pick(args.port, "PORT")
    wol_port = _as_int(
        wol.DEFAULT_PORT if raw_wol_port is None else raw_wol_port, "port"
    )
    if not 0 < wol_port < 65536:
        raise ConfigError(f"port {wol_port} is out of range (1-65535)")

    raw_repeat = pick(args.repeat, "REPEAT")
    repeat = _as_int(DEFAULT_REPEAT if raw_repeat is None else raw_repeat, "repeat")
    if not 1 <= repeat <= 100:
        raise ConfigError(f"repeat must be between 1 and 100, got {repeat}")

    listen = str(pick(args.listen, "LISTEN") or DEFAULT_LISTEN).strip()
    if not listen:
        raise ConfigError("listen address must not be empty")

    raw_http_port = pick(args.http_port, "HTTP_PORT")
    http_port = _as_int(
        DEFAULT_HTTP_PORT if raw_http_port is None else raw_http_port, "http_port"
    )
    # 0 is allowed and means "let the OS choose", which is how the tests bind
    # an ephemeral port without a race.
    if not 0 <= http_port < 65536:
        raise ConfigError(f"http port {http_port} is out of range (0-65535)")

    secret = pick(args.secret, "SECRET")
    secret = str(secret).strip() if secret is not None else None
    if secret == "":
        LOG.warning(
            "ignoring an empty secret: an empty bearer token would look like "
            "authentication while protecting nothing"
        )
        secret = None

    return Config(
        mac=mac,
        broadcasts=broadcasts,
        wol_port=wol_port,
        repeat=repeat,
        listen=listen,
        http_port=http_port,
        secret=secret,
        source=source,
    )


def _is_loopback(host: str) -> bool:
    if host in ("localhost", "::1"):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        # A hostname we cannot classify is treated as exposed, so the warning
        # errs towards noise rather than silence.
        return False


class _BadRequest(Exception):
    """Request-level problem, carrying the status to answer with."""

    def __init__(self, message: str, status: HTTPStatus = HTTPStatus.BAD_REQUEST):
        super().__init__(message)
        self.status = status


class WolBridgeHandler(BaseHTTPRequestHandler):
    """The two endpoints, plus enough HTTP hygiene to not surprise a client."""

    server: WolBridgeServer
    server_version = "wol-bridge/1.0"
    # Blank on purpose: the default leaks the interpreter version to anyone who
    # can reach the port, for no benefit.
    sys_version = ""
    # A stalled client must not hold a worker thread forever, especially since
    # the intended path to this service crosses a phone's mobile connection.
    timeout = 15

    # -- logging -----------------------------------------------------------

    def handle_one_request(self) -> None:
        self._started_at = time.monotonic()
        super().handle_one_request()

    def log_message(self, format: str, *args: Any) -> None:
        LOG.info(
            "%s %s", self.client_address[0], self._scrub(format % args)
        )

    def log_error(self, format: str, *args: Any) -> None:
        LOG.warning("%s %s", self.client_address[0], self._scrub(format % args))

    def log_request(self, code: int | str = "-", size: int | str = "-") -> None:
        elapsed_ms = (time.monotonic() - getattr(self, "_started_at", time.monotonic())) * 1000
        LOG.info(
            "%s \"%s\" %s %s %.1fms",
            self.client_address[0],
            self._scrub(self.requestline),
            code,
            size,
            elapsed_ms,
        )

    def _scrub(self, text: str) -> str:
        return redact_secret(text, self.server.config.secret)

    # -- responses ---------------------------------------------------------

    def _send_json(
        self,
        status: HTTPStatus,
        payload: Mapping[str, Any],
        headers: Mapping[str, str] | None = None,
    ) -> None:
        body = (json.dumps(payload, indent=2) + "\n").encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        # A wake response is a fact about this instant; caching it is never
        # what the caller wants.
        self.send_header("Cache-Control", "no-store")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _failure(self, status: HTTPStatus, message: str, **extra: Any) -> None:
        payload: dict[str, Any] = {"ok": False, "error": message}
        payload.update(extra)
        self._send_json(status, payload)

    def _not_found(self) -> None:
        self._failure(
            HTTPStatus.NOT_FOUND,
            f"no such endpoint: {urlsplit(self.path).path}; try GET /health or POST /wake",
        )

    def _method_not_allowed(self, allowed: str) -> None:
        self._send_json(
            HTTPStatus.METHOD_NOT_ALLOWED,
            {"ok": False, "error": f"{self.command} is not allowed here; use {allowed}"},
            headers={"Allow": allowed},
        )

    # -- auth --------------------------------------------------------------

    def _authorized(self) -> bool:
        """True if the request may wake the machine."""
        secret = self.server.config.secret
        if secret is None:
            return True
        header = self.headers.get("Authorization", "")
        scheme, _, token = header.partition(" ")
        if scheme.lower() != "bearer":
            return False
        # compare_digest, not ==: string comparison returns at the first
        # differing byte, so its running time leaks how much of the secret a
        # guess got right. It also handles unequal lengths without raising.
        return hmac.compare_digest(
            token.strip().encode("utf-8"), secret.encode("utf-8")
        )

    # -- request bodies ----------------------------------------------------

    def _read_json_body(self) -> dict[str, Any]:
        if self.headers.get("Transfer-Encoding"):
            raise _BadRequest(
                "chunked request bodies are not supported; send Content-Length"
            )
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            return {}
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise _BadRequest(f"Content-Length is not a number: {raw_length!r}") from exc
        if length < 0:
            raise _BadRequest("Content-Length must not be negative")
        if length > MAX_BODY_BYTES:
            raise _BadRequest(
                f"body larger than {MAX_BODY_BYTES} bytes",
                status=HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
            )
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise _BadRequest(f"body is not UTF-8 JSON: {exc}") from exc
        if not isinstance(parsed, dict):
            raise _BadRequest("body must be a JSON object")
        return parsed

    # -- routes ------------------------------------------------------------

    def do_GET(self) -> None:
        self._dispatch(lambda: self._route_get())

    def do_POST(self) -> None:
        self._dispatch(lambda: self._route_post())

    def _dispatch(self, action: Any) -> None:
        """Run a route, turning any escaping exception into a JSON 500.

        A traceback on stderr is fine; a dropped connection or an HTML error
        page is not, because the only client here parses JSON.
        """
        try:
            action()
        except Exception:  # noqa: BLE001 - last resort, must not leak internals
            LOG.exception(
                "unhandled error handling %s %s",
                self.command,
                self._scrub(self.path),
            )
            self._failure(HTTPStatus.INTERNAL_SERVER_ERROR, "internal error")

    def _route_get(self) -> None:
        path = _normalise_path(self.path)
        if path == "/health":
            self._health()
        elif path == "/wake":
            self._method_not_allowed("POST")
        else:
            self._not_found()

    def _route_post(self) -> None:
        path = _normalise_path(self.path)
        if path == "/wake":
            self._wake()
        elif path == "/health":
            self._method_not_allowed("GET")
        else:
            self._not_found()

    def _health(self) -> None:
        config = self.server.config
        self._send_json(
            HTTPStatus.OK,
            {
                "ok": True,
                "service": "wol-bridge",
                "authenticated": config.authenticated,
                "targets": len(config.broadcasts),
                "version": self.server_version,
            },
        )

    def _wake(self) -> None:
        config = self.server.config
        if not self._authorized():
            # The 401 body deliberately says nothing about what was supplied:
            # an error message is a very common way to leak a credential.
            self._send_json(
                HTTPStatus.UNAUTHORIZED,
                {"ok": False, "error": "missing or wrong bearer token"},
                headers={"WWW-Authenticate": 'Bearer realm="wol-bridge"'},
            )
            return

        try:
            body = self._read_json_body()
            mac = str(body.get("mac", config.mac))
            broadcasts = body.get("broadcast", config.broadcasts)
            if isinstance(broadcasts, str):
                raw_targets = (broadcasts,)
            elif isinstance(broadcasts, (list, tuple)):
                raw_targets = tuple(str(item) for item in broadcasts)
            else:
                raise _BadRequest("broadcast must be a string or a list of strings")
            if not raw_targets:
                raise _BadRequest("broadcast must name at least one address")
            # Validate the caller's addresses here rather than letting wol.wake
            # record them as per-target send failures: an address that could
            # never work is a bad request (400), not an upstream failure (502),
            # and the distinction is what tells the phone whether retrying is
            # worth anything.
            try:
                targets = tuple(wol.validate_broadcast(item) for item in raw_targets)
            except wol.WolError as exc:
                raise _BadRequest(str(exc)) from exc
            report = wol.wake(
                mac, targets, port=config.wol_port, repeat=config.repeat
            )
        except _BadRequest as exc:
            self._failure(exc.status, str(exc))
            return
        except wol.WolError as exc:
            # A per-request override can still name an unusable MAC even though
            # the configured one parsed fine at startup.
            self._failure(HTTPStatus.BAD_REQUEST, str(exc))
            return
        except OSError as exc:
            self._failure(
                HTTPStatus.INTERNAL_SERVER_ERROR, f"send failed: {exc.strerror or exc}"
            )
            return

        payload = report.as_dict()
        if not report.ok:
            # Nothing left the machine for any target: this is the caller's
            # "the wake did not even get attempted" signal, and deserves a
            # status that a reverse proxy or the phone will not mistake for
            # success.
            self._failure(
                HTTPStatus.BAD_GATEWAY,
                str(payload["error"]),
                mac=report.mac,
                targets=payload["targets"],
            )
            return
        self._send_json(HTTPStatus.OK, payload)


def _normalise_path(path: str) -> str:
    """Strip the query string and any trailing slash from a request path."""
    route = urlsplit(path).path
    if len(route) > 1 and route.endswith("/"):
        route = route.rstrip("/") or "/"
    return route


class WolBridgeServer(ThreadingHTTPServer):
    """Threading HTTP server that carries its validated config.

    Threading matters here: a wake sends a handful of UDP datagrams and is
    usually instant, but a request must never be able to block the health check
    the phone uses to decide whether the bridge is alive.
    """

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], config: Config):
        self.config = config
        super().__init__(address, WolBridgeHandler)


def build_server(config: Config) -> WolBridgeServer:
    """Bind (but do not serve) the HTTP server described by ``config``."""
    return WolBridgeServer((config.listen, config.http_port), config)


def _log_startup(config: Config) -> None:
    """Announce the deployment, including the parts that are dangerous."""
    LOG.info(
        "listening on http://%s:%d (highest-precedence config layer: %s)",
        config.listen,
        config.http_port,
        config.source,
    )
    LOG.info("target MAC   : %s", config.mac)
    LOG.info("broadcast to : %s", ", ".join(config.broadcasts))
    LOG.info(
        "wol port     : %d (%s per target)",
        config.wol_port,
        "once" if config.repeat == 1 else f"repeated {config.repeat} times",
    )
    LOG.info(
        "auth         : %s",
        "bearer token required (value redacted)"
        if config.authenticated
        else "NONE",
    )

    if not config.authenticated:
        LOG.warning(
            "AUTHENTICATION IS DISABLED. Anyone who can reach %s:%d can wake %s. "
            "Set WOL_BRIDGE_SECRET (or --secret) and send "
            "'Authorization: Bearer <secret>' to require a shared secret.",
            config.listen,
            config.http_port,
            config.mac,
        )
    elif len(config.secret or "") < 16:
        LOG.warning(
            "the shared secret is shorter than 16 characters; a short secret on "
            "an exposed port is guessable at network speed"
        )

    if not _is_loopback(config.listen):
        LOG.warning(
            "BOUND TO %s, which is reachable from other machines. The default "
            "(%s) assumes you reach this service through the DSH tunnel; only "
            "bind wider if your firewall or tunnel ACL is doing the filtering.",
            config.listen,
            DEFAULT_LISTEN,
        )


def run(config: Config) -> int:
    """Serve until interrupted. Returns the process exit code."""
    try:
        httpd = build_server(config)
    except OSError as exc:
        LOG.error(
            "cannot bind %s:%d: %s", config.listen, config.http_port, exc.strerror or exc
        )
        return 1

    _log_startup(config)

    def _stop(signum: int, _frame: object) -> None:
        # shutdown() blocks until serve_forever() has returned, and this handler
        # runs *inside* serve_forever's thread -- calling it directly would
        # deadlock. Hence the thread, which is the whole reason this is not a
        # two-line handler.
        LOG.info("signal %d received, shutting down", signum)
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    for signame in ("SIGINT", "SIGTERM"):
        if hasattr(signal, signame):
            signal.signal(getattr(signal, signame), _stop)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:  # pragma: no cover - depends on the terminal
        LOG.info("interrupted")
    finally:
        httpd.server_close()
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="server.py",
        description=(
            "Tiny HTTP service that sends Wake-on-LAN magic packets for a phone "
            "on the far side of a tunnel."
        ),
        epilog=(
            "every flag also has an environment fallback: "
            f"{ENV_PREFIX}MAC, {ENV_PREFIX}BROADCAST, {ENV_PREFIX}PORT, "
            f"{ENV_PREFIX}REPEAT, {ENV_PREFIX}LISTEN, {ENV_PREFIX}HTTP_PORT, "
            f"{ENV_PREFIX}SECRET, {ENV_PREFIX}CONFIG. "
            "Precedence: flag > environment > config file > default."
        ),
    )
    parser.add_argument("--mac", help="target MAC address")
    parser.add_argument(
        "--broadcast",
        action="append",
        metavar="ADDR",
        help="broadcast address; repeat for several subnets",
    )
    parser.add_argument(
        "--port", type=int, help=f"UDP port for the magic packet (default {wol.DEFAULT_PORT})"
    )
    parser.add_argument(
        "--repeat", type=int, help=f"datagrams per target (default {DEFAULT_REPEAT})"
    )
    parser.add_argument(
        "--listen",
        help=f"bind address (default {DEFAULT_LISTEN}; pass 0.0.0.0 to expose it)",
    )
    parser.add_argument(
        "--http-port",
        type=int,
        help=f"port the HTTP endpoint listens on (default {DEFAULT_HTTP_PORT})",
    )
    parser.add_argument(
        "--secret",
        help=(
            "shared secret required as 'Authorization: Bearer <secret>'; if "
            "omitted the endpoint is unauthenticated (a warning is logged)"
        ),
    )
    parser.add_argument("--config", help="TOML config file; see config.example.toml")
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="debug logging",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """CLI entry point. Returns the process exit code."""
    args = build_parser().parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )
    # http.server's own logger would duplicate our access log lines.
    logging.getLogger("http.server").setLevel(logging.WARNING)

    try:
        config = config_from_args(args)
    except ConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    return run(config)


if __name__ == "__main__":
    raise SystemExit(main())
