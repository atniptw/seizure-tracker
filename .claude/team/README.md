# `.claude/team/` — the team's shared desk

The Claude dev team (see `planning/claude-dev-team.md`) is hub-and-spoke: subagents can't talk
to each other, so every handoff is an artifact the Tech Lead (main thread) passes along. This
directory holds the ones that aren't a git branch or a GitHub issue.

| File | Written by | Read by | Tracked? |
|---|---|---|---|
| `log/<date>.md` | `subagent-log.sh` (SubagentStop hook) | humans, `/standup` | gitignored — local journal |
| `review-verdict.md` | `reviewer` persona | `check-review-verdict.sh` merge-gate hook | gitignored (ephemeral) |
| `last-green` | `qa` persona | `check-green-marker.sh` merge-gate hook | gitignored (ephemeral) |
| `retro/<date>.md` | `/retro` | humans, the next `/retro` | **committed** — process history |

Plus one artifact that isn't a file here at all: the **durable copy of each verdict**, posted by
`reviewer` as a comment on the issue it reviewed (`gh issue comment`).

## Where verdicts live, and why not here

`review-verdict.md` is overwritten by the next review. That is right for a gate — the gate only
cares about the change in front of it — but it means the reasoning behind each merge survives
about a day, and that reasoning is where process problems show up. Issue #4's verdict recorded
that the mandated `/code-review` pass had reviewed the wrong commit; that was the only evidence a
required gate step wasn't working.

The durable copy goes on **the issue**, not into this directory. A verdict is what a PR review
would be if this repo used PRs; since it pushes straight to `main`, the issue is the review
surface. Keeping ~10KB of review prose per change in the source tree would also go stale badly —
findings cite `file:line` against a diff that moves on — and it is the same argument that took the
subagent journal out of git. `/retro` reads them back with `gh issue view <n> --comments`.

`retro/<date>.md` *is* committed, and the distinction is deliberate: a retro is about how the team
works rather than about one diff, so it has no issue to hang off, it does not go stale the way a
line-cited review finding does, and its whole value is that the next retro can find it.

## Merge gate

A `git push` that updates `main` **and** touches code (`app/`, `lib/`, `test/`,
`firestore.rules`, `firestore-tests/`) is hard-blocked unless:

- `review-verdict.md` has `Status: PASS`, and
- `last-green` exists,

and **both** are newer than the commit(s) being pushed. Docs/config-only pushes are exempt.

Both files are gitignored and human-writable — that's the deliberate override for when Tom is
the reviewer, or ran the tests himself.

The gate is evaluated against the checkout the push comes from (the hook's `cwd`), while the two
markers are always read from the main checkout. Run `.claude/hooks/test-gates.sh` after touching
either hook — 15 assertions, including the worktree cases where the gate used to fail open.

## `review-verdict.md` format

```
Status: PASS
Branch: issue-12-observations-migration
Reviewed: 2026-09-06T21:00:00Z
Scope: move seizures + healthNotes reads onto the observations collection

## Findings
- [nit] redundant null check (data/repository/ObservationRepository.kt:88)

## Notes
Rules diff already shown to Tom.
```
