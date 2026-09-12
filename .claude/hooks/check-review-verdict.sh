#!/usr/bin/env bash
# PreToolUse hook (Bash: git push*) — hard-block a push to main unless a review
# verdict of Status: PASS exists AND it covers the code being pushed.
#
# The team's merge gate (see planning/claude-dev-team.md): a code change reaches
# main only after `reviewer` records Status: PASS AND `qa` records a green run
# (that half is check-green-marker.sh).
#
# Scope, the code-path list, the two-roots cwd handling and the coverage rule all
# live in gate-common.sh — one copy, shared with the green-marker hook.
#
# Escape hatch: review-verdict.md is gitignored and human-writable — if you (Tom)
# are the reviewer, record the verdict yourself:
#
#   .claude/hooks/team-marker.sh verdict-pass "reviewed by hand"
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gate-common.sh"

gate_preamble

verdict="$GATE_TEAM/review-verdict.md"

[ -f "$verdict" ] || gate_block "Merge gate: no review verdict.

This push touches code and would update main, but
$verdict
does not exist. Run /review (spawns the reviewer persona) and land a 'Status: PASS'
verdict first. If you are the reviewer, record it yourself:

  .claude/hooks/team-marker.sh verdict-pass \"reviewed by hand\""

status=$(gate_field "$verdict" "Status")
if ! printf '%s' "$status" | grep -qiE '^PASS$'; then
  gate_block "Merge gate: review verdict is not PASS (found: '${status:-none}').

Resolve the [blocking] findings in
$verdict
and have the reviewer re-run, or — if you are the reviewer — update the verdict."
fi

if ! reason=$(gate_covers_head "$verdict" "$GATE_REPO" "The review verdict"); then
  gate_block "Merge gate: $reason"
fi

exit 0
