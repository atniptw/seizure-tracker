# `.claude/team/` — the team's shared desk

The Claude dev team (see `planning/claude-dev-team.md`) is hub-and-spoke: subagents can't talk
to each other, so every handoff is an artifact the Tech Lead (main thread) passes along. This
directory holds the ones that aren't a git branch or a GitHub issue.

| File | Written by | Read by | Tracked? |
|---|---|---|---|
| `log/<date>.md` | `subagent-log.sh` (SubagentStop hook) | humans, `/standup` | gitignored — local journal |
| `review-verdict.md` | `reviewer` persona | `check-review-verdict.sh` merge-gate hook | gitignored (ephemeral) |
| `last-green` | `qa` persona | `check-green-marker.sh` merge-gate hook | gitignored (ephemeral) |

## Merge gate

A `git push` that updates `main` **and** touches code (`app/`, `lib/`, `test/`,
`firestore.rules`, `firestore-tests/`) is hard-blocked unless:

- `review-verdict.md` has `Status: PASS`, and
- `last-green` exists,

and **both** are newer than the commit(s) being pushed. Docs/config-only pushes are exempt.

Both files are gitignored and human-writable — that's the deliberate override for when Tom is
the reviewer, or ran the tests himself.

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
