# Security Policy

## The short version

**This project gives your phone the ability to execute arbitrary code on your
computer.** That is the entire point of it, and it is also the entire risk.
Treat your phone as if it were the keys to your house.

Three rules, in priority order:

1. **Never expose the DSH host to the public internet.** Use a WireGuard-family
   tunnel (`docs/setup-tunnel.md`). Do not port-forward `3080`. "It's behind a
   password" is not a mitigation when the thing behind the password runs
   arbitrary shell commands.
2. **Scope the tunnel ACL to your own device.** On Tailscale/Headscale, an ACL
   that says "only this phone may reach this machine on this port" costs five
   minutes and removes an entire class of problem.
3. **Do not run this on a machine you do not own or administer** without
   checking your employer's policy first. Corporate-managed machines often have
   MDM profiles that forbid this, and the agent may be able to read data you are
   not permitted to move.

## Threat model

| Adversary | Mitigations that matter |
|---|---|
| Someone on your LAN | DSH binds loopback by default; the tunnel is the only path in |
| Someone who finds your tunnel endpoint | DSH's Host/Origin trust fence + signed session cookie; tunnel ACL |
| Someone who steals your phone | Biometric lock in the app, short cookie lifetime, revoke below |
| A malicious web page in your phone's browser | Cookie is `SameSite=Strict`, `HttpOnly`, bound to the request authority |
| A prompt-injected agent | Approval policy, Auto review, sandbox mode — see below |

Out of scope: an attacker who already has code execution on the host, and a
compromised DSH build.

## The approval trap

DSH's approval policy has **no timeout and fails closed**. If the agent wants to
run a tool that needs approval and no client is connected to answer, the tool is
denied — silently, from your point of view.

The consequence for an unattended setup:

- Setting the policy to `never` makes the agent work unattended, but approval-gated
  tools fail rather than wait. Tasks can half-finish.
- Therefore `never` **must** be paired with push notifications, so you find out.
- The recommended combination is Auto review (a model reviews each tool call
  before it executes) plus push, so you can answer from the phone.

Never enable broad tool access for an unattended host *and* leave notifications
off. That combination fails silently, which is worse than failing loudly.

## Revoking access

The phone holds a signed cookie (30 days by default). To invalidate every device
at once, delete the browser-session record from the credential store:

```sh
# in ~/.dsh/.credentials.yaml, remove the client-connection/browser-session entry
# then restart the DSH host
```

A restart also rotates the one-time launch token, so any `?token=` URL you
previously copied stops working.

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.

Use GitHub's private vulnerability reporting on this repository
(Security → Report a vulnerability). If that is unavailable, open a minimal
issue that says only "security report, please contact me" with no details, and a
maintainer will reach out.

Include: affected component, reproduction steps, and impact. We aim to
acknowledge within a few days. This is a volunteer project with no bug bounty.

## Known limitations we are not going to pretend away

- **The Android app embeds a WebView pointed at your host.** If your tunnel
  endpoint is compromised, the app will happily render whatever it serves,
  inside a shell that holds a WoL sender and push credentials. Use HTTPS
  (`tailscale serve` provides a real certificate) rather than cleartext.
- **`wol-bridge` has no authentication by default.** It sends a magic packet,
  nothing more — the blast radius is "someone can wake your computer". If that
  matters to you, put it behind the same tunnel ACL and add a shared secret.
- **`sleep-guard` can keep a machine awake indefinitely if it leaks an
  assertion.** That is a availability bug, not a security one, but it is the
  most likely thing to go wrong in practice. It is why the implementation uses a
  child process rather than an in-process assertion API.
