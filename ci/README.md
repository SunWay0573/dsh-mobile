# `ci/`

## `github-workflow.yml`

The CI workflow, stored here rather than at `.github/workflows/ci.yml` because
**it cannot be pushed there.**

GitHub refuses to let an OAuth app create or update `.github/workflows/` without
the `workflow` token scope — a control that exists so a compromised token cannot
silently add CI that exfiltrates secrets. That is a reasonable control, and this
repository's tokens do not have the scope.

So the file lives somewhere it *can* be committed, which has three advantages
over leaving it on an unpushed branch:

- it is visible on GitHub, where someone might actually want to read it;
- it is backed up with the rest of the repository, rather than being a loose
  object one `git gc` from gone;
- its history is reviewable like anything else.

### Installing it

With the scope:

```sh
gh auth refresh -h github.com -s workflow   # you authorise in a browser
scripts/enable-ci.sh                        # copies this file into place and pushes
```

Without it, GitHub's web UI commits as *you*, not as a token, so it can create
the file directly:

1. <https://github.com/SunWay0573/dsh-mobile/new/main?filename=.github/workflows/ci.yml>
2. Paste the contents of [`github-workflow.yml`](github-workflow.yml)
3. Commit

[`scripts/enable-ci.sh`](../scripts/enable-ci.sh) reads this file. It checks the
scope first and **exits without creating anything** when it is missing, so
running it early is safe.

### What it runs

Five jobs, each a section of [`scripts/verify.sh`](../scripts/verify.sh) so CI
and a local run cannot disagree about what "passing" means:

| Job | What |
|---|---|
| hygiene | no tracked secrets, no credential patterns, documentation links resolve |
| plugins | structure, build, unit tests for both plugins |
| wol-bridge | syntax and unit tests |
| integration | boots a **real DSH host** with both plugins installed |
| android | builds the APK and runs its unit tests |

The integration job is the one that matters. It is the only check that can see a
plugin which installs cleanly, typechecks, passes every unit test, and never
runs — which has happened in this repository three times.
