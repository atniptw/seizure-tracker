# The Claude dev team

SeizureTracker is a solo project. Development is delegated to a small simulated team of Claude
Code agents. This doc is the charter: who the "team" is, how they hand work off, and what the
machine enforces so the process holds even when an agent cuts a corner.

Status: scaffolded **2026-09-06**. Serving the Phase 1 (Kotlin → target Firestore shape)
migration first; the Flutter personas switch on at `flutter-migration.md` Phase 0. GitHub issue
creation is **on hold** until Tom finishes reviewing the planning docs.

Related: `architecture.md`, `migration.md`, `flutter-migration.md`, `security-privacy.md`;
memories `v2-team-shape`, `coordinator-pattern`, `feedback-push-to-main`, `flutter-migration-env`,
`ios-signing-checklist`.

---

## 1. The core constraint

Claude Code subagents **cannot talk to each other**. Each is spawned cold, does one job, returns
one summary, and is gone. So the team is **hub-and-spoke**, not a mesh:

- The **main thread is the Tech Lead**. It is the only actor that reads the backlog, picks work,
  spawns specialists, carries context across a whole feature, integrates results, runs the merge,
  and updates issues / planning docs / memory. It does **not** write feature code itself.
- **Specialists are stateless.** Spawned with just an issue brief + a planning-doc pointer, they
  do one job in one lane, write output to a known path, and report a summary. They never spawn or
  message each other — they surface findings *up* to the Tech Lead, who relays.

Every handoff between specialists is therefore an explicit **artifact** the Tech Lead passes
along — auditable, survives context summarization, and a hook can gate on it.

```
                       ┌─────────────────┐
             Tom ◄────► │   TECH LEAD      │  (main thread)
                        └────────┬─────────┘
                                 │ spawns, relays artifacts
      ┌───────────┬──────────────┼──────────────┬───────────────┐
      ▼           ▼              ▼              ▼               ▼
  flutter-dev    qa          reviewer     rules-engineer   backlog-owner
   (sonnet)   (sonnet)       (opus)          (opus)          (sonnet)
                            + migration-lead (opus, Phase 1 only)

  shared desk (.claude/team/ + GitHub + worktrees):
   • the GitHub issue            • the worktree .claude/worktrees/issue-N-slug
   • review-verdict.md (reviewer)  • last-green (qa)   • log/<date>.md (auto, committed)
```

---

## 2. Roster

Agent files: `.claude/agents/*.md` (thin — mandate, model, gotchas, output path; they *reference*
this repo's docs rather than restating architecture).

| Persona | Model | Owns | Never |
|---|---|---|---|
| **Tech Lead** (main thread, not an agent file — governed by `CLAUDE.md` §"Working as a team") | — | picking work, spawning specialists, relaying artifacts, the merge, issue/doc/memory updates | writes feature code |
| `flutter-dev` | sonnet | implementing one brief in the assigned worktree (Kotlin now, Flutter/Riverpod later) | scope expansion; writing `last-green`; spawning agents |
| `qa` | sonnet | tests for a change; reproducing a bug with a failing test first; writing `last-green` on green | writing production code; writing `last-green` on a non-green / partial run |
| `reviewer` | opus | the pre-merge review of a worktree diff; writing `review-verdict.md` | editing source or tests; spawning agents |
| `rules-engineer` | opus | `firestore.rules`, `firestore-tests/`, `firestore.indexes.json`; +/- test pair per path | leaving a path without a negative test |
| `migration-lead` | opus | sequencing `migration.md`; live-data safety (backups before shape changes). **Phase 1 only** | doing the whole migration in one pass; skipping a backup |
| `backlog-owner` | sonnet | GitHub Issues: seeding from docs, splitting epics, dedupe, re-milestone | `gh issue create` while the hold is on; inventing scope not in a doc |

**Phase 2 additions** (not yet created):
- `platform-parity` (sonnet) — the iOS↔Android delta: platform channels, `share_plus` behavior,
  file paths, permissions, `Info.plist` vs manifest. Every feature signed off on *both* platforms.
- `release-manager` (sonnet) — Android → Firebase App Distribution (`household` group);
  iOS → TestFlight internal (GitHub Actions `macos-26`, ASC API key, dart-define flavors — see
  memory `ios-signing-checklist`). Drives CI, never a local build.

Model choices follow `coordinator-pattern`: opus for review / rules / migration / planning-heavy
judgment, sonnet for implementation and mechanical curation, never fable for security-adjacent
work.

---

## 3. The standard feature flow

| # | Actor | Action | Artifact |
|---|---|---|---|
| 1 | Tech Lead | `/standup`; pick an issue for the active milestone (skip `blocked` / `needs-decision`) | — |
| 2 | Tech Lead | `EnterWorktree` `issue-<n>-<slug>`; post scope + acceptance criteria + planning ref as an issue comment | issue brief |
| 3 | Tech Lead → `Plan` *(only if big/unclear)* | step breakdown | sub-tasks on the issue |
| 4 | Tech Lead → `flutter-dev` | "Implement #<n> per brief + `<doc §>`. Compile/analyze before returning." | code in worktree + summary |
| 5 | Tech Lead → `qa` | "Tests for #<n>. Run the suite. Write `last-green` on green." | tests + `last-green` |
| — | Tech Lead | qa red → back to step 4 with qa's failure detail (the only "dialogue", relayed) | — |
| 6 | Tech Lead → `reviewer` | "Review the #<n> diff. `/code-review high` (+ `security-review` if auth/rules/migration/export). Verdict → file." | `review-verdict.md` |
| — | Tech Lead | `Status: CHANGES` → back to step 4 with the findings | — |
| 7 | Tech Lead | verdict `PASS` + `last-green` fresh → merge branch to `main` with `Fixes #<n>`, `git push origin main` | commit on main |
| — | hooks | `check-review-verdict.sh`, `check-green-marker.sh`, `scan-staged-secrets.sh` (pre), `watch-main-ci.sh` (post), `flag-unpushed-main.sh` (stop) fire automatically | — |
| 8 | Tech Lead | CI green → issue auto-closed. Update `planning/` if design shifted (or spawn `backlog-owner`). `subagent-log.sh` has already journalled each specialist. | closed issue, updated docs |

### Variants

- **Bug** (`type:bug`): `qa` goes first — reproduce, write a failing test — then `flutter-dev` /
  `rules-engineer` fixes until it passes, then `reviewer`. Same gate.
- **Rules change** (`area:rules`): `rules-engineer` is *both* implementer and reviewer for
  `firestore.rules` + the Node suite; `reviewer` still covers the app-side diff. Tech Lead
  **always** surfaces the rules diff to Tom before pushing.
- **Spike** (`type:spike`): `Plan` or the relevant specialist investigates, writes findings to
  the issue, sets `needs-decision`. Tom decides. Tech Lead converts the decision into
  `type:feature` issues + a planning-doc edit. (The iOS spike round already ran this way — see
  `ios-signing-checklist`.)
- **Phase 1 → 2**: `migration-lead` retires (archive its agent file); `flutter-dev` + `qa`
  retool for Flutter; `platform-parity` + `release-manager` activate; `backlog-owner`
  re-milestones open issues.

### Human touch points (Tom, not the team)

- `needs-decision` issues — the Tech Lead stops and asks.
- Any risky merge — rules, data migration, auth — surfaced with the diff + verdict before push,
  even when the hooks are green.
- Apple enrolment / signing one-time steps (memory `ios-signing-checklist`).
- Roster / model / cost calls; lifting the issue-creation hold.

---

## 4. The merge gate (enforced)

`.claude/hooks/check-review-verdict.sh` and `.claude/hooks/check-green-marker.sh` run on
`PreToolUse` for `Bash(git push*)`. A push is **hard-blocked** when **all** of:

1. it would update `origin/main` (current branch `main`, or the command names `main`), **and**
2. the pushed diff (`origin/main..HEAD`) touches a **code path** —
   `app/` · `lib/` · `test/` · `integration_test/` · `firestore.rules` · `firestore-tests/`, **and**
3. either `review-verdict.md` is missing / not `Status: PASS` / older than `HEAD`,
   or `last-green` is missing / older than `HEAD`.

Docs-, planning-, and `.claude/`-only pushes are exempt (they still pass through
`scan-staged-secrets.sh` and `flag-unpushed-main.sh`).

**Override:** both marker files are gitignored and human-writable. When Tom reviews a change
himself, or runs the suite himself, he writes `Status: PASS` / a fresh timestamp and pushes.
This is deliberate, not a loophole — the gate exists to stop *silent* skipping, not to remove
Tom's authority.

`review-verdict.md` format is in `.claude/team/README.md`.

---

## 5. Backlog — GitHub Issues

Repo `atniptw/seizure-tracker`. Only the **Tech Lead** (fast path, mid-session) and
**`backlog-owner`** (curation, bulk, grooming) write issues. Other specialists surface findings
in their reports; the Tech Lead files them. Rationale: consistent template shape, dedupe (a cold
specialist doesn't know the backlog), and scope control.

**Labels** (on top of GitHub defaults; `duplicate` / `invalid` / `good first issue` /
`help wanted` retired):

| Group | Labels |
|---|---|
| type | `type:feature` · `bug` · `type:chore` · `type:spike` |
| area | `area:rules` · `area:auth` · `area:data-model` · `area:migration` · `area:ios` · `area:android` · `area:ci` · `area:export` · `area:ui` · `area:security-privacy` |
| status | `blocked` · `needs-decision` · `ready` |

**Milestones:** `Phase 1 — Firestore shape + rules migration` · `Phase 2 — Flutter re-platform` ·
`Backlog / post-v1`.

**Templates** (`.github/ISSUE_TEMPLATE/`, added when the hold lifts): `feature` (problem /
proposal / planning-doc ref / acceptance criteria), `bug` (repro / expected vs actual / platform
/ build), `spike` (question / why / decision owner / doc ref).

**Workflow:** branch `issue-<n>-<slug>` → commit to `main` with `Fixes #<n>` (no PRs, per
`feedback-push-to-main`) → CI → auto-close. `/standup` flags a merged branch that didn't close
its issue.

**Candidate seed issues** (for the first `/groom` once the hold lifts — not yet created):
Phase 1: `firestore.indexes.json` + `observations.details` exemption · `seizures`+`healthNotes` →
`observations` (Kotlin) · admin/member roles + rules + test pairs · pre-cutover data backup ·
offline rejected-write verification. Phase 2 epics: Flutter scaffold + dart-define flavors + CI ·
Riverpod layer · auth port · screen ports (sub-issues) · Dart PDF/CSV export · iOS signing +
TestFlight pipeline · in-app account/data deletion (deferred to first external build). Backlog:
notifications · photo/video attachments · in-progress seizure timer.

---

## 6. What was scaffolded (2026-09-06)

- `.claude/agents/` — `flutter-dev`, `qa`, `reviewer`, `rules-engineer`, `migration-lead`,
  `backlog-owner`.
- `.claude/commands/` — `standup`, `plan-feature`, `review`, `groom`, `ship` (ship is a Phase 2
  placeholder).
- `.claude/hooks/` — `check-review-verdict.sh`, `check-green-marker.sh`, `subagent-log.sh`;
  wired in `.claude/settings.json` (PreToolUse `git push*` ×2, SubagentStop ×1).
- `.claude/team/` — `README.md`, `log/` (committed journal). `review-verdict.md` + `last-green`
  are gitignored.
- Extended `hookify.block-sensitive-git-add` + `hookify.sensitive-android-files` with iOS
  signing patterns (`.p8`, `.mobileprovision`, `GoogleService-Info.plist`, `ExportOptions.plist`).
- `CLAUDE.md` — "Working as a team" section.

**Not yet done:** GitHub labels/milestones/templates + seed issues (on hold); the Phase 2
personas, `flutter-check` skill, and the `codegen-freshness` / `parity-reminder` hooks
(switch on with Flutter).

### Next: smoke test

Run one real Phase-1 issue end to end — e.g. adding `firestore.indexes.json` — through
Tech Lead → `rules-engineer` → `qa` → `reviewer` → merge gate → push, and fix whatever chafes
before leaning on the flow for the migration proper.
