#!/usr/bin/env bash
# PreToolUse hook (Bash: git push*) — hard-block a push to main unless a fresh
# PASS review verdict exists.
#
# The team's merge gate (see planning/claude-dev-team.md): a code change reaches
# main only after `reviewer` writes `Status: PASS` to
# .claude/team/review-verdict.md AND `qa` writes a fresh .claude/team/last-green
# (that half is check-green-marker.sh).
#
# Scope: only a push that updates origin/main. Pushing a worktree/topic branch is
# never gated. A pushed diff that touches no code paths (docs, planning/,
# .claude/, .github/ only) is exempt.
#
# Escape hatch: review-verdict.md is gitignored and human-writable — if you (Tom)
# are the reviewer, write `Status: PASS` to it yourself, then push.
set -uo pipefail

input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')

case "$cmd" in
  *"git push"*) ;;
  *) exit 0 ;;
esac

# Two roots, deliberately:
#   $proj — the main checkout, where the shared team markers live. CLAUDE_PROJECT_DIR
#           stays at the session's starting checkout even after EnterWorktree, which is
#           what we want for the markers: reviewer and qa produce one canonical pair.
#   $repo — the checkout whose push we are gating. The hook's stdin `cwd` follows Claude
#           into a worktree, so this is where the pushed commits actually live.
#
# Probing git in $proj for both (as this hook used to) made a push issued from a
# worktree read main's HEAD instead: origin/main..HEAD came back empty, no code paths
# matched, and the docs-only exemption below let the push through — the gate failed
# OPEN, in the one direction a gate must not. Same cwd trap that watch-main-ci.sh
# documents; the fix did not reach here until it was audited.
proj="${CLAUDE_PROJECT_DIR:-.}"
repo=$(printf '%s' "$input" | jq -r '.cwd // ""')
[ -n "$repo" ] && [ -d "$repo" ] || repo="$proj"
cd "$proj" 2>/dev/null || exit 0

# Only gate a push that lands on main: the command names main explicitly
# (`git push origin HEAD:main`), sweeps every ref (`--all`/`--mirror`), or has no
# refspec at all while we sit on main or on a branch tracking origin/main.
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

# What would this push add to main?
range="origin/main..HEAD"
git -C "$repo" rev-parse origin/main >/dev/null 2>&1 || range="HEAD"
changed=$(git -C "$repo" diff --name-only $range 2>/dev/null || true)
code=$(printf '%s\n' "$changed" | grep -E '^(app/|lib/|test/|integration_test/|firestore\.rules$|firestore-tests/)' || true)
[ -n "$code" ] || exit 0   # no code touched — merge gate does not apply

verdict=".claude/team/review-verdict.md"

block() {
  jq -n --arg r "$1" '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $r}}'
  exit 0
}

[ -f "$verdict" ] || block "Merge gate: no review verdict.

This push touches code and would update main, but .claude/team/review-verdict.md
does not exist. Run /review (spawns the reviewer persona) and land a 'Status: PASS'
verdict first. If you are the reviewer, write the verdict file yourself."

status=$(grep -iE '^[[:space:]]*Status:' "$verdict" | head -1 | sed -E 's/.*[Ss]tatus:[[:space:]]*//' | tr -d '[:space:]')
if ! printf '%s' "$status" | grep -qiE '^PASS$'; then
  block "Merge gate: review verdict is not PASS (found: '${status:-none}').

Resolve the [blocking] findings in .claude/team/review-verdict.md and have the
reviewer re-run, or — if you are the reviewer — update the verdict."
fi

head_time=$(git -C "$repo" log -1 --format=%ct HEAD 2>/dev/null || echo 0)
verdict_time=$(stat -f %m "$verdict" 2>/dev/null || stat -c %Y "$verdict" 2>/dev/null || echo 0)
if [ "${verdict_time:-0}" -lt "${head_time:-0}" ]; then
  block "Merge gate: the review verdict is older than the commit(s) being pushed.

Re-review the current diff (/review) so the verdict reflects what actually goes to main."
fi

exit 0
