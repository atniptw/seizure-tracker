# Migration Plan — shipped Firestore shape → target backend

**Status:** draft for discussion; **Phase 1 in progress** — `backup.js` / `restore.js` built,
emulator-rehearsed and through pre-merge review (issue #5: six blocking findings fixed, all six
regression-tested — the verification gate now compares field *contents*, not only document
counts and ids), the real-data rehearsal still pending; the live project inventoried read-only
2026-09-12 (§2), which moved several things in here from "assumed" to "known" ·
**Last updated:** 2026-09-12
The three live member uids were resolved against Firebase Auth by provider on 2026-09-12, which
settled the admin set (§2 inventory item 1).
**Open, waiting on Tom** (each flagged in place): what happens to the two test-junk households
(§2), the reading of the "no prod data changes" hold (§4), and the disposition of the legacy
household fields (§3).
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

## 2. The user base is two people on a closed track — but three member uids

This is the fact that shapes the entire approach:

- **Two people, two devices** — Tom and his wife, one household. **The live household's
  `members` array nevertheless holds three uids**, with three matching docs in its `members`
  subcollection (see the inventory below). Everything about roles and verification in this plan
  is written per-uid and count-agnostic as a result — never "both members".
- **Closed Firebase distribution.** The app ships only to the two of them, as testers on a
  closed track. There is no public listing; no other install is even possible. There is no
  such thing here as a "client in the wild," a straggler stuck on an old version, or an
  unknown write shape reaching Firestore.
- The devices update on request, in person.

So the migration is **one maintenance window**, not a phased rollout with dual-read/dual-write
compatibility windows, a min-version gate, or a multi-week bake. There is nothing to stay
backward-compatible *with* once the phones are on the new build, because those phones are the
only clients that exist.

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

### The live project, inventoried read-only (2026-09-12)

**Method, corrected 2026-09-12** (the #5 review caught the original sentence claiming less access
than this section demonstrably had, which weakened it precisely as evidence). **Read-only, no
writes** — that part stands and is the part that matters. But the access was two kinds, not one:

- **Counts and document ids** — REST count aggregations and `__name__`-only projections. Every
  number in the table below comes from these.
- **Document *fields*** — all three `households/{id}` documents and the live household's pet
  document (`pets/<PET-ID>`), retrieved in full. Items (2) and (5) and §3 "Legacy
  household fields" are field-value claims that no aggregation or `__name__` projection can
  produce.
- **Firebase Auth** — `getUser` on four uids (the live household's three, plus the junk
  households' owner). The records came back **in full**, including email, display name and photo
  URL; only the provider type and the created/last-refresh timestamps were carried into this
  document, deliberately, since it is committed to the repo.

**What was *not* read**, so this section is usable as evidence in both directions: no document
under `seizures`, `healthNotes`, `vets`, `petVetLinks` or `members` was retrieved — those are
counts only, plus the `members` document *ids* (which equal the uids) for the array-vs-subcollection
comparison in item 1. The two pets of `<HOUSEHOLD-3-ID>` are ids only; their fields were never
read.

**Exactly what ran** (Tech Lead, 2026-09-12, transcript in the session that produced this commit),
all against `projects/<PROJECT-ID>/databases/(default)`, authenticated with a
`gcloud auth print-access-token` bearer token:

1. `documents:runQuery` on `households`, `select.fields = [__name__]` → the three ids with their
   `createTime`/`updateTime`.
2. `documents:listCollectionIds`, and `households/{id}:listCollectionIds` for each of the three.
3. `…:runAggregationQuery` with a `COUNT` aggregation — top-level `codeIndex` and `households`,
   then each household's six subcollections. Every number in the table is one of these.
4. `households/{id}:runQuery` on `pets`, `select.fields = [__name__]`, for the two non-empty
   households.
5. `GET households/{id}` for all three — once with `?mask.fieldPaths=members`, once unmasked.
6. `GET households/<LIVE-HOUSEHOLD-ID>/pets/<PET-ID>`.
7. Firebase Auth `getUser` on the four uids, via the `firebase` MCP server.

Every endpoint there is a read. There was no `:commit`, no `PATCH`, no `DELETE`, and no write of
any kind — which is the part the §4 hold cares about. One attempt (`firestore_list_collections`
over MCP) was refused by a permission classifier and was re-done as step 2 over REST; nothing else
was blocked.

Where the plan contradicted this, the plan has been corrected in place; what is left is
four **decisions**, flagged here and at the section each one lands in.

| Household | Created / updated | seizures | healthNotes | pets | vets | petVetLinks | `members` subcoll | `members` array |
|---|---|---|---|---|---|---|---|---|
| `<LIVE-HOUSEHOLD-ID>` — **the live household** | 08-02 / 08-17 | 2 | 2 | 1 | 1 | 1 | 3 | 3 |
| `<HOUSEHOLD-2-ID>` | 08-16 / never updated | — | — | — | — | — | 0 | 1 |
| `<HOUSEHOLD-3-ID>` | 08-16 | 1 | 1 | 2 | 1 | 1 | 1 | 1 |

Three households, three `codeIndex` entries (one each). A dash means the collection does not
exist — `<HOUSEHOLD-2-ID>` has no subcollections at all.

**(1) Three member uids in the live household, not two — resolved by provider, and the admin set
is settled.** The array and the `members` subcollection agree exactly: three uids, three docs, no
mismatch either way. Resolved against Firebase Auth 2026-09-12 (provider type only; the
identities are deliberately not recorded in this doc):

| uid | Provider | Auth account created | Last refresh |
|---|---|---|---|
| `<UID-GOOGLE-1>` | **Google** | <CREATED-AT> — 18s before the household doc | within the last week |
| `<UID-ANON>` | **anonymous** — no provider record, no email, no display name | <CREATED-AT> — 35 min after the above | 2026-08-17 |
| `<UID-GOOGLE-2>` | **Google** | <CREATED-AT> | within the last week |

**`--admins` is the two Google uids** (`<UID-GOOGLE-1>`, `<UID-GOOGLE-2>`) — the two people, both
with durable identities. **The anonymous member gets no role, deliberately.**

`<UID-ANON>` is a **dead member**: anonymous uids do not survive a reinstall (`CLAUDE.md`, session/
auth flow), so nobody can sign back into that account — it is unreachable, and it holds
member-level write access to the live household today purely because its uid is still in the
`members` array. Leaving it role-less is the intended disposition, not an oversight (see §4 window
step 5, which says so at the point someone would be tempted to "fix" it).

**What the timestamps suggest** (inference, not evidence — recorded because it is the reading the
disposition above assumes): the anonymous uid was created 35 minutes after the household, and the
second *Google* uid appeared on 2026-08-17 — the same day as the anonymous uid's last refresh.
That is the shape of "the second person joined anonymously, later signed in with Google and
re-joined", which would leave exactly one stranded anonymous member and two Google people. If
instead the anonymous uid is a device still in daily use, the caveat below applies and the roles
area should be re-decided before it runs.

**One caveat on "unreachable":** it assumes no device still holds that anonymous session. If an
old phone is still signed in as `<UID-ANON>`, it keeps member-level write access until either the uid
is pulled from the `members` array or the admin-gated rules land (after which it can read and log
but not manage). That is a device question, not an Auth one, and Auth can't answer it — the last
refresh being 2026-08-17 is suggestive, not proof.

**(2) Two of the three households are test junk — open: leave them, or delete them later.**
`<HOUSEHOLD-2-ID>` and `<HOUSEHOLD-3-ID>` both carry a creation timestamp of 08-16 and both have exactly one uid in
their `members` array, the same one in each: `<UID-ANON-2>` (**anonymous**, Auth
account created <CREATED-AT>). **"Created by" is inference, not evidence** — Firestore
records no creator; the reading is that the sole member of a one-member household is whoever
created it, which is how the app's create flow works. That uid
appears in none of the live household's three — a second, different anonymous identity from the
live household's `<UID-ANON>`, and by the same reasoning also unreachable. They hold 2 of the 3 `codeIndex` entries and real documents (3
seizures/health notes between them, 2 pets, 2 vets, 2 links). **Tom has decided nothing about
them, and has since put a hold on all prod data changes until the new builds are deployed (§4) —
so nothing in this plan deletes them.** The two options and what each costs:

- **Left in place** (what the plan currently does): every area runs across all three households.
  Issue #6 would give the junk households' single uid a `role` — and by the same logic applied to
  `<UID-ANON>` it should instead be left role-less, since it is an unreachable anonymous identity.
  #7 rewrites their four observations, the meds area walks their two pets. Harmless, a little more
  to verify, and each junk household keeps its `codeIndex` entry.
- **Deleted later** — a separate, gated decision, irreversible, needing its own fresh dump and
  its own verification. The operation then narrows to one household and
  `--household=<LIVE-HOUSEHOLD-ID>` becomes the default everywhere.

Either way: **the verification gate has to handle three households, or be explicitly scoped to
one with `--household`.** `count(observations) == count(seizures) + count(healthNotes)` is only
true per household, and the junk households' documents would otherwise land in the same totals.

**(3) The "array uid with no profile doc" cruft case is real, not hypothetical.** `<HOUSEHOLD-2-ID>` has
one uid in its `members` array and zero docs in its `members` subcollection — exactly the shape
the emulator fixture invents (§5 "Testing the tooling"). It is live data; do not let anyone trim
it from the fixture as synthetic. The roles area's "create the doc with `role: member` and report
it" path (§4 area 1) fires on it for real.

**(4) Every household doc still carries abandoned legacy fields** that this plan mapped nowhere —
see §3 "Legacy household fields".

**(5) `weightKg` really is a Firestore double in prod** — on the live pet, and as `dogWeightKg`
on the household docs. The integral-double retype in §5 is live-relevant, not theoretical.

## 3. What changes

| Area | Today (shipped) | After this migration | Notes |
|---|---|---|---|
| Logged events | `seizures/{id}` + `healthNotes/{id}` — two collections, two model classes | `observations/{id}` — one polymorphic collection, envelope + `details` map | `architecture.md §3`. Biggest change; needs a backfill. |
| Event timestamp | `timestampMillis: Long` (epoch millis) | `occurredAt: Timestamp` + `createdAt` / `updatedAt: Timestamp` | **Observations only.** Firestore-native `Timestamp`; backfill converts. Non-observation `*Millis` fields (`Pet.birthDateMillis`, every doc's `createdAtMillis`, `MemberProfile.joinedAtMillis`) **stay `Long`** — see the note below the table. |
| Roles | none — every member has full write access | `members/{uid}.role: "admin" \| "member"` | `security-privacy.md §4.1`. Backfill seeds **every uid passed in `--admins` as `admin`** (explicit, never inferred). For the live household that is **the two Google uids**; the third member is an unreachable anonymous session and is deliberately left with **no** role (§2 inventory item 1). Any other array uid is created `role: "member"` and reported. |
| Membership source of truth | `households/{id}.members: [uid]` array (also the access-check) | **unchanged** — same name, same client writes | The array on the household doc is the documented source of truth for *access*; `members/{uid}` is the source of truth for *metadata + role*. No rename (see §1). |
| Member metadata | `members/{uid}` — `displayName`, `authMethod`, `joinedAtMillis` | + `role` | `authMethod` rename (`signInMethod`→`authMethod`) already done in code. `lastActiveAt` is **not** added here — it's post-v1 (`security-privacy.md §4.5`). Note that the *reason* given there no longer holds: a stranded anonymous identity is already in the live data (§2 inventory item 1). See the callout below the table. |
| Join code | `households/{id}.code` field (every member reads it) | `households/{id}/private/config.joinCode` — admin-only read/write | `security-privacy.md §4.2`, §8 item 6. Removed from the household doc at cleanup. |
| Code index | `codeIndex/{code}` = `{ householdId }` | unchanged — `{ householdId }` | `security-privacy.md §8` item 8. The join preview (which would add `householdName`) is deferred — `product-spec.md §4.0`. |
| Medications | `Pet.medications: [Medication]` embedded array; discontinue = delete | `pets/{petId}/medications/{medId}` subcollection + `active`, `startDate`, `endDate` | `architecture.md §3`. Backfill lifts the array into docs. `startDate` backfills to **`null`** (the legacy data has no real start date — inferring one from the pet doc's creation date would fabricate clinical history). |
| Attachments | dormant `photoUri: String` on `HealthNote` and `Pet` (never displayed, now removed from code) | nothing — attachments are post-v1 (`architecture.md §8`) | Backfill drops any legacy `photoUri` value. The `attachment` envelope field is added when attachments are actually built. |
| Export log | none | `households/{id}/exportLog/{id}` — admin-create, member-read | `architecture.md §7`. New, empty collection; no backfill. |

**Not changed by this migration** (kept at parity with the shipped app; several appear as
"v1" in `product-spec.md §4` but move to a later release — see `product-spec.md` "What the
next release contains"): pet `diagnosisDate` field, history filters, the frequency-trend
chart, the combined all-pets dashboard view, compare-to-similar-entries, Apple sign-in.

> **Flag for the owner of `security-privacy.md` — a stated assumption is contradicted by the
> live data.** §2.3's "a stranded anonymous identity **cannot arise in the next release** — it's
> Google-only, and Google uids survive reinstall", and the §3.2/§4.5 framing of stranding as
> design-for-later, are **false as of today**: the live household's three members are two Google
> uids and one **anonymous** uid (`<UID-ANON>`, last refresh 2026-08-17) that nobody can sign back
> into, and the two test households belong to a *second* unreachable anonymous uid. The stranding
> case is not theoretical and not deferred — it has already happened twice, before the next
> release ships. The mechanism is no mystery: the **shipped Kotlin app offers Anonymous sign-in
> today** (`CLAUDE.md`, session/auth flow), so "the next release is Google-only" is a statement
> about a release that hasn't shipped, while the *live data* was created by the one that did.
> `§8 item 8`'s parenthetical — the non-anonymous-creator assertion "protects the
> anonymous-stranding case, which the current household can't have" — is contradicted the same
> way.
>
> This plan handles its own corner of it (the anonymous member is left role-less — §4 area 1, §4
> window step 5). But §2.3's actor row, §3.2's "nothing here applies", §4.5, §8 item 8 and §10's
> "only once anonymous sign-in is actually in use" all need revisiting by whoever owns that
> document, and one of them may want a migration-era item of its own.
> **Not edited here — `security-privacy.md` is not this plan's to change.**

**Pet `archived` (new — in the next release).** Add `archived: bool` to every pet doc,
backfilled to `false` (area 4). The client switches "remove pet" from a hard-delete to
setting `archived: true` (a true delete stays available only for a pet with zero
observations). This closes the shipped-app quirk where deleting a pet orphaned its
observations. No rules change — a pet write is already admin-only (`security-privacy.md §8`).

### Legacy household fields — the pre-`pets` shape, still populated

The table above covers every *collection* and misses a set of *fields*. Every household doc in
the live project (all three) still carries the pre-multi-pet shape: `dogName`, `dogBreed`,
`dogWeightKg` (a genuine Firestore double), `vetName`, and a `medications` array of one entry —
sitting alongside the `pets` / `vets` subcollections that replaced them. The live pet doc has its
own `medications` array and its own `weightKg`.

Nothing reads them. The `Household` data class is `id, code, name, members, createdAtMillis`, and
no file under `app/src/main` mentions `dogName`, `dogBreed`, `dogWeightKg` or `diagnosisDate`. So
this is **orphaned duplicate health data that the app can neither display nor delete** — and the
only place anyone would notice it is a `backup.js` dump, which preserves it faithfully (right for
a backup, and the reason this surfaced at all).

- **Mapping decision: nothing maps.** No backfill area reads these fields. Everything the app
  actually uses already lives in `pets` / `vets` / the medications array. They are a **cleanup**
  item (§7), not a migration item.
- **Recommended disposition — delete them in the §7 cleanup, after a one-time diff.** Not in the
  window (which should stay as close to read-mostly as it can), and not before the diff: during
  the pre-window rehearsal, compare each household's legacy fields against its subcollections —
  `dogWeightKg` vs the pet's `weightKg`, `vetName` vs the `vets` docs, the household `medications`
  entry vs the pet's array. If every value is a duplicate, §7 deletes the fields along with the
  rest of the legacy shape. If anything is **unique** (an older weight, a vet that never made it
  into `vets`, a medication the pet doc doesn't have), it goes to Tom as a one-time manual merge
  into the pet/vet docs *before* the delete — that is real clinical history and no script should
  guess at it.
- **Why delete rather than leave.** `security-privacy.md §2.1` treats the household health record
  as the asset worth protecting, and §7 sets retention as "until a member deletes it". A second,
  invisible copy of a pet's weight, medication and vet that no member can see or remove is exactly
  what data minimization is for, and it is the same shape of problem as issue #17 (in-app
  account/data deletion): a deletion path that leaves undeletable copies behind is not one.
- **Still a product question, not a migration one.** **Needs Tom:** confirm delete-after-diff (the
  recommendation), or say the fields stay.

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
names here. Except where noted, these match the shipped `Seizure` model and are what the
backfill writes.

- **`seizures/{id}` → `type: "seizure"`.** `details` gets `durationSeconds`, `seizureType`,
  `symptoms` (list), `preSeizureSigns` (string), `possibleTriggers` (string),
  `recoveryMinutes`, `recoveryNotes`, `medicationGiven`, `medicationDetails`, `notes`.
  `medicationGiven` / `medicationDetails` are **renamed at backfill** from the shipped model's
  `rescueMedGiven` / `rescueMedDetails` — there is one medication concept, no "rescue" vs
  other category. There is **no** `recoveryBehavior` / `recoveryTime` field — those names in
  `architecture.md §3` and `product-spec.md §3` are phantom; no such data exists. `summary` = e.g.
  `"4 min · Generalized (grand mal)"` from duration + type, `"seizure"` if both empty.
- **`healthNotes/{id}` → `type: "note"`.** `details` gets a single `description` field. The
  health note form is now one text field (`product-spec.md §3/§4`), so any non-empty legacy
  `notes` is **merged into `description`** at backfill — `description + "\n\n" + notes` when
  both are set, `notes` alone if `description` is empty, `description` alone otherwise. No
  standalone `notes` key on `note` observations. `summary` = first ~60 chars of the merged
  `description`. Any legacy `photoUri` value is dropped.
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

### Hard constraint (2026-09-12): no prod data changes until the new builds are deployed

Tom, verbatim: *"Hold off on changing any data in prod until we get new versions of the app
deployed."*

Read literally, this inverts the window below: step 3 runs the backfill and step 6 installs the
new build, deliberately in that order, because the new app reads a shape that has to exist before
it first opens. "No data changes until the new build is deployed" and "the new build needs the
new shape already there" cannot both hold, so the instruction needs one of two readings before
any area runs against prod:

- **Reading A — "no ad-hoc prod surgery now, outside the real migration window."** The hold is on
  poking at prod *between sessions*: no exploratory writes, no partial backfills, no one-off
  console fixes. The window itself, once it is scheduled and both people are present, runs in the
  order below. *Implication:* the plan is unchanged; what changes is that every prod write waits
  for the window, and read-only work (counts, a `backup.js` dump, the §2 inventory) is the only
  prod access until then.
- **Reading B — "reorder the window: deploy the new builds first, backfill after."**
  *Implication:* the new build would launch against the legacy shape, so it must ship a
  dual-read compatibility layer — read `observations` if present else `seizures`/`healthNotes`,
  read the medications subcollection else the embedded array, tolerate a missing `role` — which
  is precisely the dual-read/dual-write window §2 rules out at two users. The new rules also
  could not be deployed until after the backfill, or the new build is locked out of the paths it
  needs. That compatibility code would be the largest app diff in the plan, written to be
  deleted.

**Recommendation: Reading A.** It is satisfiable, costs nothing, and is what every safeguard here
is already built around. Reading B buys nothing that the 45-minute supervised window doesn't
already give, and pays for it in throwaway compatibility code inside the riskiest area.

**Needs Tom's confirmation — this is his call, not the team's, and the window order below is not
rewritten on anyone else's reading of it.** Until he confirms: **no writes to prod of any kind** —
that includes issues #6/#7/#9/#10's backfills and any manual console edit. Read-only access is not
a data change and remains allowed; §4's first safeguard in fact *requires* a dump.

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
  pressure. **Done** (issue #5): the emulator round-trip is an automated test, and the
  procedure is written out command-by-command in `tools/migrate/README.md`. The rehearsal
  against a dump of the *real* data still has to happen before the window.
- **The backfill script is idempotent, and a dry run unless `--commit`** (the same convention
  `restore.js` already ships — there is no `--dry-run` flag to type). Node + Firebase Admin
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

### The window (~45 min, both people present, every member device on hand)

Gated on the hard constraint above being resolved (Reading A leaves this order as written).

0. **Pre-window (done earlier, not in the window):** take a dump, run the rehearsal (§4
   safeguards), confirm both apps are ready to install.
1. **Flush queued writes.** Open both apps online and let them fully sync, so nothing is
   sitting in a local write queue that could flush *after* the backfill has passed that
   collection. Then stop using both apps.
2. Take a fresh dump (`backup.js`) — this is the one the rollback uses.
3. Run the backfill: with no `--commit` first (that *is* the dry run), eyeball the per-area
   diff, then re-run with `--commit`. One entrypoint, areas in order: roles → join-code →
   observations → medications. (Export-log is a new empty collection — nothing to backfill.)
   **Decide the household scope first:** the project holds three households, two of them test
   junk (§2 inventory item 2). Either run across all three and verify all three, or pass
   `--household=<LIVE-HOUSEHOLD-ID>` and say so in the verification.
4. Deploy the new `firestore.rules` — a superset: every new path added and gated per
   `security-privacy.md §8`, legacy `seizures` / `healthNotes` / household-`code` access left
   permissive for now (§6).
5. **Verify roles before anyone relies on them:** for **every** uid in the household's
   `members` array — three in the live household (§2 inventory item 1) — read back
   `members/{uid}` and confirm the doc exists, and that both uids passed in `--admins` (the two
   Google uids) came back `role == "admin"`. If either admin doc is missing or has the wrong
   role, fix it via the script *before* continuing: once the admin-gated rules are live, a member
   with no `role` is locked out of every management action and cannot self-fix.

   **For the anonymous member `<UID-ANON>`, that lockout is the desired outcome.** Expect exactly
   one member doc with no `role` here, and leave it that way — do **not** "fix" it by granting a
   role. It is an unreachable identity (§2 inventory item 1); a role on it would be management
   access nobody can ever exercise or revoke from inside the app. The verification asserts it is
   role-less *on purpose*, rather than asserting all three have roles.

   *Caveat:* "unreachable" assumes no device still holds that anonymous session. If an old phone
   is still signed in as `<UID-ANON>`, it keeps member-level write access until the uid is pulled
   from the `members` array; the admin-gated rules narrow it to read-and-log but do not remove it.
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

**1. Roles.** Backfill: `--admins=<uid>[,<uid>...]` passed explicitly (never inferred from the
array, and never assumed to be two). For the live household the admin set is **settled: the two
Google uids** `<UID-GOOGLE-1>` and `<UID-GOOGLE-2>` (§2 inventory item 1). For each uid given:
ensure a `members/{uid}` doc exists, set `role: "admin"`.

**The third member, `<UID-ANON>`, is anonymous and gets no role — deliberately.** Nobody can sign
back into an anonymous uid after a reinstall, so it is a dead member that happens to still sit in
the `members` array. Leaving it role-less is the disposition, not an omission: it keeps read and
log access (rules only gate *management* on `role`) and loses nothing anyone can use.

Any *other* uid found in the `members` array gets a `members/{uid}` doc created with
`role: "member"` and is **reported, not silently normalised** — a stray uid nobody can identify
should not become an admin. The same path covers an array uid with no profile doc at all, which is
real live data (§2 inventory item 3).

**The report must distinguish an anonymous uid from a federated one**, because the disposition
differs: an anonymous uid in the array is presumptively dead weight (leave it role-less, consider
removing it), a Google one is a real person who probably *should* have a role. One
`admin.auth().getUser(uid)` per array uid answers it — `providerData` empty (and no email) means
anonymous — which is cheap at this scale and makes the report actionable instead of a list of
opaque strings. Apply the same reading to a `members/{uid}` profile doc whose uid is *not* in the
array.

Nobody is demoted; demotion to `member` is a deliberate in-app action later.

**Not a step: removing the dead anonymous member.** Pulling `<UID-ANON>` from the `members` array and
deleting its profile doc is **optional cleanup**, not a prerequisite for anything in this area —
and it is a prod data change, so it is parked under §4's hold either way. The role-less state
above is sufficient on its own. If it is ever done, it is the ordinary remove-member flow
(`security-privacy.md §4.3`) and wants the same dump-first treatment as any other write.

App: `MemberProfile` gains `role`. **`MemberRepository.upsertOwnProfile` must switch to
`set(..., SetOptions.merge())` with `role` excluded from the client-written payload** —
today it writes a whole `MemberProfile` with no merge, so once `role` exists a re-join (or
the Flutter cutover re-join) would overwrite it, and the admin-only `role` rule would then
reject the write outright, breaking the join flow. Add the admin checks in the
ViewModels/repositories that gate management actions; non-admin is a state the code must
handle even though neither current user is in it. Add a **promote/demote** action
(`MemberRepository` writes another member's `role`) and the **client-side last-admin guard**
(block demote/remove when it would leave zero admins — `security-privacy.md §4.4`).

Rules: `security-privacy.md §8` **items 1, 2, 3, 4** (item 5 is `observations`, area 3; item
10 is `exportLog`, area 5 — the earlier "1, 2, 4, 5, 10" list here was wrong):
- item 1 — `members/{uid}` create stays self-only, **but `role` must be absent or `"member"`
  on create, and immutable on any self-`update`**; another member's `role` is writable **only
  by an admin** — that's the promote/demote path (`product-spec.md §4`, `security-privacy.md
  §8` item 1). The last-admin invariant is client-only (§4.4).
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
`{ joinCode: <current code field> }`. Leave `households/{id}.code` in place until §7.
`codeIndex/{code}` keeps its `{ householdId }` shape — the join preview is deferred
(`product-spec.md §4.0`), so nothing needs the household name in the index yet.

App: read the code from `private/config` (admin-gated "show join code" UI). No join-preview
screen — enter code → join.

Rules: `private/config` — `allow read, write: if admin`; `codeIndex` `get` stays
any-signed-in; `codeIndex` `create` asserts the `{ householdId }` shape; `list` stays
`false`. The admin-of-target-household gate on `codeIndex` create/update/delete is **post-v1**
(it ships with rotation — see the note below and `security-privacy.md §8` items 6–8).

**Not in this area / not in the window:**
- **Code rotation is not built here** (see §1). It's new feature work, not a relocation, and
  building it inside the highest-risk window is the wrong place — nobody needs to rotate a
  code on migration day. Deferred to a follow-up PR: rotation must be a single atomic
  `WriteBatch` (create new `codeIndex` + delete old + update `private/config`), with its own
  `firestore-tests` cases.
- **Household creation still mints a code**, as today. `security-privacy.md §3.2`'s "no code
  until an admin links a durable credential" and `§8 item 8`'s non-anonymous-creator
  assertion don't apply — the next release is Google-only (`product-spec.md §4.0`), so every
  creator is already durable. They ship with anonymous sign-in later. Note that `§8 item 7`'s
  "admin of the
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
- **`rescueMedGiven` / `rescueMedDetails` → `medicationGiven` / `medicationDetails`** in the
  seizure `details` (§3). The sealed `ObservationDetails.Seizure` uses the new names; the
  backfill renames the legacy keys. One medication concept — no "rescue" vs other category.
  UI labels drop "rescue" too (`SeizureDetailScreen`, `AddEditSeizureScreen`, the PDF/CSV
  exporters and their tests).
- **`summary` is recomputed on every write** (§3).
- **Keep the read pattern the shipped app uses:** fetch the collection with a single
  `orderBy('occurredAt', descending: true)` and filter by `type` / pet / logger client-side.
  If `ObservationRepository` instead adds a `where(...)` alongside the `orderBy`, that's a
  composite index — which fails at runtime, during verification. If a server-side filter is
  wanted, declare the composite index in `firestore.indexes.json` (which now exists and is
  wired into `firebase.json`, so `firebase deploy --only firestore` covers rules + indexes)
  and deploy it *before* the window.

Rules: add `observations` — `read: if member`, `create: if member && loggedByUid ==
auth.uid`, `update/delete: if (admin || author) && request.resource.data.loggedByUid ==
resource.data.loggedByUid` (authorship is immutable — `security-privacy.md §8` item 5). Keep
the `seizures` / `healthNotes` rules in place until §7.

**4. Medications subcollection + pet `archived`.** Backfill, per pet: (a) set `archived:
false` on the pet doc if the field is absent; (b) for each entry in the embedded
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

App: `Pet` drops the embedded `medications` and gains `archived: bool`; `PetRepository` reads
the subcollection; "discontinue" a medication becomes `active: false` + `endDate` set instead
of a delete; current-meds UI filters `active == true`. "Remove pet" sets `archived: true`
(true delete only for a pet with no observations); the switcher and the dashboard filter
`archived == false`, **but the export pet picker does not** — it lists every pet so an
archived pet's history stays reachable for a vet report (`product-spec.md §4`). Rules:
`pets/{petId}/medications/{medId}` — `read: if member; write: if
admin` (its own nested `match`, not covered by the `pets` rule); the pet doc's own
admin-only write rule already covers the `archived` flip.

`startDate`/`endDate`/`active` are stored and written, but the next release only *shows* the
active list — no past-medications view and no "set an alarm" hand-off (`product-spec.md §4.0`).
Both are pure client features for later; nothing here blocks them.

**5. Export log.** No backfill (new empty collection). App: on a successful export, an
admin's device writes one `{ type, rangeStart, rangeEnd, petIds, createdAt }` doc; export
becomes admin-gated (`product-spec.md §4`). Rules: `exportLog/{id}` — `create: if admin`,
`read: if member`, no update/delete.

## 5. The tooling (`tools/migrate/`)

Three Node entrypoints, all Admin SDK — which bypasses Security Rules, the point of using it.
Credentials for the prod project are **either** gcloud Application Default Credentials
(`gcloud auth application-default login` — preferred: nothing long-lived on disk, one-command
revoke) **or** a service-account key in `GOOGLE_APPLICATION_CREDENTIALS` (gitignored; a
long-lived asset `security-privacy.md §2.3` treats as equivalent to the whole database). Both
scripts accept either and print which one they resolved; the ADC path additionally requires an
explicit `--project`, since ADC carries no project id. Steps in the tool's README:

- **`backup.js`** *(built — issue #5)* — recursive read of `households/{id}` + every
  subcollection + `codeIndex/*` → timestamped local JSON + a per-collection count manifest.
  Read-only; it has no write path. Subcollections are **discovered** (`listCollections()`), not
  read off a hardcoded list, so a collection added later is dumped rather than missed — and any
  collection the migration doesn't know about is reported.
- **`restore.js`** *(built — issue #5)* — from a dump: **empty the households in the dump's
  scope**, including collections the dump never saw (the delete set is crawled from the *live*
  tree, which is what makes it a rollback rather than a merge over whatever is there), re-write
  every doc, then re-read and compare **counts, document-id sets and every field value** against
  the dump, exiting non-zero on any mismatch. Counts alone would pass a doc written under the
  wrong id; ids alone would pass a codec regression that wrote `{}` for every doc, so the gate
  deep-compares content too, tolerating only the integral-double retype in item 1 below and
  reporting every field it tolerated. A dry run unless `--commit` is passed (there is no
  `--dry-run` flag to type); `--allow-prod` is required on top of `--commit` against a live
  project. **Irreversible once committed, with no outer transaction** — see "Restore procedure"
  below and the README's "If a restore is interrupted".
- **`migrate.js`** *(not built — issues #6/#7/#9/#10)* — `--area=roles|joincode|observations|meds|all`,
  a dry run unless `--commit` (same convention as `restore.js`: no `--dry-run` flag),
  `--household=<id>` (three households exist — §2 inventory item 2), `--admins=<uid>[,<uid>...]`
  (required for the roles area; a list of any length, not a pair — for the live household it is
  the two Google uids, §2 inventory item 1).

**Four things the plan above didn't anticipate, found while building the dump/restore half.**
All four are now handled in the tooling; they're recorded here because §5's restore procedure
and §7's count assertion lean on them:

1. **A restore cannot reproduce an integral double.** The Node Admin SDK's serializer encodes
   any JS number passing `Number.isSafeInteger()` as a Firestore *integer*, with no way to force
   a double — so a stored `12.0` comes back as `12`. Both scripts report every affected field by
   path (`manifest.integralDoubleFields`) rather than letting it surface during verification. The
   only double in the shipped shape is `Pet.weightKg` — **and prod really does store it as a
   `doubleValue`**, on the live pet and (as `dogWeightKg`) on all three household docs (§2
   inventory item 5), so this is a fact about the real restore, not a hypothetical. The Android
   SDK widens an integer back to a `Double?` on read and Firestore's numeric comparisons span
   both types, so the impact is a recorded type change, not data loss. Non-integral doubles, `-0`, `NaN` and the infinities
   round-trip exactly. The same limit will apply to anything `migrate.js` writes.
2. **`codeIndex` is a top-level collection, not a household subcollection.** A restore scoped to
   one household must not clear another household's join code, so `--codeindex=scoped` (the
   default) only touches codes that are in the dump or point at a household in it, and a dump
   narrowed with `--household` narrows its `codeIndex` to match. **Not moot:** the project holds
   three households and three `codeIndex` entries, two of each belonging to the test-junk
   households (§2 inventory item 2), so a single-household restore would otherwise clear codes
   that point somewhere else.
3. **"Assert the expected collections are present" can't be a hard failure by default.** An
   empty Firestore collection does not exist, so a household that has never logged a health note
   genuinely has no `healthNotes` collection and that is indistinguishable from a crawl that
   missed it. `backup.js` warns by default and fails only under `--require-expected`, which the
   pre-window rehearsal passes (there the expected contents are known).
4. **A document can hold subcollections while having no fields of its own.** A `collection.get()`
   crawl skips those and silently drops their whole subtree; a pet hard-deleted while its
   medications remained is exactly that shape (the orphan quirk §3's `archived` flag closes). The
   crawl uses `listDocuments()` and records them in `manifest.missingParents`.

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

Not "the screens render." Concretely, and **per household — the project holds three, so the gate
either covers all three or is explicitly scoped with `--household` and says so** (§2 inventory
item 2):
- `count(observations) == count(seizures) + count(healthNotes)`, per household.
- `count(pets/{petId}/medications) == length(pet.medications array)`, per pet.
- Field-by-field check of **three specific known entries** — the oldest seizure, the most
  recent seizure, one health note — against the old app's rendering: duration, type, every
  symptom, `occurredAt`, notes.
- **Every** uid in the household's `members` array has a `members/{uid}` doc, and **every uid
  passed to `--admins`** came back `role == "admin"` — for the live household, the two Google
  uids (§2 inventory item 1).
- **Every other array uid is either given `role: "member"` or is a declared exception.** Not
  "every member doc has a role": the live household's anonymous member `<UID-ANON>` is deliberately
  left role-less (§4 area 1), so the gate takes the expected-role-less uids as input and asserts
  *exactly* that set is role-less — an unexpected role-less member is a failure (it would be
  locked out with no way to self-fix), and a role appearing on a declared-role-less uid is
  **also** a failure (someone "fixed" a dead identity into management access).
- Each reported uid is labelled **anonymous or federated** (`admin.auth().getUser`,
  `providerData` empty ⇒ anonymous), since that is what decides its disposition (§4 area 1).
- **No member doc is orphaned the other way either:** a `members/{uid}` doc whose uid is not in
  the array is reported (it grants nothing, but it means the roster and the access list disagree).

`migrate.js --area=verify` runs the automated checks and **exits non-zero on any mismatch**;
the §7 cleanup delete refuses to run unless it passes.

### Restore procedure

**The commands are a written transcript in `tools/migrate/README.md` ("Restore procedure") — run
it as-is rather than reconstructing it here.** Three things from it belong in the plan because the
plan is what gets read first:

If a backfill goes wrong *before cutover* (no new-app writes yet): **dump the current state
first** (`backup.js --label=pre-rollback`, read-only, seconds), then dry-run `restore.js <dump>`,
then commit it, then redeploy the previous rules and the previous app. The restore is
**irreversible** — it deletes every document in the households the dump names, including
collections the dump never saw, before writing anything — so the pre-rollback dump is the only
copy of whatever the backfill wrote. §9 accepts losing post-dump entries; it does not require
losing them.

**If a restore is interrupted**, the answer is **re-run the identical command, including
`--commit`.** `commitInChunks` has no outer transaction, so a failure in the delete pass exits 2
with an arbitrary subset of the households deleted and nothing written back; the restore is
idempotent (live-tree-crawled deletes, fixed-path `set()` writes), so a second run completes a
partial one and a second run of a complete one is a no-op. Both are covered by the test suite.
This is the same "crash-safe by re-run" property as the `migrate.js` areas below, but it is worth
stating separately: `restore.js` is the one with a destructive first pass.

If something is discovered wrong *after cutover*: you can no longer restore losslessly (new
entries exist only in the new shape). Options are (a) fix forward with a corrective
`migrate.js` pass, or (b) `restore.js` and manually re-enter whatever was logged since the
dump. There is no automated reverse backfill; at two users the manual re-entry of a handful
of entries is the accepted fallback.

### Testing the tooling

- **Rehearsal against real data (required, pre-window):** `backup.js` prod → load the dump
  into the local Firebase emulator → run all areas → `--area=verify` → re-run for
  idempotency → run `restore.js` against the dump and confirm it round-trips. The dump/restore
  half of this is a written transcript in `tools/migrate/README.md` ("The real-data rehearsal")
  — run it as-is; it needs prod credentials (gcloud ADC is enough) and nothing else. The backfill half
  waits on `migrate.js`.
- Emulator fixture: also keep a hand-seeded "legacy shape" household (old collections, `code`
  field, no roles, embedded meds, **a member-array uid with no profile doc — which is real live
  data, not a synthetic edge case: `<HOUSEHOLD-2-ID>` has one array uid and zero member docs
  (§2 inventory item 3), so nobody should later trim this case as invented**, a doc with
  `timestampMillis == 0`) for the fast unit-style assertions. **Built** — it lives in
  `tools/migrate/__tests__/helpers.js` and is reused by the dump/restore round-trip test; add
  the backfill assertions to the same fixture rather than seeding a second one. It also carries
  two medication entries differing only in `notes` (the §9 content-hash case), a fieldless pet
  owning a medications subcollection, and a collection the migration has never heard of.
- The tooling's own tests are **their own Node + Jest package** (`tools/migrate/`), not part of
  `firestore-tests/`: that package tests `firestore.rules` with the client SDK and is owned by
  `rules-engineer`, while this one uses `firebase-admin` and deliberately bypasses rules.
  `.github/workflows/ci.yml` needs two steps added for it (see the tool's README) — not yet
  wired.
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
rollback (for an admin — after area 1 the previous app's writes to `pets`/`vets`/embedded meds
require admin). **That holds only for the uids that actually end up `admin`.** With three member
uids in the live household and the assignment still open (§2 inventory item 1), a member left as
`member` cannot write pets/vets/embedded meds from the old app at all; if the rollback story has
to work for a particular device, that device's uid has to be in `--admins`.

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
3. **Cleanup pass** (a dry run unless `--commit`, like every other area): delete
   `households/{id}.code`, delete the embedded `medications` array from pet docs, delete all
   `seizures/*` and `healthNotes/*` docs.
4. **Legacy household fields** (§3 "Legacy household fields"): delete `dogName`, `dogBreed`,
   `dogWeightKg`, `vetName` and the household-level `medications` array from every household doc.
   **Gated on** the pre-window diff having shown they hold nothing the `pets`/`vets`
   subcollections don't — or on Tom having merged whatever was unique into the pet/vet docs
   first. This is the data-minimization half of the cleanup (`security-privacy.md §2.1`/§7,
   issue #17): until it runs, the record holds a copy of a pet's weight, medication and vet that
   no member can see or delete. Recommended, still **needs Tom's confirmation** (§3).
5. **Rules:** remove the `seizures`, `healthNotes`, household-`code`, and embedded-medication
   blocks. (No `members` → `memberIds` rename — that was dropped, see §1.) The legacy household
   *fields* need no rule change — they are fields on a doc whose write rule is already admin-only.
6. **App / tests:** delete the dead model classes, repository methods, compatibility
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
- **Dump fidelity** — the dump is typed JSON (integers as strings, `Timestamp`/`GeoPoint`/
  `Bytes`/`DocumentReference`/`NaN`/`-0` tagged), so it round-trips every Firestore type
  **except** a double whose value is a safe integer, which the Node Admin SDK cannot write
  back as a double. Reported per field, accepted rather than worked around (§5). **This is live,
  not hypothetical:** prod stores `weightKg` as a real `doubleValue` on the pet and
  `dogWeightKg` on every household doc (§2 inventory item 5), so a restore will retype them and
  the manifest will name them.
- **Migration-tooling credentials** — gcloud ADC (`gcloud auth application-default login`) is
  the preferred credential for the live project, with a service-account key as the fallback.
  Same reach either way (the Admin SDK bypasses rules), but ADC leaves no long-lived key file on
  the laptop and revokes with one command, and `security-privacy.md §2.3` lists a key holder as
  an actor equivalent to the whole database. Both scripts accept either and report which they
  used; ADC additionally requires an explicit `--project`.
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
- **Pet `archived`** — backfills to `false` (§4 area 4); enables archive-instead-of-delete
  (`product-spec.md §4`), closing the shipped quirk where deleting a pet orphaned its
  observations. Verification asserts every pet doc has the field after backfill.
- **`members` → `memberIds` rename** — **dropped.** The array keeps its name (§1).
- **Code rotation** — **not built here.** Deferred to a follow-up PR (§1, §4 area 2).
- **Min-version gate** — not needed and not built. Two devices that update together.
- **Admin set for the live household** — **the two Google uids** (`<UID-GOOGLE-1>`,
  `<UID-GOOGLE-2>`), passed explicitly to `--admins`. The third member (`<UID-ANON>`) is an
  **anonymous, unreachable identity and is left with no `role` on purpose** — a dead member that
  keeps read/log access and gains no management access nobody could exercise (§2 inventory item 1,
  §4 area 1). Removing it from the `members` array is optional cleanup, parked under §4's hold,
  not a step in any area. Settled 2026-09-12 from Firebase Auth provider types; not inferred by
  any script, now or later.
- **Verification gate** — automated count assertions + a three-entry field check (§5), run at
  the window and again before the §7 cleanup delete. **Count-agnostic on roles** (every member
  doc has a `role`; the `--admins` uids came back `admin`) and either run per household across
  all three or explicitly scoped with `--household`.
- **`flagForVet` / "mention at next vet visit"** — dropped from the product entirely. No
  field on the envelope, no backfill.
- **Health note `notes`** — dropped as a standalone field; the form is now one text box
  (`product-spec.md §3/§4`). Backfill merges any non-empty legacy `notes` into `description`
  (§3). No data lost.
- **`summary` for backfilled seizures** — `"<duration> · <type>"` (e.g. `"4 min ·
  Generalized (grand mal)"`), `"seizure"` when both are empty. Recomputed on every write
  (§3); the Flutter client may drop the field and format at render time instead.

### Open — waiting on Tom (nothing runs against prod until these are answered)

Recorded here so they are not re-litigated in an issue thread. Each is written up where it
matters; this is the index. (The admin set is **no longer** on this list — it was settled
2026-09-12; see the decision above. Separately, and not a Tom decision: the
`security-privacy.md` stranded-anonymous assumption that the live data contradicts needs routing
to that doc's owner — the callout in §3.)

| Open item | Where | Recommendation |
|---|---|---|
| What happens to the **two test-junk households** (08-16, one foreign **anonymous** uid, 2 of 3 `codeIndex` entries, 4 real observations, 2 pets) | §2 inventory item 2 | No deletion planned or implied. Left in place, every area and the gate cover three households, and their anonymous owner is left role-less for the same reason `<UID-ANON>` is; if Tom later decides to delete, that is its own gated, dumped, irreversible step. |
| The reading of **"hold off on changing any data in prod until we get new versions of the app deployed"** | §4 hard constraint | Reading A (no ad-hoc prod surgery outside the window; window order unchanged). Reading B would force throwaway dual-read code into the largest area. |
| Disposition of the **legacy household fields** (`dogName`, `dogBreed`, `dogWeightKg`, `vetName`, household `medications`) | §3 "Legacy household fields", §7 step 4 | Delete in the §7 cleanup after a rehearsal diff proves they duplicate the subcollections; anything unique goes to Tom for a manual merge first. Data minimization — `security-privacy.md §2.1`/§7, issue #17. |
