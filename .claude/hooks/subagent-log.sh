#!/usr/bin/env bash
# SubagentStop hook — append an entry to the team log each time a subagent
# finishes, so the hub-and-spoke handoffs leave a paper trail in
# .claude/team/log/<date>.md (committed; see planning/claude-dev-team.md).
#
# Best-effort: if the transcript can't be parsed, log a bare entry and exit 0.
# Never blocks.
set -uo pipefail

input=$(cat)
cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

logdir=".claude/team/log"
mkdir -p "$logdir" 2>/dev/null || exit 0
logfile="$logdir/$(date -u +%Y-%m-%d).md"

transcript=$(printf '%s' "$input" | jq -r '.transcript_path // ""')

task="(task prompt unavailable)"
result="(result unavailable)"
if [ -n "$transcript" ] && [ -f "$transcript" ]; then
  task=$(jq -rs '
    [ .[]
      | select(.type=="user")
      | .message.content
      | if type=="array" then (map(.text // empty) | join(" ")) else (. // "" | tostring) end
    ] | map(select(length>0)) | .[0] // ""' "$transcript" 2>/dev/null \
    | tr '\n' ' ' | sed -E 's/  +/ /g' | cut -c1-300)
  result=$(jq -rs '
    [ .[]
      | select(.type=="assistant")
      | .message.content[]?
      | select(.type=="text")
      | .text
    ] | .[-1] // ""' "$transcript" 2>/dev/null \
    | tr '\n' ' ' | sed -E 's/  +/ /g' | cut -c1-900)
fi

[ -n "$task" ] || task="(task prompt unavailable)"
[ -n "$result" ] || result="(result unavailable)"

{
  printf '\n## %s UTC — subagent\n' "$(date -u +%H:%M)"
  printf '**Task:** %s\n\n' "$task"
  printf '**Result:** %s\n' "$result"
} >> "$logfile" 2>/dev/null || true

exit 0
