---
description: Team standup — open work, worktrees, CI status, and anything stranded.
---

Produce a standup snapshot for the SeizureTracker team. **Read-only** — change nothing.

1. **Backlog** — `gh issue list --state open --limit 40`, grouped by milestone; call out anything
   labelled `blocked` or `needs-decision`. (If there are no issues yet, say so — the backlog is
   on hold.)
2. **In flight** — `git worktree list`; for each worktree branch, its last commit subject and
   whether it is ahead of `origin/main`.
3. **CI** — `gh run list --branch main --limit 5` with status.
4. **Stranded** —
   - commits on `main` not pushed: `git log origin/main..main --oneline`
   - recent `main` commits whose message has no `#<issue>` reference
   - whether `.claude/team/review-verdict.md` and `.claude/team/last-green` are fresh (newer than
     `HEAD`) or stale
5. **Team log** — the last few entries in the most recent `.claude/team/log/<date>.md`.

Summarise: what's landed since the last standup, what's in progress, what's blocked, and the one
most useful next action.
