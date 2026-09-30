# sleep-guard

A DSH host plugin that keeps the machine awake **while work is running**, and
lets it sleep the rest of the time.

| State | Behaviour |
|---|---|
| Idle, nothing running | Sleep |
| A turn or job is running | Stay awake |
| A scheduled task is pending | Arrange an RTC self-wake |
| — | — |

## Why not just `caffeinate` forever

Because that is a different product. Permanently preventing sleep means a fan
spinning in an empty room, power draw you did not ask for, and a machine that is
awake to attackers for no reason. The interesting problem is not "keep it awake",
it is "know when it is working".

DSH already tells you. The plugin is small because the host does the hard part.

## Design

```ts
const busy = () =>
  ctx.agents.roots().some(a => a.status === 'running') ||
  ctx.jobs.list().some(j => j.status === 'running')
```

Subscribe to `agent/status`, `agent/created`, `agent/disposed`, and
`ctx.jobs.onJobsChanged()`. On a false→true transition spawn `caffeinate -i`;
on true→false, kill it.

### Two details that are easy to get wrong

**`status === 'idle'` does not mean "nothing is happening."** Background jobs
(`bash`, `subagent`) can still be running. Checking only `agent/status` produces
a machine that sleeps in the middle of a long build. Both signals are required.

**Use a child process, not an in-process assertion API.** The assertion then
lives exactly as long as a process you own. If the plugin wedges, crashes, or is
hot-reloaded, the OS releases the assertion for you. With an in-process
assertion, a single missed state transition leaves the machine unable to sleep
until reboot — the hardest possible bug to diagnose, in the least convenient
possible place.

### Scheduled work

A session's `schedule` timers are in-process `setTimeout`s. They do not fire
while the machine is asleep. So on the transition to idle, the plugin reads the
sessions' schedule records and programs an RTC wake for the next pending target:

```sh
sudo pmset schedule wake "10/01/26 09:00:00"
```

That is a native macOS facility, and it is genuinely used by the OS itself —
`pmset -g sched` typically shows entries Apple scheduled on its own behalf.

## Status

🚧 Not implemented yet. Tracking issue: TBD.

## Verifying it

The failure that matters is a leaked assertion:

```sh
# idle
pmset -g assertions | grep -i caffeinate     # expect: no output from this plugin

# start a long-running task, then re-check
pmset -g assertions | grep -i caffeinate     # expect: an assertion appears

# when the task ends
pmset -g assertions | grep -i caffeinate     # expect: it is gone
```

If the last check still shows an assertion, the disposer is wrong. Please say so
in your issue with the output.
