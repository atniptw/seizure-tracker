#!/usr/bin/env bash
# PreToolUse hook (Bash: git commit*) — scan the staged diff for secret
# material before it can be committed.
#
# Complements the hookify filename block (hookify.block-sensitive-git-add):
# that stops `git add google-services.json` by name; this stops the same
# *content* reaching a commit under a different filename, pasted into a new
# file, or swept in by `git add -A` / `git commit -a`.
#
# Output: PreToolUse deny JSON when a pattern matches, nothing otherwise.
set -uo pipefail

input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')

# Self-guard: only act on commit commands, even if the `if` matcher in
# settings.json is ignored by this Claude Code version.
case "$cmd" in
  *"git commit"*) ;;
  *) exit 0 ;;
esac

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

diff=$(git diff --cached 2>/dev/null || true)

# `git commit -a` / `-am` / `--all` stages tracked modifications at commit
# time, so fold the unstaged tracked diff into what we scan.
if printf '%s' "$cmd" | grep -qE 'git commit[^|&;]*( -a| -[a-zA-Z]*a[a-zA-Z]*| --all)'; then
  diff="${diff}
$(git diff 2>/dev/null || true)"
fi

[ -n "$diff" ] || exit 0

# Only look at added lines. Tight pattern set to keep false positives near zero:
# PEM private keys, and the two fingerprints of a Google service-account JSON /
# google-services.json.
matches=$(printf '%s' "$diff" \
  | grep -E '^\+' \
  | grep -nE '\-\-\-\-\-BEGIN [A-Z ]*PRIVATE KEY\-\-\-\-\-|"private_key"[[:space:]]*:|"type"[[:space:]]*:[[:space:]]*"service_account"|mobilesdk_app_id' \
  | head -10 || true)

[ -n "$matches" ] || exit 0

reason="Staged changes look like they contain secret material — refusing this commit.

Matching added lines:
${matches}

This matches a PEM private key, a Google service-account JSON, or
google-services.json content. If it is genuinely a false positive (a docs
snippet or test fixture), confirm with the user, then unstage/adjust or commit
the specific safe file explicitly."

jq -n --arg r "$reason" '{
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    permissionDecision: "deny",
    permissionDecisionReason: $r
  }
}'
