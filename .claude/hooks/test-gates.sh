#!/usr/bin/env bash
# Test harness for the two merge-gate hooks.
#
# Why this exists: the gate is the only hard control on what reaches main, and the
# scenario it most needs to catch — a push issued from a worktree — is exactly the
# one that used to slip through, because both hooks probed git in CLAUDE_PROJECT_DIR
# (the main checkout) rather than in the checkout being pushed. That bug was invisible
# in ordinary use: the gate stayed silent, which reads the same as "allowed".
#
# So: drive the hooks with synthetic PreToolUse JSON against throwaway repos and
# assert on the decision. Run after touching either hook.
#
#   .claude/hooks/test-gates.sh
#
# Exits non-zero on the first unmet expectation.
set -uo pipefail

HOOKS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERDICT_HOOK="$HOOKS_DIR/check-review-verdict.sh"
GREEN_HOOK="$HOOKS_DIR/check-green-marker.sh"

TMP=$(mktemp -d "${TMPDIR:-/tmp}/gate-test.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0

# --- fixture -----------------------------------------------------------------
# A bare origin, a clone standing in for the main checkout, and a worktree on a
# topic branch — the same shape the real team flow produces.
git init -q --bare "$TMP/origin.git"
git clone -q "$TMP/origin.git" "$TMP/main-checkout" 2>/dev/null
MAIN="$TMP/main-checkout"
git -C "$MAIN" config user.email t@example.com
git -C "$MAIN" config user.name tester
mkdir -p "$MAIN/app" "$MAIN/planning" "$MAIN/.claude/team"
echo baseline > "$MAIN/app/Baseline.kt"
echo seed > "$MAIN/planning/seed.md"   # git tracks files, not dirs: the docs-only
                                       # worktree needs planning/ to already exist
git -C "$MAIN" add -A
git -C "$MAIN" commit -qm baseline
git -C "$MAIN" branch -M main
git -C "$MAIN" push -q origin main 2>/dev/null

# Worktree with a CODE change (app/) — the gate must apply.
git -C "$MAIN" worktree add -q -b topic-code "$TMP/wt-code" main
echo change > "$TMP/wt-code/app/Feature.kt"
git -C "$TMP/wt-code" add -A
git -C "$TMP/wt-code" commit -qm "code change"

# Worktree with a DOCS-ONLY change — the gate must stay out of the way.
git -C "$MAIN" worktree add -q -b topic-docs "$TMP/wt-docs" main
echo doc > "$TMP/wt-docs/planning/notes.md"
git -C "$TMP/wt-docs" add -A
git -C "$TMP/wt-docs" commit -qm "docs change"

VERDICT="$MAIN/.claude/team/review-verdict.md"
MARKER="$MAIN/.claude/team/last-green"

fresh_markers() {
  printf 'Status: PASS\nBranch: topic-code\n' > "$VERDICT"
  date -u +%Y-%m-%dT%H:%M:%SZ > "$MARKER"
}
clear_markers() { rm -f "$VERDICT" "$MARKER"; }

# --- runner ------------------------------------------------------------------
# $1 label, $2 hook, $3 cwd for the hook input, $4 command, $5 expected deny|allow
check() {
  local label="$1" hook="$2" cwd="$3" cmdstr="$4" want="$5"
  local out got
  out=$(printf '{"cwd":"%s","tool_input":{"command":"%s"}}' "$cwd" "$cmdstr" \
        | CLAUDE_PROJECT_DIR="$MAIN" bash "$hook" 2>/dev/null)
  if printf '%s' "$out" | grep -q '"permissionDecision": *"deny"'; then got=deny; else got=allow; fi
  if [ "$got" = "$want" ]; then
    printf '  ok    %-58s %s\n' "$label" "$got"; pass=$((pass+1))
  else
    printf '  FAIL  %-58s want=%s got=%s\n' "$label" "$want" "$got"; fail=$((fail+1))
  fi
}

echo "check-review-verdict.sh"
clear_markers
check "worktree, code diff, no verdict"        "$VERDICT_HOOK" "$TMP/wt-code" "git push origin HEAD:main" deny
check "worktree, docs-only diff, no verdict"   "$VERDICT_HOOK" "$TMP/wt-docs" "git push origin HEAD:main" allow
check "not a push at all"                      "$VERDICT_HOOK" "$TMP/wt-code" "git status"                allow
check "push --all with code diff, no verdict"  "$VERDICT_HOOK" "$TMP/wt-code" "git push --all origin"     deny
fresh_markers
check "worktree, code diff, fresh PASS"        "$VERDICT_HOOK" "$TMP/wt-code" "git push origin HEAD:main" allow
printf 'Status: CHANGES\n' > "$VERDICT"
check "worktree, code diff, verdict=CHANGES"   "$VERDICT_HOOK" "$TMP/wt-code" "git push origin HEAD:main" deny
fresh_markers
touch -t 200001010000 "$VERDICT"
check "worktree, code diff, stale verdict"     "$VERDICT_HOOK" "$TMP/wt-code" "git push origin HEAD:main" deny

# The normal flow: Tech Lead merges the topic branch into main, then pushes from the
# main checkout. main must actually be ahead of origin/main for the gate to see code.
git -C "$MAIN" merge -q --ff-only topic-code
echo "check-review-verdict.sh (from the main checkout, post-merge)"
clear_markers
check "main checkout, code diff, no verdict"   "$VERDICT_HOOK" "$MAIN" "git push origin main" deny
fresh_markers
check "main checkout, code diff, fresh PASS"   "$VERDICT_HOOK" "$MAIN" "git push origin main" allow
check "main checkout, bare push, fresh PASS"   "$VERDICT_HOOK" "$MAIN" "git push"             allow
clear_markers
check "main checkout, bare push, no verdict"   "$VERDICT_HOOK" "$MAIN" "git push"             deny

echo "check-green-marker.sh"
clear_markers
check "worktree, code diff, no marker"         "$GREEN_HOOK" "$TMP/wt-code" "git push origin HEAD:main" deny
check "worktree, docs-only diff, no marker"    "$GREEN_HOOK" "$TMP/wt-docs" "git push origin HEAD:main" allow
fresh_markers
check "worktree, code diff, fresh marker"      "$GREEN_HOOK" "$TMP/wt-code" "git push origin HEAD:main" allow
touch -t 200001010000 "$MARKER"
check "worktree, code diff, stale marker"      "$GREEN_HOOK" "$TMP/wt-code" "git push origin HEAD:main" deny

echo
if [ "$fail" -gt 0 ]; then
  echo "FAILED: $fail of $((pass+fail))"
  exit 1
fi
echo "passed: $pass/$pass"
