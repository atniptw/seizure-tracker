#!/usr/bin/env bash
# Outbound-content guard — refuse a push that would publish live identifiers or secrets.
#
# Why this exists: on issue #5 a planning-doc rewrite carried the real Firebase project id, the
# live household's document id and three Auth uids onto a topic branch, and the branch was pushed
# to a PUBLIC repo. Nothing looked at it: scan-staged-secrets.sh only runs on `git commit` and only
# knows key/PEM shapes, and the merge gate (check-review-verdict.sh) deliberately ignores every
# push that isn't updating main — the one route this data took. In this app the live household
# id is not just a label: firestore.rules lets any signed-in user add THEMSELVES to a household's
# `members`, so knowing the id is what stands between a stranger and the health records.
#
# Two entry points, one scanner:
#   1. Claude Code PreToolUse hook on Bash (stdin: JSON)  -> deny JSON, exit 0
#   2. git pre-push hook, via .githooks/pre-push (stdin: ref lines) -> stderr, exit 1
#
# It scans every line the push would ADD — file contents and commit messages — for:
#   - any literal in .claude/local/denylist.txt (gitignored; the real project/household/uid values)
#   - Google API keys, PEM private keys, service-account / google-services.json fingerprints
#   - in *.md files only: a bare 20- or 28-char mixed-case+digit token, i.e. the shape of a
#     Firestore auto-id or a Firebase Auth uid. (Not applied to lockfiles/JSON: integrity hashes
#     have exactly this shape.)
# Mark a line `id-scan:ignore` to accept a known false positive. Matches are reported redacted —
# the report itself must not become a second copy of the value.
#
# Only commits not already on a remote are scanned: what is already public is not this hook's
# problem, and old history must not block new pushes.
set -uo pipefail

input=$(cat)
SCANNER=$(cat <<'PERL'
use strict; use warnings;
my ($denyfile) = @ARGV;
my @deny;
if (defined $denyfile && -f $denyfile && open(my $fh, '<', $denyfile)) {
  while (<$fh>) { chomp; s/\s+#.*$//; next if /^\s*(#|$)/; push @deny, $_; }
}
sub red { my $s = shift; return substr($s, 0, 4) . '...(' . length($s) . ' chars)'; }
my ($commit, $file, $inmsg, %seen) = ('', '', 0);
my $hits = 0;
sub hit { my ($kind, $val) = @_; my $k = join('|', $commit, $file, $kind, defined $val ? $val : ''); return if $seen{$k}++;
  printf "  %s  %s: %s%s\n", $commit, $file, $kind, defined $val ? " [" . red($val) . "]" : ""; $hits++; }
while (<STDIN>) {
  chomp;
  my $line;
  if (/^\@\@C (\S+)/) { $commit = $1; $file = '(commit message)'; $inmsg = 1; next; }
  if (/^diff --git /) { $inmsg = 0; next; }
  if ($inmsg) { $line = $_; }
  elsif (/^\+\+\+ (?:b\/)?(.*)/) { $file = $1; next; }
  elsif (/^\+(.*)$/) { $line = $1; }
  else { next; }
  next if $line =~ /id-scan:ignore/;
  for my $d (@deny) { hit('known live identifier (local denylist)', $d) if index($line, $d) >= 0; }
  hit('Google API key', undef)               if $line =~ /AIza[0-9A-Za-z_\-]{35}/;
  hit('private key material', undef)         if $line =~ /-----BEGIN [A-Z ]*PRIVATE KEY-----|"private_key"\s*:/;
  hit('service-account / google-services content', undef)
                                             if $line =~ /"type"\s*:\s*"service_account"|mobilesdk_app_id/;  # id-scan:ignore — the pattern names the token it hunts
  if (!$inmsg && $file =~ /\.md$/i) {
    while ($line =~ /(?<![A-Za-z0-9_\-])([A-Za-z0-9]{20}|[A-Za-z0-9]{28})(?![A-Za-z0-9_\-])/g) {
      my $t = $1; hit('id-shaped token (Firestore doc id / Auth uid?)', $t) if $t =~ /[a-z]/ && $t =~ /[A-Z]/ && $t =~ /[0-9]/;
    }
  }
}
exit($hits ? 1 : 0);
PERL
)

# --- work out the mode, the repo and the tips being pushed --------------------------------
mode=git; repo=$(pwd); tips=(); all=0
if printf '%s' "$input" | jq -e '.tool_input' >/dev/null 2>&1; then
  mode=hook
  cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')
  printf '%s' "$cmd" | grep -qE '(^|[;&|(]|[[:space:]])git([[:space:]]+-[cC][[:space:]]+[^[:space:]]+)*[[:space:]]+push([[:space:]]|$)' || exit 0
  repo=$(printf '%s' "$input" | jq -r '.cwd // ""')
  [ -d "$repo" ] || repo="${CLAUDE_PROJECT_DIR:-.}"
  # `git -C dir push` runs in dir.
  cdir=$(printf '%s' "$cmd" | sed -nE 's/.*git[[:space:]]+-C[[:space:]]+([^[:space:]]+)[[:space:]]+push.*/\1/p')
  if [ -n "$cdir" ]; then case "$cdir" in /*) repo="$cdir" ;; *) repo="$repo/$cdir" ;; esac; fi
  # Tokens after `push`, up to the end of this simple command: flags, remote, refspecs.
  rest=${cmd#*push}; rest=${rest%%&&*}; rest=${rest%%;*}; rest=${rest%%|*}
  set -f; toks=($rest); set +f
  nonflag=0
  for t in "${toks[@]:-}"; do
    case "$t" in
      --all|--mirror) all=1 ;;
      -*) ;;
      *) nonflag=$((nonflag+1))
         [ "$nonflag" -ge 2 ] || continue                 # first non-flag word is the remote
         t=${t#+}; case "$t" in :*) continue ;; esac      # `:branch` deletes — nothing to scan
         src=${t%%:*}; { [ -z "$src" ] || [ "$src" = HEAD ]; } && src=HEAD
         git -C "$repo" rev-parse --verify -q "$src^{commit}" >/dev/null 2>&1 && tips+=("$src") ;;
    esac
  done
  [ "$all" = 1 ] && tips=(--branches)
  [ "${#tips[@]}" -gt 0 ] || tips=(HEAD)
else
  # git pre-push: lines of `<local ref> <local sha> <remote ref> <remote sha>`.
  while read -r _lref lsha _rref _rsha; do
    [ -n "${lsha:-}" ] || continue
    case "$lsha" in *[!0]*) tips+=("$lsha") ;; esac      # all-zero sha = deleting a ref
  done <<<"$input"
  [ "${#tips[@]}" -gt 0 ] || exit 0
fi

# --- scan ---------------------------------------------------------------------------------
common=$(git -C "$repo" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || exit 0
denylist="$(dirname "$common")/.claude/local/denylist.txt"

emit() {   # $1 = reason; blocks in whichever mode we are in
  if [ "$mode" = hook ]; then
    jq -n --arg r "$1" '{hookSpecificOutput: {hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: $r}}'
    exit 0
  fi
  printf '%s\n' "$1" >&2
  exit 1
}

# A scan that could not run must not read as a clean one — the merge gate once failed open on a
# probe against the wrong checkout, and that is the direction a gate must not fail.
patch=$(git -C "$repo" log --no-color -p -U0 --format='@@C %h%n%s%n%b' "${tips[@]}" --not --remotes 2>&1) \
  || emit "Outbound-content guard: could not read the commits this push would publish, so it could not
scan them — refusing rather than guessing. git said: ${patch}"

report=$(printf '%s\n' "$patch" | perl -e "$SCANNER" "$denylist" 2>&1)
case $? in
  0) exit 0 ;;   # nothing found
  1) ;;          # findings — fall through to the block below
  *) emit "Outbound-content guard: the scanner itself failed, so this push was not checked — refusing.
${report}" ;;
esac

emit "Outbound-content guard: this push would publish live identifiers or secrets — refusing.

${report}

The git history of a public repo is permanent, so this is checked BEFORE the push, not after.
Real values live only in .claude/local/ (gitignored; live-inventory.md maps each to a placeholder
such as <LIVE-HOUSEHOLD-ID>). Replace the value with its placeholder, amend or rewrite the commit
that added it, and push again. If a hit is a genuine false positive, mark that line
'id-scan:ignore' — or, if you are Tom, push from your own terminal with --no-verify."
