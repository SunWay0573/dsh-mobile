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
