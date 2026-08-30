# Architecture — Pet Health Diary (v1)

**Status:** draft for discussion · **Last updated:** 2026-08-30
**Companion docs:** `product-spec.md` (features, entities, §4.0 "what the next release contains"), `security-privacy.md` (threat model, data handling, admin/access/ownership), `migration.md` (the Firestore-shape move), `flutter-migration.md` (the client re-platform)

## 0. This document is the target, not the shipped app

This describes where the app is headed, not what runs today. The shipped app is **Kotlin +
Jetpack Compose** (not Flutter) and already does the multi-pet / vets / health-notes
redesign, but on a different Firestore shape. Two sequenced plans close the gap: `migration.md`
(the Firestore shape/rules move, on the current Kotlin app) then `flutter-migration.md` (the
client re-platform). What's **not yet built**:

- **Flutter client** → `flutter-migration.md`, sequenced after `migration.md`. Next release
  targets **iOS + Android**; web is post-v1.
- **`observations` collection.** Today seizures and health notes are two collections
  (`seizures`, `healthNotes`) → `migration.md §4 area 3`.
- **Admin/member roles.** Today no roles — every member can write everything, remove anyone.
  `security-privacy.md §8` is the rule set; `migration.md §4` is the order it lands in.
- **`private/config` join-code relocation** and **medications as a subcollection** with
  `active`/`endDate` → `migration.md §4` areas 2 and 4.
- **Cloud Functions — none, and none planned.** `security-privacy.md §9` sketches a few for
  post-v1; the `memberIds`-sync and vet-cleanup Functions this doc used to describe are
  **removed from the target** (§3). Nothing here is a near-term dependency.
- **Household notifications (§5) and photo/video attachments (§8)** — out of the next release
  and backlogged. §8's local-only conclusion stands as the approach for when attachments are
  built; the shipped app's half-built `photoUri` capture was removed.
- **`diagnosisDate` / `archived` pet fields, history filters, the frequency-trend chart, the
  combined all-pets dashboard view, the in-progress seizure timer, voice dictation,
  compare-to-similar-entries** — named in `product-spec.md §4` but **not in the next release**
  (see `product-spec.md`, "What the next release contains").

## 1. Goals that shape every decision here

Pulled straight from the product spec and the project's own constraints, because they rule out a lot of otherwise-reasonable architectures:

- **Hobby-scale, single maintainer.** No ops team, no on-call. Prefer managed/serverless services over anything Tom has to patch, scale, or babysit.
- **Free or near-free at this usage level.** A handful of households, low write volume. Cost should stay at $0–few dollars/month until the app has real usage that justifies spending.
- **Offline-first is non-negotiable.** Logging a seizure must work with no connectivity and sync automatically later — this is called out as a hard requirement in the spec, not a nice-to-have.
- **No added latency to the seizure-logging flow.** Anything architectural that would make that screen slower to open or save is the wrong call.
- **No vet-facing backend surface.** Confirmed non-goal — simplifies auth and access control to "household members only," ever.

## 2. Stack at a glance

| Layer | Choice | Why |
|---|---|---|
| Client | Flutter — **iOS + Android** for the next release. Web is post-v1 (§10 Hosting row). | One codebase for the platforms in scope; see `flutter-migration.md`. |
| State management | Riverpod | Plays well with Firestore streams, testable, no boilerplate-heavy alternative needed at this scale. |
| Local persistence / offline cache | Firestore's built-in offline persistence (SQLite under the hood) | Firestore ships this for free — no separate local DB or hand-rolled sync engine to build or maintain. |
| Backend | Firebase — **Firestore + Auth only** for the next release | Serverless, generous free tier, first-class Flutter support via FlutterFire. Cloud Functions + Cloud Messaging are post-v1 (§9); no Function is deployed or planned. Picked over Supabase to avoid running any backend Tom would maintain. |
| Auth | Firebase Auth — **Google + anonymous** for the next release; Apple with App Store distribution (`security-privacy.md §3.1`) | Matches the spec's sign-in options, including "continue without an account" via anonymous auth. |
| Firebase plan | **Spark (free) only** — no billing account | Firestore + Auth stay on Spark indefinitely at this scale. Deploying any Cloud Function or Cloud Storage bucket forces Blaze (§9). |
| Push notifications | Firebase Cloud Messaging | **Post-v1, backlogged (§5)** — needs a Function, so needs Blaze. |
| Media (photos/videos) | Local device storage only + OS-native share sheet (`share_plus`) | **Post-v1, backlogged (§8).** When built: no Cloud Storage, no encryption to design. |
| PDF/CSV export | Generated on-device (Dart packages), not server-side | Works offline once data is cached locally; no server compute. |
| Hosting (web dashboard) | Firebase Hosting | **Post-v1** — the next release is iOS + Android only. |

## 3. Data model

Firestore is document/collection-based, not relational, so the spec's entities map to collections with denormalization in a few places to keep the seizure-logging read/write path cheap.

Field names below are **illustrative**. `migration.md §3` is normative for the exact
`details` keys and the `*Millis` vs `Timestamp` split — where the two disagree, `migration.md`
wins (its names are the ones the shipped models use and the backfill reads).

```
households/{householdId}
  name, createdAtMillis, members: [uid, uid, ...]
  # `members` is the client-written array that gates access (keeps its shipped name — the
  #   `memberIds` rename was dropped; migration.md §1). No Function keeps it in sync.
  # no plaintext join code here — post-cleanup it's in private/config (migration.md §7)
households/{householdId}/private/config
  joinCode                                     # admin-only read/write (security-privacy.md §8 item 7)
households/{householdId}/members/{uid}
  displayName, role: "admin" | "member", joinedAtMillis, authMethod
  # source of truth for metadata + role; only an admin may write another member's `role`,
  #   and `role` is absent-or-"member" on self-create (security-privacy.md §8 item 1)
households/{householdId}/pets/{petId}
  name, species, breed, weightKg, birthDateMillis, createdAtMillis
  # diagnosisDate, archived: post-v1 (product-spec.md "what the next release contains")
  # photoRef: post-v1 with the rest of attachments (§8)
households/{householdId}/pets/{petId}/medications/{medId}
  name, dose, frequency, notes
  active: bool, startDate, endDate            # startDate/endDate null on backfill (migration.md §3)
households/{householdId}/vets/{vetId}
  name, phone, addressOrNotes                 # shipped shape; see the vet-model note below
households/{householdId}/petVetLinks/{linkId}
  petId, vetId, vetName, role                 # flat collection (see denormalization note)
households/{householdId}/observations/{observationId}
  type: "seizure" | "note" | ...              # open-ended; new types add cheaply, see below
  petId, loggedByUid, loggedByName
  occurredAt: Timestamp                       # when the thing happened
  createdAt, updatedAt: Timestamp
  summary: string                             # feed line; a RENDER CACHE, recomputed on
                                               #   every write (migration.md §3)
  # attachment: {...} | null                  # post-v1 — see §8
  details: { ...type-specific fields... }
    # seizure: durationSeconds, seizureType, symptoms[], preSeizureSigns, possibleTriggers,
    #   recoveryMinutes, recoveryNotes, rescueMedGiven, rescueMedDetails, notes
    #   (no recoveryTime / recoveryBehavior — those were phantom; migration.md §3)
    # note: description, notes

codeIndex/{code}
  householdId, householdName                   # id + display name only (security-privacy.md §8 item 8)
  # top-level, not under households/ — a joiner resolves a code to a household id before
  #   they're a member and can read the household doc. No pet names, no vet info, no health data.
```

**Single polymorphic `observations` collection, envelope + details:** every logged thing — a seizure, a general note, and whatever gets added later (medication given, vet visit, weight check) — is something a household member observed and recorded about the pet. Rather than force each into its own collection, they all live in `observations`, discriminated by `type`. At this data volume there's no reason to give seizures special-case treatment; a single unified per-pet timeline is also simpler to query and export (§7) — and to notify on, if notifications are ever built (§5) — than merging multiple collections would be. `seizure` and `note` are the first two `type`s; more get added as new observation types come up, without restructuring anything.

The document is split into two parts on purpose:

- **Envelope** — fields common to every observation type, and the ones ever sorted or filtered on: `petId`, `type`, `occurredAt`, `loggedByUid` (history filters by logger — `product-spec.md §4`), and `loggedByUid` is also rule-load-bearing (`security-privacy.md §8 item 6`). Plus `attachment` once that lands (§8). These stay top-level so the timeline/export queries don't care what type an observation is.
- **`details`** — a type-specific payload map, read but never filtered on. Firestore doesn't enforce a schema, so this costs nothing *schema*-wise. It does cost indexes: Firestore auto-creates single-field indexes for nested map fields and array elements, so every `details.*` scalar and every `details.symptoms` element gets index entries and write-amplifies each observation. Harmless at two users, but add a single-field index **exemption** on `observations.details` (all modes off) so the "costs nothing" claim is literally true — which means the repo needs a `firestore.indexes.json` (there is none today; `firebase.json` declares only rules + emulators), added in the migration window.

The read pattern is: fetch the collection with one `orderBy('occurredAt', descending: true)` and filter by `type` / pet / logger **client-side** (this is what the shipped app does across its two collections, and `flutter-migration.md §5` keeps it). So the envelope/`details` split isn't about query capability today — it's about index surface and schema evolution. Adding a new observation type later is a new `type` value + a new `details` shape in the Dart layer (a freezed union), no new collection and no rule change beyond §6. Two things deliberately left undone: field-level validation is left to the Dart model layer (one trusted client, no third-party writers); and this is for point-in-time events, not ongoing state — a recurring medication *schedule* would need its own shape.

**Other denormalization and modeling choices, and why:**

- **Membership: the client-written `members` array on the household doc is the source of truth for *access*; `members/{uid}` is the source of truth for *metadata + role*.** There is no derived `memberIds` cache and no Function keeping anything in sync — the earlier design (a Function-maintained array so the client could answer "which households am I in" with one `array-contains` query) is dropped: the app doesn't have a multi-household picker, it resolves the household from local prefs, and a Function would force Blaze. Join and self-leave are plain array writes the rules already handle (`security-privacy.md §8 item 3`).
- **Pet↔vet links are a flat `petVetLinks` collection** (kept from the shipped app — an earlier revision of this doc proposed folding them into a `linkedVets` array on the pet doc; that's dropped). A household has a handful of pets and the client already holds the full pet list in memory, so "which pets does this vet care for" is a free client-side filter. `vetName` is copied onto each link (same reasoning as `loggedByName` on observations) so the pet screen doesn't need a join. Deleting a vet doesn't cascade (Firestore never does), but the client deletes the matching links in a `WriteBatch` alongside the vet — no Function needed. The array version would have needed a Function for *both* vet deletes and vet renames (to fan the new name into every pet), which is why it's out.
- **Medications carry `active`/`startDate`/`endDate` instead of being deleted when discontinued.** Without this, stopping a medication means deleting its doc — which quietly erases history that can matter later (a vet asking "was he ever on X," or spotting a correlation between a med change and seizure frequency). Discontinuing a medication is now an update (`active: false`, `endDate` set), not a delete, so the pet's full medication history stays intact. The current-medications screen just filters `active: true`; a history view can show everything. This matters more once people other than Tom are the ones managing the data — a "discontinued" state is much harder to do wrong than remembering not to delete something.
- `loggedByName` is copied onto the observation at write time instead of joined from the member doc, so history/export screens don't need an extra read per observation, and old observations still show the right name if someone changes their display name later.
- **Vet doc shape is the shipped one — `name`, `phone`, `addressOrNotes`** (one free-text field, not separate address/email). The design brief sketches a richer contact (email, structured address); that's a post-v1 model change, not part of the re-platform. `flutter-migration.md §5` ports the shipped shape.

## 4. Offline-first logging and sync

This is the architecture's central requirement, so it's worth being explicit about how it's satisfied rather than assumed:

- FlutterFire's Firestore SDK persists a local cache automatically (enabled by default on mobile). Reads come from cache instantly; writes are queued locally and applied optimistically to the UI.
- The seizure form writes directly to the local cache and returns immediately — the save button doesn't wait on a network round trip. The SDK flushes queued writes to Firestore itself once connectivity returns; there's no custom retry/queue logic to write or maintain.
- The one thing this pattern doesn't give for free: conflict handling if two people edit the *same* observation offline at the same time. The access split (`security-privacy.md` §4.1) narrows the editors to its logger plus admins, so a genuine concurrent edit is rare; last-write-wins (Firestore's default) is an acceptable trade-off rather than something to engineer around. **But** the likelier offline conflict is delete-vs-edit — one device deletes an entry, the other edits it offline, and a `set()` would *resurrect* the deleted doc silently. So observation edits use `update()` (which fails on a missing doc), not `set()` — `migration.md §4 area 3`.
- **Security Rules are not evaluated on the local cache.** A write a non-admin (or a since-demoted admin) queues offline is applied optimistically to the UI, then rejected on flush — the SDK drops it and the cache reverts with no error surfacing on the screen that made it. Once the role split lands, every management action has this path. Mitigation is client-side: never let a non-admin *initiate* a gated write. `flutter-migration.md §11` verifies the rejected-write behavior on a real device in Phase 3.
- The in-progress seizure timer (a post-v1 feature) would live in local app state (Riverpod), not Firestore — no reason to round-trip a running timer through the network layer.

## 5. Household notifications

**Out of the next release — backlogged.** The spec's "saving notifies the rest of the
household" is not built and not scheduled. Firebase Cloud Messaging is the natural fit when
it's time (a client can't push to another user's device directly, so this needs a small Cloud
Function trigger on observation-create) — but that Function would put the project on the Blaze
plan, which everything else here (§8, §9) is built to avoid. So this feature carries a real
cost decision (accept Blaze for one Function, or find a non-Function path) that's deferred
along with the feature. Nothing to lock in now.

## 6. Access control

Enforced via Firestore Security Rules rather than a custom backend layer:

- A user can **read** anything under `households/{householdId}/**` only if their uid is in that household's `members` array.
- **Writes are role-split** (see `security-privacy.md §4.1` for the rationale, `§8` for the per-collection rules and the `isMember()`/`isAdmin()` helpers — every admin gate is the *conjunction* of membership and role): any member may create an `observations` doc and edit/delete one where `loggedByUid` is their own uid; everything else — `pets`, per-pet `medications`, `vets`, `petVetLinks`, the household doc (rename), the `members` subcollection and roles, `private/config`, `exportLog` — is admin-only.
- The plaintext join code moves to an **admin-only** `households/{id}/private/config` doc — but only after `migration.md §7` cleanup removes the legacy `code` field; until then it's still on the household doc.
- Full mechanics of joining, admin transfer, and account durability are in `security-privacy.md §§3–4`; the order the §8 rule changes land in is `migration.md §4`.

Security Rules are the entire access-control layer — no server-side authorization code to write or audit beyond the rules file itself, which fits the "no vet-facing backend surface, no backend to maintain" goal directly.

## 7. Export (PDF / CSV)

Generated **on-device**, not via a Cloud Function, for two reasons: it works offline once the relevant observations are in the local cache, and it avoids paying for (or waiting on) server compute for something Dart can do directly.

- CSV: straightforward serialization of the filtered observation set.
- PDF: built with the `pdf` and `printing` Dart packages — a clean report with a header and per-observation sections. (A trend chart is a post-v1 feature — when it lands it's a widget rendered to an image via `RenderRepaintBoundary.toImage` and embedded; `flutter-migration.md §4` names the charting package.)
- Attachments are post-v1 (§8), so exports are text-only for now. When attachments land: because they're local-only, an export built on one device can only embed the media present on that device, and the exported file should say so ("N attachments not available on this device") rather than read as data loss.
- Exporting is an **admin-only** action (`security-privacy.md` §4.1) — an export leaves the household as a file. A non-admin who needs the vet report asks an admin, or hands the vet the phone directly (which anyone can do).
- The "log of past exports" is a small record (export type, date range, timestamp) written to `households/{householdId}/exportLog/{id}` — created only by an admin (the rules match the export gate, `security-privacy.md` §8), readable by any member so anyone can see when an export last happened.

## 8. Media storage — post-v1; approach decided: local-only, no cloud

**Photo/video attachments are out of the next release and backlogged.** The shipped app's
half-built photo capture (a `photoUri` string that was never displayed or exported) has been
removed. This section records the *approach* for when attachments are built — that decision
holds — but nothing here is in the next version.

The product spec flagged this as unresolved: attachments need to be useful to the household, but must not sit in the cloud the same way the rest of the household's data does. The original plan here was Firebase Storage with client-side encryption. Two things changed that conclusion:

First, Cloud Storage for Firebase now requires the Blaze (pay-as-you-go) plan for any new project — as of a September 2024 change, even a default bucket with zero usage needs a linked billing account. Realistic usage would likely stay within Google Cloud's Always Free tier (5 GB-months storage, 100 GB/month egress), but it's no longer possible to avoid the billing account requirement itself on a new project, which cuts against the "free, nothing to babysit" goal in §1.

Second, and more decisively: given how the household actually uses this app — people who see each other in person at least sometimes — going through the cloud at all was solving a problem that didn't need solving. **Attachments (photos and video) live only on the device that captured them.** Firestore stores just a small attachment reference on the observation (who captured it, what type) — effectively free, since it's tiny like the rest of the observation data. Sharing the actual file to another household member, or to a vet, is a manual action through the platform's native share sheet (`share_plus` in Flutter, which surfaces AirDrop on iOS and Nearby Share on Android, alongside Messages/email/whatever else is installed) — no custom transfer protocol, no server relay, reusing infrastructure Apple and Google already maintain.

This is a deliberate trade-off, made with eyes open: attachments are **not** automatically available on every household member's device the way the rest of an observation is. A photo exists on the phone that took it until someone deliberately sends it elsewhere. Given this app is free, built as a gift on Tom's own time, and the value of an attachment being *available* clearly outweighs the inconvenience of it not being *automatically everywhere*, that's an acceptable trade — and it can be revisited later if it turns out to bother people more than expected.

What this buys, beyond dodging the Blaze requirement:

- **No encryption to design, and no household secret to distribute or lose.** The original recommendation required an AES key derived from a household-level secret, plus a whole side-quest in `security-privacy.md` about how that secret gets backed up and recovered. None of that exists now — the media never reaches any server Tom operates or any third party in the first place, which is a stronger version of "the provider can't read it" than encryption ever gave.
- **No Cloud Function for media cleanup.** Retention (§9's function list) previously needed a trigger to delete Storage objects when their observation was deleted. With local-only storage, that's just a local file delete on whatever device holds the file — no server-side state to reconcile.
- Receiving a shared attachment needs a small piece of UX that didn't exist before: matching a file that arrived via the share sheet back to the correct observation on the recipient's device (most likely: the recipient opens the app's share target, picks or confirms the observation it belongs to). Worth designing when this gets built, but it's a self-contained feature, not an open architectural question.

## 9. Cost at hobby scale

For two people (and low volume even if it ever grew to a handful of households):

- **Firestore + Auth:** comfortably within Firebase's free **Spark** plan.
- **No Cloud Functions, no Cloud Storage, no Blaze plan, no billing account on file.** Every Function `security-privacy.md §9` sketches (last-durable-admin re-check, join/removal notifications, recursive household delete) belongs to a post-v1 feature, and the `memberIds`-sync and vet-cleanup Functions were removed from the target entirely (§3). Media (§8) and notifications (§5) are post-v1. Nothing pushes the project off Spark.
- If a future feature does need Blaze, Firebase's pricing is per-use, so cost scales with usage rather than jumping to a flat monthly charge. The two candidates on the horizon are notifications (§5) and a cloud backup / PITR for the health record (`security-privacy.md §10`).

## 10. Deployment

- Flutter builds to Android (Firebase App Distribution, `household` group) and iOS (TestFlight). See `flutter-migration.md §10` — iOS is new platform surface: an Apple Developer account, signing, a macOS CI runner.
- Firestore Security Rules **and `firestore.indexes.json`** deploy via the Firebase CLI (the indexes file doesn't exist yet — added in the migration window, §3). No Functions to deploy.
- Web (Firebase Hosting) is post-v1.
- No staging backend — a single Firebase project, given the scale. Worth revisiting only if usage or the number of contributors grows.

## 11. Open items for other docs

- Household join/invite mechanism, admin transfer, account durability → `security-privacy.md §§3–4`; the order the rule changes land → `migration.md §4`
- **Anonymous-auth member stranding** → `security-privacy.md §4.5` (doesn't apply to the current household — both Google; retained as design for if anonymous sign-in is used)
- The backend/data-model migration onto this shape → `migration.md`; the client re-platform → `flutter-migration.md`
- A staging Firebase project or a wider CI pipeline, if the project grows past two-person scale
