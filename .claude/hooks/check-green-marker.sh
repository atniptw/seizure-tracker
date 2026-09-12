#!/usr/bin/env bash
# PreToolUse hook (Bash: git push*) — hard-block a push to main that changes code
# unless a green test marker exists AND it covers the code being pushed.
#
# Pairs with check-review-verdict.sh. `qa` records .claude/team/last-green only
# after a fully green run for the change under review.
#
# Scope, the code-path list, the two-roots cwd handling and the coverage rule all
# live in gate-common.sh — one copy, shared with the verdict hook.
#
# Escape hatch: last-green is gitignored and human-writable — if you ran the suite
# yourself and it passed:
#
#   .claude/hooks/team-marker.sh green "./gradlew test — 412/412"
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gate-common.sh"

gate_preamble

marker="$GATE_TEAM/last-green"

[ -f "$marker" ] || gate_block "Merge gate: no green test marker.

This push changes code and would update main, but
$marker
does not exist. Have qa run the suite for this change — it records the marker on a
green run. If you ran the tests yourself and they passed:

  .claude/hooks/team-marker.sh green \"what you ran\""

if ! reason=$(gate_covers_head "$marker" "$GATE_REPO" "The green test marker"); then
  gate_block "Merge gate: $reason"
fi

exit 0
