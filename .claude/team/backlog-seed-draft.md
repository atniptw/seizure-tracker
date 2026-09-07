# Backlog seed draft — first pass

**Status:** DRAFT ONLY — no issues created (`gh issue create` not run; hold is still on).
**Prepared by:** backlog-owner · **Date:** 2026-09-07
**Backlog state checked:** `gh issue list --state all` → 0 issues. `gh label list` → only GitHub
defaults + `type:chore`/`type:feature`/`type:spike` exist (no `area:*`/status labels yet,
`bug` exists but not `type:bug` — not my lane to fix). `gh api .../milestones` → `[]`. Nothing to
dedupe against.

Read in full: `architecture.md`, `product-spec.md`, `security-privacy.md`, `migration.md`,
`flutter-migration.md`, `CLAUDE.md`, `planning/claude-dev-team.md`.

---

## Important scope note before the list

`claude-dev-team.md §5`'s "candidate seed issues" for Phase 1 — the 5 items Tom approved for
full detail — are **not** a 1:1 cover of `migration.md §4`'s five backfill areas. `migration.md`
defines five areas in this order: **1. Roles · 2. Join-code relocation · 3. `observations`
collection · 4. Medications subcollection + pet `archived` · 5. Export log.** The approved
candidate list only maps to areas **1** (roles) and **3** (`observations`, plus its
`firestore.indexes.json` prerequisite), and adds two cross-cutting safety items (pre-cutover
backup tooling; offline rejected-write verification) that aren't one of the five numbered areas
at all — they're `migration.md §4`'s "Safeguards" and `architecture.md §4`'s offline-cache
caveat, respectively.

**That means areas 2 (join-code → `private/config`), 4 (medications subcollection +
`archived`), and 5 (export log) are not drafted below** — they weren't in Tom's approved
5-item list for this round. Flagging so the next `/groom` pass seeds them explicitly rather than
assuming this round covered all of `migration.md`. See "Gaps I did not file" at the bottom for
the full list of what else `migration.md`/`security-privacy.md` defer that isn't here.

**Recommended landing order for the 5 issues below** (my sequencing, following `migration.md`'s
"later areas lean on earlier ones" plus its own safeguard-first framing — not a re-ordering of
scope, just of sequence):

1. Backup/restore tooling (#2 below) — needed before anything is rehearsed or run against prod.
2. `firestore.indexes.json` + exemption (#1) — independent config chore, can run in parallel
   with roles, but should land no later than the observations backfill.
3. Admin/member roles (#3) — `migration.md`'s area 1, first in its area ordering.
4. `observations` collection (#4) — area 3, depends on roles being live and the index
   exemption in place.
5. Offline rejected-write verification (#5) — nothing to verify rejecting until #3 ships.

---

## Phase 1 — full detail

### 1. Add `firestore.indexes.json` with an `observations.details` single-field index exemption

- **Labels:** `type:chore`, `area:rules`, `area:migration`, `ready`
- **Milestone:** Phase 1 — Firestore shape + rules migration

No `firestore.indexes.json` exists today — `firebase.json` declares only rules + emulators, and
`firebase deploy --only firestore` deploys rules only. Once the `observations.details` map field
lands (issue #4), Firestore auto-creates a single-field index for every `details.*` scalar and
every `details.symptoms` array element, write-amplifying each observation for zero query benefit
— the read pattern is a single `orderBy(occurredAt)` + client-side filtering, never a `details.*`
query.

**Proposal:** add `firestore.indexes.json` at the repo root with a `fieldOverrides` entry for
`observations.details` disabling all index modes; wire it into `firebase.json` so a single
`firebase deploy` (or a documented `--only firestore:indexes` step) covers rules and indexes
together.

**Planning-doc ref:** `architecture.md §3` ("add a single-field index exemption on
`observations.details`... the repo needs a `firestore.indexes.json`... added in the migration
window") and `§10` Deployment; `migration.md §4` area 3, last bullet.

**Acceptance criteria:**
- [ ] `firestore.indexes.json` declares a `fieldOverrides` exemption on `observations.details`
      with all index modes disabled
- [ ] `firebase.json` references the indexes file; `firebase deploy` (or a documented
      `firestore:indexes`-only target) deploys it alongside rules
- [ ] Verified against a real or emulator project that the exemption is live
- [ ] No composite `where()`+`orderBy()` index is introduced as a side effect — the read pattern
      stays `orderBy(occurredAt, descending)` + client-side `type`/pet/logger filtering
      (`migration.md §4` area 3)

**Dependencies:** should land before or alongside issue #4 (the `observations` backfill) —
Firestore doesn't retroactively strip index entries from docs written before the exemption
existed, so any doc written first (including rehearsal data) still gets indexed.

---

### 2. Build pre-cutover backup/restore tooling (`tools/migrate/backup.js` + `restore.js`) and rehearse it

- **Labels:** `type:chore`, `area:migration`, `ready`
- **Milestone:** Phase 1 — Firestore shape + rules migration

Two real people's entire seizure/health history lives only in Firestore. There is no dump/export
tool today, and `migration.md` is explicit that none of the backfill areas (roles, observations,
etc.) should be run against prod without one — the whole migration is framed as "one maintenance
window" whose only real risks are operator error and losing history, both guarded by a backup
taken first and a rehearsal against real data before the window.

**Proposal:** implement `migration.md §4`/`§5` in full — `tools/migrate/backup.js` (Admin SDK,
recursive read of `households/{id}` + every subcollection + `codeIndex/*` → a timestamped local
JSON dump + a per-collection count manifest) and `tools/migrate/restore.js` (delete the affected
collections, re-write every doc from a dump, compare counts to the manifest, report). Deliberately
**not** `gcloud firestore export` — that needs the Blaze plan + a GCS bucket, which
`architecture.md §9` commits to never needing. A README documents the
`GOOGLE_APPLICATION_CREDENTIALS` service-account setup; the key and dumps are gitignored.

**Planning-doc ref:** `migration.md §2` (the risk list) + `§4` "Safeguards" + `§5` "The tooling" +
`§9` "Decisions" (backup mechanism); `security-privacy.md §2.1/§2.3` (the migration tooling
itself as an asset/actor in the threat model).

**Acceptance criteria:**
- [ ] `backup.js` recursively dumps `households/{id}` + all subcollections + `codeIndex/*` to a
      timestamped local JSON file with a per-collection count manifest
- [ ] `restore.js` deletes the named collections, re-writes every doc from a dump, and compares
      resulting counts to the manifest, reporting any mismatch
- [ ] Both scripts are exercised against the Firebase Local Emulator Suite (no prod credentials
      needed for this path) with at least a seeded-data round-trip test
- [ ] `tools/migrate/README.md` documents the service-account credential setup and states the key
      + dump files are gitignored
- [ ] At least one rehearsal is performed before this issue closes: `backup.js` against a copy of
      real data → load into the local emulator → `restore.js` round-trips → counts match the
      manifest (the full backfill-areas rehearsal in `migration.md §5` "Testing the tooling"
      depends on issues #3/#4 and the not-yet-seeded areas 2/4/5 also existing — this issue
      delivers the dump/restore half independently)

**Dependencies:** should land and have at least a basic rehearsal before issues #3 (roles) or #4
(observations) are run against prod — `migration.md §4` step 0 is "take a dump, run the
rehearsal" before anything else in the window.

---

### 3. Admin/member roles — `firestore.rules` + `members/{uid}.role` + matching `firestore-tests` pairs

- **Labels:** `type:feature`, `area:rules`, `area:auth`, `area:migration`, `ready`
- **Milestone:** Phase 1 — Firestore shape + rules migration

Today every household member has identical write access — any member can rename the household,
edit any pet/vet/medication, or remove any other member. The target model splits this into
**admin** (full write access) and **member** (log-and-view, plus edit/delete only the
observations they logged themselves), enforced in `firestore.rules`, not just the app.

**Proposal:** implement `migration.md §4` area 1 in full:
- **Backfill:** `members/{uid}.role` seeded via `--admins=<uid1>,<uid2>` passed explicitly (never
  inferred from the array); any other array uid gets `role: "member"` and is **reported**, not
  silently normalized.
- **App:** `MemberProfile` gains `role`. `MemberRepository.upsertOwnProfile` switches to
  `set(..., SetOptions.merge())` with `role` excluded from the client-written payload — today's
  whole-doc `set()` would clobber `role` once it exists and break the join flow once `role` is
  admin-gated. Add a promote/demote action (an admin writes another member's `role`) and the
  client-side last-admin guard (block a demote/remove that would leave zero admins). Add admin
  checks in the ViewModels/repositories gating pets/vets/medications/household-rename/member
  management — non-admin is a real state the code must handle even though neither current user is
  in it yet.
- **Rules:** `security-privacy.md §8` items 1–4 exactly as specified — the `isMember`/
  `memberRole`/`isAdmin` helpers (the membership-conjunction and the `.get('role','member')`
  default are both load-bearing, not style choices); `members/{uid}` create+self-update with
  `role` locked to absent-or-`"member"`; `members/{uid}` delete admin-or-self-leave;
  household-doc writes admin-only with diff-constrained join/self-leave carve-outs; `pets`/
  `vets`/`petVetLinks`/`pets/{id}/medications` become `read: member; write: admin` (medications
  needs its own nested `match` — a `pets` rule doesn't cover the subcollection).

**Planning-doc ref:** `migration.md §3` (roles table row) + `§4` "1. Roles"; `security-privacy.md
§4.1` (role table), `§4.4` (last-admin invariant, client-only for this release), `§8` (rules
items 1–4 + helper functions).

**Acceptance criteria:**
- [ ] `migrate.js --area=roles` backfills `members/{uid}.role` for `--admins=<uid,uid>`, dry-run
      by default; unrecognized array uids get `role: "member"` and are printed in the summary
- [ ] `MemberRepository.upsertOwnProfile` uses merge-`set()` and never writes `role` from the
      client
- [ ] Promote/demote UI + repository method exists, gated client-side to admin-only
- [ ] Client blocks a demote/remove that would leave zero admins, with a user-facing message
- [ ] `firestore.rules` implements `isMember`/`memberRole`/`isAdmin` exactly as
      `security-privacy.md §8` specifies (`.get('role','member')`, never `.data.role`)
- [ ] `firestore-tests/` has a positive+negative pair for every changed path: `members`
      create/self-update/delete, household-doc write (join carve-out, self-leave carve-out, plain
      admin-only rename), `pets`/`vets`/`petVetLinks`/medications read-vs-write
- [ ] `firestore-tests` case: a joiner cannot rename the household in the same write as joining
      (the `affectedKeys` guard)
- [ ] `firestore-tests` case: a non-admin cannot write `role` on their own or another member's doc
- [ ] `app/src/test` emulator-backed repository/ViewModel suites updated for the new admin gating

**Dependencies:** needs issue #2's backup/restore tooling rehearsed first (`migration.md §4` step
0). Per `migration.md`'s stated area order ("later areas lean on earlier ones"), lands before
issue #4. Also the prerequisite for issue #5 — nothing is admin-gated to reject before this ships.

---

### 4. Collapse `seizures` + `healthNotes` into a unified `observations` collection (Kotlin app)

- **Labels:** `type:feature`, `area:data-model`, `area:migration`, `area:android`, `ready`
- **Milestone:** Phase 1 — Firestore shape + rules migration

⚠️ **Epic-sized** — `migration.md` itself calls this "the largest app diff — `data/model`,
`data/repository`, and every `ui/*` package that touches entries." It's kept as one issue here
because it's one of Tom's approved 5 for this round, but the Tech Lead should consider splitting
it (e.g. backfill script + rules/tests vs. Kotlin model/repository/UI port) when picked up rather
than handing the whole thing to `flutter-dev` in one brief.

Seizures and health notes are two Firestore collections and two Kotlin model/repository classes
today. The target shape is one polymorphic `observations` collection (`type: "seizure"|"note"`,
envelope + `details` map) so history/dashboard/export don't need the client-side merge the
shipped app does today (`ui/common/Entry.kt`'s sealed-merge workaround), and so new observation
types can be added later without a new collection or rules change.

**Proposal:** implement `migration.md §4` area 3 in full — backfill copies every `seizures/*` and
`healthNotes/*` doc into `observations/*` per the exact field mapping in `migration.md §3` (same
doc ids; `medicationGiven`/`medicationDetails` renamed from `rescueMedGiven`/`rescueMedDetails`;
health-note `notes` merged into `description`; `summary` synthesized). App: replace `Seizure` +
`HealthNote` with an `Observation` envelope + a sealed `ObservationDetails` hierarchy; collapse
`SeizureRepository` + `HealthNoteRepository` into `ObservationRepository`; **edits use `update()`,
never `set()`** (a stale offline edit must fail, not resurrect a doc deleted by the other phone);
recompute `summary` on every write; keep the `orderBy(occurredAt)` + client-side-filter read
pattern — no `where()` alongside `orderBy()` without adding a composite index first. Rules: add
`observations` per `security-privacy.md §8` item 6; keep the legacy `seizures`/`healthNotes` rules
live alongside it (they're not touched until the not-yet-seeded §7 cleanup).

**Planning-doc ref:** `migration.md §3` ("`observations` envelope, and how each legacy doc maps")
+ `§4` "3. `observations` collection"; `architecture.md §3` (data model, envelope/`details`
rationale); `security-privacy.md §8` item 6.

**Acceptance criteria:**
- [ ] `migrate.js --area=observations` backfills every `seizures/{id}` → `observations/{id}`
      (`type:"seizure"`) and `healthNotes/{id}` → `observations/{id}` (`type:"note"`), preserving
      legacy doc ids, dry-run by default
- [ ] Seizure fields renamed at backfill: `rescueMedGiven`→`medicationGiven`,
      `rescueMedDetails`→`medicationDetails`; no `recoveryTime`/`recoveryBehavior` fields written
      (phantom names per `migration.md §3`)
- [ ] Health-note backfill merges non-empty legacy `notes` into `description`
      (`description + "\n\n" + notes` when both set); legacy `photoUri` dropped
- [ ] `createdAt` falls back to `occurredAt` when the legacy `createdAtMillis` is missing or `0`
- [ ] Kotlin `Observation` + sealed `ObservationDetails(Seizure|Note)` replace `Seizure`/
      `HealthNote`; `ObservationRepository` replaces `SeizureRepository`+`HealthNoteRepository`
- [ ] All observation edits use `update()`, never `set()` — verified by a test that an offline
      edit against a doc deleted by another device fails instead of resurrecting it
- [ ] `summary` is recomputed and rewritten on every observation write
- [ ] History/dashboard/export read the single `observations` collection with
      `orderBy(occurredAt, descending)` + client-side `type`/pet/logger filtering — no composite
      query added
- [ ] `firestore.rules` `observations` block matches `security-privacy.md §8` item 6 exactly
      (create requires `loggedByUid == auth.uid`; update/delete requires admin-or-author **and**
      immutable `loggedByUid`); legacy `seizures`/`healthNotes` rules left untouched
- [ ] `firestore-tests/` positive+negative pairs for `observations` create/update/delete
      (author-only edit, admin edit, immutable authorship, non-member denied)
- [ ] `migrate.js --area=verify` (or equivalent) asserts
      `count(observations) == count(seizures) + count(healthNotes)` per household, plus a
      field-by-field check of three known entries (oldest seizure, newest seizure, one health
      note) against the old app's rendering
- [ ] UI labels drop "rescue" (`SeizureDetailScreen`, `AddEditSeizureScreen`, PDF/CSV exporters +
      their tests)

**Dependencies:** depends on issue #1 (`firestore.indexes.json`) landing first or alongside, and
issue #2's tooling being rehearsed. Per `migration.md`'s area ordering, lands after issue #3
(roles) — the window's step 7 explicitly re-runs the observations area after both phones are on
the new build, which assumes roles/rules are already deployed.

---

### 5. Verify rejected-write handling for non-admin actions on a real device

*(Round 2 — revised 2026-09-07: Tom resolved the Phase-1/Phase-3 `needs-decision` from the first
draft. This item stays numbered "5" and stays physically here for traceability from the original
draft, but it is now a **Phase 2** thin stub, not a Phase 1 full-detail issue — see the note at
the end of this entry.)*

- **Labels:** `type:chore`, `area:security-privacy`, `area:ios`, `area:android`
- **Milestone:** Phase 2 — Flutter re-platform

Security Rules aren't evaluated against Firestore's local offline cache, so a non-admin device
can optimistically apply an admin-gated write and have it silently reverted only once it flushes
to the server, with no error surfaced on screen. `flutter-migration.md §11` already schedules the
real check for this: on a real, airplane-moded device in Phase 3, verify (a) a rules-rejected
write (a demoted-role edit) doesn't crash the app and doesn't leave silently-reverted/inconsistent
state, and (b) — per `architecture.md §4`'s stated mitigation — the UI never lets a non-admin
*initiate* a gated action in the first place, across both iOS and Android. Ref:
`flutter-migration.md §11`; `architecture.md §4`.

**Resolution note:** the original draft flagged this `needs-decision` because the candidate list
put it in Phase 1 (Kotlin, emulator-based) while the only planning-doc text calling for
*device-level* verification is `flutter-migration.md §11`'s Phase 3 real-device check. Tom
decided to fold this into that Phase 3 check rather than build a separate Kotlin-side emulator
version — the Kotlin app retires within weeks of the Flutter cutover (`migration.md §2`), so a
Phase-1-only verification would be thrown away almost immediately. No Phase 1 issue for this
remains.

---

## Phase 2 — thin stubs

### 6. Flutter scaffold + dart-define flavors + CI

- **Labels:** `type:chore`, `area:ios`, `area:android`, `area:ci`
- **Milestone:** Phase 2 — Flutter re-platform

Scaffold the Flutter app (`flutter create`, org `com.atnip.seizuretracker`), add the core package
set, run `flutterfire configure` to register the iOS app and emit config, wire the Firebase
emulator for local dev, and stand up `flutter analyze` + `riverpod_lint` + a CI job. Includes the
iOS platform chores flagged as the largest front-loaded risk: Apple Developer enrolment, bundle
id, an App Store Connect record, both testers added as internal TestFlight testers, the
Google-Sign-In URL scheme in `Info.plist`, and one green `flutter build ipa`. Sequenced after all
of Phase 1 lands (`flutter-migration.md §2`) so the client is built against the already-migrated
Firestore shape. Ref: `flutter-migration.md §4, §8` "Phase 0", `§10, §11`.

### 7. Port state management to Riverpod

- **Labels:** `type:chore`, `area:ios`, `area:android`
- **Milestone:** Phase 2 — Flutter re-platform

Replace the Kotlin `ViewModel`+`StateFlow` layer with Riverpod (`StreamProvider`/
`NotifierProvider`/`AsyncNotifierProvider`, `.family`/`.autoDispose`), including a
`sessionProvider` mirroring `SessionViewModel`'s `Loading`/`NeedsSetup`/`Ready` states, an
`isAdminProvider` (the membership+role conjunction mirroring the rules' `isAdmin()`), and the
`go_router` `redirect` + `refreshListenable` bridge flagged as the fiddliest single piece of the
port. Ref: `flutter-migration.md §6`.

### 8. Port auth (Google sign-in only)

- **Labels:** `type:feature`, `area:auth`, `area:ios`, `area:android`
- **Milestone:** Phase 2 — Flutter re-platform

Port `AuthRepository` to `firebase_auth` + `google_sign_in`. The next release stays Google-only,
so the "continue without an account" path and `signInAnonymously` are dropped, not ported —
deferred until anonymous sign-in returns with its safety net (`security-privacy.md §3.1`). Watch
`google_sign_in` ≥7.x's breaking `initialize`/`authenticate` API and the required iOS URL-scheme
entry. Ref: `flutter-migration.md §3, §5, §8` "Phase 2"; `security-privacy.md §3.1`.

### 9. Port screens to Flutter (epic — file per-screen sub-issues at pickup)

- **Labels:** `type:feature`, `area:ui`, `area:ios`, `area:android`
- **Milestone:** Phase 2 — Flutter re-platform

Port the ~20 screens + ~12 shared widgets inventoried in `flutter-migration.md §7` at parity with
the shipped Kotlin app, plus one new read surface (the past-exports list,
`security-privacy.md §8` item 10). This is an epic — split into sub-issues along
`flutter-migration.md`'s own phase grouping (shared widgets/Phase 0, logging core/Phase 3, pets &
vets/Phase 4, household & settings/Phase 5) rather than filing it as a single issue. Ref:
`flutter-migration.md §7, §8`.

### 10. Port PDF/CSV export to Dart

- **Labels:** `type:feature`, `area:export`, `area:ios`, `area:android`
- **Milestone:** Phase 2 — Flutter re-platform

Replace the hand-rolled `android.graphics.pdf.PdfDocument` exporter and hand-rolled CSV
string-building with the `pdf`+`printing` packages (CSV package optional — the hand-rolled version
is ~40 lines either way). Budget a design pass: the `pdf`/`printing` output is expected to look
different from the current renderer, and there's no unit test on today's PDF layout to diff
against. Ref: `flutter-migration.md §4, §8` "Phase 6", `§11`.

### 11. iOS signing + TestFlight release pipeline

- **Labels:** `type:chore`, `area:ios`, `area:ci`
- **Milestone:** Phase 2 — Flutter re-platform

Stand up the release side of iOS distribution: an Apple Developer account, a distribution
certificate (mandatory from the first upload — unlike Android's currently debug-signed release
build), a macOS CI runner job, ASC API key-based upload, and TestFlight internal testing for both
people (no Beta App Review needed for internal testing). Recurring cost to track: TestFlight
builds expire after 90 days, a rebuild-and-upload chore Android App Distribution doesn't have.
Ref: `flutter-migration.md §10, §11`; memory `ios-signing-checklist`.

### 12. In-app account/data deletion

- **Labels:** `type:feature`, `area:security-privacy`, `needs-decision`
- **Milestone:** Phase 2 — Flutter re-platform

**Weakest-grounded stub in this batch.** Today, deleting an account is a manual two-step process
(unlink Google in settings, then a separate Firebase Auth user deletion —
`security-privacy.md §7`), not a dedicated in-app flow. `claude-dev-team.md §5` defers this to
"the first external build," but no planning doc actually specifies an in-app deletion UI, and
none names the likely real driver (an app-store account-deletion requirement for any app that
supports account creation) explicitly. Needs a decision on what "in-app" deletion should cover
beyond today's two manual steps, and when it's actually required, before this gets acceptance
criteria. Ref: `security-privacy.md §7` "Delete my account" row; `claude-dev-team.md §5`.

---

## Backlog / post-v1 — thin stubs

### 13. Household notifications

- **Labels:** `type:feature`, `area:data-model`, `needs-decision`
- **Milestone:** Backlog / post-v1
- *(Area-label fit is imperfect — notifications don't cleanly match any label in the taxonomy;
  `area:data-model` is the closest since the open question is a Cloud Function trigger on
  `observations` writes. Worth a dedicated `area:notifications` label if this ever gets built.)*

"Saving notifies the rest of the household" is deferred because it needs a Cloud Function trigger
on observation-create (a client can't push to another user's device directly), and deploying any
Function forces the project onto the Blaze plan — which every other design decision in this
project is built to avoid. The approach is sketched but the Blaze-vs-no-Function trade-off is an
explicit open decision, not settled. Ref: `architecture.md §5`; `security-privacy.md §9`;
`product-spec.md §4.0` (deferred list).

### 14. Photo/video attachments

- **Labels:** `type:feature`, `area:ui`, `area:data-model`
- **Milestone:** Backlog / post-v1

Local-only, no cloud storage: Firestore stores just a small attachment reference (who captured
it, type); sharing a file to another member or a vet is a manual action through the OS share
sheet (`share_plus` — AirDrop on iOS, Nearby Share on Android). This approach is **settled**, not
open (`architecture.md §8`, `security-privacy.md §5.4`) — what's unbuilt is the feature itself,
including the "receiving a shared attachment" UX (matching a file that arrives via the share
sheet back to the correct observation on the recipient's device). Ref: `architecture.md §8`;
`security-privacy.md §5.4`; `product-spec.md §4.0`.

---

## Gaps I did not file

Scope explicitly deferred or decided-but-not-built in the planning docs, not filed here because
it wasn't in Tom's approved 5-item Phase-1 list or the named Phase-2/backlog stubs — flagging so
a future `/groom` pass seeds these deliberately rather than the backlog looking "done" after this
round:

- **`migration.md §4` area 2** — join-code relocation to `households/{id}/private/config`
  (admin-only read/write). Rules item 7 in `security-privacy.md §8`.
- **`migration.md §4` area 4** — medications subcollection (`pets/{id}/medications/{medId}`,
  content-hash doc ids) + pet `archived: bool` (archive-instead-of-hard-delete).
- **`migration.md §4` area 5** — the `exportLog` collection (admin-create, member-read) and
  making export admin-gated.
- **`migration.md §7`** — the post-Flutter-cutover cleanup (drop legacy `seizures`/
  `healthNotes`/`code`/embedded-medications). Explicitly gated on the Flutter cutover, not a
  calendar date — premature to seed until Phase 2 is underway.
- **Code rotation** (`security-privacy.md §4.2, §10`) — explicitly "not built here," deferred to
  its own follow-up PR with its own rules + tests.
- **Firebase App Check** (`security-privacy.md §2.3, §10`) — named as "the highest-value item" on
  the deferred list, device attestation against `codeIndex` brute-forcing.
- **CSPRNG swap for `HouseholdCode`** (`security-privacy.md §2.3, §10`) — `Random.Default` →
  `SecureRandom`/`Random.secure()`; earmarked for the Flutter port specifically.
- **Cloud backup / PITR for the health record** (`security-privacy.md §2.4, §10`) — closes the
  data-loss-by-member-error gap; has Blaze-plan implications.
- **Per-invite single-use codes** (`security-privacy.md §4.2, §10`) — deferred design question,
  no Function work started.
- **Anonymous sign-in + everything downstream of it** (§3.1–3.3, §4.5, the force-link gate,
  `lastActiveAt` stranding detection) — explicitly out of the next release; Google-only for now.
- **Two open self-contained decisions** noted in `security-privacy.md §10`: can a member edit
  their own display name (leans "yes, cheap" per the doc's own reasoning), and should a member be
  able to export (leans "revisit with real usage"). Neither is scheduled; both are candidates for
  a `needs-decision` issue if/when picked up.
- **Household teardown / recursive delete** — no UI, no rule, not in the next release; the
  Cloud Function is design-only.
- **Deferred product surface** named throughout `product-spec.md §4.0`: pet `diagnosisDate`,
  history filters, the frequency-trend chart, the combined all-pets dashboard view,
  compare-to-similar-entries, Apple sign-in, QR-code join, join preview-before-confirm, web
  dashboard/Firebase Hosting.

## Planning-doc inconsistencies found

1. **The Phase-1 candidate list vs. `migration.md`'s five areas** (detailed above under "scope
   note") — the approved 5-item list is a strict subset of `migration.md §4`, not a full cover.
   Not a contradiction inside the docs themselves, but worth flagging so nobody assumes this
   round finished Phase 1.
2. **"Offline rejected-write verification" has two different homes** — `claude-dev-team.md §5`
   lists it as a Phase 1 (Kotlin) item, but the only planning text that calls for verifying
   rejected writes on a real device is `architecture.md §4`'s pointer to
   `flutter-migration.md §11`, Phase 3 of the Flutter rewrite. I drafted issue #5 as a lighter
   Phase-1 emulator-based check and flagged it `needs-decision` on whether the heavier device
   check should also run against the (soon-to-retire) Kotlin app or wait for Phase 3. See issue
   #5's rationale.
3. No other direct contradictions between docs found — `migration.md` already self-corrects one
   internal error I checked (§4 area 1's rules-item list: "the earlier '1, 2, 4, 5, 10' list here
   was wrong," corrected in the same paragraph to "1, 2, 3, 4"), so nothing to flag there beyond
   what the doc already flags itself.

---

# Round 2 — 2026-09-07

Tom reviewed the first draft and made three calls (relayed by the Tech Lead):

1. Seed `migration.md §4`'s three remaining areas (join-code relocation, medications +
   `archived`, export log) at full detail, Phase 1 milestone — drafted below as issues #15–#17.
2. Issue #5 (offline rejected-write verification) moves to Phase 2, folded into
   `flutter-migration.md §11`'s real-device check — **edited in place above**, not re-drafted
   here.
3. Issue #4 (`observations`) stays one issue with its epic warning — no change.

Labels below use the now-created taxonomy exactly: `area:rules` `area:auth` `area:data-model`
`area:migration` `area:ios` `area:android` `area:ci` `area:export` `area:ui`
`area:security-privacy`, plus `blocked`/`needs-decision`/`ready`. Plain `bug`, not `type:bug`.

## Revised end-to-end Phase 1 landing order (7 issues)

Supersedes the "Recommended landing order" note at the top of this file (which only covered
issues #1–#4). This follows `migration.md §4`'s own area sequence (1 roles → 2 join-code → 3
observations → 4 meds → 5 export log — "later areas lean on earlier ones") with the two
cross-cutting safety issues slotted at their actual dependency points, not forced into the
doc's area numbering:

1. **#2 — backup/restore tooling.** No data dependency itself, but `migration.md §4` step 0 is
   "take a dump, run the rehearsal" before any area touches prod — must exist and have at least
   one rehearsal first.
2. **#3 — admin/member roles.** `migration.md` area 1. Provides the `isMember`/`isAdmin` rules
   helpers every other area's rules depend on.
3. **#15 — join-code relocation.** `migration.md` area 2. Needs `isAdmin()` from #3; otherwise
   independent of everything else.
4. **#1 — `firestore.indexes.json` exemption.** Not one of `migration.md`'s numbered areas, but
   its only real dependency is "must land before or alongside the `observations` backfill" — this
   is the natural slot, immediately before #4/observations rather than earlier.
5. **#4 — `observations` collection.** `migration.md` area 3. Depends on #3 (roles/rules
   pattern) and #1 (index exemption in place before the `details` map gets written).
6. **#16 — medications subcollection + pet `archived`.** `migration.md` area 4. Needs `isAdmin()`
   from #3; no technical dependency on #15 or #4 that I could find in `migration.md` — the doc's
   "keep this sequence" instruction reads as a checklist order for one migration window rather
   than areas 2/3/4 depending on each other directly. Flagging in case Tom wants strict
   area-order adherence regardless.
7. **#17 — export log.** `migration.md` area 5, last in the doc's own ordering. Needs `isAdmin()`
   from #3; the most independent of the three new areas — could land any time after #3.

## 15. Relocate the join code to admin-only `households/{id}/private/config`

- **Labels:** `type:feature`, `area:rules`, `area:migration`, `area:security-privacy`, `ready`
- **Milestone:** Phase 1 — Firestore shape + rules migration

Today the join code is a plaintext `code` field on the household doc, which **every** member can
read — there's no rules-level way to enforce "a member can't see the join code"
(`security-privacy.md §4.1`), only a UI-level hide. The target moves the code to an admin-only
`households/{id}/private/config` doc. The separate top-level `codeIndex/{code}` collection (which
maps a human-typed code to a household id, so a device that isn't a member yet can resolve a code
before it can read the household doc at all) is a different mechanism and is untouched by this
issue.

**Proposal:** implement `migration.md §4` area 2:
- **Backfill:** write `households/{id}/private/config = { joinCode: <current households/{id}.code
  value> }`. Additive only — `households/{id}.code` is left in place until the separate,
  not-yet-scheduled `migration.md §7` cleanup issue removes it, so the legacy rules (and a
  rollback to the old app) stay valid throughout.
- `codeIndex/{code}` keeps its existing `{ householdId }`-only shape and rules, unchanged — the
  join preview that would add `householdName` is deferred (`product-spec.md §4.0`).
- **App:** an admin-gated "show/share join code" UI reads from `private/config` instead of the
  household doc. No join-preview screen — enter code → join stays the flow.
- **Rules:** `private/config` — `allow read, write: if isAdmin(id)`; `codeIndex` `get` stays any
  signed-in user by exact id (required for the join); `codeIndex` `create` asserts the
  `{ householdId }`-only shape; `list` stays `false`. **Do not** gate `codeIndex`
  create/update/delete to admin-of-target-household in this issue —
  `security-privacy.md §8` item 8 marks that (and the non-anonymous-creator assertion) explicitly
  **post-v1, shipping with code rotation**, and it requires household creation to reorder its
  writes first (household doc → member doc → `codeIndex`), which is out of scope here.
- **Explicitly not in this area** (per `migration.md`'s own callout): code rotation (its own
  follow-up PR with its own rules + tests); any change to household creation's write order.

**Interaction with the join flow, spelled out:** a device that hasn't joined a household has no
uid in that household's `members` array, so it could never read the household doc *or*
`private/config` either way — before this change, a non-member's only route to the code was
never through a household-level read at all. The only thing a joining device reads is
`codeIndex/{code}` by exact id, which this issue does not touch. So the join flow (enter code →
`codeIndex` get → household id → write own uid into `members`) is unaffected; what changes is only
where an *existing admin* reads the code back from to show/share it.

**Planning-doc ref:** `migration.md §4` "2. Join-code relocation"; `security-privacy.md §4.2`
(joining mechanics) + `§8` item 7 (`private/config` rule) + item 8 (`codeIndex` shape assertion,
rotation/durable-creator explicitly post-v1).

**Acceptance criteria:**
- [ ] `migrate.js --area=joincode` writes `households/{id}/private/config = { joinCode: <value of
      households/{id}.code> }`, dry-run by default, idempotent (fixed doc path, merge write)
- [ ] `households/{id}.code` is left untouched by this area (removed only by the separate,
      not-yet-scheduled §7 cleanup issue)
- [ ] `codeIndex/{code}` document shape and rules are unchanged by this issue
- [ ] `firestore.rules` adds `households/{id}/private/config` — `allow read, write: if
      isAdmin(id)` (using the `isAdmin` helper from issue #3)
- [ ] Kotlin app: an admin-only "show/share join code" UI reads from `private/config`, not the
      household doc's `code` field
- [ ] `firestore-tests/` pair: an admin can read/write `private/config`; a non-admin member and a
      non-member are both denied
- [ ] `firestore-tests/` confirms a signed-in-but-not-yet-member device can still `codeIndex.get`
      by exact id, and cannot `list` `codeIndex`
- [ ] End-to-end join flow (enter code → resolve via `codeIndex` → join) verified unaffected
- [ ] Household creation's write order and code-minting behavior are **not** changed by this
      issue (scoped to the unscheduled rotation follow-up)

**Dependencies:** needs issue #3 (roles) for the `isAdmin()` rules helper. No dependency on
issue #1, #4, #16, or #17.

## 16. Add medications subcollection + pet `archived` flag

- **Labels:** `type:feature`, `area:data-model`, `area:migration`, `area:android`, `ready`
- **Milestone:** Phase 1 — Firestore shape + rules migration

Medications are an embedded array on the `Pet` doc today — discontinuing one means deleting it
from the array, silently erasing history a vet might ask about later ("was he ever on X"). And
removing a pet is a hard delete that orphans its logged observations (a shipped-app quirk). The
target: medications become their own subcollection with `active`/`startDate`/`endDate` so
discontinuing is an update, not a delete; pets gain `archived: bool` so "remove a pet" becomes
archive-not-delete.

**Proposal:** implement `migration.md §4` area 4:
- **Backfill, per pet:** (a) set `archived: false` on the pet doc if the field is absent; (b) for
  each entry in the embedded `medications` array, create
  `pets/{petId}/medications/{hashId}` — `hashId` = first 20 hex of
  `SHA-256(JSON.stringify([name, dose, frequency, notes]))` — with the medication's fields plus
  `active: true`, `startDate: null`, `endDate: null`. `startDate` stays `null`: the legacy data has
  no real start date, and inferring one from the pet's creation date would fabricate clinical
  history.
- **Guard:** skip any pet whose `medications` subcollection is already non-empty (so a re-run
  after an in-app edit doesn't duplicate); assert per pet
  `count(subcollection docs written) == medications.length` and **abort the whole area** on any
  mismatch.
- **App:** `Pet` drops the embedded `medications` array, gains `archived: bool`. `PetRepository`
  reads the subcollection. New medications the app creates use Firestore auto-ids, never the
  backfill hash (the hash is a backfill-only device, never re-derived or treated as an identity
  scheme). "Discontinue" a medication becomes `active: false` + `endDate` set, never a delete; the
  current-medications UI filters `active == true`. "Remove pet" sets `archived: true` instead of
  hard-deleting (a true delete stays available only for a pet with zero observations). The
  active-pet switcher and dashboard both filter `archived == false`; **the export pet picker does
  not** — it lists every pet (archived ones tagged) so an archived pet's history stays reachable
  for a vet report.
- **Rules:** `pets/{petId}/medications/{medId}` gets its own nested `match` block — `read: if
  isMember(hid); write: if isAdmin(hid)` — a `pets/{petId}` rule does not automatically cover this
  subcollection. The pet doc's existing admin-only write rule already covers the `archived` flip;
  no separate rule needed for that field.
- **Explicitly out of scope:** a past-medications view and any "set an alarm" hand-off are both
  post-v1 client features (`product-spec.md §4.0`) — `startDate`/`endDate`/`active` are stored and
  written here, but the next release only ever shows the active list.

**Planning-doc ref:** `migration.md §3` (medications + `archived` rows, "what changes" table) +
`§4` "4. Medications subcollection + pet `archived`"; `architecture.md §3` (medication doc shape
rationale); `security-privacy.md §7` (deletion & retention — "discontinue a medication" / "remove
a pet" rows) + `§8` item 5 (nested `match` rule).

**Acceptance criteria:**
- [ ] `migrate.js --area=meds` backfills, per pet: `archived: false` if absent, and one
      `pets/{petId}/medications/{hashId}` doc per embedded medication entry (hash per the formula
      above)
- [ ] Backfilled medication docs have `active: true`, `startDate: null`, `endDate: null`
- [ ] Backfill skips (does not duplicate into) any pet whose `medications` subcollection is
      already non-empty
- [ ] Backfill asserts `count(subcollection docs written) == length(pet.medications array)` per
      pet and aborts the area on any mismatch, without partially applying to other pets
- [ ] Kotlin `Pet` model drops the embedded `medications` array and gains `archived: bool`;
      `PetRepository` reads `pets/{petId}/medications`
- [ ] New medications created via the app use Firestore auto-ids, never the content hash
- [ ] "Discontinue medication" sets `active: false` + `endDate`, never deletes the doc
- [ ] Current-medications UI filters `active == true`
- [ ] "Remove pet" sets `archived: true`; a hard delete is offered only when the pet has zero
      observations
- [ ] Active-pet switcher and dashboard filter `archived == false`; the export pet picker does not
      filter by `archived` (archived pets shown, tagged)
- [ ] `firestore.rules` adds a nested `pets/{petId}/medications/{medId}` match: `read: if
      isMember(hid); write: if isAdmin(hid)`
- [ ] `firestore-tests/` pair for the medications subcollection (member read allowed, member write
      denied, admin write allowed) and for a non-admin flipping `archived` on a pet doc (denied)
- [ ] `migrate.js --area=verify` (or equivalent) asserts
      `count(pets/{petId}/medications) == length(pet.medications array)` per pet, matching the
      `migration.md §5` verification gate

**Dependencies:** needs issue #3 (roles) for `isAdmin()`. No technical dependency on issue #15 or
#4 found in `migration.md` — see the landing-order note above.

## 17. Add `exportLog` collection and make export admin-gated

- **Labels:** `type:feature`, `area:export`, `area:migration`, `area:android`, `ready`
- **Milestone:** Phase 1 — Firestore shape + rules migration

Exporting a PDF/CSV report today leaves no household record of when it happened or who did it,
and any member — not just an admin — can trigger it, even though `product-spec.md`/
`architecture.md §7` frame sharing a vet report as an admin action (an export leaves the household
as a file). The target: export becomes admin-gated, and every successful export leaves a small
record any member can read.

**Proposal:** implement `migration.md §4` area 5:
- **No backfill** — `exportLog` is a new, empty collection.
- **App:** gate the existing export screen/action behind the `isAdmin` check from issue #3 — a
  non-admin should not be able to reach or trigger export at all, consistent with "never let a
  non-admin initiate a gated write" (`architecture.md §4`). On a successful export, write one
  `households/{id}/exportLog/{id}` doc: `{ type, rangeStart, rangeEnd, petIds, createdAt }`.
- **Rules:** `exportLog/{id}` — `create: if isAdmin(hid); read: if isMember(hid)`; no update, no
  delete.
- **Explicitly out of scope:** a UI to browse/list past exports.
  `flutter-migration.md §7` treats the past-exports list as a new Flutter-only read surface ("the
  shipped app has no read side") — this issue only writes the log; no Kotlin-side browsing UI is
  required by `migration.md` and none is drafted here.

**Planning-doc ref:** `migration.md §3` (export log row, "what changes") + `§4` "5. Export log";
`architecture.md §7` (export log rationale, admin-gated export); `security-privacy.md §4.1`
(export row in the role table) + `§8` item 10 (rules) + `§10` (member-export flagged as
revisit-later — see note below).

**Acceptance criteria:**
- [ ] Export (PDF and CSV) in the current Kotlin app is only reachable/triggerable by an admin
      session; a non-admin sees no export entry point
- [ ] On a successful export, the app writes one `households/{id}/exportLog/{id}` doc:
      `{ type, rangeStart, rangeEnd, petIds, createdAt }`
- [ ] `firestore.rules` adds `exportLog/{id}`: `create: if isAdmin(hid); read: if isMember(hid)`;
      no update or delete for anyone
- [ ] `firestore-tests/` pair: admin can create, member cannot; any member (including non-admin)
      can read; nobody can update or delete an existing entry
- [ ] No backfill script needed for this area (confirmed empty-collection start)
- [ ] No Kotlin UI added in this issue to browse past `exportLog` entries (deferred to the
      Flutter past-exports-list screen, issue #9)

**Dependencies:** needs issue #3 (roles) for `isAdmin()`. No dependency on #1, #15, #4, or #16 —
the most independent of the three new areas.

**Not `needs-decision`, but worth a footnote:** `security-privacy.md §10` flags "should a member
be able to export?" as an open question to revisit with real usage, calling loosening it later
low-risk. The decision *for this issue* is already made (admin-only, per the §4.1 table) — this
is a known, deliberate revisit-later, not a blocker.

## Minor doc note (not blocking, not filed as an issue)

`migration.md §4` area 2's text references `security-privacy.md §8` **item 7** for the
"admin-of-target-household `codeIndex`-create check requires the creator's `members/{uid}` doc to
exist first" caveat. Re-reading `security-privacy.md §8`'s own numbered list, that caveat is
actually under **item 8** (the `codeIndex` item) — item 7 is `private/config`. Likely a stale
cross-reference in `migration.md`, not a substantive disagreement — both docs agree the
admin-of-target-household gate is post-v1 (ships with code rotation), so nothing in issue #15's
acceptance criteria depends on resolving which item number is correct. Flagging only so whoever
touches `migration.md` next fixes the citation.

## Needs-decision check across the three new areas

None of #15/#16/#17 needed a `needs-decision` label — all three were fully specified in
`migration.md §4` with no open branch point blocking implementation. The only things worth Tom's
awareness (neither blocking): the item-7-vs-8 citation slip above, and the already-acknowledged
"member export access" revisit-later flag under issue #17.
