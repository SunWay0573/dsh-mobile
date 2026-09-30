# Test fixtures

Not part of the product. Nothing here is installed by a user, and nothing here
is published.

## `job-holder`

A minimal DSH plugin that opens one background job and holds it for 25 seconds.

`scripts/verify-integration.sh` needs it to answer a question that loading alone
cannot: **does `sleep-guard` actually hold a sleep assertion while work is in
flight?**

The obvious way to make work be in flight is an agent turn, which means an LLM
call, which means a test that needs a network connection and a budget. Instead
this calls `ctx.jobs.start` with no owner. An unowned job is exactly what
`sleep-guard` counts as busy, so starting one exercises the real code path
through the real service with no model involved.

The script then watches the platform's own view of the world — `pmset -g
assertions` on macOS, `systemd-inhibit --list` on Linux — for the assertion to
appear and then to go away. That is the plugin's actual contract, observed from
outside the process.

Written in plain JavaScript on purpose. A fixture that needs its own build step
is a fixture that breaks independently of the thing it is testing, and a broken
fixture is worse than no fixture because it trains people to ignore failures.

It logs when it takes and releases the job, so a failing run distinguishes "the
job never started" from "the assertion never appeared" — which look identical
from the outside otherwise.

Two things it had to get right, both found by running it:

- `ctx.jobs.start` refuses with *"no job controller serves this agent"* unless a
  controller is attached. The tools package attaches one for the agent that
  calls the tool; this fixture is not an agent and has no turn, so it attaches
  its own with `attachController`.
- The assertion has to be counted as a **difference from a baseline**, not
  absolutely. A machine very often already holds one for unrelated reasons — the
  development machine for this did — and a count of lines matching `caffeinate`
  counts two per assertion because of the `Details:` line. An absolute check
  reported "held" before the host started and "leaked" forever after.

## `approval-emitter`

Raises one `approval/request` about eight seconds after the host settles.

`mobile-bridge`'s whole purpose is that a phone in a pocket finds out the agent
is blocked, and checking that it *loads* says nothing about whether a
notification is ever sent. The real path needs an agent turn that hits an
approval-gated tool: slow, non-deterministic, and it costs money.

`approval/request` is an ordinary waterfall event, so the fixture raises one
directly via `ctx.waterfall`. Everything downstream is the production path — the
same listener, the same message builder, the same HTTP request. Only the reason
the event exists is synthetic.

The payload is deliberately minimal: `mobile-bridge` reads `agent.session.id`,
`toolName` and `reason`, so those are what the fixture supplies. Anything more
would be the fixture guessing at a shape the real asker owns.

## `notify-sink.mjs`

A webhook that records what it receives, one JSON line per request. It exists so
the notification check observes a real HTTP request rather than trusting the
plugin's own account of itself.

It truncates its output file at start-up, so a run that delivers nothing cannot
pass by reading the previous run's file.
