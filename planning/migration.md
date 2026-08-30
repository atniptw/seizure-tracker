# Migration Plan — shipped Firestore shape → target backend

**Status:** draft for discussion · **Last updated:** 2026-08-30
**Companion docs:** `architecture.md` (target data model, §0 gap list), `product-spec.md`
(features, entities, "what the next release contains"), `security-privacy.md` (roles, join
mechanics, §8 rule changes), `flutter-migration.md` (the client rewrite this sequences before)

## 1. Scope

This plan covers the **backend / data-model move** on the *current Kotlin + Compose app*:
getting the shipped app onto the Firestore shape, Security Rules, and role model that
`architecture.md` and `security-privacy.md` describe, **without losing any of the household's
logged history**. Landing the data-shaped decisions now — while the schema is small — means
the eventual Flutter port (`flutter-migration.md`) is a near-pure client rewrite against a
backend that already looks like its target.

**Out of scope** (deferred — see §8 for why each is safe to defer):

- The Flutter client rewrite (`flutter-migration.md`, sequenced after this).
- **Any Cloud Functions.** Deploying one forces the project onto the Blaze plan (billing
  account required since the 2024 change — see `architecture.md §8`). Nothing here needs one.
- **Renaming the `members` array to `memberIds`.** The array keeps its shipped name. Nothing
  in the target design depends on the name, and renaming the field that `firestore.rules`
  gates every read and write on is a lockout-class change for no benefit — `architecture.md`
  §3/§6 have been updated to say `members`.
- **Code rotation.** Rotating/regenerating the join code doesn't exist in the app and isn't
  built here — it's genuinely new feature work, and for two people who coordinate in person
  the "a removed member kept the code" threat is ~nil (neither is leaving). Deferred to a
  follow-up PR with its own rules + tests; see §4 area 2 and `security-privacy.md §2.3`.
- Consolidating `petVetLinks` into a `linkedVets` array on the pet doc (dropped from the
  target entirely — see §8 and `architecture.md §3`).
- Household teardown / recursive delete (no such feature ships today).

After this migration the target docs still describe more than the app does; the remaining gap
is entirely *client features* and *scale optimizations*, not *data shape*.

## 2. The user base is two people on a closed track

This is the fact that shapes the entire approach:

- **Two users, two devices** — Tom and his wife, one household.
- **Closed Firebase distribution.** The app ships only to the two of them, as testers on a
  closed track. There is no public listing; no other install is even possible. There is no
  such thing here as a "client in the wild," a straggler stuck on an old version, or an
  unknown write shape reaching Firestore.
- Both devices update on request, in person.

So the migration is **one maintenance window**, not a phased rollout with dual-read/dual-write
compatibility windows, a min-version gate, or a multi-week bake. There is nothing to stay
backward-compatible *with* once both phones are on the new build, because those two phones are
the only clients that exist.

The risks that do remain are all about **losing real seizure history**, and there are three,
each with a matching safeguard in §4:

1. **Operator error during the backfill** — a bad `--commit`, a mapping bug. Guarded by: a
   local JSON dump taken first, an idempotent dry-run-first script, and a rehearsal of the
   whole backfill against a copy of the real data before the window.
2. **Entries logged around the window boundary** — anything written to the legacy collections
   between the observations copy and both phones updating. Guarded by: flushing queued writes
   before the window, and a mandatory re-run of the observations area *after* both phones are
   on the new build.
3. **The cleanup delete (§7)** — the one irreversible step. Guarded by: a fresh dump
   immediately before it, and an automated count assertion that blocks the delete on a
   mismatch.

One thing the "one window" framing does **not** give you: a lossless undo after cutover. Once
the new build writes its first entry, that entry exists only in the new shape — "redeploy the
old app" from that point on silently drops everything logged since. The lossless-rollback
window ends at the first write from the new build (§4); after that the path is fix-forward.

## 3. What changes

| Area | Today (shipped) | After this migration | Notes |
|---|---|---|---|
| Logged events | `seizures/{id}` + `healthNotes/{id}` — two collections, two model classes | `observations/{id}` — one polymorphic collection, envelope + `details` map | `architecture.md §3`. Biggest change; needs a backfill. |
| Event timestamp | `timestampMillis: Long` (epoch millis) | `occurredAt: Timestamp` + `createdAt` / `updatedAt: Timestamp` | **Observations only.** Firestore-native `Timestamp`; backfill converts. Non-observation `*Millis` fields (`Pet.birthDateMillis`, every doc's `createdAtMillis`, `MemberProfile.joinedAtMillis`) **stay `Long`** — see the note below the table. |
| Roles | none — every member has full write access | `members/{uid}.role: "admin" \| "member"` | `security-privacy.md §4.1`. Backfill seeds **the two known uids as `admin`** (passed explicitly, not inferred); any other array uid is created `role: "member"` and reported. |
| Membership source of truth | `households/{id}.members: [uid]` array (also the access-check) | **unchanged** — same name, same client writes | The array on the household doc is the documented source of truth for *access*; `members/{uid}` is the source of truth for *metadata + role*. No rename (see §1). |
| Member metadata | `members/{uid}` — `displayName`, `authMethod`, `joinedAtMillis` | + `role` | `authMethod` rename (`signInMethod`→`authMethod`) already done in code. `lastActiveAt` is **not** added here — it's post-v1 (`security-privacy.md §4.5`), since both members are Google and stranding can't occur. |
| Join code | `households/{id}.code` field (every member reads it) | `households/{id}/private/config.joinCode` — admin-only read/write | `security-privacy.md §4.2`, §8 item 6. Removed from the household doc at cleanup. |
| Code index | `codeIndex/{code}` = `{ householdId }` | `codeIndex/{code}` = `{ householdId, householdName }` | `security-privacy.md §8` item 8. Adds the join-preview name, nothing else. |
| Medications | `Pet.medications: [Medication]` embedded array; discontinue = delete | `pets/{petId}/medications/{medId}` subcollection + `active`, `startDate`, `endDate` | `architecture.md §3`. Backfill lifts the array into docs. `startDate` backfills to **`null`** (the legacy data has no real start date — inferring one from the pet doc's creation date would fabricate clinical history). |
| Attachments | dormant `photoUri: String` on `HealthNote` and `Pet` (never displayed, now removed from code) | nothing — attachments are post-v1 (`architecture.md §8`) | Backfill drops any legacy `photoUri` value. The `attachment` envelope field is added when attachments are actually built. |
| Export log | none | `households/{id}/exportLog/{id}` — admin-create, member-read | `architecture.md §7`. New, empty collection; no backfill. |

**Not changed by this migration** (kept at parity with the shipped app; several appear as
"v1" in `product-spec.md §4` but move to a later release — see `product-spec.md` "What the
next release contains"): pet `archived` / `diagnosisDate` fields, history filters, the
frequency-trend chart, the combined all-pets dashboard view, the in-progress seizure timer,
voice dictation, compare-to-similar-entries, Apple sign-in. Deleting a pet still hard-deletes
(and orphans its observations — a pre-existing quirk, not introduced here).

### `observations` envelope, and how each legacy doc maps

```
observations/{id}
  type: "seizure" | "note"
  petId, loggedByUid, loggedByName
  occurredAt: Timestamp        # from timestampMillis
  createdAt:  Timestamp        # from createdAtMillis; if missing OR 0 (the Kotlin default,
                               #   present on early docs) fall back to occurredAt
  updatedAt:  Timestamp        # = createdAt at backfill time
  summary: string              # synthesized (see below); a RENDER CACHE, not authoritative
  details: { ...type-specific... }
  # no attachment field — attachments are post-v1 (architecture.md §8)
```

This field list is **normative** — where `architecture.md §3` or `flutter-migration.md §5`
use different names for the seizure `details` fields, they're illustrative and defer to the
names here (which are the ones the shipped `Seizure` model actually uses and the backfill
actually reads).

- **`seizures/{id}` → `type: "seizure"`.** `details` gets `durationSeconds`, `seizureType`,
  `symptoms` (list), `preSeizureSigns` (string), `possibleTriggers` (string),
  `recoveryMinutes`, `recoveryNotes`, `rescueMedGiven`, `rescueMedDetails`, `notes`. There is
  **no** `recoveryBehavior` / `recoveryTime` field — those names in `architecture.md §3` and
  `product-spec.md §3` are phantom; no such data exists. `summary` = e.g.
  `"4 min · Generalized (grand mal)"` from duration + type, `"seizure"` if both empty.
- **`healthNotes/{id}` → `type: "note"`.** `details` gets `description`, `notes`.
  `summary` = first ~60 chars of `description`. Any legacy `photoUri` value is dropped.
- Legacy `id` is **preserved** as the new doc id (`observations/{sameId}`), so a backfill
  re-run is idempotent and any local reference survives.
- **`summary` is recomputed and rewritten on every observation write by the app** — it's a
  cache of a feed line derived from fields *in the same document*, so it saves no read; it
  exists only so the history feed doesn't have to format every row. If the new app ever
  writes an observation without recomputing it, the feed shows a stale line indefinitely.
  (The Flutter client may instead drop the field and format the line at render time —
  `flutter-migration.md` decides; either way the backfill populates it so history isn't
  blank on day one.)

## 4. Approach: one window, one script, one rules deploy

### Safeguards

- **A local JSON dump before touching anything.** `tools/migrate/backup.js` — Admin SDK,
  recursive read of `households/{id}` + every subcollection + `codeIndex/*`, written to a
  timestamped local JSON file with a per-collection count manifest. This is deliberately
  **not** `gcloud firestore export`: managed export/import requires the Blaze plan and a GCS
  bucket, which the project doesn't have and `architecture.md §9` commits to never needing.
  Two people's history is a few thousand docs — the dump takes seconds and costs nothing.
- **A matching `tools/migrate/restore.js`**, and a written restore procedure (see §5). A
  restore is: delete the affected collections, re-write from the dump, compare counts against
  the manifest. Rehearse it once against the emulator so it isn't first attempted under
  pressure.
- **The backfill script is idempotent and `--dry-run` by default.** Node + Firebase Admin
  SDK (bypasses Security Rules), run locally by Tom against the prod project. Deterministic
  doc ids everywhere possible (observations reuse legacy ids; `private/config` is a fixed
  path; medication docs keyed by a content hash — see §5), `merge: true` writes, "create if
  absent" for member docs. Any area is safe to re-run — a second pass is a no-op.
- **Rehearsal against real data.** Before the window: load the dump into the local Firebase
  emulator, run all areas end-to-end against it, diff the result, re-run for idempotency.
  This converts the window from "run it and see" to "replay something already known to work"
  on data that actually has the accumulated cruft (zeros, nulls, empty strings) a synthetic
  fixture doesn't.
- **Legacy collections stay in place** through the §7 cleanup — the authoritative copy until
  the new shape is confirmed good. **But note the limit:** this is a lossless rollback only
  until the first write from the new build. After that, new entries exist only in
  `observations` / the medications subcollection; redeploying the old app from that point
  silently drops them. So the real rollback story is: *before* cutover, redeploy old app +
  old rules; *after* cutover, fix forward (or run `restore.js` and accept losing everything
  logged since the dump).

### The window (~45 min, both phones, both people present)

0. **Pre-window (done earlier, not in the window):** take a dump, run the rehearsal (§4
   safeguards), confirm both apps are ready to install.
1. **Flush queued writes.** Open both apps online and let them fully sync, so nothing is
   sitting in a local write queue that could flush *after* the backfill has passed that
   collection. Then stop using both apps.
2. Take a fresh dump (`backup.js`) — this is the one the rollback uses.
3. Run the backfill: `--dry-run` first, eyeball the per-area diff, then `--commit`. One
   entrypoint, areas in order: roles → join-code → observations → medications. (Export-log is
   a new empty collection — nothing to backfill.)
4. Deploy the new `firestore.rules` — a superset: every new path added and gated per
   `security-privacy.md §8`, legacy `seizures` / `healthNotes` / household-`code` access left
   permissive for now (§6).
5. **Verify roles before anyone relies on them:** read back both `members/{uid}` docs and
   confirm `role == "admin"` on each. If either is missing or wrong, fix it via the script
   *before* continuing — once the admin-gated rules are live, a member with no `role` is
   locked out and can't self-fix.
6. Install the new app build on both phones.
7. **Re-run `--area=observations --commit`** after both phones are on the new build and have
   been opened online once. This is idempotent by design; it sweeps anything logged to the
   legacy collections between step 3 and now. (Do the same for `--area=meds` if a medication
   was changed in that gap.)
8. Verify (see §5 "Verification gate") — counts, then a field-by-field check of three known
   entries. Not just "the screens render."
9. Done. Legacy collections remain until the §7 cleanup (gated on the Flutter cutover — see
   `flutter-migration.md §2`), but the lossless-rollback window closes as soon as either
   phone writes a new entry.

If it's ever easier to do this per-area instead of all at once, that's fine — each area is
its own couch session with the same shape (dump, backfill that area, deploy the incremental
rules, verify, update both phones). The per-area path still needs step 1 (flush) and step 7
(re-run) around the observations area specifically.

### Order of the shape changes

Later areas lean on earlier ones, so keep this sequence:

**1. Roles.** Backfill: `--admins=<uid1>,<uid2>` passed explicitly (never inferred from the
array). For each: ensure a `members/{uid}` doc exists, set `role: "admin"`. Any *other* uid
found in the `members` array gets a `members/{uid}` doc created with `role: "member"` and is
**reported, not silently normalised** — a stray uid nobody can identify should not become an
admin. Nobody is demoted; demotion to `member` is a deliberate in-app action later.

App: `MemberProfile` gains `role`. **`MemberRepository.upsertOwnProfile` must switch to
`set(..., SetOptions.merge())` with `role` excluded from the client-written payload** —
today it writes a whole `MemberProfile` with no merge, so once `role` exists a re-join (or
the Flutter cutover re-join) would overwrite it, and the admin-only `role` rule would then
reject the write outright, breaking the join flow. Add the admin checks in the
ViewModels/repositories that gate management actions; non-admin is a state the code must
handle even though neither current user is in it.

Rules: `security-privacy.md §8` **items 1, 2, 3, 4** (item 5 is `observations`, area 3; item
10 is `exportLog`, area 5 — the earlier "1, 2, 4, 5, 10" list here was wrong):
- item 1 — `members/{uid}` create stays self-only, **but `role` must be absent or `"member"`
  on create, and immutable on any self-`update`** (see `security-privacy.md §8`).
- item 2 — `members/{uid}` delete becomes admin-only, plus a self-leave carve-out.
- **item 3 — household-doc writes become admin-only**, with two diff-constrained carve-outs
  (join: `affectedKeys` is exactly `["members"]`, array grows by one, the element is
  `auth.uid`; self-leave: same but shrinks by one). Without this, "rename the household is
  admin-only" (`product-spec.md §4`) isn't actually enforced after the migration.
- item 4 — `pets` / `pets/{petId}/medications/{medId}` (its own nested match — a `pets` rule
  does not cover the subcollection) / `vets` / `petVetLinks` become `read: if member;
  write: if admin`.

"Admin" is defined once as `isAdmin() = isMember() && get(/…/members/$(uid)).data.get('role',
'member') == 'admin'` — the membership conjunction matters (a bare role check lets a stranger
who self-writes a member doc pass every gate), and `.get('role', 'member')` matters because a
plain `.data.role` **denies hard** on a member doc that has no `role` field, which is the
normal steady state for any future joiner.

**2. Join-code relocation.** Backfill: write `households/{id}/private/config` with
`{ joinCode: <current code field> }`; add `householdName` to the existing `codeIndex/{code}`
doc. Leave `households/{id}.code` in place until §7.

App: read the code from `private/config` (admin-gated "show join code" UI); the join-preview
screen reads `householdName` from the index.

Rules: `private/config` — `allow read, write: if admin`; `codeIndex` `get` stays
any-signed-in; `codeIndex` create/update/delete gated to an admin of the target household
plus a shape assertion (`security-privacy.md §8` items 6–8).

**Not in this area / not in the window:**
- **Code rotation is not built here** (see §1). It's new feature work, not a relocation, and
  building it inside the highest-risk window is the wrong place — nobody needs to rotate a
  code on migration day. Deferred to a follow-up PR: rotation must be a single atomic
  `WriteBatch` (create new `codeIndex` + delete old + update `private/config`), with its own
  `firestore-tests` cases.
- **Household creation still mints a code**, as today. `security-privacy.md §3.2`'s "no code
  until an admin links a durable credential" and `§8 item 8`'s non-anonymous-creator
  assertion are **rules only** for now — the app-side gate is deferred. Both current members
  are Google, so the rule is belt-and-braces; but note that `§8 item 7`'s "admin of the
  target household" `codeIndex`-create check requires the creator's `members/{uid}` doc to
  exist *before* the `codeIndex` write, which the shipped `createHousehold` batch doesn't
  guarantee. Either reorder those writes (household doc → member doc → `codeIndex`) or move
  code minting out of creation into the (deferred) invite action. Pick one before shipping
  item 7's rule.

**3. `observations` collection.** The big app change. Backfill: copy every `seizures/*` and
`healthNotes/*` doc into `observations/*` per the §3 mapping (same doc ids, overwrite). App:
replace `Seizure` + `HealthNote` with an `Observation` envelope + a sealed
`ObservationDetails` hierarchy; `SeizureRepository` + `HealthNoteRepository` collapse into
`ObservationRepository` reading/writing `observations` only; history / dashboard / export read
the unified collection. This is the largest app diff — `data/model`, `data/repository`, and
every `ui/*` package that touches entries.

- **Edits use `update()`, not `set()`.** The shipped repositories `set()` the whole doc on an
  edit; against `observations` that would *resurrect* an entry the other phone deleted offline
  (last-write-wins with no conflict signal). `update()` fails on a missing doc, so a stale
  offline edit fails instead of undeleting.
- **`summary` is recomputed on every write** (§3).
- **Keep the read pattern the shipped app uses:** fetch the collection with a single
  `orderBy('occurredAt', descending: true)` and filter by `type` / pet / logger client-side.
  If `ObservationRepository` instead adds a `where(...)` alongside the `orderBy`, that's a
  composite index — which fails at runtime, during verification. If a server-side filter is
  wanted, add `firestore.indexes.json` and wire it into `firebase.json` (neither exists
  today — `firebase deploy --only firestore` currently deploys rules only) *before* the
  window.

Rules: add `observations` — `read: if member`, `create: if member && loggedByUid ==
auth.uid`, `update/delete: if (admin || author) && request.resource.data.loggedByUid ==
resource.data.loggedByUid` (authorship is immutable — `security-privacy.md §8` item 5). Keep
the `seizures` / `healthNotes` rules in place until §7.

**4. Medications subcollection.** Backfill: for each pet, for each entry in the embedded
`medications` array, create `pets/{petId}/medications/{hashId}` with the fields + `active:
true`, `startDate: null` (see §3 table — legacy start dates are genuinely unknown; the UI
renders null as "start date not recorded"), `endDate: null`.

- **`hashId`** = first 20 hex of `SHA-256(JSON.stringify([name, dose, frequency, notes]))` —
  includes `notes` and uses a delimited encoding, so two array entries that differ only in
  notes don't collapse into one doc.
- **Guard:** skip any pet whose `medications` subcollection is already non-empty (so a re-run
  after an in-app edit doesn't orphan-and-duplicate). Assert per pet that
  `count(subcollection docs written) == medications.length`; abort the area on a mismatch.
- New medications created by the app use Firestore auto-ids — the hash is a backfill device
  only, never an identity scheme, never re-derived.

App: `Pet` drops the embedded `medications`; `PetRepository` reads the subcollection;
"discontinue" becomes `active: false` + `endDate` set instead of a delete; current-meds UI
filters `active == true`. Rules: `pets/{petId}/medications/{medId}` — `read: if member;
write: if admin` (its own nested `match`, not covered by the `pets` rule).

**5. Export log.** No backfill (new empty collection). App: on a successful export, an
admin's device writes one `{ type, rangeStart, rangeEnd, petIds, createdAt }` doc; export
becomes admin-gated (`product-spec.md §4`). Rules: `exportLog/{id}` — `create: if admin`,
`read: if member`, no update/delete.

## 5. The tooling (`tools/migrate/`)

Three Node entrypoints, all Admin SDK (a service-account key for the prod project,
`GOOGLE_APPLICATION_CREDENTIALS`, gitignored — steps in the tool's README):

- **`backup.js`** — recursive read of `households/{id}` + every subcollection + `codeIndex/*`
  → timestamped local JSON + a per-collection count manifest.
- **`restore.js`** — from a dump: delete the named collections, re-write every doc, compare
  counts to the manifest, report. See "Restore procedure" below.
- **`migrate.js`** — `--area=roles|joincode|observations|meds|all`, `--dry-run` (default) vs
  `--commit`, `--household=<id>`, `--admins=<uid>,<uid>` (required for the roles area).

**`migrate.js` properties:**
- **Idempotent:** deterministic doc ids where possible (observations reuse legacy ids;
  `private/config` is a fixed path; medication docs use the content hash from §4 area 4),
  `merge: true` writes, "create if absent" for member docs. Re-running any area is a no-op —
  *except* the meds area, which is idempotent only via its "skip pets whose subcollection is
  non-empty" guard.
- **Crash-safe by re-run:** observations and roles are deterministic-id + merge, so a
  `SIGKILL` mid-area leaves a partial-but-correct state that a clean re-run completes. The
  response to any interrupted area is always "re-run that area from the start."
- **Batched** in chunks of 400 writes; logs a per-area, per-household summary (docs read,
  written, skipped, **reported** — e.g. an unknown array uid, a profile doc whose uid isn't
  in the array). Dry-run output is a diff the operator eyeballs before `--commit`.

### Verification gate (§4 window step 8, and again before §7 cleanup)

Not "the screens render." Concretely:
- `count(observations) == count(seizures) + count(healthNotes)`, per household.
- `count(pets/{petId}/medications) == length(pet.medications array)`, per pet.
- Field-by-field check of **three specific known entries** — the oldest seizure, the most
  recent seizure, one health note — against the old app's rendering: duration, type, every
  symptom, `occurredAt`, notes.
- Both `members/{uid}` docs have `role == "admin"`.

`migrate.js --area=verify` runs the automated checks and **exits non-zero on any mismatch**;
the §7 cleanup delete refuses to run unless it passes.

### Restore procedure

If a backfill goes wrong *before cutover* (no new-app writes yet): `restore.js <dump>` for
the affected collections, redeploy the previous rules, redeploy the previous app. Confirm
counts against the manifest.

If something is discovered wrong *after cutover*: you can no longer restore losslessly (new
entries exist only in the new shape). Options are (a) fix forward with a corrective
`migrate.js` pass, or (b) `restore.js` and manually re-enter whatever was logged since the
dump. There is no automated reverse backfill; at two users the manual re-entry of a handful
of entries is the accepted fallback.

### Testing the tooling

- **Rehearsal against real data (required, pre-window):** `backup.js` prod → load the dump
  into the local Firebase emulator → run all areas → `--area=verify` → re-run for
  idempotency → run `restore.js` against the dump and confirm it round-trips.
- Emulator fixture: also keep a hand-seeded "legacy shape" household (old collections, `code`
  field, no roles, embedded meds, a member-array uid with no profile doc, a doc with
  `timestampMillis == 0`) for the fast unit-style assertions.
- Every rule change ships with matching `firestore-tests/` cases in the same commit (CI
  already runs that suite — `.github/workflows/ci.yml`), including the new carve-outs: a
  joiner can't rename the household in the join write; a member's own profile write can't
  alter `role`; a non-admin can't change the household `name`.
- Kotlin side: the emulator-backed repository/ViewModel suites (`app/src/test`) get updated
  alongside each area's app change and are the regression net for the new read/write paths.

## 6. Rules

The window's single `firestore.rules` deploy is a **superset**: new paths added and gated per
`security-privacy.md §8`, legacy `seizures` / `healthNotes` / household-`code` access still
permitted. The §7 cleanup deploy removes the legacy blocks once the new shape is confirmed.
The legacy paths stay open in between purely so "redeploy the previous app" remains a working
rollback (for an admin — after area 1 the previous app's writes to `pets`/`vets`/embedded
meds require admin, and both current members are admin, so this holds).

One thing the superset is **not**: embedded medications don't get a separate carve-out.
They're fields on the pet doc, and area 1 makes pet-doc writes admin-only — there's no
field-level rule keeping embedded-med writes open while restricting the rest of the doc, and
nothing here needs one. So "the old app can still edit meds" is true only for an admin, from
area 1 onward.

## 7. Cleanup

**Gate:** after the Flutter cutover (`flutter-migration.md §2` step 5) — not "a few days."
Deleting the legacy collections earlier removes the fallback the Flutter build depends on.
The gate is "both phones are on the Flutter build and its history/dashboard/export verify
clean," not a calendar date.

**This is the only irreversible step in the whole plan.** So, in one PR:

1. **Fresh dump** (`backup.js`) immediately before anything is deleted — the §4 dump is now
   months stale and has none of the post-migration data.
2. **`migrate.js --area=verify`** must pass (see §5) — the cleanup delete refuses to run
   otherwise.
3. **Cleanup pass** (`--dry-run` by default, like every other area): delete
   `households/{id}.code`, delete the embedded `medications` array from pet docs, delete all
   `seizures/*` and `healthNotes/*` docs.
4. **Rules:** remove the `seizures`, `healthNotes`, household-`code`, and embedded-medication
   blocks. (No `members` → `memberIds` rename — that was dropped, see §1.)
5. **App / tests:** delete the dead model classes, repository methods, compatibility
   branches, and legacy fixtures.

Until this PR lands, a rollback *to before cutover* is "`restore.js` + previous rules +
previous app." After cutover it's fix-forward (§5).

## 8. Why the deferred items are safe to defer

- **Cloud Functions — none, and none planned.** `security-privacy.md §9` sketches several
  (last-durable-admin re-check, join/removal notifications, recursive household delete). Each
  would move the project to Blaze for a capability nothing in the next release needs: the
  last-admin check is adequately client+rule enforced at two Google admins, notifications are
  themselves backlogged (`architecture.md §5`), and household teardown has no UI and no rule
  (`delete: if false` stays). `architecture.md §0/§9` carry the same "not built, post-v1"
  status.
- **`memberIds` as a source of truth** — there is no separate `memberIds`; the client-written
  `members` array on the household doc *is* the documented source of truth for access, and
  `members/{uid}` is the source of truth for metadata + role. The Function that
  `architecture.md §3` used to describe (keeping a derived cache in sync) is gone from the
  target — it only mattered for a "which households am I in" query the app doesn't make.
- **`petVetLinks` stays a flat collection** — the `linkedVets`-array consolidation is dropped
  from the target entirely (`architecture.md §3`), not merely deferred. The array needed a
  Cloud Function for both vet deletes *and* renames; the flat collection needs neither (the
  client deletes matching links in a `WriteBatch` when a vet is deleted).
- **Household teardown** — no UI, no rule, nothing to migrate.

## 9. Decisions

Settled for a two-person closed-track deployment:

- **Backup mechanism** — a local Admin-SDK JSON dump (`tools/migrate/backup.js`), **not**
  `gcloud firestore export` (which needs Blaze + a GCS bucket the project doesn't have).
- **Rollback** — lossless only before the first write from the new build; fix-forward after
  (§5). No automated reverse backfill; manual re-entry of a handful of post-cutover entries
  is the accepted fallback at this scale.
- **Rehearsal** — the full backfill + restore is replayed against a dump of the real data in
  the emulator before the window (§5). Non-negotiable.
- **Backfilled medication doc ids** — first 20 hex of `SHA-256(JSON.stringify([name, dose,
  frequency, notes]))`; the meds area skips pets whose subcollection is already populated and
  aborts on a per-pet count mismatch (§4 area 4).
- **Medication `startDate`** — backfills to `null`; legacy start dates are genuinely unknown
  and inferring one would fabricate clinical history.
- **`members` → `memberIds` rename** — **dropped.** The array keeps its name (§1).
- **Code rotation** — **not built here.** Deferred to a follow-up PR (§1, §4 area 2).
- **Min-version gate** — not needed and not built. Two devices that update together.
- **Verification gate** — automated count assertions + a three-entry field check (§5), run at
  the window and again before the §7 cleanup delete.
- **`flagForVet` / "mention at next vet visit"** — dropped from the product entirely. No
  field on the envelope, no backfill.
- **`summary` for backfilled seizures** — `"<duration> · <type>"` (e.g. `"4 min ·
  Generalized (grand mal)"`), `"seizure"` when both are empty. Recomputed on every write
  (§3); the Flutter client may drop the field and format at render time instead.
