# Migration Plan — shipped Firestore shape → target backend

**Status:** draft for discussion · **Last updated:** 2026-08-30
**Companion docs:** `architecture.md` (target data model, §0 gap list), `product-spec.md`
(features, entities), `security-privacy.md` (roles, join mechanics, §8 rule changes)

## 1. Scope

This plan covers the **backend / data-model move** on the *current Kotlin + Compose app*:
getting the shipped app onto the Firestore shape, Security Rules, and role model that
`architecture.md` and `security-privacy.md` describe, **without losing any of the household's
logged history**. Landing the data-shaped decisions now — while the schema is small — means
the eventual Flutter port (`flutter-migration.md`) is a near-pure client rewrite against a
backend that already looks like its target.

**Out of scope** (deferred — see §8 for why each is safe to defer):

- The Flutter client rewrite (`flutter-migration.md`, sequenced after this).
- Splitting `memberIds` into a Function-maintained cache + `members` subcollection as sole
  source of truth. Keep the client-written array.
- **Any Cloud Functions.** Deploying one forces the project onto the Blaze plan (billing
  account required since the 2024 change — see `architecture.md §8`). Nothing here needs one.
- Consolidating `petVetLinks` into a `linkedVets` array on the pet doc.
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

The one risk that does remain is **operator error during the backfill** destroying real
seizure history. Everything in §4's safeguards exists for that, and only that.

## 3. What changes

| Area | Today (shipped) | After this migration | Notes |
|---|---|---|---|
| Logged events | `seizures/{id}` + `healthNotes/{id}` — two collections, two model classes | `observations/{id}` — one polymorphic collection, envelope + `details` map | `architecture.md §3`. Biggest change; needs a backfill. |
| Event timestamp | `timestampMillis: Long` (epoch millis) | `occurredAt: Timestamp` + `createdAt` / `updatedAt: Timestamp` | Firestore-native `Timestamp`, not `Long`. Backfill converts. |
| Roles | none — every member has full write access | `members/{uid}.role: "admin" \| "member"` | `security-privacy.md §4.1`. Backfill seeds **both existing members as `admin`**. |
| Membership source of truth | `households/{id}.members: [uid]` array (also the access-check) | unchanged — array stays, **renamed `members` → `memberIds`**, still client-written | Rename only, at §7 cleanup. The subcollection stays metadata + `role`. |
| Member metadata | `members/{uid}` — `displayName`, `authMethod`, `joinedAtMillis` | + `role`, + `lastActiveAt` | `authMethod` rename (`signInMethod`→`authMethod`) already done in code. |
| Join code | `households/{id}.code` field (every member reads it) | `households/{id}/private/config.joinCode` — admin-only read/write | `security-privacy.md §4.2`, §8 item 6. Removed from the household doc at cleanup. |
| Code index | `codeIndex/{code}` = `{ householdId }` | `codeIndex/{code}` = `{ householdId, householdName }` | `security-privacy.md §8` item 8. Adds the join-preview name, nothing else. |
| Medications | `Pet.medications: [Medication]` embedded array; discontinue = delete | `pets/{petId}/medications/{medId}` subcollection + `active`, `startDate`, `endDate` | `architecture.md §3`. Backfill lifts the array into docs. |
| Attachments | dormant `photoUri: String` on `HealthNote` and `Pet` (never displayed, now removed from code) | nothing — attachments are post-v1 (`architecture.md §8`) | Backfill drops any legacy `photoUri` value. The `attachment` envelope field is added when attachments are actually built. |
| Export log | none | `households/{id}/exportLog/{id}` — admin-create, member-read | `architecture.md §7`. New, empty collection; no backfill. |

### `observations` envelope, and how each legacy doc maps

```
observations/{id}
  type: "seizure" | "note"
  petId, loggedByUid, loggedByName
  occurredAt: Timestamp        # from timestampMillis
  createdAt:  Timestamp        # from createdAtMillis (fallback: occurredAt)
  updatedAt:  Timestamp        # = createdAt at backfill time
  summary: string              # synthesized (see below)
  details: { ...type-specific... }
  # no attachment field — attachments are post-v1 (architecture.md §8)
```

- **`seizures/{id}` → `type: "seizure"`.** `details` gets `durationSeconds`, `seizureType`,
  `symptoms`, `preSeizureSigns`, `possibleTriggers`, `recoveryMinutes`, `recoveryNotes`,
  `rescueMedGiven`, `rescueMedDetails`, `notes`. `summary` = e.g.
  `"4 min · Generalized (grand mal)"` from duration + type, `"seizure"` if both empty.
- **`healthNotes/{id}` → `type: "note"`.** `details` gets `description`, `notes`.
  `summary` = first ~60 chars of `description`. Any legacy `photoUri` value is dropped.
- Legacy `id` is **preserved** as the new doc id (`observations/{sameId}`), so a backfill
  re-run is idempotent and any local reference survives.

## 4. Approach: one window, one script, one rules deploy

### Safeguards (all cheap, all kept)

- **Firestore export before touching anything.** `gcloud firestore export gs://<bucket>` (or
  the console's one-off export). This *is* the rollback: if the new build is broken, redeploy
  the previous app + previous rules and the data is untouched — legacy collections aren't
  deleted until §7.
- **The backfill script is idempotent and `--dry-run` by default.** Node + Firebase Admin
  SDK (bypasses Security Rules), run locally by Tom against the prod project. Deterministic
  doc ids everywhere possible (observations reuse legacy ids; `private/config` is a fixed
  path; medication docs keyed by a hash of `name+dose+frequency`), `merge: true` writes,
  "create if absent" for member docs. Any area is safe to re-run — a second pass is a no-op.
- **Legacy collections stay in place** through the §7 cleanup. They aren't just history —
  they're the authoritative copy until the new shape is confirmed good.

### The window (~30 min, both phones, both people present)

1. Firestore export.
2. Run the backfill: `--dry-run` first, eyeball the per-area diff, then `--commit`. One
   entrypoint, run the areas in order (§4's ordering): roles → join-code → observations →
   medications. (Export-log is a new empty collection — nothing to backfill.)
3. Deploy the new `firestore.rules` — a superset: every new path added and gated per
   `security-privacy.md §8`, legacy `seizures` / `healthNotes` / household-`code` /
   embedded-medication access left permissive for now (§6).
4. Install the new app build on both phones.
5. Open both apps and confirm: pet list, current-medications list, seizure + health-note
   history, dashboard counts/charts, and a PDF/CSV export all render correctly.
6. Done. Legacy collections remain for a few days as rollback insurance.

If it's ever easier to do this per-area instead of all at once, that's fine — each area is
its own ~10-minute couch session with the same shape (export, backfill that area, deploy the
incremental rules, update both phones). Nothing about a two-device closed track makes the
one-shot window riskier than splitting it.

### Order of the shape changes

Later areas lean on earlier ones, so keep this sequence:

**1. Roles.** Backfill: ensure a `members/{uid}` doc exists for both uids, set `role:
"admin"` on both — nobody is demoted; demotion to `member` is a deliberate in-app action
later, if ever. App: `MemberProfile` gains `role`; the two existing members are both admin
(new joiners would default to `"member"` per `security-privacy.md §4.1`, but there are no new
joiners). Add the admin checks in the ViewModels/repositories that gate management actions;
non-admin is a state the code should handle even though neither current user is in it. Rules:
`security-privacy.md §8` items 1, 2, 4, 5, 10 — `pets` / `medications` / `vets` /
`petVetLinks` become `read: if member; write: if admin`; `members/{uid}` delete and `role`
writes become admin-only (plus self-leave). "Admin" = `get(members/$(uid)).data.role ==
"admin"`.

**2. Join-code relocation.** Backfill: write `households/{id}/private/config` with
`{ joinCode: <current code field> }`; add `householdName` to the existing `codeIndex/{code}`
doc. Leave `households/{id}.code` in place until §7. App: read the code from `private/config`;
the "show join code" UI becomes admin-gated; rotation writes the new `codeIndex` doc, updates
`config`, deletes the old `codeIndex` doc; the join-preview screen reads `householdName` from
the index. Rules: `private/config` — `allow read, write: if admin`; `codeIndex`
create/update/delete gated to an admin of the target household plus a shape assertion
(`{ householdId, householdName }`, non-anonymous creator); `get` stays any-signed-in
(`security-privacy.md §8` items 6–8).

**3. `observations` collection.** The big app change. Backfill: copy every `seizures/*` and
`healthNotes/*` doc into `observations/*` per the §3 mapping (same doc ids, overwrite). App:
replace `Seizure` + `HealthNote` with an `Observation` envelope + a sealed
`ObservationDetails` hierarchy; `SeizureRepository` + `HealthNoteRepository` collapse into
`ObservationRepository` reading/writing `observations` only; history / dashboard / export read
the unified collection. This is the largest app diff — `data/model`, `data/repository`, and
every `ui/*` package that touches entries. Rules: add `observations` — `read: if member`,
`create: if member && loggedByUid == auth.uid`, `update/delete: if admin || author`
(`security-privacy.md §8` item 5). Keep the `seizures` / `healthNotes` rules in place until
§7.

**4. Medications subcollection.** Backfill: for each pet, for each entry in the embedded
`medications` array, create `pets/{petId}/medications/{hashId}` with the fields + `active:
true`, `startDate: <pet.createdAt or null>`, `endDate: null`. App: `Pet` drops the embedded
`medications`; `PetRepository` reads the subcollection; "discontinue" becomes `active: false`
+ `endDate` set instead of a delete; current-meds UI filters `active == true`. Rules:
`pets/{petId}/medications/{medId}` — `read: if member; write: if admin`.

**5. Export log.** No backfill (new empty collection). App: on a successful export, an
admin's device writes one `{ type, rangeStart, rangeEnd, petIds, createdAt }` doc; export
becomes admin-gated (`product-spec.md §4`). Rules: `exportLog/{id}` — `create: if admin`,
`read: if member`, no update/delete.

## 5. The backfill script

`tools/migrate/` — single Node entrypoint, `--area=roles|joincode|observations|meds|all`,
`--dry-run` (default) vs `--commit`, `--household=<id>` to scope a test run.

- **Auth:** Admin SDK with a service-account key for the prod project
  (`GOOGLE_APPLICATION_CREDENTIALS`). Key file gitignored; steps in the tool's README.
- **Idempotent:** deterministic doc ids where possible (observations reuse legacy ids;
  `private/config` is a fixed path; medication docs use a stable hash of
  `name+dose+frequency`), `merge: true` writes, "create if absent" for member docs. Re-running
  any area is a no-op.
- **Batched** in chunks of 400 writes; logs a per-area, per-household summary (docs read,
  written, skipped). Dry-run output is a diff the operator eyeballs before `--commit`.

### Testing the backfill

- Emulator: seed a "legacy shape" fixture household (old collections, `code` field, no roles,
  embedded meds, and — for coverage — a member-array uid with no profile doc), run each area
  against it, assert the resulting shape doc-by-doc, then re-run and assert **no change**
  (idempotency). Runs in the existing `firestore-tests/` Node harness.
- Every rule change ships with matching `firestore-tests/` cases in the same commit (CI
  already runs that suite — `.github/workflows/ci.yml`).
- Kotlin side: the emulator-backed repository/ViewModel suites (`app/src/test`) get updated
  alongside each area's app change and are the regression net for the new read/write paths.

## 6. Rules

The window's single `firestore.rules` deploy is a **superset**: new paths added and gated per
`security-privacy.md §8`, legacy `seizures` / `healthNotes` / household-`code` /
embedded-medication access still permitted. The §7 cleanup deploy removes the legacy blocks
once the new shape is confirmed. The legacy paths stay open in between purely so "redeploy the
previous app" remains a working rollback — not for any live client that needs them.

## 7. Cleanup

A few days after the window — gated on "both phones have run the new build and history
renders correctly," not a calendar date — in one PR:

- **Backfill cleanup pass:** delete `households/{id}.code`, delete the embedded `medications`
  array from pet docs, delete all `seizures/*` and `healthNotes/*` docs.
- **Rules:** remove the `seizures`, `healthNotes`, household-`code`, and embedded-medication
  blocks. Rename the `members` array to `memberIds` here (both clients are already updated, so
  it's a plain rename).
- **App / tests:** delete the dead model classes, repository methods, compatibility branches,
  and legacy fixtures.

Until this PR lands, a full rollback is "redeploy the previous app + previous rules" — the
legacy data is still there and authoritative.

## 8. Why the deferred items are safe to defer

- **`memberIds` Function-maintained cache.** Its only job is to avoid loading a `members`
  subcollection to answer "which households am I in." The client writes the array directly
  today and it works; the Function in `architecture.md §3` guarantees a single writer once
  membership logic gets complex — not needed at two members in one household.
- **All Cloud Functions.** `security-privacy.md §9` lists five. `memberIds` sync and
  last-durable-admin re-check are belt-and-braces over client+rule enforcement.
  Join/removal notifications belong to the notification feature, which `architecture.md §5`
  itself defers. Recursive household delete belongs to a delete-household feature that
  doesn't exist. Every one would move the project to Blaze for no capability this migration
  needs.
- **`petVetLinks` → `linkedVets` array.** The flat collection works and needs no
  vet-deletion cleanup Function as long as it stays a collection. Consolidating is a
  read-optimization for the Flutter client to decide on.
- **Household teardown.** No UI, no rule (`delete: if false` stays), nothing to migrate.

## 9. Decisions

Former open questions, settled for a two-person closed-track deployment:

- **Backfilled medication doc ids** — stable hash of `name+dose+frequency`. A double-run
  can't duplicate.
- **Min-version gate** — **not needed and not built.** Two devices that update together; a
  stale device silently writing to a frozen collection cannot happen.
- **Verification-period length** — not a fixed number of days. Gate the §7 cleanup on "both
  devices have opened the new build and history/dashboard/export look right."
- **`flagForVet` / "mention at next vet visit"** — dropped from the product entirely, in code
  and in the target docs. No `flagForVet` field on the `observations` envelope, no backfill
  for it. The vet report is the export; there's no separately curated list.
- **`summary` wording for backfilled seizures** — `"<duration> · <type>"` (e.g. `"4 min ·
  Generalized (grand mal)"`), `"seizure"` when both are empty. The Flutter feed design may
  refine this later; the field just has to be populated now so history isn't blank.
