#!/usr/bin/env bash
# SubagentStop hook — append an entry to the team log each time a named team
# specialist finishes, so the hub-and-spoke handoffs leave a paper trail in
# .claude/team/log/<date>.md (gitignored, local; see planning/claude-dev-team.md).
#
# Only the six personas are journalled. Logging every subagent — Explore, Plan,
# general-purpose, whatever a one-off search spawned — buried the actual handoffs
# in noise: 91KB in a single day, which is the file /standup is supposed to skim.
#
# Best-effort: if the transcript can't be parsed, log a bare entry and exit 0.
# Never blocks.
set -uo pipefail

input=$(cat)
cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

agent=$(printf '%s' "$input" | jq -r '.agent_type // ""')
case "$agent" in
  flutter-dev|qa|reviewer|rules-engineer|migration-lead|backlog-owner|platform-parity|release-manager) ;;
  *) exit 0 ;;
esac

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
  printf '\n## %s UTC — %s\n' "$(date -u +%H:%M)" "$agent"
  printf '**Task:** %s\n\n' "$task"
  printf '**Result:** %s\n' "$result"
} >> "$logfile" 2>/dev/null || true

exit 0
