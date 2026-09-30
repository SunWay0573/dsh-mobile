# Making the DSH host reachable

DSH binds `127.0.0.1` by default and the CLI **deliberately refuses**
`--host 0.0.0.0`:

```
error: --host 0.0.0.0 is intentionally not supported yet for safety:
it would expose remote code execution to the network; use 127.0.0.1 instead
```

That refusal is correct, and this document does not work around it by exposing
the machine. The host stays on loopback; a tunnel provides the path in.

## Choose a tunnel

| Option | Good when | Watch out for |
|---|---|---|
| **WireGuard family** (Tailscale, Headscale, plain WG) | You want zero public exposure and per-device ACLs | Control plane reachability varies by region; self-hosting Headscale removes the dependency |
| **frp / reverse proxy on a VPS** | The WireGuard control plane is unreachable, or you want a stable hostname | You now own a public endpoint — terminate TLS and keep it patched |
| **Router port-forward** | Never | A public endpoint that runs arbitrary shell commands |

## The trust fence

Whatever you choose, DSH will reject the request before it even checks
authentication unless the `Host` header satisfies its fence:

- `Host` must be loopback, **or** listed in `trustedHosts`
- if an `Origin` header is present it must equal the `Host` authority exactly
- `Sec-Fetch-Site: cross-site` is rejected outright

This is why proxies that rewrite `Host` fail with a 403 that looks like an auth
problem but is not. **Verify `Host` is preserved before debugging anything else.**

`trustedHosts` lives on the `connection` row, not on `webserver`. Append to the
existing expression rather than replacing it:

```yaml
# ~/.dsh/profiles/web/cordis.patch.yml
- id: connection
  name: '@deepseek-ai/dsh-client-connection'
  inject: [webRuntime]
  config:
    trustedHosts: !!js ['dsh.example.com', ...ctx.webRuntime.trustedHosts]
```

### Alternative: bind the tunnel interface

If you would rather expose the tailnet address directly, override the `webserver`
row. Note that when bound to all interfaces DSH automatically adds every
non-internal IPv4 address to `trustedHosts`, which covers a Tailscale `100.x`
address without further configuration.

```yaml
- id: webserver
  name: '@deepseek-ai/dsh-host-webserver'
  inject: [webStartup]
  config:
    host: '0.0.0.0'
    port: !!js ctx.webStartup.port ?? 3080
    compression: gzip
    compressionLevel: 1
    compressionThresholdBytes: 1024
```

This also exposes the port to your whole LAN. Pair it with a firewall rule that
permits only the tunnel subnet.

## First login

DSH authenticates with a one-time launch token exchanged for a signed cookie:

```
http://<host>:<port>/?token=<launch-token>  →  303  →  HttpOnly; SameSite=Strict cookie
```

The token is regenerated on every host start, so after a restart you need to
re-open a fresh `?token=` URL. Run the host with `--no-open` and keep the printed
URL somewhere you can reach it.

## Verify, in this order

```sh
# 1. the host is up locally
curl -sI http://127.0.0.1:3080/            # expect 401 (up, unauthenticated)

# 2. the tunnel preserves Host
curl -sI https://<your-hostname>/          # expect 401, not 403

# 3. authentication works end to end
#    open https://<your-hostname>/?token=<...> in the phone's browser
```

A 403 at step 2 means `Host`/`Origin` is being rewritten. A 401 means the fence
passed and you are simply not authenticated yet — which is the correct state.
