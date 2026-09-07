#!/usr/bin/env bash
# PreToolUse hook (Bash: git push*) — hard-block a push to main that changes code
# when no fresh green test marker exists.
#
# Pairs with check-review-verdict.sh. `qa` writes .claude/team/last-green (a UTC
# timestamp + context) only after a fully green run for the change under review.
#
# Same scoping as the verdict hook: only pushes that update origin/main, only when
# the pushed diff touches code paths.
#
# Escape hatch: last-green is gitignored and human-writable — if you ran the suite
# yourself and it passed, write a UTC timestamp to it.
set -uo pipefail

input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')
case "$cmd" in
  *"git push"*) ;;
  *) exit 0 ;;
esac

# Two roots, deliberately — see the long note in check-review-verdict.sh.
#   $proj — main checkout, where the one canonical marker lives.
#   $repo — the checkout whose push we are gating (follows Claude into a worktree).
proj="${CLAUDE_PROJECT_DIR:-.}"
repo=$(printf '%s' "$input" | jq -r '.cwd // ""')
[ -n "$repo" ] && [ -d "$repo" ] || repo="$proj"
cd "$proj" 2>/dev/null || exit 0

branch=$(git -C "$repo" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
case "$cmd" in
  *" main"*|*":main"*|*" --all"*|*" --mirror"*) targets_main=1 ;;
  *)
    if [ "$branch" = "main" ]; then
      targets_main=1
    else
      upstream=$(git -C "$repo" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || echo "")
      case "$upstream" in */main) targets_main=1 ;; *) targets_main=0 ;; esac
    fi
    ;;
esac
[ "$targets_main" = "1" ] || exit 0

range="origin/main..HEAD"
git -C "$repo" rev-parse origin/main >/dev/null 2>&1 || range="HEAD"
changed=$(git -C "$repo" diff --name-only $range 2>/dev/null || true)
code=$(printf '%s\n' "$changed" | grep -E '^(app/|lib/|test/|integration_test/|firestore\.rules$|firestore-tests/)' || true)
[ -n "$code" ] || exit 0

marker=".claude/team/last-green"

block() {
  jq -n --arg r "$1" '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $r}}'
  exit 0
}

[ -f "$marker" ] || block "Merge gate: no green test marker.

This push changes code and would update main, but .claude/team/last-green does not
exist. Have qa run the suite for this change — it writes the marker on a green run.
If you ran the tests yourself and they passed, write a UTC timestamp to that file."

head_time=$(git -C "$repo" log -1 --format=%ct HEAD 2>/dev/null || echo 0)
marker_time=$(stat -f %m "$marker" 2>/dev/null || stat -c %Y "$marker" 2>/dev/null || echo 0)
if [ "${marker_time:-0}" -lt "${head_time:-0}" ]; then
  block "Merge gate: the green test marker is older than the commit(s) being pushed.

Re-run the suite (qa) against the current diff so .claude/team/last-green reflects it."
fi

exit 0
