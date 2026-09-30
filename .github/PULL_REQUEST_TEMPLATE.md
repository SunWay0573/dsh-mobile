## What this changes

<!-- One paragraph. If the title already says it, say why instead. -->

## Why

<!-- The problem, not the patch. What was wrong, or what could not be done? -->

## How it was verified

<!--
  Paste what `scripts/verify.sh` reported. Be specific about skips: a section
  that skipped on your machine is honest and useful, whereas "tests pass" is
  neither.

  If you touched a plugin, say whether you ran the integration check
  (`scripts/verify.sh --only=integration`). It boots a real DSH host, and it is
  the only check that can see a plugin which installs, typechecks, passes every
  unit test, and never runs. That has happened three times here.
-->

```
scripts/verify.sh
```

## Anything a reviewer should push back on

<!--
  Optional, and the most useful section when it is not empty. A decision you
  were unsure about, an alternative you rejected, a test you could not make work
  as intended. "I weakened this test because X" is worth saying out loud.
-->

## Checklist

- [ ] `scripts/verify.sh` passes, or the failures are explained above.
- [ ] No secrets in the diff: ntfy topics, MAC addresses, tunnel hostnames,
      `~/.dsh/.credentials.yaml`.
- [ ] If this changes power management, networking or authentication, the
      failure mode considered is described above.
- [ ] Documentation updated where the behaviour changed. The READMEs carry
      design rationale, not just instructions — a change that makes a paragraph
      there wrong should update it.
