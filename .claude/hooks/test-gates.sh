#!/usr/bin/env bash
# Test harness for the merge gate — the two PreToolUse hooks and the team-marker.sh
# writer they read.
#
# Why this exists: the gate is the only hard control on what reaches main, and its
# failures have all been silent ones. A hook that probes the wrong checkout, a
# marker written where the gate never looks, a code path missing from the filter —
# each of those makes the gate say nothing, which reads exactly like "allowed".
#
# So: drive the hooks with synthetic PreToolUse JSON against throwaway repos and
# assert on the decision, in BOTH directions. A gate that never blocks and a gate
# that always blocks are equally useless, and the second one is what makes people
# start reaching for the escape hatch by reflex.
#
#   .claude/hooks/test-gates.sh
#
# Exits non-zero on the first unmet expectation.
set -uo pipefail

HOOKS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERDICT_HOOK="$HOOKS_DIR/check-review-verdict.sh"
GREEN_HOOK="$HOOKS_DIR/check-green-marker.sh"
MARKER_TOOL="$HOOKS_DIR/team-marker.sh"

TMP=$(mktemp -d "${TMPDIR:-/tmp}/gate-test.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0

# --- fixture -----------------------------------------------------------------
# A bare origin, a clone standing in for the main checkout, and worktrees on topic
# branches — the same shape the real team flow produces.
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
BASELINE=$(git -C "$MAIN" rev-parse HEAD)

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

# Worktree with a TOOLS change (tools/migrate/) — the migration backup/restore
# scripts get pointed at two real users' only copy of their health history, so the
# gate must cover them exactly as it covers app/. It did not until issue #5: the
# path list predated the directory, so the whole of tools/ pushed unguarded.
git -C "$MAIN" worktree add -q -b topic-tools "$TMP/wt-tools" main
mkdir -p "$TMP/wt-tools/tools/migrate"
echo tool > "$TMP/wt-tools/tools/migrate/backup.js"
git -C "$TMP/wt-tools" add -A
git -C "$TMP/wt-tools" commit -qm "tooling change"

VERDICT="$MAIN/.claude/team/review-verdict.md"
MARKER="$MAIN/.claude/team/last-green"

# Record markers the way team-marker.sh does: naming the commit they cover.
# $1 = checkout whose HEAD the marker attests to, $2 = Status (verdict only).
write_verdict() {
  local repo="$1" status="${2:-PASS}" sha tree
  sha=$(git -C "$repo" rev-parse HEAD)
  tree=$(git -C "$repo" rev-parse 'HEAD^{tree}')
  { printf 'Status: %s\nBranch: fixture\nScope: fixture verdict\n' "$status"
    printf '\n<!-- recorded by team-marker.sh -->\n'
    printf 'Commit: %s\nTree: %s\n' "$sha" "$tree"; } > "$VERDICT"
}
write_green() {
  local repo="$1" sha tree
  sha=$(git -C "$repo" rev-parse HEAD)
  tree=$(git -C "$repo" rev-parse 'HEAD^{tree}')
  { date -u +%Y-%m-%dT%H:%M:%SZ
    printf 'Commit: %s\nTree: %s\nRan: fixture\n' "$sha" "$tree"; } > "$MARKER"
}
covering_markers() { write_verdict "$1" PASS; write_green "$1"; }
clear_markers()    { rm -f "$VERDICT" "$MARKER"; }

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

# $1 label, $2 condition already evaluated as 0/1
assert() {
  if [ "$2" = 0 ]; then
    printf '  ok    %-58s %s\n' "$1" "true"; pass=$((pass+1))
  else
    printf '  FAIL  %-58s %s\n' "$1" "expected true"; fail=$((fail+1))
  fi
}

echo "check-review-verdict.sh — scope"
clear_markers
check "worktree, code diff, no verdict"        "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" deny
check "worktree, docs-only diff, no verdict"   "$VERDICT_HOOK" "$TMP/wt-docs"  "git push origin HEAD:main" allow
check "not a push at all"                      "$VERDICT_HOOK" "$TMP/wt-code"  "git status"                allow
check "push --all with code diff, no verdict"  "$VERDICT_HOOK" "$TMP/wt-code"  "git push --all origin"     deny
check "worktree, tools/ diff, no verdict"      "$VERDICT_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" deny

echo "check-review-verdict.sh — status"
covering_markers "$TMP/wt-code"
check "code diff, PASS covering HEAD"          "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" allow
covering_markers "$TMP/wt-tools"
check "tools/ diff, PASS covering HEAD"        "$VERDICT_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" allow
write_verdict "$TMP/wt-code" CHANGES
check "code diff, verdict=CHANGES"             "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" deny

echo "check-review-verdict.sh — coverage (does the verdict describe THIS code?)"
# The issue-#4 shape: the review ran in the main checkout, so it PASSed the commit
# already on main rather than the change under review. Timestamps cannot see this;
# the commit it names can.
write_verdict "$MAIN" PASS
check "PASS, but for a different commit"       "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" deny

# The regression that motivated all of this: a PASS verdict left over from the
# previous issue used to satisfy the gate for the next one as long as its mtime was
# newer than HEAD, so `touch` laundered it. Now it cannot.
write_verdict "$MAIN" PASS
touch "$VERDICT"
check "PASS for another commit, freshly touched" "$VERDICT_HOOK" "$TMP/wt-code" "git push origin HEAD:main" deny

# ...and the other direction, which is the false block people were hitting: a
# verdict that genuinely covers the code is still good however old the file is.
covering_markers "$TMP/wt-code"
touch -t 200001010000 "$VERDICT"
check "PASS covering HEAD, ancient mtime"      "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" allow

printf 'Status: PASS\nBranch: fixture\n' > "$VERDICT"
check "PASS with no Commit: line"              "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" deny

printf 'Status: PASS\nCommit: deadbeefdeadbeefdeadbeefdeadbeefdeadbeef\n' > "$VERDICT"
check "PASS naming a commit that does not exist" "$VERDICT_HOOK" "$TMP/wt-code" "git push origin HEAD:main" deny

echo "check-review-verdict.sh — drift after review"
# A docs commit layered on a reviewed change must NOT invalidate the review: the
# reviewed code is still exactly the code that ships. This is the false block that
# made the gate feel arbitrary, and it is why the rule is a code diff, not a tree
# compare.
covering_markers "$TMP/wt-code"
echo note > "$TMP/wt-code/planning/after.md"
git -C "$TMP/wt-code" add -A
git -C "$TMP/wt-code" commit -qm "docs on top of a reviewed change"
check "docs commit on top of a covering PASS"  "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" allow
check "  ...and the green marker agrees"       "$GREEN_HOOK"   "$TMP/wt-code"  "git push origin HEAD:main" allow

# A code commit on top must invalidate it.
echo more > "$TMP/wt-code/app/Extra.kt"
git -C "$TMP/wt-code" add -A
git -C "$TMP/wt-code" commit -qm "unreviewed code on top"
check "code commit on top of a covering PASS"  "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" deny
check "  ...and the green marker agrees"       "$GREEN_HOOK"   "$TMP/wt-code"  "git push origin HEAD:main" deny

echo "check-review-verdict.sh — from the main checkout, post-merge"
# The normal flow: Tech Lead fast-forwards the topic branch into main, then pushes.
git -C "$MAIN" merge -q --ff-only topic-code
clear_markers
check "main checkout, code diff, no verdict"   "$VERDICT_HOOK" "$MAIN" "git push origin main" deny
covering_markers "$MAIN"
check "main checkout, code diff, covering PASS" "$VERDICT_HOOK" "$MAIN" "git push origin main" allow
check "main checkout, bare push, covering PASS" "$VERDICT_HOOK" "$MAIN" "git push"             allow
clear_markers
check "main checkout, bare push, no verdict"   "$VERDICT_HOOK" "$MAIN" "git push"             deny

# A verdict recorded against the topic branch tip still covers main after a
# fast-forward merge — same commit, so the handoff costs nothing.
covering_markers "$TMP/wt-code"
check "main checkout, verdict from the worktree" "$VERDICT_HOOK" "$MAIN" "git push origin main" allow

echo "check-green-marker.sh"
clear_markers
check "worktree, code diff, no marker"         "$GREEN_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" deny
check "worktree, docs-only diff, no marker"    "$GREEN_HOOK" "$TMP/wt-docs"  "git push origin HEAD:main" allow
write_green "$TMP/wt-tools"
check "worktree, tools/ diff, covering marker" "$GREEN_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" allow
write_green "$MAIN"
check "marker for a different commit"          "$GREEN_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" deny
write_green "$TMP/wt-tools"
touch -t 200001010000 "$MARKER"
check "covering marker, ancient mtime"         "$GREEN_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" allow

echo "team-marker.sh — writes where the gate reads"
# Issue #5 in miniature: qa ran in a worktree and wrote a relative path, so a real
# green run landed in the worktree and the gate went on reading the stale copy in
# the main checkout. The writer must resolve the main checkout from either place.
clear_markers
( cd "$TMP/wt-tools" && bash "$MARKER_TOOL" green "fixture suite 9/9" ) >/dev/null 2>&1
[ -f "$MARKER" ]; assert "green recorded from a worktree lands in main checkout" $?
[ ! -e "$TMP/wt-tools/.claude/team/last-green" ]; assert "  ...and NOT in the worktree" $?
check "green recorded in-place covers that push" "$GREEN_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" allow
check "  ...but not a push from another branch"  "$GREEN_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" deny

( cd "$TMP/wt-tools" && bash "$MARKER_TOOL" verdict-pass "fixture hand review" ) >/dev/null 2>&1
check "verdict-pass covers that push"            "$VERDICT_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" allow
check "  ...but not a push from another branch"  "$VERDICT_HOOK" "$TMP/wt-code"  "git push origin HEAD:main" deny

( cd "$TMP/wt-tools" && bash "$MARKER_TOOL" status ) >/dev/null 2>&1
rc=$?; [ "$rc" = 0 ]; assert "status agrees the gate would allow here" $?
( cd "$TMP/wt-code" && bash "$MARKER_TOOL" status ) >/dev/null 2>&1
rc=$?; [ "$rc" != 0 ]; assert "status agrees the gate would block elsewhere" $?

echo
if [ "$fail" -gt 0 ]; then
  echo "FAILED: $fail of $((pass+fail))"
  exit 1
fi
echo "passed: $pass/$pass"
