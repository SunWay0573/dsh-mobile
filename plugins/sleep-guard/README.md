# sleep-guard

A DSH host plugin that keeps the machine awake **while work is running**, and
lets it sleep the rest of the time.

| State | Machine |
|---|---|
| Idle, nothing running | **Sleep.** Quiet, cool, and not awake for no reason. |
| A turn or a background job is in flight | **Stay awake.** Never sleep mid-task. |

## Why not just `caffeinate` forever

Because that is a different product. Permanently preventing sleep means a fan
spinning in an empty room, power you did not ask for, and a machine that is
awake to attackers for no reason. The interesting problem is not "keep it
awake", it is "know when it is working".

DSH already knows. The plugin is small because the host does the hard part.

## Install

```sh
dsh plugin --profile web add /path/to/plugins/sleep-guard
```

## Design

The plugin is three layers, chosen so the part that can be *wrong* is the part
that is *testable*:

| File | Role | Tested how |
|---|---|---|
| `src/busy.ts` | Is the host busy? Pure functions over plain data. | Directly, no host, no processes |
| `src/assertion.ts` | Hold and reliably release the assertion. | Fake child process drives every failure path |
| `src/index.ts` | Cordis wiring: subscribe, react, tear down. | Fake context, real `sleep` process |

```ts
const busy = () =>
  ctx.agents.list().some(a => a.status === 'running') ||
  collectJobs(ctx).some(j => j.status === 'running' || j.status === 'stopping')
```

On a false→true edge it spawns `caffeinate -i`; on true→false it kills it.

### Three details that are easy to get wrong

**1. `status === 'idle'` does not mean "nothing is happening."**

An idle agent has no *driver* scheduled — but a background job (`bash`,
`subagent`) outlives the turn that started it. Checking only agents produces a
machine that sleeps in the middle of a build.

**2. `jobs.list()` with no argument returns only *unowned* jobs.**

This is documented, and it is easy to miss:

```
List caller-owned and unowned jobs in registration order.
@param caller - reading session; omission sees only unowned jobs.
```

Calling `list()` once and calling it done silently misses **every ordinary
session-owned job** — which is the entire reason jobs are consulted. The plugin
unions the unowned set with `list(sessionId)` for every live agent, subagents
included (hence `agents.list()`, not `roots()`).

**3. There is no `onJobsChanged` in DSH 0.2.x.**

It existed in 0.1.x and was removed. The replacement is the job event stream:

```ts
ctx.jobs.events.subscribe({ owners: 'all' }, recompute)
```

Without it, a `bash` job settling produces no agent event at all, and the
assertion would stay held until the max-hold backstop fired — the machine
staying awake for up to six hours after the work finished.

### Why a child process, not an assertion API

macOS exposes `IOPMAssertionCreateWithName`; Linux has D-Bus inhibitors. Using
either directly would avoid spawning anything — and would mean a bug in this
plugin leaves the machine **permanently unable to sleep**, which is the worst
failure mode available here: invisible, unloaded with the plugin, fixed only by
a reboot.

A child process inverts that. The assertion lives exactly as long as a process
we own, so if the plugin wedges, crashes, or is hot-reloaded, the OS releases
it. The cost is one short-lived process per busy period.

Every error path prefers "may sleep when it should not" over "can never sleep".
A missed wake-up delays a task. A leaked assertion runs a fan all night.

## Scheduled work is not covered

A session's `schedule` timers are in-process `setTimeout`s. **They do not fire
while the machine is asleep**, so this plugin will happily let the machine sleep
through a scheduled task.

The fix is an RTC wake event, which needs root:

```sh
sudo pmset schedule wake "10/01/26 09:00:00"
```

That is left to the operator rather than done silently by a plugin, because a
plugin asking for root to schedule wake-ups is not a thing this project is
willing to ship by default.

## Verifying it

The tests cover the logic and the wiring. What they cannot prove is that
`caffeinate` really holds an assertion on your machine — that needs the host.

```sh
cd plugins/sleep-guard
pnpm test        # 54 tests
pnpm build       # emits lib/, which DSH loads
```

Then, against a live host:

```sh
# idle — expect no assertion from this plugin
pmset -g assertions | grep -i caffeinate

# start a long task, then re-check — expect an assertion to appear
# when the task finishes — expect it to disappear
```

If the last check still shows one, the disposer is wrong. Please open an issue
with the output.

Note that a *pre-existing* permanent `caffeinate` from some other tool will
confuse this check. `pmset -g assertions` names the owning process; check the
pid before concluding anything.

## Configuration

```yaml
- id: sleep-guard
  name: dsh-mobile-sleep-guard
  config:
    # Executable holding the assertion. Defaults to `caffeinate` on macOS and
    # `systemd-inhibit` on Linux.
    command: caffeinate
    args: ['-i']
    # Hard cap on one held period, in milliseconds. A backstop against a missed
    # work-finished signal, not a policy. Default: 6 hours.
    maxHoldMs: 21600000
    # Log every acquire and release.
    verbose: false
```

`-i` rather than `-s` on macOS is deliberate: `-s` also blocks display sleep,
which would leave the screen lit in an empty room.
