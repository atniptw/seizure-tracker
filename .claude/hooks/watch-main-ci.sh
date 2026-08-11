#!/usr/bin/env bash
# PostToolUse hook (Bash: git push*, gh pr merge*) — after commits land on
# main, surface the freshly-triggered CI run so Claude watches it to
# completion and fixes failures instead of walking away after the push.
#
# mode=gitpush: only proceed if the local branch is main post-push (cheap,
#   no network, skips the common case of pushing a feature branch). The
#   target commit is the local HEAD.
# mode=ghmerge: gh pr merge always targets this repo's default branch, so
#   always proceed. The target commit is origin/main after a fetch.
#
# Rather than guessing "the CI run is fresh if it's under N seconds old"
# (GitHub can take longer than a short sleep to register a new workflow run,
# causing false negatives), this polls gh run list until it finds a run
# whose headSha matches the commit we just pushed.
set -euo pipefail

mode="${1:-gitpush}"

stdin_json=$(cat)
success=$(echo "$stdin_json" | jq -r '.tool_response.success // true')
[ "$success" = "true" ] || exit 0

if [ "$mode" = "gitpush" ]; then
  current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
  [ "$current_branch" = "main" ] || exit 0
  target_sha=$(git rev-parse HEAD 2>/dev/null || echo "")
else
  git fetch origin main -q 2>/dev/null || exit 0
  target_sha=$(git rev-parse origin/main 2>/dev/null || echo "")
fi
[ -n "$target_sha" ] || exit 0

run_json=""
for attempt in 1 2 3 4 5 6 7; do
  [ "$attempt" -eq 1 ] || sleep 3
  candidate=$(gh run list --branch main --workflow ci.yml --limit 5 \
    --json databaseId,createdAt,url,headSha 2>/dev/null) || candidate=""
  run_json=$(echo "$candidate" | jq -c --arg sha "$target_sha" '[.[] | select(.headSha == $sha)] | .[0] // empty')
  [ -n "$run_json" ] && [ "$run_json" != "null" ] && break
done

[ -n "$run_json" ] && [ "$run_json" != "null" ] || exit 0

run_id=$(echo "$run_json" | jq -r '.databaseId')
url=$(echo "$run_json" | jq -r '.url')

jq -n --arg url "$url" --arg id "$run_id" '{
  hookSpecificOutput: {
    hookEventName: "PostToolUse",
    additionalContext: ("A CI run was just triggered on main: \($url) (run id \($id)). Watch it to completion (e.g. `gh run watch \($id) --exit-status`) and if it fails, diagnose and fix the failure, then push the fix.")
  }
}'
