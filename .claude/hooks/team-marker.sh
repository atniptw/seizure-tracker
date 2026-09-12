#!/usr/bin/env bash
# Record — and inspect — the two merge-gate markers.
#
# Run this instead of writing .claude/team/last-green or review-verdict.md by hand.
# Both markers must land in the MAIN checkout (where the hooks read them) and must
# name the commit they cover (that is what the gate checks). Getting either wrong
# has cost this team real time twice: a genuine green run that landed in a worktree
# the gate never looks at, and a five-day-old PASS verdict for a different issue
# that the gate could not tell from a fresh one. This script gets both right from
# any checkout, so nobody has to remember.
#
#   team-marker.sh path
#       Print the canonical .claude/team directory.
#
#   team-marker.sh green "<what you ran>"
#       Record a green test run for the current HEAD. qa runs this, only after a
#       fully green run of the suite the change actually needs.
#
#   team-marker.sh verdict <body-file>
#       Record a review verdict for the current HEAD. <body-file> is the verdict in
#       the shape reviewer.md specifies; its Status: line is preserved as written,
#       and Commit:/Tree: are normalised to this checkout's HEAD. Prints the path of
#       the canonical copy — post THAT file to the issue, so both copies agree.
#
#   team-marker.sh verdict-pass "<one-line scope>"
#       Shorthand for a human reviewer: record a minimal PASS verdict for HEAD.
#
#   team-marker.sh status
#       What the gate currently sees, and whether it covers HEAD. Run this before
#       pushing, or when a block message surprises you.
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gate-common.sh"

die() { printf 'team-marker: %s\n' "$1" >&2; exit 1; }

git rev-parse --git-dir >/dev/null 2>&1 || die "not inside a git repository"

TEAM=$(gate_team_dir ".") || die "could not resolve the team directory"
mkdir -p "$TEAM"

HEAD_SHA=$(git rev-parse HEAD 2>/dev/null) || die "cannot resolve HEAD"
HEAD_TREE=$(git rev-parse 'HEAD^{tree}')
BRANCH=$(git rev-parse --abbrev-ref HEAD)
TOPLEVEL=$(git rev-parse --show-toplevel)
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# A marker is only worth anything if it describes committed state. An agent that
# records one with the change still sitting in the working tree is attesting to
# something the gate cannot see and main will never receive.
warn_if_dirty() {
  local dirty
  dirty=$(git status --porcelain --untracked-files=no 2>/dev/null | head -5)
  [ -n "$dirty" ] || return 0
  printf 'team-marker: WARNING — uncommitted changes in %s:\n' "$TOPLEVEL" >&2
  printf '%s\n' "$dirty" | sed 's/^/  /' >&2
  printf 'team-marker: the marker covers commit %s, NOT those edits. Commit first.\n' \
    "$(git rev-parse --short HEAD)" >&2
}

report_where() {
  printf 'team-marker: recorded %s\n' "$1"
  printf '  checkout : %s (%s)\n' "$TOPLEVEL" "$BRANCH"
  printf '  commit   : %s\n' "$(git rev-parse --short HEAD)"
  printf '  written  : %s\n' "$1"
}

cmd="${1:-status}"
case "$cmd" in

  path)
    printf '%s\n' "$TEAM"
    ;;

  green)
    ran="${2:-}"
    [ -n "$ran" ] || die 'usage: team-marker.sh green "<what you ran>"'
    warn_if_dirty
    marker="$TEAM/last-green"
    {
      printf '%s\n' "$NOW"
      printf 'Branch: %s\n' "$BRANCH"
      printf 'Commit: %s\n' "$HEAD_SHA"
      printf 'Tree: %s\n' "$HEAD_TREE"
      printf 'Checkout: %s\n' "$TOPLEVEL"
      printf 'Ran: %s\n' "$ran"
    } > "$marker"
    report_where "$marker"
    ;;

  verdict)
    body="${2:-}"
    [ -n "$body" ] && [ -f "$body" ] || die 'usage: team-marker.sh verdict <body-file>'
    status=$(gate_field "$body" "Status")
    printf '%s' "$status" | grep -qiE '^(PASS|CHANGES)$' \
      || die "the verdict body needs a 'Status: PASS' or 'Status: CHANGES' line (found: '${status:-none}')"

    # The reviewer states the commit it reviewed. If that is not this checkout's
    # HEAD, say so loudly rather than quietly rewriting it — a mismatch usually
    # means the review ran somewhere other than the worktree it was briefed on,
    # which is exactly how issue #4's review ended up reading the previous change.
    claimed=$(gate_field "$body" "Commit")
    if [ -n "$claimed" ] && ! git rev-parse --verify -q "${claimed}^{commit}" >/dev/null 2>&1; then
      die "the verdict names commit $claimed, which does not exist in $TOPLEVEL.
Are you in the checkout you reviewed? (HEAD here is $(git rev-parse --short HEAD) on $BRANCH)"
    fi
    if [ -n "$claimed" ] && [ "$(git rev-parse "$claimed")" != "$HEAD_SHA" ]; then
      printf 'team-marker: WARNING — the verdict says it reviewed %s, but HEAD here is %s.\n' \
        "$claimed" "$(git rev-parse --short HEAD)" >&2
      printf 'team-marker: recording it against %s. If that is not what you read, stop.\n' \
        "$(git rev-parse --short HEAD)" >&2
    fi
    warn_if_dirty

    verdict="$TEAM/review-verdict.md"
    {
      cat "$body"
      printf '\n<!-- recorded by team-marker.sh — the gate reads the lines below -->\n'
      printf 'Commit: %s\n' "$HEAD_SHA"
      printf 'Tree: %s\n' "$HEAD_TREE"
      printf 'Checkout: %s (%s)\n' "$TOPLEVEL" "$BRANCH"
      printf 'Recorded: %s\n' "$NOW"
    } > "$verdict"
    report_where "$verdict"
    printf '  next     : post THIS file to the issue, so both copies agree —\n'
    printf '             gh issue comment <n> --body-file %s\n' "$verdict"
    ;;

  verdict-pass)
    scope="${2:-}"
    [ -n "$scope" ] || die 'usage: team-marker.sh verdict-pass "<one-line scope>"'
    warn_if_dirty
    verdict="$TEAM/review-verdict.md"
    {
      printf 'Status: PASS\n'
      printf 'Branch: %s\n' "$BRANCH"
      printf 'Author: Tom\n'
      printf 'Reviewed: %s\n' "$NOW"
      printf 'Scope: %s\n\n' "$scope"
      printf '## Findings\n\nReviewed by hand; no blocking findings recorded.\n'
      printf '\n<!-- recorded by team-marker.sh -->\n'
      printf 'Commit: %s\n' "$HEAD_SHA"
      printf 'Tree: %s\n' "$HEAD_TREE"
      printf 'Checkout: %s (%s)\n' "$TOPLEVEL" "$BRANCH"
      printf 'Recorded: %s\n' "$NOW"
    } > "$verdict"
    report_where "$verdict"
    ;;

  status)
    printf 'team dir : %s\n' "$TEAM"
    printf 'checkout : %s (%s)\n' "$TOPLEVEL" "$BRANCH"
    printf 'HEAD     : %s\n\n' "$(git rev-parse --short HEAD)"
    rc=0
    for pair in "review-verdict.md:The review verdict" "last-green:The green test marker"; do
      f="$TEAM/${pair%%:*}"; label="${pair#*:}"
      if [ ! -f "$f" ]; then
        printf '%-18s MISSING\n' "${pair%%:*}"; rc=1; continue
      fi
      st=$(gate_field "$f" "Status")
      if [ "${pair%%:*}" = "review-verdict.md" ] && ! printf '%s' "$st" | grep -qiE '^PASS$'; then
        printf '%-18s NOT PASS (%s)\n' "${pair%%:*}" "${st:-none}"; rc=1; continue
      fi
      if reason=$(gate_covers_head "$f" "." "$label"); then
        printf '%-18s covers HEAD\n' "${pair%%:*}"
      else
        printf '%-18s DOES NOT COVER HEAD\n' "${pair%%:*}"
        printf '%s\n' "$reason" | sed 's/^/    /'
        rc=1
      fi
    done
    printf '\n'
    if [ "$rc" = 0 ]; then
      printf 'The merge gate would allow a code push from here.\n'
    else
      printf 'The merge gate would BLOCK a code push from here.\n'
    fi
    exit "$rc"
    ;;

  *)
    die "unknown command '$cmd' — try: path | green | verdict | verdict-pass | status"
    ;;
esac
