#!/usr/bin/env bash
#
# Verify the whole repository.
#
# This is the single source of truth for what "passing" means, and CI calls it
# rather than reimplementing the checks. Two reasons that matters:
#
#   1. A contributor can run exactly what CI runs, without pushing. For a
#      project whose Android build needs a ~1 GB SDK, "push and wait" is a poor
#      feedback loop.
#   2. The checks cannot drift. When the workflow and this script disagree, this
#      script is right and the workflow is a bug.
#
# Checks degrade rather than fail when a toolchain is absent, but say so loudly:
# a check that silently did nothing is worse than one that failed, because it
# looks like a pass. Under --strict a skip becomes a failure, which is what CI
# uses.
#
# Usage:
#   scripts/verify.sh                       # everything available here
#   scripts/verify.sh --strict              # missing toolchains are failures
#   scripts/verify.sh --only=plugins        # one section (what CI jobs use)
#   scripts/verify.sh --only=hygiene,android
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

STRICT=0
ONLY=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --strict) STRICT=1 ;;
    --only=*) ONLY="${1#--only=}" ;;
    --only) shift; ONLY="${1:-}" ;;
    -h|--help) sed -n '3,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) printf 'unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
  shift
done

selected() {
  [[ -z "$ONLY" ]] && return 0
  [[ ",$ONLY," == *",$1,"* ]]
}

PASS=0
FAIL=0
SKIP=0
FAILED_NAMES=()

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); }
skip() { printf '  \033[33mSKIP\033[0m %s (%s)\n' "$1" "$2"; SKIP=$((SKIP + 1)); }

need() {
  if command -v "$1" >/dev/null 2>&1; then
    return 0
  fi
  if [[ $STRICT -eq 1 ]]; then
    bad "$2: $1 not found"
  else
    skip "$2" "$1 not found"
  fi
  return 1
}

run() {
  local name="$1"; shift
  local output
  if output="$("$@" 2>&1)"; then
    ok "$name"
  else
    bad "$name"
    printf '%s\n' "$output" | tail -25 | sed 's/^/       /'
  fi
}

# ── Repo hygiene ────────────────────────────────────────────────────────────
# This project's own setup instructions involve tunnel configs, MAC addresses
# and push topics. All three are easy to commit by accident, and the blast
# radius is "someone else can wake or control your machine".

if selected hygiene; then
  bold "Repo hygiene"

  secret_shaped='(^|/)(\.credentials\.yaml|\.env|secrets/|.*\.pem|.*\.key|keystore\.properties|local\.properties|frpc\.(toml|ini)|wol-.*\.local\..*)$'
  if found=$(git ls-files | grep -E "$secret_shaped"); then
    bad "no secret-shaped files tracked"
    printf '%s\n' "$found" | sed 's/^/       /'
  else
    ok "no secret-shaped files tracked"
  fi

  if found=$(git grep -nIE 'BEGIN (RSA|OPENSSH|EC|PRIVATE) PRIVATE KEY|gh[pousr]_[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16}' -- . 2>/dev/null); then
    bad "no credential patterns in tracked files"
    printf '%s\n' "$found" | sed 's/^/       /'
  else
    ok "no credential patterns in tracked files"
  fi
fi

# ── Plugins ─────────────────────────────────────────────────────────────────
# Type-checking before testing is deliberate: it runs against the real DSH type
# definitions, and it is what caught three API mistakes that would otherwise
# have failed silently -- jobs.list() returning only unowned jobs, onJobsChanged
# having been removed in 0.2.x, and approval/request being a waterfall whose
# observers must delegate.

if selected plugins; then
  bold "Plugins"

  if need node "plugins" && need pnpm "plugins"; then
    if [[ ! -d plugins/node_modules ]]; then
      echo "  installing plugin dependencies..."
      (cd plugins && pnpm install --frozen-lockfile >/dev/null 2>&1) \
        || (cd plugins && pnpm install >/dev/null 2>&1)
    fi
    run "plugins typecheck against DSH types" bash -c 'cd plugins && npx tsc -b --force'
    run "plugins build" bash -c 'cd plugins && pnpm run build'
    run "sleep-guard tests" bash -c 'cd plugins/sleep-guard && node --test test/*.test.ts'
    run "mobile-bridge tests" bash -c 'cd plugins/mobile-bridge && node --test test/*.test.ts'
  fi
fi

# ── wol-bridge ──────────────────────────────────────────────────────────────
# Linux is this component's deployment target and is where SO_BROADCAST is
# actually enforced (macOS tolerates its absence), so running the suite there is
# real coverage rather than a formality.

if selected wol-bridge; then
  bold "wol-bridge"

  if need python3 "wol-bridge"; then
    run "syntax check" bash -c 'python3 -m py_compile wol-bridge/*.py'
    run "unit tests" bash -c 'python3 -m unittest discover -s wol-bridge -p "test_*.py"'
  fi
fi

# ── Android ─────────────────────────────────────────────────────────────────
# Needs a JDK, an Android SDK, and a network that can reach the Maven hosts --
# which of those are reachable varies by network. A missing toolchain degrades
# to a skip so a contributor without the SDK can still verify everything else.

if selected android; then
  bold "Android"

  ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if ! command -v java >/dev/null 2>&1; then
    if [[ $STRICT -eq 1 ]]; then bad "android: java not found"; else skip "android" "java not found"; fi
  elif [[ ! -d "$ANDROID_SDK" ]]; then
    if [[ $STRICT -eq 1 ]]; then bad "android: no SDK at $ANDROID_SDK"; else skip "android" "no Android SDK at $ANDROID_SDK"; fi
  else
    export ANDROID_HOME="$ANDROID_SDK"
    # local.properties is gitignored; both CI and a fresh clone need it written.
    [[ -f android/local.properties ]] || echo "sdk.dir=$ANDROID_SDK" > android/local.properties

    # The release variant must not permit cleartext. It is one line to get wrong
    # -- copying the debug config into src/main is the obvious way to "fix" a
    # LAN connection failing -- and the consequence is shipping an app that will
    # happily talk plaintext to anywhere. Checked from source so it costs
    # nothing and does not need a release build.
    if python3 - <<'CHECK'
import sys, xml.etree.ElementTree as ET
path = 'android/app/src/main/res/xml/network_security_config.xml'
base = ET.parse(path).getroot().find('base-config')
sys.exit(0 if base is not None and base.get('cleartextTrafficPermitted') == 'false' else 1)
CHECK
    then
      ok "release refuses cleartext by default"
    else
      bad "release refuses cleartext by default"
    fi
    run "unit tests" bash -c 'cd android && ./gradlew testDebugUnitTest --no-daemon --quiet'
    run "assemble debug APK" bash -c 'cd android && ./gradlew assembleDebug --no-daemon --quiet'
    if compgen -G "android/app/build/outputs/apk/debug/*.apk" >/dev/null; then
      ok "APK produced"
    else
      bad "APK produced"
    fi
  fi
fi

# ── Summary ─────────────────────────────────────────────────────────────────

echo
bold "Summary"
printf '  %d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
if [[ $FAIL -gt 0 ]]; then
  printf '  failed: %s\n' "${FAILED_NAMES[*]}"
  exit 1
fi
if [[ $SKIP -gt 0 && $STRICT -eq 1 ]]; then
  echo "  --strict: skips count as failures"
  exit 1
fi
exit 0
