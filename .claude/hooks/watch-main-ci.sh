#!/usr/bin/env bash
# PostToolUse hook (Bash: git push*, gh pr merge*) — after commits land on
# main, surface the freshly-triggered CI run so Claude watches it to
# completion and fixes failures instead of walking away after the push.
#
# mode=gitpush: only proceed if the local branch is main post-push (cheap,
#   no network, skips the common case of pushing a feature branch).
# mode=ghmerge: gh pr merge always targets this repo's default branch, so
#   always proceed.
set -euo pipefail

mode="${1:-gitpush}"

stdin_json=$(cat)
success=$(echo "$stdin_json" | jq -r '.tool_response.success // true')
[ "$success" = "true" ] || exit 0

if [ "$mode" = "gitpush" ]; then
  current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
  [ "$current_branch" = "main" ] || exit 0
fi

sleep 2

run_json=$(gh run list --branch main --workflow ci.yml --limit 1 \
  --json databaseId,createdAt,url 2>/dev/null) || exit 0

created_at=$(echo "$run_json" | jq -r '.[0].createdAt // empty')
[ -n "$created_at" ] || exit 0

created_epoch=$(date -d "$created_at" +%s 2>/dev/null || date -j -f "%Y-%m-%dT%H:%M:%SZ" "$created_at" +%s 2>/dev/null) || exit 0
now_epoch=$(date +%s)
age=$(( now_epoch - created_epoch ))
[ "$age" -le 120 ] || exit 0

run_id=$(echo "$run_json" | jq -r '.[0].databaseId')
url=$(echo "$run_json" | jq -r '.[0].url')

jq -n --arg url "$url" --arg id "$run_id" '{
  hookSpecificOutput: {
    hookEventName: "PostToolUse",
    additionalContext: ("A CI run was just triggered on main: \($url) (run id \($id)). Watch it to completion (e.g. `gh run watch \($id) --exit-status`) and if it fails, diagnose and fix the failure, then push the fix.")
  }
}'
