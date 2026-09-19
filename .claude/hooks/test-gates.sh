#!/usr/bin/env bash
# Test harness for the two merge-gate hooks and the outbound-content guard.
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
GUARD_HOOK="$HOOKS_DIR/scan-outgoing-ids.sh"
PREPUSH_HOOK="$(cd "$HOOKS_DIR/../.." && pwd)/.githooks/pre-push"

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

# Worktree with a TOOLS change (tools/migrate/) — the migration backup/restore
# scripts get pointed at two real users' only copy of their health history, so the
# gate must cover them exactly as it covers app/. It did not until issue #5: the
# path list predated the directory, so the whole of tools/ pushed unguarded.
git -C "$MAIN" worktree add -q -b topic-tools "$TMP/wt-tools" main
mkdir -p "$TMP/wt-tools/tools/migrate"
echo tool > "$TMP/wt-tools/tools/migrate/backup.js"
git -C "$TMP/wt-tools" add -A
git -C "$TMP/wt-tools" commit -qm "tooling change"


# --- outbound-content guard fixtures ------------------------------------------------------
# The guard must catch what the merge gate never looks at: a plain topic-branch push. Every
# leaky branch below is pushed to a topic ref, not main. Values are assembled at runtime so this
# file never contains a real-looking secret itself (scan-staged-secrets.sh would refuse to commit it).
LIVE_ID="TESTONLYlive0household01"                 # stands in for a denylisted real value
SHAPED_ID="aB3dE6gH9jK2mN5pQ8sT1vW4yZ7c"           # 28 chars, mixed case + digit: uid-shaped
mkdir -p "$MAIN/.claude/local" "$MAIN/.claude/hooks"
cp "$GUARD_HOOK" "$MAIN/.claude/hooks/scan-outgoing-ids.sh"   # the pre-push wrapper resolves the guard from the main checkout
printf '# test denylist\n%s\n' "$LIVE_ID" > "$MAIN/.claude/local/denylist.txt"

mkleak() {   # $1 branch, $2 path, $3 line, $4 commit message
  git -C "$MAIN" worktree add -q -b "$1" "$TMP/wt-$1" main
  mkdir -p "$(dirname "$TMP/wt-$1/$2")"
  printf '%s\n' "$3" > "$TMP/wt-$1/$2"
  git -C "$TMP/wt-$1" add -A
  git -C "$TMP/wt-$1" commit -qm "${4:-leak fixture}"
}
mkleak leak-literal planning/inventory.md "household $LIVE_ID has three members"
mkleak leak-shaped  planning/uids.md      "member uid $SHAPED_ID"
mkleak leak-apikey  planning/notes.md     "key $(printf 'AIza%s' "$(printf 'a%.0s' $(seq 35))")"
mkleak leak-pem     planning/notes2.md    "$(printf -- '-----BEGIN %s KEY-----' 'RSA PRIVATE')"
mkleak leak-message planning/fine.md      "nothing sensitive here" "Record the live household $LIVE_ID"
mkleak clean-lock   tools/migrate/package-lock.json "\"integrity\": \"sha512-$SHAPED_ID\""
mkleak clean-marked planning/marked.md    "example uid $SHAPED_ID id-scan:ignore"
mkleak clean-placeholder planning/ph.md   "household <LIVE-HOUSEHOLD-ID> (uid <UID-ANON>)"

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
  local label="$1" hook="$2" cwd="$3" cmdstr="$4" want="$5" needle="${6:-}"
  local out got
  out=$(printf '{"cwd":"%s","tool_input":{"command":"%s"}}' "$cwd" "$cmdstr" \
        | CLAUDE_PROJECT_DIR="$MAIN" bash "$hook" 2>/dev/null)
  if printf '%s' "$out" | grep -q '"permissionDecision": *"deny"'; then got=deny; else got=allow; fi
  if [ "$got" = "$want" ] && [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF "$needle"; then
    printf '  FAIL  %-58s denied, but not for the expected reason (%s)\n' "$label" "$needle"; fail=$((fail+1))
  elif [ "$got" = "$want" ]; then
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
check "worktree, tools/ diff, no verdict"      "$VERDICT_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" deny
fresh_markers
check "worktree, code diff, fresh PASS"        "$VERDICT_HOOK" "$TMP/wt-code" "git push origin HEAD:main" allow
check "worktree, tools/ diff, fresh PASS"      "$VERDICT_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" allow
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
check "worktree, tools/ diff, no marker"       "$GREEN_HOOK" "$TMP/wt-tools" "git push origin HEAD:main" deny
fresh_markers
check "worktree, code diff, fresh marker"      "$GREEN_HOOK" "$TMP/wt-code" "git push origin HEAD:main" allow
touch -t 200001010000 "$MARKER"
check "worktree, code diff, stale marker"      "$GREEN_HOOK" "$TMP/wt-code" "git push origin HEAD:main" deny


echo "scan-outgoing-ids.sh (Claude hook mode — topic-branch pushes, which the merge gate ignores)"
check "denylisted literal in a file"           "$GUARD_HOOK" "$TMP/wt-leak-literal" "git push origin leak-literal" deny "known live identifier"
check "denylisted literal, bare push"          "$GUARD_HOOK" "$TMP/wt-leak-literal" "git push"                     deny "known live identifier"
check "denylisted literal, HEAD:refspec"       "$GUARD_HOOK" "$TMP/wt-leak-literal" "git push origin HEAD:refs/heads/x" deny "known live identifier"
check "denylisted literal, -u and force flags" "$GUARD_HOOK" "$TMP/wt-leak-literal" "git push -u --force-with-lease origin leak-literal" deny "known live identifier"
check "denylisted literal, chained command"    "$GUARD_HOOK" "$TMP/wt-leak-literal" "cd . && git push origin leak-literal && echo done" deny "known live identifier"
check "denylisted literal, git -C form"        "$GUARD_HOOK" "$MAIN" "git -C $TMP/wt-leak-literal push origin leak-literal" deny "known live identifier"
check "leaky branch named from the main checkout" "$GUARD_HOOK" "$MAIN" "git push origin leak-literal"      deny "known live identifier"
check "push --all sweeps the leaky branches"   "$GUARD_HOOK" "$MAIN" "git push --all origin"                deny "known live identifier"
check "uid-shaped token in a .md file"         "$GUARD_HOOK" "$TMP/wt-leak-shaped" "git push origin leak-shaped" deny "id-shaped token"
check "Google API key"                         "$GUARD_HOOK" "$TMP/wt-leak-apikey" "git push origin leak-apikey" deny "Google API key"
check "PEM private key header"                 "$GUARD_HOOK" "$TMP/wt-leak-pem"    "git push origin leak-pem"    deny "private key material"
check "denylisted literal only in the message" "$GUARD_HOOK" "$TMP/wt-leak-message" "git push origin leak-message" deny "(commit message)"
check "same-shape token in package-lock.json"  "$GUARD_HOOK" "$TMP/wt-clean-lock"  "git push origin clean-lock"  allow
check "id-scan:ignore on the line"             "$GUARD_HOOK" "$TMP/wt-clean-marked" "git push origin clean-marked" allow
check "placeholders instead of values"         "$GUARD_HOOK" "$TMP/wt-clean-placeholder" "git push origin clean-placeholder" allow
check "clean main, leaky branches not named"   "$GUARD_HOOK" "$MAIN" "git push origin main"                 allow
check "not a push"                             "$GUARD_HOOK" "$TMP/wt-leak-literal" "git status"             allow
check "mentions push but is not git push"      "$GUARD_HOOK" "$TMP/wt-leak-literal" "echo git-pusher"         allow

# Once the leaky commit is on a remote it is already public: old history must not block new pushes.
git -C "$TMP/wt-leak-literal" push -q --no-verify origin leak-literal 2>/dev/null
check "already-pushed leak does not re-block"  "$GUARD_HOOK" "$TMP/wt-leak-literal" "git push origin leak-literal" allow

echo "scan-outgoing-ids.sh (git pre-push mode — covers Tom's terminal and subagent pushes alike)"
zero=0000000000000000000000000000000000000000
check_prepush() {   # $1 label, $2 cwd, $3 branch, $4 expected exit (0|1)
  local sha; sha=$(git -C "$2" rev-parse "$3")
  local out; out=$(printf 'refs/heads/%s %s refs/heads/%s %s\n' "$3" "$sha" "$3" "$zero" | (cd "$2" && bash "$PREPUSH_HOOK" origin url) 2>&1); local rc=$?
  if [ "$rc" = "$4" ]; then printf '  ok    %-58s exit %s\n' "$1" "$rc"; pass=$((pass+1))
  else printf '  FAIL  %-58s want=%s got=%s\n' "$1" "$4" "$rc"; fail=$((fail+1)); fi
  if [ "$4" = 1 ] && printf '%s' "$out" | grep -qF "$LIVE_ID"; then
    printf '  FAIL  %-58s report echoed the full value\n' "$1"; fail=$((fail+1)); fi
}
check_prepush "leaky topic branch, new ref"    "$TMP/wt-leak-shaped"      leak-shaped      1
check_prepush "denylisted literal, new ref"    "$TMP/wt-leak-message"     leak-message     1
check_prepush "placeholders"                   "$TMP/wt-clean-placeholder" clean-placeholder 0

echo
if [ "$fail" -gt 0 ]; then
  echo "FAILED: $fail of $((pass+fail))"
  exit 1
fi
echo "passed: $pass/$pass"
