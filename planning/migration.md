# Migration Plan — shipped Firestore shape → target backend

**Status:** draft for discussion · **Last updated:** 2026-08-29
**Companion docs:** `architecture.md` (target data model, §0 gap list), `product-spec.md`
(features, entities), `security-privacy.md` (roles, join mechanics, §8 rule changes)

## 1. Scope

This plan covers **only the backend / data-model move** on the *current Kotlin + Compose
app*: getting the shipped app onto the Firestore shape, Security Rules, and role model that
`architecture.md` and `security-privacy.md` describe, **without losing any live household
data**. The point is to land the hard, data-shaped decisions while the codebase is small and
the user base is a handful of coordinating households — so that the eventual Flutter port
(`architecture.md §0`) is a near-pure client rewrite against a backend that already looks
like its target.

**Explicitly out of scope here** (and deferred — see §9 for why each is safe to defer):

- The Flutter client rewrite.
- Splitting `memberIds` into a Function-maintained cache + `members` subcollection as sole
  source of truth. We keep the client-maintained array.
- **Any Cloud Functions.** Deploying Functions forces the project onto the Blaze plan
  (billing account required since the 2024 change, same as Cloud Storage — see
  `architecture.md §8`). Nothing in this migration needs one.
- Consolidating `petVetLinks` into a `linkedVets` array on the pet doc.
- Household teardown / recursive delete (no such feature ships today).

After this migration the target docs still describe more than the app does; the remaining
gap is entirely *client features* and *scale optimizations*, not *data shape*.

## 2. What actually changes

| Area | Today (shipped) | After this migration | Notes |
|---|---|---|---|
| Logged events | `seizures/{id}` + `healthNotes/{id}` — two collections, two model classes | `observations/{id}` — one polymorphic collection, envelope + `details` map | `architecture.md §3`. Biggest change; needs a backfill. |
| Event timestamp | `timestampMillis: Long` (epoch millis) | `occurredAt: Timestamp` + `createdAt` / `updatedAt: Timestamp` | Firestore-native `Timestamp`, not `Long`. Backfill converts. |
| Roles | none — every member has full write access | `members/{uid}.role: "admin" \| "member"` | `security-privacy.md §4.1`. Backfill seeds **every existing member as `admin`**. |
| Membership source of truth | `households/{id}.members: [uid]` array (also the access-check) | unchanged — array stays, **renamed `members` → `memberIds`**, still client-written | Rename only. The subcollection stays metadata + `role`. |
| Member metadata | `members/{uid}` — `displayName`, `authMethod`, `joinedAtMillis` | + `role`, + `lastActiveAt` | `authMethod` rename (`signInMethod`→`authMethod`) already done in code. |
| Join code | `households/{id}.code` field (every member reads it) | `households/{id}/private/config.joinCode` — admin-only read/write | `security-privacy.md §4.2`, §8 item 6. Removed from the household doc. |
| Code index | `codeIndex/{code}` = `{ householdId }` | `codeIndex/{code}` = `{ householdId, householdName }` | `security-privacy.md §8` item 8. Adds the join-preview name, nothing else. |
| Medications | `Pet.medications: [Medication]` embedded array; discontinue = delete | `pets/{petId}/medications/{medId}` subcollection + `active`, `startDate`, `endDate` | `architecture.md §3`. Backfill lifts the array into docs. |
| Attachments | `photoUri: String` on `HealthNote` and `Pet` | `attachment: { type, capturedByUid, localFileRef } \| null` on the observation | `architecture.md §8`. Still local-only; just the envelope shape. Low stakes — most are empty. |
| Export log | none | `households/{id}/exportLog/{id}` — admin-create, member-read | `architecture.md §7`. New, empty collection; no backfill. |

### `observations` envelope, and how each legacy doc maps

```
observations/{id}
  type: "seizure" | "note"
  petId, loggedByUid, loggedByName
  occurredAt: Timestamp        # from timestampMillis
  createdAt:  Timestamp        # from createdAtMillis (fallback: occurredAt)
  updatedAt:  Timestamp        # = createdAt at backfill time
  flagForVet: bool
  summary: string              # synthesized (see below)
  attachment: {...} | null
  details: { ...type-specific... }
```

- **`seizures/{id}` → `type: "seizure"`.** `details` gets `durationSeconds`, `seizureType`,
  `symptoms`, `preSeizureSigns`, `possibleTriggers`, `recoveryMinutes`, `recoveryNotes`,
  `rescueMedGiven`, `rescueMedDetails`, `notes`. `flagForVet: true` (a seizure is always
  vet-relevant). `summary` = e.g. `"4 min · Generalized (grand mal)"` from duration + type,
  `"seizure"` if both empty. `attachment: null` (seizures have no photo field today).
- **`healthNotes/{id}` → `type: "note"`.** `details` gets `description`, `notes`.
  `flagForVet` = the doc's `flaggedForVet`. `summary` = first ~60 chars of `description`.
  `attachment` = `{ type: "photo", capturedByUid: loggedByUid, localFileRef: photoUri }` if
  `photoUri` non-empty, else `null`.
- Legacy `id` is **preserved** as the new doc id (`observations/{sameId}`), so a backfill
  re-run is idempotent and any local reference survives.

## 3. Constraints that shape the approach

- **Firestore has no migration framework.** Every shape change is: (a) make the rules
  tolerate *both* shapes, (b) move the data, (c) move the clients, (d) tighten the rules and
  drop the old shape. Steps (a) and (d) are separate deploys.
- **Clients in the wild.** The app is distributed to the household group via `release.yml`;
  there is no forced-update mechanism and no min-version gate. The user base is tiny and
  coordinates in person / by text, so the plan leans on **a coordinated update window**
  rather than long-lived dual-read/dual-write. §7 covers the fallback if that's not viable.
- **Backfill runs outside the app.** A one-off Node script using the Firebase Admin SDK
  (bypasses Security Rules) run locally by Tom against the production project. There is no
  `functions/` dir and we're not adding one. The script lives in `tools/migrate/`,
  idempotent, with a `--dry-run` default and an explicit `--commit` flag.
- **Missing member docs.** Early households may have uids in the `members` array with **no
  `members/{uid}` profile doc**. The role backfill must *create* those (role `admin`,
  `displayName` = `"Member"` fallback, `authMethod` = `"unknown"`) or the tightened
  admin-gated rules lock that uid out of every management action.

## 4. Phase sequence

Each phase is independently shippable and independently revertible. Recommended order —
later phases assume earlier ones landed.

### Phase 0 — groundwork (no data change, no rule change)

- Add `tools/migrate/` — Admin SDK script scaffold, `package.json`, a README with the
  service-account-key steps, `*.serviceaccount.json` added to `.gitignore`.
- Add an emulator-seeded fixture: a "legacy shape" household (old collections, `code` field,
  no roles, embedded meds, a member-array uid with no profile doc) for tests to migrate.
- Extend `firestore-tests/` with a second describe block that will hold the new-shape rule
  tests as each phase adds them.
- No production change. Merges to main on its own.

### Phase 1 — roles (additive, then tighten)

1. **Backfill A:** for every household, for every uid in `members`: ensure a `members/{uid}`
   doc exists, set `role: "admin"` on all of them. (Everyone keeps exactly the access they
   have today — no one is silently demoted. Demotion to `member` is a deliberate per-person
   action an admin takes later, in-app.)
2. **App:** `MemberProfile` gains `role` (default `"member"` for *new* joiners per
   `security-privacy.md §4.1`; creator sets own to `"admin"`). Add the admin checks in the
   ViewModels/repositories that gate management actions. UI: hide/disable management controls
   for non-admins, show the member list to everyone.
3. **Rules:** deploy `security-privacy.md §8` items 1, 2, 4, 5, 10 — `pets` / `medications` /
   `vets` / `petVetLinks` become `read: if member; write: if admin`; `members/{uid}` delete
   and `role` writes become admin-only (plus self-leave); `seizures`/`healthNotes` writes
   become `admin || author` (see Phase 3 for the collection rename). "Admin" =
   `get(members/$(uid)).data.role == "admin"`.
4. **Rule-eval cost note:** the admin check adds one `get()` (the member doc) on top of the
   existing parent-household `get()` per management write. Acceptable at this volume; it's
   the reason `architecture.md §6` eventually wants `memberIds` on the household doc for the
   *read* path (unchanged here) and the role only consulted on writes.

Backfill A must land **before** the rules deploy, or existing members lose write access.

### Phase 2 — join code relocation

1. **Backfill B:** for each household, write `households/{id}/private/config` with
   `{ joinCode: <current code field> }`; add `householdName` to the existing
   `codeIndex/{code}` doc. Leave `households/{id}.code` in place for now.
2. **Rules:** add `private/config` — `allow read, write: if admin`
   (`security-privacy.md §8` item 6). Update `codeIndex` create/update/delete to admin-of-
   target-household + assert the shape `{ householdId, householdName }` and non-anonymous
   creator (§8 items 7, 8). `get` stays any-signed-in.
3. **App:** read the code from `private/config` (admins only); the "show join code" UI
   becomes admin-gated. Code rotation writes the new `codeIndex` doc, updates `config`,
   deletes the old `codeIndex` doc. Join-preview screen reads `householdName` from the index.
4. **Cleanup (later, §8):** stop writing `households/{id}.code`, then Backfill D strips it.

### Phase 3 — `observations` collection

The big one. Recommended: **coordinated cutover**, not dual-write.

1. **Rules:** add `observations` — `read: if member`, `create: if member && loggedByUid ==
   auth.uid`, `update/delete: if admin || author` (`security-privacy.md §8` item 5). Keep the
   existing `seizures` / `healthNotes` rules in place for now.
2. **Backfill C (pass 1):** copy every `seizures/*` and `healthNotes/*` doc into
   `observations/*` per the mapping in §2. Idempotent (same doc ids, overwrite).
3. **App:** replace `Seizure` + `HealthNote` models with an `Observation` envelope + a
   sealed `ObservationDetails` hierarchy; `SeizureRepository` + `HealthNoteRepository`
   collapse into `ObservationRepository` reading/writing `observations` only. History /
   dashboard / export read the unified collection. This is the largest app diff — the
   `data/model`, `data/repository`, and every `ui/*` package that touches entries.
4. **Cutover window:** announce a ~1-hour window to the household group. Everyone updates the
   app. Run **Backfill C pass 2** (picks up anything written to the legacy collections
   between pass 1 and everyone updating). After this, legacy collections are frozen in
   practice.
5. **Verify:** spot-check counts (`seizures` + `healthNotes` == `observations` per
   household), open the app on each device, confirm history renders.
6. Legacy `seizures` / `healthNotes` collections and rules stay untouched until §8 cleanup —
   they *are* the rollback.

### Phase 4 — medications subcollection

1. **Rules:** add `pets/{petId}/medications/{medId}` — `read: if member; write: if admin`.
2. **Backfill E:** for each pet, for each entry in the embedded `medications` array, create
   `pets/{petId}/medications/{autoId}` with the fields + `active: true`, `startDate:
   <pet.createdAt or null>`, `endDate: null`.
3. **App:** `Pet` drops the embedded `medications`; `PetRepository` reads the subcollection;
   "discontinue" becomes `active: false` + `endDate` set instead of a delete; current-meds
   UI filters `active == true`.
4. **Cleanup (later):** Backfill D strips the embedded array from pet docs.

### Phase 5 — export log

- **Rules:** add `exportLog/{id}` — `create: if admin`, `read: if member`, no update/delete.
- **App:** on a successful export, an admin's device writes one `{ type, rangeStart,
  rangeEnd, petIds, createdAt }` doc. Export becomes admin-gated (`product-spec.md §4`).
- No backfill — new empty collection.

## 5. The backfill script

`tools/migrate/` — single Node entrypoint, one function per phase (`--phase=1..5` or
`--phase=all`), `--dry-run` (default) vs `--commit`, `--household=<id>` to scope a test run.

- **Auth:** Admin SDK with a service-account key for the prod project
  (`GOOGLE_APPLICATION_CREDENTIALS`). Key file gitignored; steps in the tool's README.
- **Idempotent:** every phase is safe to re-run — deterministic doc ids where possible
  (observations reuse legacy ids; `private/config` is a fixed path), `merge: true` writes,
  "create if absent" for member docs and medication docs (guard medication docs with a
  deterministic id like `md_<index>` or a content hash so pass 2 doesn't duplicate).
- **Batched** in chunks of 400 writes; logs a per-household summary (docs read, docs
  written, skipped).
- **Dry-run output** is a diff the operator eyeballs before `--commit`.

### Testing the backfill

- Emulator: seed the Phase 0 legacy fixture, run each phase against it, assert the resulting
  shape doc-by-doc. Runs in the existing `firestore-tests/` Node harness
  (`firebase emulators:exec ... npm test`).
- Re-run each phase a second time in the same test and assert **no change** (idempotency).
- Kotlin side: the emulator-backed repository/ViewModel suites (`app/src/test`) get updated
  alongside each Phase's app change and are the regression net for the new read/write paths.

## 6. Rules transition strategy

At every point, deployed rules accept the **union** of the shapes that any live client might
write:

- Phases 1–2: rules gain the new paths but keep the old ones fully open to members.
- Phase 3: `observations` rules live *alongside* `seizures`/`healthNotes` rules for the
  whole cutover window.
- §8 cleanup: a single deploy removes the legacy `seizures`, `healthNotes`, `code`-field,
  and embedded-medication allowances once nothing writes them.

Every rule change ships with matching `firestore-tests/` cases in the same commit (the CI
job already runs that suite — `.github/workflows/ci.yml`).

## 7. If a coordinated window isn't viable

If devices can't be updated together (someone's travelling, a petsitter mid-stay), replace
the Phase 3 cutover with **temporary dual-write** in the new app version:

- New app writes each observation to *both* `observations/{id}` and the matching legacy
  collection with the same id, for one release.
- Old app keeps writing only legacy; the backfill runs on a cron-style manual cadence (Tom
  runs it every few days) to sweep legacy → `observations`.
- Once every device is confirmed updated, ship a release that drops the legacy write, run a
  final backfill pass, then proceed to §8 cleanup.

This costs an extra write per log and a more complex app version, which is why it's the
fallback, not the default, for a user base this small.

## 8. Cleanup (after a verification period)

Hold ~2 weeks after Phase 3–4 land and everyone's updated. Then, in one PR:

- **Backfill D:** delete `households/{id}.code`, delete the embedded `medications` array from
  pet docs, delete all `seizures/*` and `healthNotes/*` docs.
- **Rules:** remove the `seizures`, `healthNotes`, household-`code`, and embedded-medication
  rule blocks. `members` array reference is already renamed to `memberIds`.
- **App / tests:** delete the dead model classes, repository methods, and any
  compatibility branches; drop the legacy fixtures.

Until this PR, a full rollback is "redeploy the previous app + previous rules" — the legacy
data is still there and authoritative.

## 9. Why the deferred items are safe to defer

- **`memberIds` Function-maintained cache.** Its only job is to avoid loading a `members`
  subcollection to answer "which households am I in." Today the client writes the array
  directly and it works. The Function exists in `architecture.md §3` to guarantee a single
  writer once membership logic gets complex — not needed at a handful of members per
  household. Keeping the client-written array also keeps join/self-leave as plain array
  writes the rules already handle.
- **All Cloud Functions.** `security-privacy.md §9` lists five. `memberIds` sync and
  last-durable-admin re-check are belt-and-braces over client+rule enforcement. Join/removal
  notifications belong to the notification feature, which `architecture.md §5` itself defers.
  Recursive household delete belongs to a delete-household feature that doesn't exist. Every
  one of them would move the project to Blaze for no capability this migration needs.
- **`petVetLinks` → `linkedVets` array.** The flat collection works and needs no
  vet-deletion cleanup Function as long as it stays a collection (the client deletes matching
  links, or a stale link is tolerated). Consolidating is a read-optimization for the Flutter
  client to decide on.
- **Household teardown.** No UI, no rule (`delete: if false` stays), nothing to migrate.

## 10. Open questions

- **Deterministic ids for backfilled medication docs** — content hash vs `md_<index>` vs
  accepting that a double-run before app cutover could duplicate (then dedupe in pass 2).
  Lean toward a stable hash of `name+dose+frequency`.
- **`flagForVet` for backfilled seizures** — this plan sets `true`. Confirm that's the
  desired dashboard behavior (every historical seizure shows in "mention at next vet visit")
  or set `false` and let people flag retroactively.
- **`summary` wording** for backfilled seizures — `"<duration> · <type>"` is the current
  proposal; the Flutter feed design may want something else, but the field has to be
  populated now so history isn't blank.
- **Min-version gate** — worth adding a tiny `config/appMinVersion` doc + a startup check
  before Phase 3, so a stale device shows "update required" instead of silently writing to a
  frozen collection? Cheap; probably yes.
- **Verification-period length** (§8) — 2 weeks is a guess; tie it to "every known device
  has opened the new version at least once" instead of a calendar date.
