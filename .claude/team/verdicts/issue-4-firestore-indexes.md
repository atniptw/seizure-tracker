Status: PASS
Issue: #4 — Add `firestore.indexes.json` with an `observations.details` single-field index exemption
Branch: issue-4-firestore-indexes
Commit: 663fa45
Author: rules-engineer
Reviewed: 2026-09-07T19:52:00Z
Scope: Adds `firestore.indexes.json` (no composite indexes + one `fieldOverrides` exemption on `observations.details`), wires `firestore.indexes` into `firebase.json`, adds a non-emulator shape guard `firestore-tests/indexes.test.js`, and documents the deploy path in README + corrects `architecture.md §3/§10` and `migration.md §4`.

## Findings

- [non-blocking] README overstates the app's query inventory: "every query the app makes is a
  single `orderBy` on one field". `VetRepository.kt:53` is
  `links(householdId).whereEqualTo("vetId", vetId).get()` — a `where()` with no `orderBy`. Still
  a single-field query, so the conclusion (no composite index needed) is correct; the sentence
  as written is not. (README.md:130)
- [non-blocking] The "does not exempt any envelope field" guard has a hole. Firestore supports
  `fieldPath: "*"` as a collection-level exemption covering *all* fields in a collection group.
  A `*` override would silently disable indexing on `occurredAt` and break the timeline query,
  yet passes this test, which only checks that named envelope paths are absent from the list.
  Add `expect(exempted).not.toContain("*")`. (firestore-tests/indexes.test.js:58-67)
- [nit] `expect(Object.keys(override).sort()).toEqual(["collectionGroup","fieldPath","indexes"])`
  is over-specified. `ttl` is a legal sibling key that firebase-tools accepts
  (`firestore/api.js` `validateField`), so a legitimate later addition fails this test with a
  message that does not describe the actual problem. Asserting the three required keys are
  present would guard the same thing without the brittleness. (firestore-tests/indexes.test.js:53)
- [nit] The guard is a pure filesystem/JSON check but lives in a suite that CI and `CLAUDE.md`
  only ever invoke wrapped in `firebase emulators:exec` (`.github/workflows/ci.yml`,
  "Firestore security rules tests"). So the one assertion set that explicitly does not need the
  emulator is nonetheless gated on the emulator starting. Harmless today; worth knowing if the
  rules suite is ever split out.
- [nit] `architecture.md §3` now carries a "**Done** — ..." status marker inside a normative
  design paragraph. Factually correct, but it mixes build status into a doc `CLAUDE.md`
  describes as design intent; §0 (the gap list) is the usual home for that.
  (planning/architecture.md:119)

No blocking findings.

## Verification performed

The change rests on one technical claim. I confirmed it against primary documentation rather
than accepting it:

1. **Map-subtree inheritance — CONFIRMED.** `firebase.google.com/docs/firestore/query-data/index-overview`,
   verbatim: "If you create an index exemption for a map field, the map's subfields inherit those
   settings. You can, however, define index exemptions for specific subfields. If you delete an
   exemption for a subfield, the subfield will inherit its parent's exemption settings, if they
   exist, or the database-wide settings if no parent exemptions exist." The single entry on
   `details` therefore covers every `details.*` scalar and the `details.symptoms` array-contains
   entry. The change does not under-deliver, and a per-subfield override can still be added later
   if one is ever wanted — so this is reversible, not a one-way door.

2. **`indexes: []` really means all modes off — CONFIRMED in the CLI source.**
   `firebase-tools/lib/firestore/api.js` `patchField` sends
   `PATCH ... {indexConfig: {indexes: []}}` with `updateMask=indexConfig` when `ttl` is absent —
   an explicit empty index config, not an inherit. `validateField` uses a truthiness check
   (`!obj[prop]`), and `[]` is truthy in JS, so the empty array passes validation rather than
   tripping "Must contain indexes". The file is valid strict JSON and matches the canonical
   `fieldOverrides` shape.

3. **`collectionGroup` is the only expressible scope — CONFIRMED.** Firestore single-field index
   configuration is keyed by collection group id; there is no way to scope it to the
   `households/{id}/observations` parent path. The exemption therefore applies to every
   `observations` collection under every household, which is exactly the intent. Grepped the repo:
   `observations` is used as a collection id nowhere else in the current schema or the planned one
   (`architecture.md §3`, `migration.md §3`), so there is no collateral collection group.

4. **The emulator genuinely ignores the file — CONFIRMED.**
   `firebase-tools/lib/emulator/firestoreEmulator.js` only ever passes `rules`, `project_id`,
   `host`, `port` to the emulator binary; `indexes` appears nowhere in its args. The test file's
   header comment and the README bullet are accurate, and the test correctly does not try to
   assert against the emulator.

5. **Retroactivity claim — CONFIRMED.** Changing a property from indexed to excluded affects only
   entities written subsequently; existing index entries persist until the document is rewritten.
   The README bullet and the issue's stated dependency on #7 are right.

6. **`"indexes": []` is a truthful description of current query patterns — CONFIRMED.** Grepped
   `app/src/main` for every `where*`/`orderBy`/`collectionGroup` call. Four `orderBy` calls
   (`SeizureRepository.kt:21`, `HealthNoteRepository.kt:21`, `VetRepository.kt:24`,
   `PetRepository.kt:21`), each on a single field, plus the one bare `whereEqualTo` at
   `VetRepository.kt:53`. Nothing pairs a `where()` with an `orderBy()`, and there is no
   `collectionGroup` query anywhere. No composite index is required, and none is introduced.

## Acceptance criteria

- AC1 (exemption with all modes disabled) — met.
- AC2 (`firebase.json` references it; documented deploy target) — met; README documents all three
  `--only` targets.
- AC3 (verified live) — deferred by agreement; requires an authenticated `firebase deploy` against
  Tom's real project. Documented in README with both a console path and a
  `gcloud firestore indexes fields list` path.
- AC4 (no composite index introduced as a side effect) — met, see item 6 above.

No drift from `architecture.md §3/§10`, `migration.md §4 area 3`, or the normative envelope in
`migration.md §3`. The doc corrections are factually right.

## Notes

- **The one genuinely irreversible thing, and it is at deploy time, not merge time.**
  `firestore.indexes.json` is the *complete* intended index configuration. The first
  `firebase deploy --only firestore:indexes` against the real project will offer to delete any
  composite index or field override that already exists there and is not in this file
  (`api.js` `fieldOverridesToDelete` / `indexesToDelete`). Deleting a composite index is not
  cheap to undo — rebuilding takes time and queries needing it fail meanwhile. Suggest Tom runs
  `gcloud firestore indexes composite list` and `gcloud firestore indexes fields list` against
  the project *before* the first deploy so he knows what the prompt is offering to remove. No CI
  workflow deploys Firestore (checked `.github/workflows/`), so this is a manual, one-person risk.
- **`--only firestore` also overwrites rules.** README step 5 has Tom publishing `firestore.rules`
  by hand in the console. If the live rules have ever drifted from the repo copy, the newly
  advertised `firebase deploy --only firestore` silently replaces them. For this issue's purpose
  he only needs `--only firestore:indexes`. Worth saying out loud even though the README does list
  the narrower target.
- **Follow-up candidate, deliberately out of scope for #4.** The normative envelope
  (`migration.md §3`) also carries `summary` — a synthesized ~60-char render cache, rewritten on
  every observation write — and `loggedByName`. Both are read but never queried, and both get
  automatic ascending + descending single-field indexes. That is the same argument that justifies
  the `details` exemption. Not a defect here; a candidate for a small follow-up issue, ideally
  decided before #7 writes the first observation, since the exemption is not retroactive.
- The merge gate does apply to this push: `check-review-verdict.sh` counts `firestore-tests/` as
  a code path, and `firestore-tests/indexes.test.js` is in the diff.
- Phase 1/2 lint steps were not run and are not applicable: the diff touches no Kotlin, Dart, or
  Gradle files. `qa` owns the test run; I did not run the suites and this verdict does not rest
  on them.
- `security-review` not run. None of its triggers are touched — no `firestore.rules` change, no
  auth change, no migration or export code. The `firebase.json` edit changes what a deploy covers,
  which is the deploy-time risk captured in the first note above.

## Process note — the `/code-review high` pass mis-targeted

The mandated `/code-review high` pass did **not** review this diff. It ran from the main checkout
(HEAD = `db9a5de`), where `origin/main...HEAD` is empty, so it fell back to reviewing the tip
commit — `planning/ios-release.md`, the *parent* of `663fa45`, already merged to main. It never
saw `firestore.indexes.json`, `firebase.json`, or `firestore-tests/indexes.test.js`.

Consequence: it contributes nothing to this verdict. The `Status: PASS` above rests entirely on my
own review, which covered the diff directly and verified the six load-bearing claims listed under
"Verification performed". Nothing is folded in from the skill run.

Its 12 findings on `planning/ios-release.md` are real work but belong to a different commit; they
have been relayed to the Tech Lead separately for a follow-up issue. Two I spot-checked and
confirm: `app/build.gradle.kts:17` is `applicationId = "com.atnip.seizuretracker"` (so the doc's
`flutter create --org com.atnip.seizuretracker` would yield a mismatched bundle id), and
`.github/workflows/release.yml` does trigger a `distribute` job on `v*.*.*` tags (so the proposed
push-to-main distribute job would be a second live path to the same testers).

Side effect: that run also confirmed CI `34154805497` is `success` on `db9a5de` — the run a hook
flagged mid-review. Nothing to fix there.
