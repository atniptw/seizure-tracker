#!/usr/bin/env bash
# SessionStart hook — surface stranded team state at the top of a session.
#
# /standup reports all of this and more, but only when someone remembers to run
# it. The states below are the ones that are silently costly: commits sitting on
# main that never reached CI, a stale PASS verdict that a later commit invalidated,
# and worktrees left behind after their work merged (which make /standup's "in
# flight" section read as busy when nothing is).
#
# Advisory only: prints context, never blocks. Says nothing when all is well, so
# a clean repo starts a clean session.
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

notes=""
add() { notes="${notes}- $1
"; }

# Commits on main that never reached origin — they get no CI and no release pickup.
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
if [ "$branch" = "main" ] && git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then
  ahead=$(git rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)
  [ "${ahead:-0}" -gt 0 ] 2>/dev/null && add "$ahead commit(s) on main are unpushed — no CI has run on them."
fi

# Merge-gate markers. This used to compare each marker's mtime against main's HEAD
# and call anything older "stale" — which was noise, because the markers routinely
# and correctly describe a worktree branch rather than main. The gate's own rule is
# whether a marker covers the code being pushed, and that can only be judged from
# the checkout doing the pushing, which is not necessarily this one.
#
# So report what the markers point at, and warn only about the state that is
# genuinely broken everywhere: a marker that names no commit at all, which the gate
# refuses from any checkout.
for f in .claude/team/review-verdict.md .claude/team/last-green; do
  [ -f "$f" ] || continue
  sha=$(grep -iE '^[[:space:]]*Commit:' "$f" 2>/dev/null | tail -1 \
        | sed -E 's/^[[:space:]]*[^:]*:[[:space:]]*//' | tr -d '[:space:]')
  if [ -z "$sha" ]; then
    add "$f names no commit — the merge gate will refuse a code push. Re-record it with .claude/hooks/team-marker.sh."
  elif ! git merge-base --is-ancestor "$sha" HEAD 2>/dev/null; then
    desc=$(git describe --all --always "$sha" 2>/dev/null || echo "$sha")
    add "$f covers $desc, which is not in main yet — it is for work still in flight, not for a push from here."
  fi
done

# Worktrees whose branch is fully merged into main: finished work still on disk.
while read -r wt; do
  [ -n "$wt" ] || continue
  wb=$(git -C "$wt" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
  [ -n "$wb" ] || continue
  # The primary checkout is on main and is trivially an ancestor of itself; it is
  # not a prunable worktree. Only branches off main can be.
  [ "$wb" = "main" ] && continue
  if git merge-base --is-ancestor "$wb" main 2>/dev/null; then
    add "worktree $(basename "$wt") ($wb) is fully merged into main — prunable."
  fi
done <<EOF
$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print $2}')
EOF

[ -n "$notes" ] || exit 0

printf '%s' "$notes" | jq -Rs '{hookSpecificOutput: {hookEventName: "SessionStart", additionalContext: ("Team state needing attention (advisory — from .claude/hooks/session-brief.sh; run /standup for the full picture):\n" + .)}}'
exit 0
