#!/usr/bin/env bash
#
# Put the CI workflow in place and push it.
#
# The workflow has been written and reviewed for a while, but it cannot be
# pushed: GitHub refuses to let an OAuth app create or update
# `.github/workflows/` without the `workflow` token scope, which exists so a
# compromised token cannot silently add CI that exfiltrates secrets. That is a
# good control, and this script does not try to go around it — it checks for the
# scope and tells you the one command that grants it.
#
# The scope has to be granted by a human in a browser. A device code expires in
# about fifteen minutes, which is why this is a script you run rather than
# something a long-running agent session can complete on your behalf.
#
# Usage:
#   scripts/enable-ci.sh            # check, then install and push
#   scripts/enable-ci.sh --check    # only report whether it would work
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

# The canonical workflow lives at a path that CAN be committed, because one that
# only exists in a git branch object is one `git gc` away from being lost, and
# is invisible on GitHub where a user might want to read it.
SOURCE_FILE="ci/github-workflow.yml"
SOURCE_BRANCH="${CI_SOURCE_BRANCH:-ci-workflow}"
TARGET=".github/workflows/ci.yml"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
no()   { printf '  \033[31m✗\033[0m %s\n' "$1"; }
note() { printf '  \033[2m%s\033[0m\n' "$1"; }

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

bold "Enabling CI"

# ── gh present and authenticated ────────────────────────────────────────────
if ! command -v gh >/dev/null 2>&1; then
  no "gh is not on PATH"
  note "install it from https://cli.github.com, then run this again"
  exit 1
fi
ok "gh found"

if ! gh auth status >/dev/null 2>&1; then
  no "not logged in to GitHub"
  note "run: gh auth login"
  exit 1
fi

ACCOUNT="$(gh api user --jq .login 2>/dev/null || echo '?')"
ok "authenticated as $ACCOUNT"

# ── the scope this needs ────────────────────────────────────────────────────
# gh reports scopes for the active token; `workflow` is what the Contents API
# and git-receive-pack both require for this path.
SCOPES="$(gh auth status 2>&1 | sed -n "s/.*Token scopes: //p" | head -1)"

if [[ "$SCOPES" == *"'workflow'"* ]]; then
  ok "token has the workflow scope"
  HAVE_SCOPE=1
else
  HAVE_SCOPE=0
  no "token is missing the workflow scope"
  note "current scopes: ${SCOPES:-unknown}"
fi

# ── the workflow itself must exist on the source branch ─────────────────────
if [[ -f "$SOURCE_FILE" ]] || git show "$SOURCE_BRANCH:$TARGET" >/dev/null 2>&1; then
  ok "workflow found at $SOURCE_FILE"
  HAVE_SOURCE=1
else
  HAVE_SOURCE=0
  no "no $TARGET on branch $SOURCE_BRANCH"
fi

if [[ $CHECK_ONLY -eq 1 ]]; then
  echo
  if [[ $HAVE_SCOPE -eq 1 && $HAVE_SOURCE -eq 1 ]]; then
    note "ready: run scripts/enable-ci.sh without --check"
    exit 0
  fi
  note "not ready; see the failures above"
  exit 1
fi

if [[ $HAVE_SCOPE -eq 0 ]]; then
  cat <<'INSTRUCTIONS'

  Run this, in your own terminal:

      gh auth refresh -h github.com -s workflow

  It opens a browser and asks you to authorise the extra scope. Nothing about
  your password passes through this project or through any agent session — the
  authorisation happens between you and GitHub.

  Then run this script again.

INSTRUCTIONS
  exit 1
fi

if [[ $HAVE_SOURCE -eq 0 ]]; then
  no "cannot continue without a workflow to install"
  exit 1
fi

# ── install it ──────────────────────────────────────────────────────────────
mkdir -p .github/workflows
if [[ -f "$SOURCE_FILE" ]]; then
  mkdir -p "$(dirname "$TARGET")"
  cp "$SOURCE_FILE" "$TARGET"
else
  git show "$SOURCE_BRANCH:$TARGET" > "$TARGET"
fi
git add "$TARGET"

if git diff --cached --quiet; then
  ok "workflow already on this branch; nothing to commit"
else
  git commit -q -m "ci: enable the workflow

Installed by scripts/enable-ci.sh once the workflow token scope was granted.
GitHub refuses to let an OAuth app create .github/workflows/ without it, which
is why the file waited on a branch instead."
  ok "committed"
fi

git push origin HEAD 2>&1 | tail -3

echo
bold "Pushed"
note "watch the run with:  gh run watch"
note "or list them with:    gh run list --limit 5"
