#!/usr/bin/env bash
# .claude/hooks/verify-phase-gate.sh
#
# Stop hook for ADBye. Checks the MECHANICAL subset of each phase's exit
# criteria: real release build + code-level wiring. It cannot verify
# Testing Gate items that need a device/emulator/CI run (constraints
# #3's APK artifact, #4 Espresso, #5 real-IPC, #8 CI pipeline) — those
# must be self-reported in the conversation. See the phase-gate skill.
#
# Exit 0 = clean, turn may end. Exit 2 = blocked, stderr is fed back as
# the reason. Adjust the Gradle task names below if this repo uses a
# non-default module name (e.g. ":app:assembleRelease").

set -uo pipefail
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

STATE_FILE=".claude/phase-state.json"
if [ ! -f "$STATE_FILE" ]; then
  echo "No $STATE_FILE — phase gate not initialized, skipping." >&2
  exit 0
fi

PHASE=$(grep -o '"current_phase"[[:space:]]*:[[:space:]]*[0-9]*' "$STATE_FILE" | grep -o '[0-9]*$')
PHASE=${PHASE:-0}
FAIL=0
fail() { echo "BLOCK (phase $PHASE): $1" >&2; FAIL=1; }

# --- universal: fast debug compile first (cheap signal), then the real
#     constraint #3 release build with R8/ProGuard on ---
echo "verify-phase-gate: debug compile check..." >&2
if ! ./gradlew compileDebugJavaWithJavac -q > /tmp/vpg_debug.log 2>&1; then
  fail "debug compile failed:"
  tail -n 30 /tmp/vpg_debug.log >&2
fi

if [ "$FAIL" -eq 0 ]; then
  echo "verify-phase-gate: release build (constraint #3, R8 on)..." >&2
  if ! ./gradlew assembleRelease -q > /tmp/vpg_release.log 2>&1; then
    fail "release build failed. If this fails but debug compiles, check"
    echo "  signingConfig is set up, and check for R8-stripped code" >&2
    echo "  (reflection/JNI/crypto classes need -keep rules in" >&2
    echo "  proguard-rules.pro):" >&2
    tail -n 40 /tmp/vpg_release.log >&2
  fi
fi
rm -f /tmp/vpg_debug.log /tmp/vpg_release.log

# --- universal: constraint #8 — check latest GitHub Actions run for this
#     commit, if `gh` is installed and authenticated. Best-effort: this
#     does NOT hard-fail just because `gh` isn't set up (most local dev
#     machines won't have it configured), but DOES block on a confirmed
#     CI failure. Real gap-filling for #8 is .github/workflows/
#     phase-gate-ci.yml itself, not this hook. ---
if command -v gh >/dev/null 2>&1; then
  SHA=$(git rev-parse HEAD 2>/dev/null || true)
  if [ -n "$SHA" ]; then
    CI_JSON=$(gh run list --commit "$SHA" --json status,conclusion --jq '.[0] // empty' 2>/dev/null || true)
    if [ -n "$CI_JSON" ]; then
      echo "verify-phase-gate: latest CI run for $SHA: $CI_JSON" >&2
      case "$CI_JSON" in
        *'"conclusion":"failure"'*) fail "GitHub Actions CI failed for $SHA — run 'gh run view' for details." ;;
        *'"status":"in_progress"'*|*'"status":"queued"'*)
          echo "  note: CI still running for $SHA — re-check before advancing the phase." >&2 ;;
      esac
    else
      echo "  note: no CI run found yet for $SHA — push so constraint #8 can actually be checked." >&2
    fi
  fi
else
  echo "  note: 'gh' CLI not found — cannot check constraint #8 (CI pipeline) from here. Push and check GitHub Actions manually before advancing." >&2
fi

# --- universal: no orphaned new .java files ---
CHANGED=$(git diff --name-only --diff-filter=A HEAD -- '*.java' 2>/dev/null || true)
for f in $CHANGED; do
  cn=$(basename "$f" .java)
  refs=$(grep -rl --include="*.java" --include="*.xml" -F "$cn" . 2>/dev/null | grep -vF "$f" | wc -l)
  if [ "$refs" -eq 0 ]; then
    fail "$f ($cn) added but not referenced anywhere else in the tree."
  fi
done

# --- phase-specific mechanical checks ---
case "$PHASE" in
  0)
    find . -name FilterListManager.java | grep -q . || fail "FilterListManager.java not found."
    grep -rq "FilterListManager" --include=CoreService.java . || fail "FilterListManager not referenced from CoreService."
    grep -rq "addList" --include=FilterListManager.java . || fail "addList() not found in FilterListManager."
    ;;
  1)
    grep -rq "POS_PROTECTION" --include=FirewallActivity.java . || fail "POS_PROTECTION not found in FirewallActivity."
    find . -name "ProtectionFragment*.java" | grep -q . || fail "ProtectionFragment not found."
    for k in pref_protect_adblock pref_protect_tracking pref_protect_annoyance pref_protect_dns pref_protect_firewall pref_protect_security; do
      grep -rq "$k" --include=Prefs.java . || echo "  note: expected pref key '$k' not found — confirm your naming matches the 6 toggles in PLAN.md" >&2
    done
    grep -rq "mergeEnabledLists" --include="*.java" . || fail "mergeEnabledLists() (or your real equivalent — edit this check if renamed) not found."
    ;;
  2)
    find . -name BypassManager.java | grep -q . || fail "BypassManager.java not found."
    grep -rl "com.android.vending" --include="*.java" . | grep -q . || fail "Play Store UID allowlist entry not found."
    grep -rl "googlevideo.com" --include="*.java" . | grep -q . || fail "googlevideo.com allowlist entry not found."
    grep -rq "5228" --include="*.java" . || fail "FCM port bypass (5228) not found."
    ;;
  3)
    grep -rq "filterHttps" --include="*.java" . || fail "filterHttps flag not referenced in the capture path."
    ;;
  4)
    grep -rq "rulesPath" --include="MitmAPI*.java" . || fail "rulesPath field not found in MitmAPI.MitmConfig."
    grep -rq "pref_global_https_filtering" --include="*.java" --include="*.xml" . || fail "pref_global_https_filtering not found."
    grep -rq "needsSetup" --include="*.java" . || fail "MitmAddon.needsSetup() gate not found."
    ;;
  *)
    echo "verify-phase-gate: unrecognized phase $PHASE, skipping phase-specific checks." >&2
    ;;
esac

if [ "$FAIL" -ne 0 ]; then
  echo "---" >&2
  echo "This hook only covers code-level wiring + the release build." >&2
  echo "Testing Gate items (manual/network/E2E/CI) are NOT checked here — verify and report those yourself." >&2
  exit 2
fi

echo "PHASE $PHASE: mechanical checks clear. Confirm you've also run and reported this phase's Testing Gate before advancing current_phase." >&2
exit 0
