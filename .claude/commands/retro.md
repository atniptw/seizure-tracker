---
description: Team retro — what shipped since the last one, where the process leaked, and at most three changes to make.
argument-hint: [period, e.g. "since v0.0.1" or "last 2 weeks" — defaults to since the last retro]
---

Run a retrospective for the SeizureTracker team.

`/standup` asks *what is happening now*. This asks *what actually happened, and what should change
about how we work*. Output is a written retro at `.claude/team/retro/<date>.md`, committed, plus a
short spoken summary.

**Period:** $ARGUMENTS. If empty, use the date of the most recent file in `.claude/team/retro/`
as the start; if there are no retros yet, use the last 14 days. State the period you settled on.

## 1. Close the loop on the last retro first

Before gathering anything new, read the previous retro in `.claude/team/retro/` and go through its
action items one at a time. For each: **done / not done / abandoned**, with the evidence (a commit,
a file, an issue). A retro series that never checks its own last set of actions is just a diary —
this step is what makes it worth writing. If the previous retro's actions are mostly "not done",
say so plainly and make that the subject of this retro rather than adding three more.

## 2. Gather evidence

Prefer cheap structured sources; go to the journal last and selectively.

- **Shipped** — `git log --oneline` over the period on `main`; closed issues
  (`gh issue list --state closed --search "closed:>=<start>"`); any tags.
- **Gate integrity** — the verdicts for changes that merged in the period. `reviewer` posts each
  one as a comment on the issue it reviewed, so read them from there:

  ```bash
  gh issue list --state closed --search "closed:>=<start>" --json number
  gh issue view <n> --comments          # the verdict is the comment headed "review verdict"
  ```

  For each: `Status`, `Author`, whether any `[blocking]` finding was recorded, and anything under a
  "Process note" heading — that last one is the point of reading them at all. A verdict whose
  `Author` is Tom rather than a persona is a deliberate human override; fine individually, but
  count them, because a run of overrides means the gate is being worked around rather than used.

  **A merged change with no verdict comment at all is itself a finding** — either it took the
  docs-only exemption (check whether it should have) or it went round the gate.
- **CI — treat every failure on `main` as a local-gate escape, not just a red run.** This is the
  highest-signal section of the retro. The merge gate only lets a code push through when
  `last-green` says the suite passed locally, so a failure *after* that push means the local step
  didn't catch something CI did. Work out which:

  ```bash
  gh run list --branch main --limit 30 --json conclusion,createdAt,displayTitle,databaseId
  gh run view <id> --json displayTitle,jobs   # which step failed
  ```

  For each failure, name the step that failed and then the reason it escaped — they are different
  questions, and only the second produces an action:

  1. **CI checks something no local step does.** The real gap to look for. CI's last step is
     `./gradlew build`, which runs lint and assembles every variant; `last-green` is written after
     `./gradlew test`. A lint or assemble failure therefore *cannot* be caught by the current local
     gate. If a failure lands in the `Build` step, this is why.
  2. **The marker overstated what ran** — a filtered `--tests` run, or a green marker written
     before the last commit. `qa` owns this; the fix is in the brief, not in CI.
  3. **Environment-only failure** — the emulator suite's `TimeoutCancellationException` flake, a
     missing secret, a toolchain version. Three of this repo's four historical failures were in
     `Unit + integration tests (Firebase emulator)` and at least two were that flake (hence the
     retry plugin); the fourth was CI bootstrapping. Worth distinguishing, because "make the local
     run stricter" is the wrong action for these.

  Say which of the three each failure was. A failure with no matching action is a failure that
  will recur.

  Also count **cancellations** — usually a push superseding another mid-run. Harmless one at a
  time; a cluster means changes are being pushed faster than they are being verified, and it hides
  real failures because a cancelled run never reports.
- **Process leaks** — concrete, detectable things:
  - commits on `main` with no `#<issue>` reference
  - changes that merged with no archived verdict at all
  - worktrees or branches abandoned without merging (`git branch --no-merged main`)
  - test-suite flakes that were re-run rather than fixed
  - anything in `.claude/hooks/` that fired incorrectly, or a gate that had to be bypassed
- **Journal** — `.claude/team/log/<date>.md` is large and local. Do **not** read it whole. Grep the
  persona headings (`grep -n '^## ' <file>`) to see who ran when, and read only the entries around
  something you are already suspicious of.

## 3. Write the retro

To `.claude/team/retro/<YYYY-MM-DD>.md`, in this shape:

```
# Retro — <YYYY-MM-DD>
Period: <start> → <end>
Shipped: <n issues, n commits>

## Last retro's actions
- <action> — done / not done / abandoned (<evidence>)

## What shipped
- #<n> <title> (<commit>)

## What worked
- <thing worth keeping, and why it worked>

## Where the process leaked
- <what happened> (<evidence: commit / run id / file:line>) — <why it matters>

## Dropped follow-ups
- <a verdict note or planning-doc decision that never became an issue> (<source>)

## Actions
1. <change> — owner: <Tom | Tech Lead | persona>, check: <how the next retro will know>
2. ...
```

Rules for the write-up:

- **Cite evidence for every claim** — a commit, a run id, a `file:line`, a verdict. A retro finding
  nobody can check is an opinion, and it will be re-litigated next time.
- **At most three actions.** A retro that produces fifteen improvements produces none. If more than
  three things are genuinely wrong, pick the three with the most leverage and say explicitly what
  you are choosing not to act on.
- **Every action needs an owner and a check** — how the *next* retro will be able to tell whether
  it happened. "Be more careful" fails this test; "brief specialists with an absolute worktree path,
  checked by reviewer reports naming their checkout" passes it.
- **Describe systems, not agents behaving badly.** "The review ran in the wrong checkout because
  briefs said 'the current worktree'" is useful; "reviewer was careless" is not, and a persona
  cannot read the retro and improve anyway — only the prompts and hooks can.
- **Note what you could not determine.** Missing evidence (no archived verdict, a period before
  the journal existed) is itself a finding about the record-keeping.

## 4. Afterwards

- Commit the retro (`.claude/team/retro/` is tracked — these accumulate on purpose).
- If the retro found dropped follow-ups that should be issues, **list them and offer**; don't file
  them here. Filing is `backlog-owner`'s job via `/groom`, and it needs Tom's go.
- If an action changes a persona, hook or command, say so — that's a real change to make, not a
  note to remember.
