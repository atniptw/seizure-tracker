# Architecture — Pet Health Diary (v1)

**Status:** draft for discussion · **Last updated:** 2026-08-29
**Companion docs:** `product-spec.md` (features, entities, non-goals), `security-privacy.md` (threat model, data handling, admin/access/ownership mechanics)

## 0. This document is the target, not the shipped app

This describes where the app is headed, not what runs today. The shipped app is **Kotlin +
Jetpack Compose** (not Flutter) and already does the multi-pet / vets / health-notes
redesign, but on a different Firestore shape. Things below that are **not yet built**:

- **Flutter client.** Today it's native Android/Kotlin; the Flutter port is a separate plan.
- **`observations` collection.** Today seizures and health notes are two collections
  (`seizures`, `healthNotes`), not one polymorphic collection.
- **Admin/member roles.** Today there are no roles — every member can write everything, and
  any member can remove any member. `security-privacy.md` §8 lists the rule changes this needs.
- **`memberIds` cache + `members` subcollection as source of truth.** Today the household
  doc's `members` array is the sole source of truth for access (`firestore.rules`); the
  `members/{uid}` subcollection holds display-name/auth-method metadata only, and there is
  no Cloud Function keeping anything in sync.
- **Cloud Functions.** None are deployed. Every Function named in this doc and in
  `security-privacy.md` §9 is proposed, not live.
- **`households/{id}/private/config`.** Today the plaintext join code is a `code` field on
  the household doc; the admin-only config doc does not exist yet.
- **Medications as a subcollection with `active`/`endDate`.** Today they're an embedded
  array on the pet doc with no lifecycle fields; discontinuing one deletes it.
- **Household notifications (§5) and photo/video attachments (§8).** Both are explicitly
  **out of the next release and backlogged** — see those sections. §5 stays a design sketch;
  §8's local-only conclusion still stands as the approach for when attachments are built. The
  shipped app has no notification code, and its half-built `photoUri` capture was removed.

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
| Client | Flutter (iOS, Android, and web for the phone-in-hand-optional dashboard case) | Already decided; one codebase for the platforms in scope. |
| State management | Riverpod | Plays well with Firestore streams, testable, no boilerplate-heavy alternative needed at this scale. |
| Local persistence / offline cache | Firestore's built-in offline persistence (SQLite under the hood) | Firestore ships this for free — no separate local DB or hand-rolled sync engine to build or maintain. |
| Backend | Firebase (Firestore, Auth, Cloud Functions, Cloud Messaging) | Serverless, generous free tier, first-class Flutter support via FlutterFire. Picked over Supabase specifically to avoid running any backend Tom would need to maintain. |
| Auth | Firebase Auth (Google, Apple, anonymous) | Matches the spec's three sign-in options exactly, including "continue without an account" via anonymous auth. |
| Push notifications | Firebase Cloud Messaging | For "saving notifies the rest of the household" — **post-v1, backlogged (§5)**. |
| Media (photos/videos) | Local device storage only + OS-native share sheet (`share_plus`) | No Cloud Storage, no Blaze plan requirement, no encryption to design. **Post-v1, backlogged (§8).** |
| PDF/CSV export | Generated on-device (Dart packages), not server-side | Avoids a Cloud Function cold-start on the export path and keeps exports working offline once data is cached locally. |
| Hosting (web dashboard, if built) | Firebase Hosting | Free tier, same project as everything else. |

## 3. Data model

Firestore is document/collection-based, not relational, so the spec's entities map to collections with denormalization in a few places to keep the seizure-logging read/write path cheap.

```
households/{householdId}
  name, createdAt, memberIds: [uid, uid, ...]
  # memberIds is a derived cache — see denormalization note below
  # no plaintext join code here — it's in private/config so non-admins can't read it
households/{householdId}/private/config
  joinCode                                     # admin-only read/write (security-privacy.md §4.2)
households/{householdId}/members/{uid}
  displayName, role: "admin" | "member", joinedAt, authMethod
  # source of truth for household membership; only an admin may write another member's `role`
households/{householdId}/pets/{petId}
  name, species, breed, weight, birthDate, diagnosisDate, archived: bool
  # photoRef: post-v1 with the rest of attachments (§8)
  linkedVets: [{ vetId, vetName, role }, ...]
  # sole copy of the pet↔vet relationship — see denormalization note below
households/{householdId}/pets/{petId}/medications/{medId}
  name, dose, frequency, notes
  active: bool, startDate, endDate            # endDate null while active — see note below
households/{householdId}/vets/{vetId}
  name, phone, notes
households/{householdId}/observations/{observationId}
  type: "seizure" | "note" | ...              # open-ended; new types add cheaply, see below
  petId, loggedByUid, loggedByName
  occurredAt                                  # when the thing happened
  createdAt, updatedAt
  summary: string                             # short human-readable line for the feed,
                                               #   e.g. "2 min tonic-clonic"
  # attachment: {...} | null                  # post-v1 — see §8. Not written in the next
                                               #   release; added to the envelope when
                                               #   attachments are built.
  details: { ...type-specific fields... }
    # seizure: duration, seizureType, symptoms[], preSeizureSigns[], triggers,
    #   recoveryTime, recoveryBehavior, recoveryNotes, rescueMedGiven, rescueMedDetails, notes
    # note: description, notes

codeIndex/{code}
  householdId, householdName                   # id + display name only (security-privacy.md §4.2)
  # top-level, not under households/ — a joiner resolves a code to a household id before
  #   they're a member and can read the household doc. Carries the household's display name
  #   for the join preview and nothing else: no pet names, no vet info, no health data.
```

**Single polymorphic `observations` collection, envelope + details:** every logged thing — a seizure, a general note, and whatever gets added later (medication given, vet visit, weight check) — is something a household member observed and recorded about the pet. Rather than force each into its own collection, they all live in `observations`, discriminated by `type`. At this data volume there's no reason to give seizures special-case treatment; a single unified per-pet timeline is also simpler to query, notify on (§5), and export (§7) than merging multiple collections would be. `seizure` and `note` are the first two `type`s; more get added as new observation types come up, without restructuring anything.

The document is split into two parts on purpose:

- **Envelope** — fields common to every observation type, and specifically the fields ever queried, sorted, or filtered on: `petId`, `type`, `occurredAt` (plus `attachment` once that lands, §8). These stay top-level so the timeline/export queries don't care what type an observation is.
- **`details`** — a type-specific payload map, read but never filtered on. Firestore doesn't enforce a schema, so this costs nothing structurally; it just keeps the type-specific fields out of the shared query surface.

This means adding a new observation type later (medication given, vet visit note, weight check) is just a new `type` value and a new `details` shape defined in the Flutter data layer (a sealed/freezed union per type works well so the client isn't passing raw maps around) — no new collection, no new Firestore indexes, and no security-rule changes beyond what §6 already covers. Two things this deliberately leaves undone: field-level validation ("seizure observations must have `seizureType`") is left to the Dart model layer rather than Security Rules, since there's a single trusted client codebase and no third-party writers; and this pattern is meant for point-in-time logged events, not ongoing state — something like a recurring medication *schedule* (as opposed to a log of doses given) would need its own shape rather than being forced into `observations`.

**Other denormalization and modeling choices, and why:**

- **`memberIds` is a Cloud-Function-maintained cache, not a second source of truth.** The `members/{uid}` subcollection is the source of truth for household membership (role, display name, join date). The flat `memberIds` array on the household doc exists purely so the client can answer "which households am I in" at sign-in with a single `array-contains` query, without loading every household's `members` subcollection. It's kept in sync by a Cloud Function trigger on `members` writes — the same pattern used for `linkedVets` below — so there's exactly one writer of the array and no risk of a client write updating one copy and not the other.
- **Pet↔vet links live only on the pet doc, with no reverse subcollection.** Originally modeled as a `vets/{vetId}/petLinks` subcollection with a denormalized summary mirrored onto the pet — but a household has only a handful of pets, and the client already holds the full pet list in memory for ordinary navigation (pet switcher, dashboard). So "which pets does this vet care for" is a free client-side filter over data that's already loaded, not a query that needs its own backing collection. Dropping the second collection means there's nothing to keep in sync at all — simpler than denormalizing, not just an acceptable shortcut. `vetName` is still copied onto the `linkedVets` entry (same reasoning as `loggedByName` on observations) so the pet screen doesn't need a join. One thing this doesn't handle automatically: deleting a vet won't cascade-remove it from pets' `linkedVets` arrays (Firestore never cascades), so vet deletion needs a small Cloud Function to strip stale references.
- **Medications carry `active`/`startDate`/`endDate` instead of being deleted when discontinued.** Without this, stopping a medication means deleting its doc — which quietly erases history that can matter later (a vet asking "was he ever on X," or spotting a correlation between a med change and seizure frequency). Discontinuing a medication is now an update (`active: false`, `endDate` set), not a delete, so the pet's full medication history stays intact. The current-medications screen just filters `active: true`; a history view can show everything. This matters more once people other than Tom are the ones managing the data — a "discontinued" state is much harder to do wrong than remembering not to delete something.
- `loggedByName` is copied onto the observation at write time instead of joined from the member doc, so history/export screens don't need an extra read per observation, and old observations still show the right name if someone changes their display name later.

## 4. Offline-first logging and sync

This is the architecture's central requirement, so it's worth being explicit about how it's satisfied rather than assumed:

- FlutterFire's Firestore SDK persists a local cache automatically (enabled by default on mobile). Reads come from cache instantly; writes are queued locally and applied optimistically to the UI.
- The seizure form writes directly to the local cache and returns immediately — the save button doesn't wait on a network round trip. The SDK flushes queued writes to Firestore itself once connectivity returns; there's no custom retry/queue logic to write or maintain.
- The one thing this pattern doesn't give for free: conflict handling if two people edit the *same* observation offline at the same time. The access split (`security-privacy.md` §4.1) narrows the editors of any given observation to its logger plus the household's admins, so a genuine concurrent edit is even rarer than before; last-write-wins (Firestore's default) is an acceptable trade-off for v1 rather than something to engineer around.
- The in-progress seizure timer state lives in local app state (Riverpod), not Firestore, until the observation is saved — no reason to round-trip a running timer through the network layer at all.

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

- A user can **read** anything under `households/{householdId}/**` only if their uid is in that household's `memberIds`.
- **Writes are role-split** (see `security-privacy.md` §4.1 for the product rationale, §8 for the per-collection rules): any member may create an `observations` doc and edit/delete one where `loggedByUid` is their own uid; everything else — `pets`, `medications`, `vets`, `petVetLinks`, household settings, the `members` subcollection and roles, the join code, `exportLog` — is writable only by a member with `role: "admin"` on their `members/{uid}` doc.
- The plaintext join code lives in an **admin-only** `households/{id}/private/config` doc, not on the household doc, so a non-admin member can't read it.
- Full mechanics of *how* someone gets added to a household, admin transfer, and account durability are specified in `security-privacy.md` (§§3–4). This section only covers how the rules enforce that.

Security Rules are the entire access-control layer — no server-side authorization code to write or audit beyond the rules file itself, which fits the "no vet-facing backend surface, no backend to maintain" goal directly.

## 7. Export (PDF / CSV)

Generated **on-device**, not via a Cloud Function, for two reasons: it works offline once the relevant observations are in the local cache, and it avoids paying for (or waiting on) server compute for something Dart can do directly.

- CSV: straightforward serialization of the filtered observation set.
- PDF: built with the `pdf` and `printing` Dart packages — enough for a clean report with a header, per-observation sections, and an optional trend chart rendered to an image and embedded.
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

Rough numbers for a handful of households (say, under 20 users, low daily write volume):

- **Firestore, Auth, Hosting, FCM:** comfortably within Firebase's free Spark plan at this scale.
- **Cloud Functions:** none are deployed or planned for the next release. The functions this doc and `security-privacy.md` §9 sketch (membership-cache sync, notification fan-out, vet-deletion cleanup, last-durable-admin re-check, join/removal notifications, recursive household delete) are all low-volume triggers that would fit the free tier's compute allowance — but deploying *any* Function requires the Blaze plan (billing account), which is why every feature that needs one is deferred.
- **No Cloud Storage, no Cloud Functions, no Blaze plan.** Media is post-v1 (§8) and notifications are post-v1 (§5), so nothing pushes this project off the Spark plan — it stays fully free at this scale, with no billing account on file.
- If future features ever do need Blaze (some other metered service), Firebase's pricing is per-use rather than a flat server bill, so cost scales with actual usage rather than jumping to a fixed monthly charge.

## 10. Deployment

- Flutter builds to iOS/Android via standard app store pipelines (out of scope here); web build deploys to Firebase Hosting.
- Firestore Security Rules and Cloud Functions deploy via the Firebase CLI; both are small enough to live in source control alongside the client app with no separate infra repo needed.
- No staging backend planned for v1 — a single Firebase project, given the scale and single-maintainer context. Worth revisiting only if usage or the number of contributors grows.

## 11. Open items for other docs

- Household join/invite mechanism, admin transfer, account durability → resolved in `security-privacy.md` §§3–4
- **Anonymous-auth member stranding** → resolved in `security-privacy.md` §4.5 (sole admin must hold a durable credential; link-account nudge; `lastActiveAt` soft-detection; admin removal path)
- Any decision to introduce a staging Firebase project or CI pipeline beyond what's in §10, if the project grows past single-maintainer hobby scale
