# `.claude/team/` — the team's shared desk

The Claude dev team (see `planning/claude-dev-team.md`) is hub-and-spoke: subagents can't talk
to each other, so every handoff is an artifact the Tech Lead (main thread) passes along. This
directory holds the ones that aren't a git branch or a GitHub issue.

| File | Written by | Read by | Tracked? |
|---|---|---|---|
| `log/<date>.md` | `subagent-log.sh` (SubagentStop hook) | humans, `/standup` | gitignored — local journal |
| `review-verdict.md` | `reviewer` persona | `check-review-verdict.sh` merge-gate hook | gitignored (ephemeral) |
| `last-green` | `qa` persona | `check-green-marker.sh` merge-gate hook | gitignored (ephemeral) |
| `verdicts/<branch>.md` | `reviewer` persona | humans, `/retro` | **committed** — why each change merged |
| `retro/<date>.md` | `/retro` | humans, the next `/retro` | **committed** — process history |

## Why verdicts are archived twice

`review-verdict.md` is overwritten by the next review, so on its own it holds the current verdict
and no history. That is fine for a gate — the gate only cares about the change in front of it —
but it means the record of *why* each change was allowed onto `main` survives about a day. The
`verdicts/` copy is the durable one, and it is where process problems accumulate: issue #4's
verdict recorded that the mandated `/code-review` pass had reviewed the wrong commit, which was
the only evidence that a required gate step wasn't working. `/retro` reads this directory.

`retro/` is the other half. A retro's actions are checked by the *next* retro, so they have to
outlive the session that wrote them.

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
