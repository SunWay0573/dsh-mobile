#!/usr/bin/env bash
#
# Boot a real DSH host with these plugins installed, and check that they load.
#
# This is the strongest check in the repository, and it is separate from
# scripts/verify.sh because it is slow and needs to install the DSH CLI.
#
# It exists because three bugs shipped that nothing else could see:
#
#   1. No `dsh.bundle` declaration. `dsh plugin add` installs the package as a
#      plain dependency, warns once, and the host never mounts it.
#   2. A value import from a package that was only a development dependency.
#      The import does not exist at runtime and the plugin aborts at load.
#   3. A patch `name:` that did not match the package name. The loader imports
#      that string verbatim.
#
# In all three cases the package is correct, the typecheck passes and every unit
# test passes. The plugin simply never runs. The only way to know is to start a
# host and look.
#
# Usage:
#   scripts/verify-integration.sh
#   DSH_E2E_KEEP=1 scripts/verify-integration.sh   # keep the sandbox for poking
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

DSH_VERSION="${DSH_VERSION:-0.2.0-rc.2}"
SANDBOX="${DSH_E2E_SANDBOX:-$(mktemp -d "${TMPDIR:-/tmp}/dsh-e2e.XXXXXX")}"
# The CLI is cached outside the sandbox: it is ~200 MB and identical between
# runs, so re-downloading it on every verify would make this check one that
# people skip. The DSH_HOME stays in the sandbox, because that state is what
# must not leak.
CLI_CACHE="${DSH_E2E_CLI_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/dsh-e2e-cli}"
CLI_DIR="$CLI_CACHE/$DSH_VERSION"
DSH_HOME_DIR="$SANDBOX/home"
PORT="${DSH_E2E_PORT:-34999}"
HOST_PID=""

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
info() { printf '  \033[2m%s\033[0m\n' "$1"; }
FAILURES=0

cleanup() {
  if [[ -n "${SINK_PID:-}" ]] && kill -0 "$SINK_PID" 2>/dev/null; then
    kill "$SINK_PID" 2>/dev/null || true
  fi
  if [[ -n "$HOST_PID" ]] && kill -0 "$HOST_PID" 2>/dev/null; then
    kill "$HOST_PID" 2>/dev/null || true
    sleep 1
    kill -9 "$HOST_PID" 2>/dev/null || true
  fi
  if [[ "${DSH_E2E_KEEP:-0}" == "1" ]]; then
    printf '\nSandbox kept at %s\n' "$SANDBOX"
  else
    rm -rf "$SANDBOX"
  fi
}
trap cleanup EXIT INT TERM

# Sampled before anything is installed, because other software on the machine may
# already be holding sleep assertions of its own.
BASELINE="$(
  if [[ "$(uname -s)" == "Darwin" ]]; then
    pmset -g assertions 2>/dev/null | grep -cE '^[[:space:]]*pid [0-9]+\(caffeinate\)' || true
  elif command -v systemd-inhibit >/dev/null 2>&1; then
    systemd-inhibit --list 2>/dev/null | grep -c 'DSH is running a task' || true
  else
    echo "unsupported"
  fi
)"
if [[ "$BASELINE" == "unsupported" ]]; then BASELINE=0; fi

bold "Integration: do the plugins actually load in a real host?"

# ── 1. A DSH CLI to load them with ──────────────────────────────────────────
# Installed into the sandbox rather than globally, so this never touches the
# machine's own DSH installation or its ~/.dsh state.
if command -v dsh >/dev/null 2>&1 && [[ "${DSH_E2E_USE_GLOBAL_CLI:-0}" == "1" ]]; then
  DSH="$(command -v dsh)"
  info "using the dsh already on PATH: $DSH"
else
  if [[ ! -x "$CLI_DIR/node_modules/.bin/dsh" ]]; then
    mkdir -p "$CLI_DIR"
    info "installing @deepseek-ai/dsh@$DSH_VERSION (first run only, cached afterwards)"
    if ! (cd "$CLI_DIR" && npm init -y >/dev/null 2>&1 \
        && npm install --no-audit --no-fund "@deepseek-ai/dsh@$DSH_VERSION" >/dev/null 2>&1); then
      fail "could not install the DSH CLI"
      exit 1
    fi
  else
    info "using the cached DSH CLI at $CLI_DIR"
  fi
  DSH="$CLI_DIR/node_modules/.bin/dsh"
fi

if [[ ! -x "$DSH" ]]; then
  fail "no usable dsh at $DSH"
  exit 1
fi
pass "DSH CLI available ($("$DSH" --version 2>/dev/null | head -1))"

export DSH_HOME="$DSH_HOME_DIR"
mkdir -p "$DSH_HOME"

# ── 2. Install each plugin ──────────────────────────────────────────────────
PLUGINS=()
for dir in plugins/*/; do
  name="$(basename "$dir")"
  [[ -f "$dir/package.json" ]] || continue
  grep -q '"private": true' "$dir/package.json" 2>/dev/null && continue
  PLUGINS+=("$name")
done

if [[ ${#PLUGINS[@]} -eq 0 ]]; then
  fail "no plugin packages found under plugins/"
  exit 1
fi

# The plugins must be built; the host loads lib/, not src/.
if [[ ! -f plugins/sleep-guard/lib/index.js ]]; then
  info "building the plugins first"
  (cd plugins && npx tsc -b >/dev/null 2>&1) || true
fi

for name in "${PLUGINS[@]}"; do
  package_name="$(node -p "require('./plugins/$name/package.json').name")"

  # `dsh plugin add` warns when a package declares no dsh.bundle. Catching the
  # warning here is the whole point: the command still succeeds, and the plugin
  # is simply never mounted.
  add_output="$("$DSH" plugin --profile web add "$REPO_ROOT/plugins/$name" 2>&1)"
  if grep -q "declares no dsh.bundle" <<<"$add_output"; then
    fail "$name: installed but declares no dsh.bundle, so the host will never mount it"
  else
    pass "$name: installed as a profile layer"
  fi

  # And the layer must actually be in the bundle list, not merely claimed.
  if node -e "
    const d = require('$DSH_HOME_DIR/profiles/web/package.json')
    process.exit(d.dsh.profile.bundles.includes('$package_name') ? 0 : 1)
  " 2>/dev/null; then
    pass "$name: present in the profile bundle list"
  else
    fail "$name: absent from the profile bundle list after install"
  fi
done

# ── A webhook to deliver to ─────────────────────────────────────────────────
# mobile-bridge posts notifications to a URL. To find out whether one is actually
# delivered -- rather than trusting the plugin's own account of itself --
# something has to be on the other end. This also exercises the configuration
# path a real user takes, because the endpoint is set the same way they would
# set it: an id-targeted config override in the profile patch layer.
SINK_PORT="${DSH_E2E_SINK_PORT:-34998}"
SINK_LOG="$SANDBOX/notifications.jsonl"
node "$REPO_ROOT/scripts/fixtures/notify-sink.mjs" "$SINK_PORT" "$SINK_LOG" \
  >"$SANDBOX/sink.log" 2>&1 &
SINK_PID=$!

for _ in $(seq 1 20); do
  grep -q "notify-sink listening" "$SANDBOX/sink.log" 2>/dev/null && break
  sleep 0.5
done

if grep -q "notify-sink listening" "$SANDBOX/sink.log" 2>/dev/null; then
  pass "webhook sink listening on 127.0.0.1:$SINK_PORT"
else
  fail "webhook sink did not start"
  sed 's/^/       /' "$SANDBOX/sink.log" | tail -5
fi

PROFILE_DIR="$DSH_HOME_DIR/profiles/web"
mkdir -p "$PROFILE_DIR"
cat > "$PROFILE_DIR/cordis.patch.yml" <<PATCH
# Written by scripts/verify-integration.sh. This is the same id-targeted config
# override a user would write, so the check exercises the real configuration
# path rather than injecting state some other way.
- id: mobile-bridge
  name: dsh-mobile-bridge
  config:
    url: http://127.0.0.1:$SINK_PORT/notify
    token: e2e-token
    baseUrl: https://e2e.invalid
    notifyOnApproval: true
    notifyOnTurnEnd: false
PATCH
pass "mobile-bridge configured through the profile patch layer"

# The job-holder fixture is installed alongside, so sleep-guard has something to
# notice. See scripts/fixtures/README.md for why a fixture is needed at all.
for fixture in job-holder approval-emitter; do
  fixture_dir="$REPO_ROOT/scripts/fixtures/$fixture"
  if [[ -d "$fixture_dir" ]]; then
    "$DSH" plugin --profile web add "$fixture_dir" >/dev/null 2>&1
    pass "$fixture fixture installed"
  else
    fail "$fixture fixture missing at $fixture_dir"
  fi
done

# ── 3. Both rows must appear in the composed tree ───────────────────────────
# A package can be in the bundle list and still contribute nothing if its patch
# is wrong. The dumped config is what the host will actually load.
config="$("$DSH" --profile web --dump-config 2>/dev/null)"
for name in "${PLUGINS[@]}"; do
  package_name="$(node -p "require('./plugins/$name/package.json').name")"
  if grep -q "name: $package_name\$" <<<"$config"; then
    pass "$name: row present in the composed config tree"
  else
    fail "$name: no row named $package_name in the composed config tree"
  fi
done

# ── 4. Boot it and read what the host says ──────────────────────────────────
# This is the assertion that would have caught all three bugs. Everything above
# can pass while the host still reports that an entry did not activate.
boot_log="$SANDBOX/boot.log"
"$DSH" --profile web --no-open --port "$PORT" >"$boot_log" 2>&1 &
HOST_PID=$!

# Poll for the listen line rather than sleeping a fixed amount: a slow machine
# should not turn into a false failure.
for _ in $(seq 1 60); do
  grep -q "dsh web: http" "$boot_log" 2>/dev/null && break
  kill -0 "$HOST_PID" 2>/dev/null || break
  sleep 1
done

if grep -q "dsh web: http" "$boot_log"; then
  pass "host booted and printed its URL"
else
  fail "host did not reach the listening state"
  sed 's/^/       /' "$boot_log" | tail -20
fi

if grep -q "did not activate" "$boot_log"; then
  fail "the host reported an entry that did not activate:"
  grep -A3 "did not activate" "$boot_log" | sed 's/^/       /'
else
  pass "every entry activated — no plugin failed to load"
fi

# Loading is not the same as working: a plugin can mount and still throw on its
# first event. Any uncaught error in the log is a failure.
if grep -qE "^\s*(Error|TypeError|ReferenceError):" "$boot_log"; then
  fail "the host logged an uncaught error:"
  grep -E "^\s*(Error|TypeError|ReferenceError):" "$boot_log" | head -5 | sed 's/^/       /'
else
  pass "no uncaught errors in the host log"
fi

# ── 5. Does sleep-guard actually hold an assertion? ─────────────────────────
# Loading is not working. Everything above can pass while the plugin never holds
# anything, which is the one thing it exists to do. The fixture opens a job; this
# watches the platform's own view of the world for the assertion to appear and
# then to go away.
#
# Counted as a *difference from a baseline*, not an absolute. A machine very
# often already has a `caffeinate` running for some unrelated reason -- this one
# did, from a keep-awake agent installed months earlier -- and an absolute count
# would report "held" before the host even started and "leaked" forever after.
# The baseline is sampled below, before anything is installed.
assertion_count() {
  if [[ "$(uname -s)" == "Darwin" ]]; then
    # One line per assertion. Matching loosely would also catch the
    # "Details: caffeinate asserting forever" line and double-count.
    pmset -g assertions 2>/dev/null | grep -cE '^[[:space:]]*pid [0-9]+\(caffeinate\)' || true
  elif command -v systemd-inhibit >/dev/null 2>&1; then
    systemd-inhibit --list 2>/dev/null | grep -c 'DSH is running a task' || true
  else
    echo "unsupported"
  fi
}

mechanism="$(assertion_count)"
if [[ "$mechanism" == "unsupported" ]]; then
  info "skipping the assertion check: nothing to observe on $(uname -s)"
else
  # Wait for the fixture to announce it took a job.
  for _ in $(seq 1 40); do
    grep -q "dsh-e2e-job-holder: holding job" "$boot_log" 2>/dev/null && break
    sleep 1
  done

  if ! grep -q "dsh-e2e-job-holder: holding job" "$boot_log" 2>/dev/null; then
    fail "the fixture never started a job, so the assertion cannot be observed"
    sed 's/^/       /' "$boot_log" | tail -10
  else
    pass "fixture opened a background job (baseline was $BASELINE)"

    held="$BASELINE"
    for _ in $(seq 1 20); do
      held="$(assertion_count)"
      [[ "$held" -gt "$BASELINE" ]] && break
      sleep 1
    done

    if [[ "$held" -gt "$BASELINE" ]]; then
      pass "sleep-guard holds an assertion while work is in flight ($BASELINE -> $held)"
    else
      fail "no new sleep assertion appeared while a job was running"
      info "this is the plugin's whole purpose; check that sleep-guard activated"
    fi

    # …and it must be released. A leaked assertion is the failure mode this
    # plugin is designed around: it keeps a machine awake in an empty room, and
    # it survives the plugin being unloaded.
    for _ in $(seq 1 45); do
      grep -q "dsh-e2e-job-holder: released job" "$boot_log" 2>/dev/null && break
      sleep 1
    done

    if grep -q "dsh-e2e-job-holder: released job" "$boot_log" 2>/dev/null; then
      after=""
      for _ in $(seq 1 15); do
        after="$(assertion_count)"
        [[ "$after" -le "$BASELINE" ]] && break
        sleep 1
      done
      if [[ "$after" -le "$BASELINE" ]]; then
        pass "the assertion was released once the work finished (back to $after)"
      else
        fail "an assertion is still held after the job finished: leak ($BASELINE -> $after)"
        info "a leaked assertion keeps the machine awake until it is rebooted"
      fi
    else
      fail "the fixture never released its job"
    fi
  fi
fi

# ── 6. Does an approval actually reach the phone? ───────────────────────────
# mobile-bridge's purpose is that a phone in a pocket finds out the agent is
# blocked. The fixture raises the event; this checks that a real HTTP request
# came out the other end, with the content a person could act on.
for _ in $(seq 1 30); do
  [[ -s "$SINK_LOG" ]] && break
  sleep 1
done

if [[ ! -s "$SINK_LOG" ]]; then
  fail "no notification was delivered to the webhook"
  info "the fixture should have raised an approval/request about 8s after boot"
  grep -E "approval-emitter" "$boot_log" 2>/dev/null | sed 's/^/       /' | tail -3
else
  pass "a notification reached the webhook"

  # Content, not just arrival. A notification with an empty body or no tap
  # target is delivered and useless.
  delivered="$(head -1 "$SINK_LOG")"
  body="$(printf '%s' "$delivered" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);console.log(j.body)})')"
  priority="$(printf '%s' "$delivered" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);console.log(j.headers.priority ?? "")})')"
  click="$(printf '%s' "$delivered" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);console.log(j.headers.click ?? "")})')"
  auth="$(printf '%s' "$delivered" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);console.log(j.headers.authorization ?? "")})')"

  if [[ "$body" == *"e2e fixture"* ]]; then
    pass "the notification carries the asker's reason"
  else
    fail "unexpected notification body: $body"
  fi

  if [[ "$priority" == "urgent" ]]; then
    pass "approval notifications are urgent, so a phone does not ignore them"
  else
    fail "expected priority urgent, got '${priority:-none}'"
  fi

  if [[ "$click" == *"e2e-approval-session"* ]]; then
    pass "the notification deep-links back to the session"
  else
    fail "expected a deep link to the session, got '${click:-none}'"
  fi

  if [[ "$auth" == "Bearer e2e-token" ]]; then
    pass "the configured bearer token was sent"
  else
    fail "expected the configured token, got '${auth:-none}'"
  fi
fi

# ── Summary ─────────────────────────────────────────────────────────────────
echo
bold "Summary"
if [[ $FAILURES -gt 0 ]]; then
  printf '  %d check(s) failed\n' "$FAILURES"
  exit 1
fi
printf '  all integration checks passed\n'
exit 0
