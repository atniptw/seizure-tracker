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

# Merge-gate markers: present but older than HEAD is worse than absent, because a
# stale PASS looks like a fresh one at a glance.
head_time=$(git log -1 --format=%ct HEAD 2>/dev/null || echo 0)
for f in .claude/team/review-verdict.md .claude/team/last-green; do
  [ -f "$f" ] || continue
  t=$(stat -f %m "$f" 2>/dev/null || stat -c %Y "$f" 2>/dev/null || echo 0)
  [ "${t:-0}" -lt "${head_time:-0}" ] && add "$f is older than HEAD — stale, and the merge gate will refuse a code push."
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
