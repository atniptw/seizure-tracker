#!/usr/bin/env bash
# Shared library for the merge gate. Sourced by check-review-verdict.sh,
# check-green-marker.sh and team-marker.sh — never run directly.
#
# Why this file exists
# --------------------
# Every merge-gate bug this team has hit has been the same bug: a shared artifact
# addressed one way in a worktree and another way in the main checkout, or a rule
# duplicated in two hooks so a fix reached only one of them.
#
#   issue #4   the reviewer ran /code-review in the main checkout, where
#              origin/main...HEAD was empty, and reviewed the previous change.
#   e895c45    both hooks probed git in CLAUDE_PROJECT_DIR while the push came
#              from a worktree; the gate failed OPEN.
#   issue #5   qa wrote .claude/team/last-green as a relative path, so a genuine
#              green run landed in the worktree while the hooks read the stale
#              copy in the main checkout.
#   issue #5   the code-path list predated tools/, so tools/migrate/ — the scripts
#              pointed at two real users' only copy of their health history —
#              pushed unguarded. The list lived in two hooks; both needed editing.
#
# So: one definition of the team directory, one definition of what counts as code,
# one definition of "has this been reviewed", used by everything. Adding a path or
# changing the rule is a one-line edit in one file.
#
# Verify with .claude/hooks/test-gates.sh after any change here.

# What the gate considers code. A push touching none of these (docs, planning/,
# .claude/, .github/) is exempt. Keep in sync with nothing — this is the only copy.
GATE_CODE_PATHS='^(app/|lib/|test/|integration_test/|tools/|firestore\.rules$|firestore-tests/)'

# Absolute path to the main checkout's .claude/team, resolved from any checkout.
# --git-common-dir points at the shared .git for a worktree and at the local one
# otherwise; --path-format=absolute (git >= 2.31) keeps it from coming back as a
# bare ".git" that only resolves relative to the caller's cwd.
gate_team_dir() {
  local repo="${1:-.}" common
  common=$(git -C "$repo" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || common=""
  if [ -z "$common" ]; then
    common=$(git -C "$repo" rev-parse --git-common-dir 2>/dev/null) || return 1
    case "$common" in
      /*) ;;
      *) common="$(cd "$repo" && cd "$common" && pwd)" ;;
    esac
  fi
  printf '%s/.claude/team\n' "$(dirname "$common")"
}

# Does this git-push command land on main? Explicit refspec, a sweeping --all /
# --mirror, or a bare push from main or from a branch tracking origin/main.
gate_targets_main() {
  local cmd="$1" repo="$2" branch upstream
  case "$cmd" in
    *" main"*|*":main"*|*" --all"*|*" --mirror"*) return 0 ;;
  esac
  branch=$(git -C "$repo" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
  [ "$branch" = "main" ] && return 0
  upstream=$(git -C "$repo" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || echo "")
  case "$upstream" in */main) return 0 ;; esac
  return 1
}

# The range this push would add to main.
gate_range() {
  local repo="$1"
  if git -C "$repo" rev-parse origin/main >/dev/null 2>&1; then
    printf 'origin/main..HEAD\n'
  else
    printf 'HEAD\n'
  fi
}

# Print the code files this push would change; empty means the gate does not apply.
gate_changed_code() {
  local repo="$1" range
  range=$(gate_range "$repo")
  git -C "$repo" diff --name-only $range 2>/dev/null \
    | grep -E "$GATE_CODE_PATHS" || true
}

# Read a header field ("Status") out of a marker file. Case-insensitive on the key,
# tolerant of leading space, first match wins — the header is at the top.
gate_field() {
  local file="$1" key="$2"
  grep -iE "^[[:space:]]*${key}:" "$file" 2>/dev/null \
    | head -1 \
    | sed -E 's/^[[:space:]]*[^:]*:[[:space:]]*//' \
    | tr -d '[:space:]'
}

# Same, but last match wins. Used for Commit:/Tree:, which team-marker.sh appends
# as a trailer after the reviewer's body. The reviewer states the commit in their
# own header too; the trailer is machine-written from the checkout that recorded
# the marker, so when both are present the trailer is the one to trust.
gate_field_last() {
  local file="$1" key="$2"
  grep -iE "^[[:space:]]*${key}:" "$file" 2>/dev/null \
    | tail -1 \
    | sed -E 's/^[[:space:]]*[^:]*:[[:space:]]*//' \
    | tr -d '[:space:]'
}

# The heart of the gate: does $file attest to the code that is about to be pushed?
#
# This used to be an mtime comparison — the marker had to be newer than HEAD. That
# proxy was wrong in both directions. `touch` satisfied it, so a five-day-old PASS
# verdict for a different issue would wave the next issue through; and any commit
# after the review (a rebase, an amend, a team-log commit on top) made a perfectly
# valid verdict look stale, which is the false block that kept stopping real work.
#
# The honest question is whether the reviewed code is the code being pushed, so ask
# that: the marker names the commit it covers, and we diff that commit against HEAD
# over the code paths. No code difference means what was reviewed is what ships —
# docs or planning commits layered on top are fine. Any code difference blocks.
#
# Echoes a human-readable reason on failure; returns 0 when the marker covers HEAD.
gate_covers_head() {
  local file="$1" repo="$2" label="$3"
  local sha tree head_tree drift

  sha=$(gate_field_last "$file" "Commit")
  tree=$(gate_field_last "$file" "Tree")

  if [ -z "$sha" ] && [ -z "$tree" ]; then
    printf '%s does not say which commit it covers (no "Commit:" line).

Markers written before the gate checked coverage look like this. Re-record it from
the checkout it attests to — that stamps the commit:

  .claude/hooks/team-marker.sh green "<what you ran>"
  .claude/hooks/team-marker.sh verdict-pass "<one-line scope>"' "$label"
    return 1
  fi

  head_tree=$(git -C "$repo" rev-parse --verify -q 'HEAD^{tree}' 2>/dev/null || echo "")
  [ -n "$head_tree" ] || { printf 'cannot resolve HEAD in %s.' "$repo"; return 1; }

  # Fast path, and the only path when the named commit is no longer reachable
  # (branch deleted and gc'd): an exact tree match means identical content.
  if [ -n "$tree" ]; then
    local full_tree
    full_tree=$(git -C "$repo" rev-parse --verify -q "${tree}^{tree}" 2>/dev/null || echo "")
    [ -n "$full_tree" ] && [ "$full_tree" = "$head_tree" ] && return 0
  fi

  if [ -z "$sha" ]; then
    printf '%s records a tree that does not match what is being pushed.' "$label"
    return 1
  fi

  if ! git -C "$repo" rev-parse --verify -q "${sha}^{commit}" >/dev/null 2>&1; then
    printf '%s names commit %s, which does not exist in this repository.
A verdict recorded from the wrong checkout, or against a branch that has since
been deleted, looks exactly like this.' "$label" "$sha"
    return 1
  fi

  if [ "$(git -C "$repo" rev-parse --verify -q "${sha}^{tree}")" = "$head_tree" ]; then
    return 0
  fi

  # Trees differ. Ignore the difference if none of it is code.
  drift=$(git -C "$repo" diff --name-only "$sha" HEAD 2>/dev/null \
          | grep -E "$GATE_CODE_PATHS" || true)
  if [ -z "$drift" ]; then
    return 0
  fi

  printf '%s covers commit %s, but the code being pushed has moved on since:

%s

Re-run against the current diff so the marker reflects what actually goes to main.' \
    "$label" "$(git -C "$repo" rev-parse --short "$sha")" "$(printf '%s' "$drift" | sed 's/^/  /')"
  return 1
}

# Emit the PreToolUse deny envelope and stop.
gate_block() {
  jq -n --arg r "$1" '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $r}}'
  exit 0
}

# Common preamble for both hooks. Reads the PreToolUse JSON on stdin and decides
# whether the gate applies at all; exits 0 (allow) when it does not.
#
# Two roots, deliberately:
#   GATE_PROJ — the main checkout, where the one canonical pair of markers lives.
#               CLAUDE_PROJECT_DIR stays at the session's starting checkout even
#               after EnterWorktree, which is what we want for the markers.
#   GATE_REPO — the checkout whose push is being gated. The hook's stdin `cwd`
#               follows Claude into a worktree, so this is where the pushed
#               commits actually live. Probing git in GATE_PROJ for both (as these
#               hooks once did) made a push from a worktree read main's HEAD:
#               origin/main..HEAD came back empty, nothing matched the code paths,
#               and the docs-only exemption let it through — failing OPEN.
gate_preamble() {
  local input cmd
  input=$(cat)
  cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')
  case "$cmd" in
    *"git push"*) ;;
    *) exit 0 ;;
  esac

  GATE_PROJ="${CLAUDE_PROJECT_DIR:-.}"
  GATE_REPO=$(printf '%s' "$input" | jq -r '.cwd // ""')
  [ -n "$GATE_REPO" ] && [ -d "$GATE_REPO" ] || GATE_REPO="$GATE_PROJ"

  gate_targets_main "$cmd" "$GATE_REPO" || exit 0
  [ -n "$(gate_changed_code "$GATE_REPO")" ] || exit 0

  GATE_TEAM=$(gate_team_dir "$GATE_PROJ") || exit 0
  export GATE_PROJ GATE_REPO GATE_TEAM
}
