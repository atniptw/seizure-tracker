# Security & Privacy — Pet Health Diary (v1)

**Status:** draft for discussion · **Last updated:** 2026-08-30
**Companion docs:** `product-spec.md` (features, entities, non-goals, "what the next release contains"), `architecture.md` (Flutter/Firebase stack, data model, Security Rules enforcement), `migration.md` (the order the §8 rule changes actually land in)

> **Scope note (2026-08-30).** The live deployment is two people, both on Google sign-in, on
> a closed test track — no other install is possible. Several mechanisms below are retained as
> *design* but explicitly **not built for the next release**: code rotation, all Cloud
> Functions (§9), `lastActiveAt`, the force-link-before-invite gate, household deletion, and
> the durability half of the last-admin invariant. Each is marked inline. The load-bearing
> parts for the next release are the admin/member role split (§4.1) and the §8 rule changes
> that enforce it.

## 1. What this document decides

`product-spec.md` and `architecture.md` both defer a cluster of questions to this doc:

- How someone joins a household, and who controls that.
- What a plain member can do vs. an admin (member = log-and-view; admin = everything else).
- How admin status is held, granted, and transferred.
- Account durability — what happens to a membership when the identity behind it goes away,
  especially the "continue without an account" case.
- The threat model the Security Rules are actually defending against.
- How the household's data is handled at rest, in transit, and on-device.
- Deletion and retention: what a member/household/account teardown does to the data.

`architecture.md` §6 already covers *how the Security Rules enforce* access ("uid in the
household doc's `members` array"). This document defines the *process* those rules enforce,
and calls out (§8) every rule change the decisions here imply so the rules file and
`architecture.md` can be updated to match. `migration.md §4` is the authority on the order
those changes land in.

This is a hobby-scale app built as a gift, with a handful of trusted households. The
decisions below are deliberately proportionate to that — where a heavier mechanism (an
approval queue, per-invite tokens, client-side encryption of the structured data) would
buy real security only at real usage we don't have, this doc records the lighter choice
and the residual risk it accepts, so a future revisit has the reasoning in hand.

## 2. Threat model

### 2.1 Assets worth protecting

- **A household's health record** — pet profiles, the observation timeline (seizures and
  notes), medications, vet contacts. Not catastrophic if exposed, but it's a family's
  private information about a sick animal and they expect it to stay within the household.
- **Member identities** — display names and sign-in method per household. Low sensitivity
  on their own; the point is that they're only visible to co-members.
- **Attachments (photos/video)** — *post-v1* (`architecture.md` §8); §5.4 records the stance
  for when they're built. The next release stores no media at all.
- **Continued access** — a household not being locked out of its own data (availability, not
  just confidentiality). **Note the gap:** there is no cloud backup or point-in-time recovery
  in the next release, so a member's accidental hard-delete of an observation (§7) is
  recoverable only from a prior export. See §2.4 — data-loss-by-member-error is currently
  out of scope, which is a known weak point against this asset. The migration adds a one-off
  local dump (`migration.md §4`), not an ongoing backup.
- **The migration tooling itself** — a local JSON dump of the whole health record and a prod
  Admin SDK service-account key on Tom's laptop (`migration.md §5`). Both are unrestricted
  copies of / access to the record above. Kept offline, gitignored; the dump is deleted once
  the `migration.md §7` cleanup verifies. See §2.3.

### 2.2 Trust boundaries

- **Inside a household: read + log is fully trusted; managing is admin-only.** Every member
  — admin or not, Google or anonymous — can *read* everything in the household (pets, vets,
  medications, the whole observation history, the dashboard) and can *log* new observations.
  Beyond that there is a deliberate split (§4.1): only admins can change pet profiles,
  medications, vets, household settings, the member roster, or export a report, and a
  non-admin can edit or delete only the entries they logged themselves. This is a
  management-surface hierarchy, **not** a visibility one — no data is hidden from a member.
  We are still not meaningfully defending a member against a co-member with bad intent (a
  non-admin can still corrupt the timeline by mis-logging, and the restrictions are only as
  strong as the rules in §8); the split is about preventing *accidental* damage to shared
  configuration by someone who was only ever meant to log entries, and about matching
  product-spec §2's persona expectations.
- **Between households: zero trust.** A uid in household A must never be able to read or
  write household B, or enumerate households, pets, or codes. This is the main job of
  `firestore.rules`.
- **The client is trusted for schema, not for authorization.** There is one codebase, no
  third-party writers, so field-level validity ("a seizure observation has `seizureType`")
  is left to the Dart model layer. Anything that gates *access* is in the rules, never only
  in the app.

### 2.3 Actors and what stops them

| Actor | Goal | What stops it |
|---|---|---|
| A stranger with no code | Read a household's data | Security Rules: reads require `uid in members`; the household id is unguessable and the only route to it is a `codeIndex` get with the **exact** code (never listable). So the code *is* the membership capability — the rules never verify code knowledge directly; they rely on the id being secret and on the join clause only letting a caller add their own uid |
| A stranger who guessed/brute-forced a code | Join a household | 32⁶ ≈ 1.07B codes, get-by-id only (no query), so this is online guessing against Firebase. **There is no effective per-user rate limit** — anyone with the APK can `signInAnonymously()` and script `codeIndex` gets against the project quota. The real defenses are the keyspace size and the code being useless without an admin not noticing. The named upgrade is **Firebase App Check** (device attestation, no Blaze requirement) — §10. Also: `util/HouseholdCode` uses `Random.Default`, a non-CSPRNG — the 1.07B figure assumes a cryptographic RNG; switch to `SecureRandom` / `Random.secure()` (see §10 and `flutter-migration.md`). Residual risk accepted at this scale — see §4.2 |
| A **removed** former member | Keep reading, or re-join | Removal drops their uid from `members` — reads/writes stop on the next rule evaluation. **Code rotation is not built for the next release** (§4.2), so a removed member who kept the code can re-join at will. Acceptable now because neither current member is leaving; the mitigation until rotation ships is "don't share the code with anyone you'd later remove." |
| A **malicious current member** | Vandalize data, exfiltrate | Largely out of scope — a member is trusted to read everything and log entries (§2.2). Admin-only writes (§4.1) limit an ordinary member to damaging the timeline via bad log entries and only their own edits/deletes; a *malicious admin* is fully out of scope. Mitigations are social (don't add people you don't trust; grant admin sparingly) plus history being recoverable only via prior exports |
| Someone holding a member's **unlocked phone** | Read the local cache, log entries | Optional device-level app lock (§6). A determined attacker with the unlocked device or filesystem access is out of scope |
| A **network attacker** | Intercept traffic | TLS on every Firebase connection; no app-level plaintext |
| **Google / Firebase** as the platform | Read stored data | In scope as an acknowledged limitation: the structured data is encrypted at rest with Google-managed keys, not end-to-end. The next release stores **no media at all** (attachments are post-v1), so the highest-sensitivity asset class simply isn't in the cloud; §5.4 records the local-only stance for when it is |
| Someone who obtains the **migration dump or service-account key** | Read/rewrite the whole record | Key and dump are gitignored and kept off any synced location; the dump is deleted after `migration.md §7` verifies. In scope as an acknowledged, time-boxed exposure during the migration only |
| A **stranded anonymous identity** | (Not an attacker) locks a seat or an admin slot | Does not apply to the current household (both Google). §3.2 guarantees any multi-person household has a durable admin; §4.5 covers detecting and removing the stale seat if anonymous sign-in is ever used |

### 2.4 Explicitly out of scope for v1

- Defending a member against other members of the same household.
- **Data-loss by member error** — an accidental hard-delete of an observation is recoverable
  only from a prior export; there is no cloud backup or PITR in the next release. Noted as a
  known weak point against the "continued access" asset (§2.1); the upgrade is Firestore
  scheduled backups / PITR, both of which have plan implications (`architecture.md §9`).
- End-to-end encryption of the structured record (pet/observation/vet data).
- A compromised Google/Apple account upstream of our auth.
- Nation-state / forensic recovery from a seized unlocked device.
- Any vet-facing surface — there is none, ever (product-spec §5).

## 3. Identity & accounts

### 3.1 Sign-in methods

Landing on a Firebase Auth uid that the rules check:

| Method | uid durability | Intended for | Status |
|---|---|---|---|
| Google (Credential Manager / FlutterFire) | Survives reinstall, device wipe, new phone | Anyone willing to attach an account — the default | **Next release** |
| Anonymous ("continue without an account") | Tied to the app install — **lost** on reinstall, "clear data", or a new device | A joiner who'd rather not attach anything (e.g. a petsitter); a solo user trying the app before they invite anyone (§3.2) | **Next release** |
| Apple | Same as Google | iOS users who prefer it | **Deferred** — with App Store distribution. TestFlight internal testing (the closed iOS track, `flutter-migration.md §10`) doesn't require it; Apple's "offer Sign in with Apple if you offer another third-party sign-in" rule bites only at App Store submission. Add it then. |

For the next release the durable-identity set (§3.2, §4.4) is **Google only**.

No passwords, ever. No email/password provider — it adds a credential to secure and a reset
flow to build for no benefit here.

### 3.2 The durability rule: no shared household without a durable admin

An anonymous uid that gets lost is unrecoverable — the next sign-in mints a brand-new uid,
and there is no way to prove "I was that person." If that lost identity was the household's
only admin, **the household is permanently locked** out of roster management: no one can add
or remove members or promote a new admin.

> **Not built for the next release.** Both current members are on Google, so the
> force-link-before-invite hard gate below — and `§8 item 8`'s non-anonymous-`codeIndex`
> assertion — protect a scenario that cannot arise. They're the item on this list most likely
> to *break* a working flow (a token-refresh lag after `linkWithCredential` makes the rule
> deny the very write it's meant to allow — see §8 item 8). Retained as design; implement
> alongside anonymous sign-in actually being used. The plain "you can't demote/remove the
> last admin" check (§4.4) is kept.

That failure only *matters* once a second person's access depends on that admin, so the rule
is scoped to exactly that moment rather than blocking anonymous creation outright:

- **Creating and using a household solo is allowed anonymously.** Someone can sign in with
  "continue without an account", create a household, add pets, and log entries with no
  Google/Apple credential. If they lose the device they lose their own un-shared data — the
  same deal as any no-account single-device app, and nobody else is stranded.
- **Before the household can gain a second member, the creating admin must link a durable
  (Google/Apple) credential.** The join code is not generated or shown until then; the
  "invite someone" / "share code" action triggers the link prompt first (`linkWithCredential`
  keeps the same uid, so nothing migrates — §3.3). This guarantees that the instant a second
  person *can* join, an admin already holds a recoverable identity.
- From there the standing invariant is just **"the household always retains at least one
  admin with a durable credential"**, re-checked on every admin removal or demotion (§4.4).

Create-household screen copy should set the expectation up front: "You can start without an
account — you'll just need to add Google or Apple before you can invite anyone else."

### 3.3 Account linking (anonymous → durable)

Firebase Auth's `linkWithCredential` upgrades an anonymous user to Google/Apple **keeping
the same uid** — membership, authored observations, and `loggedByName` all carry over with
zero migration. Two kinds of prompt:

- **A hard gate, once:** an anonymous creator hits a required link prompt the first time
  they try to invite someone / reveal the join code (§3.2). This is the *only* place linking
  is mandatory.
- **A soft, dismissible nudge everywhere else, never a wall:** after an anonymous user
  *joins* a household ("Add a Google or Apple account so you don't lose access if you
  reinstall"); to a solo anonymous creator who hasn't invited anyone yet (framed around not
  losing their own data, not stranding anyone); and once more if an anonymous member is
  later promoted to admin.
- The link action is also available anytime from settings.

On a successful link, the app updates the member's own `members/{uid}` doc so `authMethod`
reflects the new durable provider — the last-durable-admin invariant (§4.4) and the rules in
§8 read durability off that field, so it must not go stale.

### 3.4 Session & re-auth

Firebase keeps the user signed in across app launches (refresh token in the platform
keystore). We don't force periodic re-auth — it would hurt the "never has to think" flow
for no proportionate gain. Device-level protection is handled separately and locally (§6).

## 4. Household membership lifecycle

### 4.1 Roles

Two roles, stored as `role: "admin" | "member"` on `households/{id}/members/{uid}`. The
`members/{uid}` subcollection is the source of truth for **metadata + role**; the
client-written `members` array on the household doc is the source of truth for **access** (it
keeps its shipped name — the `memberIds` rename was dropped, see `migration.md §1`, and
`architecture.md §3/§6` say `members`). There is no Function keeping anything in sync. A
household can have **more than one admin** (product-spec §2).

A **member is log-and-view only**; everything that manages the household is admin-only
(product-spec §2). A partner or family member who co-manages pets should be made an admin;
"member" is for the petsitter / occasional logger and anyone who should only ever add
entries.

| Capability | Admin | Member |
|---|---|---|
| **Read** everything — pets, vets, medications, full history, dashboard | ✅ | ✅ |
| **Log** a new observation (seizure / health note) | ✅ | ✅ |
| Edit / delete an observation **they logged themselves** | ✅ | ✅ |
| Edit / delete an observation **someone else logged** | ✅ | ❌ |
| Add / edit / archive pets; edit a pet profile | ✅ | ❌ |
| Add / edit / discontinue medications | ✅ | ❌ |
| Add / edit vets and pet–vet links | ✅ | ❌ |
| Rename the household or a pet | ✅ | ❌ |
| Export a report (PDF / CSV) | ✅ | ❌ |
| View the member list | ✅ | ✅ |
| See / copy / show the join code | ✅ | ❌ |
| Rotate the join code | ✅ *(post-v1 — not built; §4.2)* | ❌ |
| Add / remove a member; promote / demote an admin | ✅ | ❌ |
| Delete the household | *post-v1 — no mechanism; `delete: if false` (§7, §8 item 9)* | — |

Notes on specific rows:

- **A member can't see the join code.** Combined with the roster being admin-only, this
  means only an admin can bring anyone new in. Enforcing this properly needs the `code`
  field moved off the household doc (which every member reads) into an admin-only location —
  see §4.2 and §8; a UI-only hide is a weaker fallback.
- **A member can't export.** An export leaves the household as a file; product-spec §4 frames
  sharing with the vet as an owner action. A member who needs the vet report asks an admin.
- **A member's display name** is set when they join and is not editable by them afterward in
  v1 (it's not in the list above). Note this is *belt* only, not *braces*: `loggedByName` is
  snapshotted onto each observation at write time (`architecture.md §3`), so a later name
  change wouldn't rewrite history anyway — which removes the main argument against allowing
  the edit. See §10.
- **Not a household write, so any member can do it for themselves:** picking the active /
  default pet, the pet switcher, and setting a medication reminder (which hands off to the
  phone's alarm app — product-spec §4). These are per-device preferences (the local
  key–value store — `UserPrefs`/DataStore today, `shared_preferences` in Flutter), not shared
  state, so the admin split doesn't apply.
- The force-link-before-invite gate (§3.2) is **post-v1** — both current members are Google,
  so it protects nothing yet.

### 4.2 Joining

**Mechanism: a rotating, household-level join code.** Kept from the current app
(`util/HouseholdCode.kt`): 6 chars from a 32-char ambiguity-free alphabet (A–Z and 2–9,
minus `I` and `O`), resolved via the
top-level `codeIndex/{code}` document to a household id, because a non-member can't query
`/households` under the rules.

Changes from today:

- **The code lives where only admins can read it.** Today it's a `code` field on the
  household doc, which every member reads. To make "a member can't see the join code" (§4.1)
  real rather than a UI nicety, the code moves to an **admin-only** `households/{id}/private/config`
  doc (`allow read, write: if admin`). The household doc keeps no plaintext code.
  `codeIndex/{code}` is unchanged (it stays get-by-id readable for the join to work).
  **This is true only after `migration.md §7` cleanup removes the legacy `code` field** — the
  migration keeps both in place through the window and the whole Flutter build (`migration.md
  §3, §6`), so until then any member reads the code straight off the household doc, and its
  offline cache keeps a copy on any device that ever read it. Treat a code that was ever
  visible to a member as known to them.
- **`codeIndex` get is the join capability, not a lookup.** The rules never check that a
  joiner knows a code — they rely on the household id being unguessable, and the only route
  to the id is a `codeIndex` get with the exact code. So relocating the code doesn't change
  the security model; it changes who can *conveniently* read it.
- **The code doesn't exist until the household goes multi-person** — *design; the app-side
  behavior is post-v1* (§3.2). Household creation still mints a code today. `§8 item 7`'s
  rotation-create rule and `item 8`'s durable-creator assertion assume creation *stops*
  minting the code; reconcile that before shipping those rules (`migration.md §4 area 2`).
- **Rotation is post-v1 — not built.** When built: an admin regenerates the code and the app
  writes the new `codeIndex/{newCode}` doc, updates `private/config`, and deletes the old
  `codeIndex` doc **in a single atomic `WriteBatch`** — three non-atomic writes risk leaving
  two live codes (rotation silently fails) or zero (nobody can join), and because `list` is
  denied there's no way to discover or clean up an orphan. Until rotation ships, "an admin
  controls who's in" reduces to "don't give the code to anyone you'd later want to exclude"
  (`firestore.rules` today: `codeIndex` is `allow update, delete: if false`).
- **QR code (new, design brief).** The QR encodes the same code (or a deep link containing
  it). It has exactly the same sensitivity as the code — a screenshot of the QR is a leaked
  code — and no separate security properties.
- **Preview before confirming (new, design brief).** To show "You're about to join *The
  Bear & Milo house*" before the joiner commits, the `codeIndex/{code}` doc carries the
  household's **display name** in addition to the id — nothing else, no pet names, no health
  data. This is a conscious, minimal relaxation of today's index doc, which holds the
  household id and nothing else (`firestore.rules`): a household nickname is low-sensitivity,
  and anyone holding the code was given it on purpose. `architecture.md` §3 documents the
  id + display-name shape as the target. Pet names and photos are *not* in the index; a fuller preview (pets)
  only renders after the join write lands, with a "this isn't my household — leave" escape
  hatch.

**Joining is instant on a valid code — no admin approval queue.** Knowing the current code
*is* the authorization. An approval step would add friction to onboarding (the joiner waits;
an admin has to be present and notice) for a household of people who are, by construction,
coordinating in person or by text anyway. Instead:

- Once notifications exist (post-v1, `architecture.md` §5), joining notifies the household's
  admins ("Sam joined the household") so a join is never silent. Until then the join shows up
  in the member list, which every member can see.
- The residual risk: a code that leaked (forwarded screenshot, shoulder-surf) lets an
  outsider join in the window before an admin notices and rotates. Accepted for v1. The
  upgrade path if it ever bites — admin-generated single-use invite codes with a short TTL —
  is noted in §10.

### 4.3 Removing a member

- **Removing someone else: admins only** (tightened from today, where any member can remove
  any member). **Leaving yourself: anyone, any role** — a member can always delete their own
  `members/{uid}` doc and pull their own uid from the `members` array, subject only to the
  last-admin invariant (§4.4) if they're an admin.
- Removal = remove the target's uid from the household `members` array **and** delete their
  `members/{uid}` profile doc. **Write order matters:**
  - *An admin removing someone else* deletes the array entry first, then the profile doc
    (today's `HouseholdRepository.removeMember` order, for the rule reason in that code's
    comment).
  - *An admin self-leaving* must write the `members` array first and delete their own
    `members/{uid}` doc **second** — the admin check `get()`s the caller's own member doc, so
    deleting it first would make the array write fail. The self-leave carve-out in §8 item 3
    is what lets the array write through without the admin gate.
- **Their past observations stay.** `loggedByName` was snapshotted at write time
  (`architecture.md §3`) so the history still reads correctly. Deleting a member does not
  delete what they logged.
- **After a removal, an admin should rotate the code** so a removed member who kept it can't
  re-join — but rotation is post-v1 (§4.2), so for now the only lever is not having shared
  the code with them. Once notifications exist (post-v1) the removal notice should carry the
  reminder.

### 4.4 Admin grant, transfer, and the last-admin invariant

- The **creator** is the first admin. They may still be anonymous while solo; by the time
  the household has a second member, §3.2's gate has forced them to link — so a shared
  household always starts life with at least one durable admin.
- Any admin can **promote** a member to admin or **demote** another admin, by writing the
  `role` field on that member's `members/{uid}` doc. This requires a rules change — today a
  member doc is writable only by its own uid (§8).
- **Invariant, enforced on every demote/remove — and on provider unlink and account
  deletion (§7), which can strip an admin's durability without touching roles:** the
  household must retain **at least one admin** (next release), and — as design, once
  anonymous sign-in is used — at least one admin with a durable credential. A violating
  action is blocked in the app with a clear message ("Make someone else an admin first" /
  "The last admin needs a Google account").
- **For the next release this is client-only.** Firestore Rules can't count admins across a
  subcollection cheaply, and there is no Cloud Function (§9) — so a hand-crafted request
  could momentarily break the invariant with nothing to repair it. That means the
  self-reported `authMethod` field is the *only* durability signal in the system, despite
  §3.3 distrusting it. Accepted because both current admins are Google and neither is
  leaving; the trust model (§2.2) covers a member editing their own requests. The
  Function-backed re-check (checking `getUser(uid).providerData` via the Admin SDK) stays in
  §9 as design for if the project ever takes on Blaze.
- **Transfer** is just promote-then-demote (or promote-then-leave). There's no dedicated
  "transfer ownership" action because there's no single "owner" — only the admin set.

### 4.5 Stranded anonymous members

**Current household:** both members are signed in with Google, so this scenario doesn't apply
to the live deployment today. The mechanics below stay in the design because anonymous sign-in
remains a supported path (a petsitter, a try-before-invite solo user).

The failure `architecture.md` §11 flags: an anonymous member reinstalls or resets their
device, loses the uid, and their `members/{uid}` entry now belongs to an identity **no
living device can authenticate as**. It's a dangling seat, not a security hole (that ghost
uid can't actually do anything), but it's untidy and confusing.

- **Prevention first:** §3.2 guarantees any household with more than one member has at least
  one durable admin, so a stranded anonymous uid can never be the *only* way back into roster
  management; the §3.3 nudge pushes the other anonymous members toward linking too.
- **Detection (soft) — post-v1.** Design: each `members/{uid}` doc carries a `lastActiveAt`,
  updated (throttled to ~once/day) when the app opens, so the admin member list can surface
  "hasn't opened the app in 90+ days." Not built for the next release (`migration.md §3` does
  not add the field) — it exists only to soft-detect stranding among *anonymous* users, which
  the current two-Google household can't have, and it would force §8 item 1 into field-level
  update rules it can otherwise skip. Never auto-removes regardless.
- **Cleanup:** an admin removes the stale member the same way as any other removal (§4.3).
  Because a stranded anonymous user can't protest and can't be "the last durable admin,"
  this is always safe.
- **If a linked (Google/Apple) member loses access** — they don't. That's the whole point
  of §3.2 / §3.3: signing in again anywhere returns the same uid.

## 5. Data handling & storage

### 5.1 In transit

All Firebase traffic (Firestore, Auth) is TLS. No app-level plaintext channels. (FCM and
Cloud Functions are post-v1 — §9 — and would be TLS too.) The device-to-device attachment
sharing (§5.4, post-v1) would ride Apple/Google transports whose transport security is
theirs, not ours.

### 5.2 At rest, in the cloud

Firestore encrypts documents at rest with Google-managed keys. This is **not** end-to-end —
Google can technically read the structured record, and so could anyone who compromised the
Firebase project's credentials. For a family's pet-health notes this is an accepted
limitation (§2.3). The next release stores no media at all; §5.4 records the local-only
stance for when photos/video are built, which is the point at which "the provider genuinely
cannot see it" would start to matter.

### 5.3 At rest, on the device

FlutterFire's offline persistence is a local SQLite database (`architecture.md` §2). It is
**not encrypted** beyond the OS's own full-disk encryption (standard on modern iOS/Android).
Anyone with the unlocked device, or filesystem-level access to an unlocked/rooted device,
can read the cached household data. The mitigation is the optional app lock in §6. Local
DataStore/prefs equivalents (household id, display name, app-lock setting) are per-device
and never synced.

### 5.4 Media — local-only (post-v1)

Photo/video attachments are **backlogged — not in the next release** (`architecture.md` §8).
This section records the privacy stance for when they're built; the shipped app has no
attachment feature.

The stance: photos and video never touch any cloud we operate. Firestore stores only a tiny
reference (who captured it, type). Sharing a file to another member or a vet is a manual
action through the OS share sheet. Full rationale and trade-offs in `architecture.md` §8. The
privacy win, restated in this doc's terms: for media there is **no provider-can-read
question at all**, because the file never leaves the devices that hold it. The cost — an
attachment isn't automatically on every member's device — is accepted in §8 there.

### 5.5 Third parties and telemetry

- The only data processor is **Google/Firebase** (backend, auth). (Apple joins the list if
  Sign in with Apple ships — §3.1.)
- **No analytics, crash-reporting, advertising, or attribution SDK in v1** — this is a
  deliberate choice, not an oversight (Crashlytics, for instance, needs no Blaze plan). If
  crash reporting is ever added it must carry no pet data, no observation content, no member
  names — stack traces only.
- **The distribution platforms** — Firebase App Distribution (Android) and TestFlight (iOS,
  `flutter-migration.md §10`) — collect their own crash/ANR and tester-usage telemetry by
  default. That carries no app data: no pet data, no observation content, no member names.
- No data is sold, shared, or used for any purpose other than running the household's diary.
  With the two bullets above noted, this is short enough to *be* the privacy policy,
  near-verbatim.

## 6. Device-level protection (app lock)

An **optional** biometric / device-passcode lock on app open (design brief, "biometric
re-auth" — Flutter `local_auth`):

- Purely local. It gates opening the app; it does not touch Firebase auth, sync, or the
  session.
- Protects the on-device cache (§5.3) and a signed-in session if the phone is lost or shared
  while unlocked.
- **Opt-in**, with a one-time prompt after setup ("Lock the app with Face ID?"). Not on by
  default — a false lockout (biometric fails, no fallback understood) on the *seizure-logging
  flow* is exactly the kind of friction product-spec §5 forbids, so this must always fall
  back to the device passcode and never block logging behind a spinner.
- Honest about its limits: it's a speed bump against a casual snoop with your unlocked
  phone, not protection against a determined attacker or forensic extraction (§2.4).

## 7. Deletion & retention

| Action | What happens | Notes |
|---|---|---|
| Delete an **observation** | Hard delete of the Firestore doc | Only the member who logged it, or an admin (§4.1). Last-write-wins, no soft-delete/tombstone in v1 (`architecture.md` §4). **Recoverable only from a prior export** — no backup/PITR (§2.4). Edits use `update()` not `set()` so a stale offline edit can't resurrect a deleted doc (`migration.md §4 area 3`) |
| Discontinue a **medication** | `active: false` + `endDate` set — **not** a delete | Deliberate: preserves history a vet may ask about (`architecture.md` §3) |
| Remove a **member** | uid pulled from the `members` array + `members/{uid}` deleted (order per §4.3); their observations remain | §4.3. Rotation reminder is post-v1 (§4.2) |
| Delete a **household** | *Post-v1 — no mechanism.* Client rule stays `allow delete: if false` (§8 item 9). The recursive-delete Cloud Function is design only (§9); household teardown is not in the next release |
| Delete my **account** | Google: unlink in settings, then Firebase Auth user deletion. Anonymous: stop using it (the uid is already ephemeral) | Authored observations stay (household data). A member who wants their *name* scrubbed from history is a manual maintainer action in v1 — §10. **Unlinking the last durable admin's provider triggers the last-admin invariant check (§4.4)** — client-only for the next release, so a request-level bypass isn't repaired. A solo anonymous creator who just stops leaves an orphaned household doc; harmless at this scale, no cleanup in v1 |

**Retention:** no automatic expiry. The whole value of the record is its longevity — a
seizure-frequency trend over two years is the point. Data lives until a member deletes it or
the household is torn down.

## 8. Security Rules changes this document implies

For `firestore.rules` and `architecture.md` §6 to be updated to match §§3–7. `migration.md
§4` is the authority on **which window/area each item lands in**; this section is the *what*.

### Definitions (add once, near the top of the rules file)

```
function isMember(hid)  { return request.auth != null
                          && request.auth.uid in get(/databases/$(db)/documents/households/$(hid)).data.members; }
function memberRole(hid) { return get(/databases/$(db)/documents/households/$(hid)/members/$(request.auth.uid)).data.get('role', 'member'); }
function isAdmin(hid)    { return isMember(hid) && memberRole(hid) == 'admin'; }
```

Two things this gets right that a naive version doesn't:
- **`isAdmin` is the conjunction of membership and role — never the role check alone.** A
  bare `get(members/$(uid)).data.role == 'admin'` passes for *anyone* who can write a
  `members/{uid}` doc under that household, and item 1's create rule (below) still lets a
  non-member do that. Without the `isMember` conjunction, a stranger who knows a household id
  self-writes `role: "admin"` and clears every admin gate.
- **`.get('role', 'member')`, not `.data.role`.** A plain `.data.role` on a member doc with
  no `role` field is a hard deny. A member with no `role` is the *normal steady state* for
  every future joiner (item 1 makes `role` admin-only, so a joiner can't set their own), so
  the default-to-`member` form is mandatory, not defensive nicety.

### Items

1. **`members/{memberUid}` — create and self-update.**
   - `create` — `request.auth.uid == memberUid` **and** `!('role' in request.resource.data)
     || request.resource.data.role == 'member'`. The self-only create is kept (the join-race
     rationale in the current rules comment), but it is now security-relevant — a `members`
     doc carries `role` — so it must not be a path to writing `role: "admin"`. A cleaner
     long-term shape is a batched membership + profile write gated on `isMember`; the
     absent-or-`member` guard is the minimal fix.
   - `update` of the caller's **own** doc — `role` and `joinedAt` must be **unchanged**
     (`request.resource.data.role == resource.data.role`). `displayName` self-edit: see §10
     (currently disallowed; if allowed later, it's a `displayName`-only diff). `authMethod`
     may be refreshed on account link (§3.3). `lastActiveAt` is post-v1 (§4.5) — no rule for
     it in the next release.
   - `role` written by someone else — only `isAdmin(hid)`, never the target. The
     last-durable-admin invariant (§4.4) is *not* expressed here — client-only for the next
     release.
2. **`members/{memberUid}` — delete.** Only `isAdmin(hid)` or `request.auth.uid == memberUid`
   (self-leave). Today any member may delete any member doc.
3. **`households/{id}` — writes admin-only, with two diff-constrained carve-outs.** Today the
   update rule lets any member write the whole doc. Needed: `update: if isAdmin(id)`, **or**
   one of:
   - *join* — `request.resource.data.diff(resource.data).affectedKeys().hasOnly(['members'])`
     **and** the `members` array grew by exactly one element **and** that element is
     `request.auth.uid`. (Today's join clause constrains only the array delta, not
     `affectedKeys` — so a joiner can rename the household in the same write. The
     `affectedKeys` guard closes that.)
   - *self-leave* — same `hasOnly(['members'])`, array shrank by exactly one, removed element
     is `request.auth.uid`.
   Delete stays `if false` (item 9). **`firestore-tests` case:** a joiner cannot change
   `name` in the join write.
4. **`pets` / `vets` / `petVetLinks` — read by any member, write by admins.** From today's
   `read, write: if isMember` to `read: if isMember(hid); write: if isAdmin(hid)`.
5. **`pets/{petId}/medications/{medId}` — its own nested `match`.** A `match /pets/{petId}`
   block does **not** cover the `medications` subcollection — it needs its own rule:
   `read: if isMember(hid); write: if isAdmin(hid)`. (`migration.md §4 area 4` has this
   right; call it out here so §8 isn't read as "item 4 covers it".)
6. **`observations` — read + create by any member; update/delete by author or admin, with
   immutable authorship.** `read: if isMember(hid)`; `create: if isMember(hid) &&
   request.resource.data.loggedByUid == request.auth.uid`; `update, delete: if (isAdmin(hid)
   || resource.data.loggedByUid == request.auth.uid) && request.resource.data.loggedByUid ==
   resource.data.loggedByUid` — the last clause stops an author rewriting authorship on their
   own doc. Replaces the `seizures` / `healthNotes` rules (which stay live alongside it
   through the migration + Flutter window — see item 11).
7. **`households/{id}/private/config` — new doc, `allow read, write: if isAdmin(id)`.** Holds
   the plaintext join code (moved off the household doc — §4.2). **Effective only after
   `migration.md §7` removes the legacy `code` field**; until then the code is also readable
   on the household doc by any member.
8. **`codeIndex/{code}` — shape assertion; rotation + durable-creator are post-v1.** `get`
   stays "any signed-in, by exact id" (required for the join); `list` stays `false`. `create`
   asserts the shape `{ householdId, householdName }` (§4.2 preview — name only, no pet
   data). **Post-v1** (with rotation): `create`/`update`/`delete` gated to `isAdmin` of the
   target household — which requires the creator's `members/{uid}` doc to exist *before* the
   `codeIndex` write, so household creation must either reorder its writes or stop minting the
   code (`migration.md §4 area 2`). The non-anonymous-creator assertion is also post-v1 (it
   protects the anonymous-stranding case, which the current household can't have); when built,
   check `firebase.identities` contains `google.com` (or `apple.com`), **not** `token.email
   != null` (unreliable for Apple), and require a forced `getIdToken(refresh: true)` after
   `linkWithCredential` or the token still reads `anonymous` and the rule denies the write it
   exists to allow.
9. **`households/{id}` delete stays `if false`** — household teardown is post-v1 (§7, §9), no
   client path and no Function.
10. **`households/{id}/exportLog/{id}` — `create: if isAdmin(hid)`, `read: if isMember(hid)`,
    no update/delete.** Export is an admin action (§4.1); `read` lets anyone see when the
    last export happened.
11. **Legacy `seizures` / `healthNotes` / household-`code` / embedded medications stay
    member-permissive** through the migration window *and* the whole Flutter build, per
    `migration.md §6`. So until `migration.md §7` cleanup, the §4.1 admin write-split is
    **bypassable via the legacy collections** — a non-admin who kept the old app (or crafts a
    request) can still write `seizures`/`healthNotes` directly. Accepted because both current
    members are admin; noted so it's not a surprise. The split is only fully enforced once the
    legacy rules are removed.

## 9. Cloud Functions this document implies — none built, none planned

**No Cloud Function is deployed or scheduled for the next release**, and none can be without
moving the project to the Blaze plan (`architecture.md §0/§9`, `migration.md §8`). Everything
below is design intent for if that ever changes; each is currently covered another way or
belongs to a deferred feature:

- **Last-durable-admin invariant re-check** (trigger on `members` writes) — the invariant is
  client-only for now (§4.4). This would check `getUser(uid).providerData` via the Admin SDK
  to catch a request-level bypass.
- **Join / removal notifications to admins** — belongs to the notification feature, itself
  post-v1 (`architecture.md §5`).
- **Recursive household delete** — belongs to household teardown, which has no UI and no rule
  (§7). Not in the next release.
- (Only if per-invite codes land — §10) invite-token issue/redeem/expire.

There is **no** `memberIds`-sync Function and **no** vet-deletion-cleanup Function in the
target any more — the first is gone because the client-written `members` array is the
documented source of truth (§4.1), the second because `petVetLinks` stays a flat collection
the client cleans up transactionally (`architecture.md §3`, `migration.md §8`).

## 10. Open items / deferred

**Named upgrades (not built for the next release):**

- **Firebase App Check** — device attestation on every Firestore/Auth call, the real defense
  against scripted `codeIndex` brute-forcing (§2.3). Near-free, no Blaze requirement, pairs
  naturally with `flutter-migration.md` Phase 0. The highest-value item on this list.
- **CSPRNG for `HouseholdCode`** — the shipped generator uses `Random.Default`; switch to
  `SecureRandom` (Kotlin) / `Random.secure()` (Dart). Small, do it in the Flutter port
  (`flutter-migration.md §12` must not "port verbatim" here).
- **Code rotation** — an admin regenerating the join code (§4.2). Single atomic `WriteBatch`;
  own `firestore-tests` cases. The prerequisite for a removed member not being able to
  re-join.
- **Cloud-backup / PITR** for the health record — closes the data-loss-by-member-error gap
  (§2.4). Has plan implications (`architecture.md §9`).
- **Firestore scheduled backups** as the lighter version of the above.

**Deferred design questions:**

- **Per-invite single-use codes** with a short TTL, if leaked-code joins ever become a real
  problem (§4.2). Needs invite Functions + a "pending invites" UI.
- **Self-serve "scrub my name from history"** for a departed member (§7). Manual maintainer
  action for now.
- **Can a member edit their own display name?** §4.1 says no. Since `loggedByName` is
  snapshotted (`architecture.md §3`), the "it rewrites history" objection doesn't hold — so
  this is close to free to allow. Decide during the member/settings screen design; if
  allowed, it's a `displayName`-only self-update (§8 item 1).
- **Should a member be able to export?** §4.1 says no. Revisit with real usage; read-only
  export changes no data, so loosening later is low-risk.
- **Exact UX of the force-link-before-invite gate** (§3.2) — for the Flutter onboarding
  design pass, *and* only once anonymous sign-in is actually in use (§3.2 note).
- **A written privacy policy** for anyone outside the household — §5.5 is most of it; needs
  to be an actual document if the app is ever distributed beyond family.
- **App-lock fallback behavior** (§6) — verify in `flutter-migration.md` Phase 3 that a
  biometric failure can never block or delay a save (`local_auth`).
