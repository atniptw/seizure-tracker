#!/usr/bin/env bash
# Stop hook — when the session ends with commits on `main` that were never
# pushed, block the stop and tell Claude to push them.
#
# Rationale: this repo's workflow is commit -> push to main -> watch CI (see
# .claude/hooks/watch-main-ci.sh). A commit that never reaches origin/main
# never gets CI'd (branch protection's `build` check runs post-push) and never
# gets picked up by release.yml, so a stranded local commit is almost always a
# mistake worth surfacing before the session closes.
#
# Scope: unpushed commits only. A merely-dirty working tree (unstaged edits,
# untracked files) is common and frequently deliberate, so it is NOT flagged.
#
# Loop guard: Claude Code sets stop_hook_active when the current stop was
# itself triggered by a Stop hook continuation — bail in that case so we never
# force an infinite continue loop.
set -uo pipefail

input=$(cat)

if [ "$(printf '%s' "$input" | jq -r '.stop_hook_active // false')" = "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
[ "$branch" = "main" ] || exit 0

# Needs an upstream to compare against.
git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1 || exit 0

ahead=$(git rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)
[ "${ahead:-0}" -gt 0 ] 2>/dev/null || exit 0

commits=$(git log --oneline --no-decorate '@{u}..HEAD' 2>/dev/null || true)

reason="${ahead} commit(s) on main are not pushed to origin:

${commits}

Push them with \`git push origin main\` so CI runs (branch protection enforces
the build check post-push) and release.yml can pick them up — then watch the CI
run to completion. If leaving them local is intentional, say so and stop again."

jq -n --arg r "$reason" '{decision: "block", reason: $r}'
