# Security & Privacy — Pet Health Diary (v1)

**Status:** draft for discussion · **Last updated:** 2026-08-29
**Companion docs:** `product-spec.md` (features, entities, non-goals), `architecture.md` (Flutter/Firebase stack, data model, Security Rules enforcement)

## 1. What this document decides

`product-spec.md` and `architecture.md` both defer a cluster of questions to this doc:

- How someone joins a household, and who controls that.
- How admin status is held, granted, and transferred.
- Account durability — what happens to a membership when the identity behind it goes away,
  especially the "continue without an account" case.
- The threat model the Security Rules are actually defending against.
- How the household's data is handled at rest, in transit, and on-device.
- Deletion and retention: what a member/household/account teardown does to the data.

`architecture.md` §6 already covers *how the Security Rules enforce* access ("uid in
`memberIds`"). This document defines the *process* those rules enforce, and calls out
(§8) every rule change the decisions here imply so the rules file and `architecture.md`
can be updated to match.

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
- **Attachments (photos/video)** — handled entirely differently: local-only, never in any
  cloud we operate. See `architecture.md` §8; §5.4 here covers what that buys us.
- **Continued access** — a household not being locked out of its own data (the flip side of
  access control: availability, not just confidentiality).

### 2.2 Trust boundaries

- **Inside a household: full trust.** Every member — admin or not, Google or anonymous —
  can read and write every pet, vet, and observation in the household. There is no
  data-level hierarchy (product-spec §2). We are not defending a member against another
  member; a petsitter you brought in can see everything and could delete an entry. The
  admin/member split is only about *managing the roster*, not about data.
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
| A stranger with no code | Read a household's data | Security Rules: reads require `uid in memberIds`; `codeIndex` is get-by-exact-id only, never listable, and holds no health data |
| A stranger who guessed/brute-forced a code | Join a household | 31⁶ ≈ 887M codes, get-by-id only (no query), realistically requires online guessing against Firebase; rate-limited by Firebase and by the code being useless without also being unremoved. Residual risk accepted at this scale — see §4.2 |
| A **removed** former member | Keep reading, or re-join | Removal drops their uid from `memberIds` (reads/writes stop immediately) **and** an admin rotates the code (§4.2). Between removal and rotation, a removed member who kept the code could re-add themselves — accepted, see §4.3 |
| A **malicious current member** | Vandalize data, exfiltrate | Out of scope — full trust inside the household (§2.2). Mitigations are social (don't add people you don't trust) plus history being recoverable only via prior exports |
| Someone holding a member's **unlocked phone** | Read the local cache, log entries | Optional device-level app lock (§6). A determined attacker with the unlocked device or filesystem access is out of scope |
| A **network attacker** | Intercept traffic | TLS on every Firebase connection; no app-level plaintext |
| **Google / Firebase** as the platform | Read stored data | In scope as an acknowledged limitation: the structured data is encrypted at rest with Google-managed keys, not end-to-end. This is why media is deliberately kept out of the cloud entirely (§5.4) — for the one asset class where "the provider genuinely cannot see it" was worth the cost |
| A **stranded anonymous identity** | (Not an attacker) locks a seat or an admin slot | §3.2 guarantees any multi-person household already has a durable admin, so a stranded anon uid is never the only way back in; §4.5 covers detecting and removing the stale seat |

### 2.4 Explicitly out of scope for v1

- Defending a member against other members of the same household.
- End-to-end encryption of the structured record (pet/observation/vet data).
- A compromised Google/Apple account upstream of our auth.
- Nation-state / forensic recovery from a seized unlocked device.
- Any vet-facing surface — there is none, ever (product-spec §5).

## 3. Identity & accounts

### 3.1 Sign-in methods

Three, matching product-spec §4, all landing on a Firebase Auth uid that the rules check:

| Method | uid durability | Intended for |
|---|---|---|
| Google (Credential Manager / FlutterFire) | Survives reinstall, device wipe, new phone | Anyone willing to attach an account — the default |
| Apple | Same as Google | iOS users who prefer it; required for App Store if any third-party sign-in is offered |
| Anonymous ("continue without an account") | Tied to the app install — **lost** on reinstall, "clear data", or a new device | A joiner who'd rather not attach anything (e.g. a petsitter there for two weeks); also a solo user trying the app before they invite anyone (§3.2) |

No passwords, ever. No email/password provider — it adds a credential to secure and a reset
flow to build for no benefit here.

### 3.2 The durability rule: no shared household without a durable admin

An anonymous uid that gets lost is unrecoverable — the next sign-in mints a brand-new uid,
and there is no way to prove "I was that person." If that lost identity was the household's
only admin, **the household is permanently locked** out of roster management: no one can add
or remove members or promote a new admin.

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

Two roles, stored as `role: "admin" | "member"` on `households/{id}/members/{uid}`
(`architecture.md` §3 — the `members` subcollection is the source of truth; `memberIds` on
the household doc is the Function-maintained access-check cache, and today's app calls that
array `members`). A household can have **more than one admin** (product-spec §2).

| Capability | Admin | Member |
|---|---|---|
| Read/write all pets, vets, observations | ✅ | ✅ |
| View the member list | ✅ | ✅ |
| See / copy / show the join code | ✅ | ✅ |
| Rotate the join code | ✅ | ❌ |
| Remove a member | ✅ | ❌ |
| Promote a member to admin / demote an admin | ✅ | ❌ |
| Rename the household or a pet | ✅ | ✅ |
| Delete the household | ✅ (confirmation-gated; members notified first — §7) | ❌ |

Members seeing the join code is intentional — that's how "read it out to your partner"
works today and there's no threat model reason to hide it from someone who's already inside.

Revealing the join code for the first time and adding the first other member require the
acting admin to hold a durable credential (§3.2). That's a one-time gate at the
solo → shared transition, not a check on every invite afterward.

### 4.2 Joining

**Mechanism: a rotating, household-level join code.** Kept from the current app
(`util/HouseholdCode.kt`): 6 chars from a 31-char ambiguity-free alphabet, resolved via the
top-level `codeIndex/{code}` document to a household id, because a non-member can't query
`/households` under the rules.

Changes from today:

- **The code doesn't exist until the household goes multi-person.** A solo anonymous creator
  has no join code at all until they link a durable credential and choose to invite someone
  (§3.2). Every household that already has more than one member always has a live code.
- **The code is rotatable.** An admin can regenerate it; the app writes the new
  `codeIndex/{newCode}` doc, points the household's `code` field at it, and **deletes the
  old `codeIndex` doc** so the previous code stops resolving. This is the primary lever for
  "an admin controls who's in": control who you give the current code to, and rotate it when
  someone should no longer be able to join (after removing a petsitter, or if you think it
  leaked).
- **QR code (new, design brief).** The QR encodes the same code (or a deep link containing
  it). It has exactly the same sensitivity as the code — a screenshot of the QR is a leaked
  code — and no separate security properties.
- **Preview before confirming (new, design brief).** To show "You're about to join *The
  Bear & Milo house*" before the joiner commits, the `codeIndex/{code}` doc carries the
  household's **display name** in addition to the id — nothing else, no pet names, no health
  data. This is a conscious, minimal relaxation of `architecture.md` §3's "holds nothing but
  the mapping": a household nickname is low-sensitivity, and anyone holding the code was
  given it on purpose. Pet names and photos are *not* in the index; a fuller preview (pets)
  only renders after the join write lands, with a "this isn't my household — leave" escape
  hatch.

**Joining is instant on a valid code — no admin approval queue.** Knowing the current code
*is* the authorization. An approval step would add friction to onboarding (the joiner waits;
an admin has to be present and notice) for a household of people who are, by construction,
coordinating in person or by text anyway. Instead:

- Joining triggers a notification to the household's admins ("Sam joined the household") —
  see `architecture.md` §5 when notifications are designed — so a join is never silent.
- The residual risk: a code that leaked (forwarded screenshot, shoulder-surf) lets an
  outsider join in the window before an admin notices and rotates. Accepted for v1. The
  upgrade path if it ever bites — admin-generated single-use invite codes with a short TTL —
  is noted in §10.

### 4.3 Removing a member

- **Admins only** (tightened from today, where any member can remove any member).
- Removal = delete the target's `members/{uid}` profile doc **and** remove their uid from
  the household `memberIds` array. Their reads and writes stop on the next rule evaluation.
- **Their past observations stay.** Those are household data, not the departing member's
  personal data; `loggedByName` was snapshotted at write time (`architecture.md` §3) so the
  history still reads correctly. Deleting a member does not delete what they logged.
- **The app should prompt the admin to rotate the code** right after a removal — otherwise a
  removed member who kept the code can immediately re-join. The removal notification to
  other admins should say so too.
- An admin removing **themselves** (leaving the household) is allowed, subject to the
  last-admin invariant below.

### 4.4 Admin grant, transfer, and the last-admin invariant

- The **creator** is the first admin. They may still be anonymous while solo; by the time
  the household has a second member, §3.2's gate has forced them to link — so a shared
  household always starts life with at least one durable admin.
- Any admin can **promote** a member to admin or **demote** another admin, by writing the
  `role` field on that member's `members/{uid}` doc. This requires a rules change — today a
  member doc is writable only by its own uid (§8).
- **Invariant, enforced on every demote/remove:** the household must retain **at least one
  admin, and at least one admin with a durable (Google/Apple) credential.** A demote or
  self-removal that would violate this is blocked in the app with a clear message ("Make
  someone else an admin first" / "The last admin needs a Google or Apple account").
  Firestore Rules can't express "count the remaining admins" cheaply across a subcollection,
  so this invariant is enforced **client-side plus a Cloud Function** re-check on
  `members` writes that flags/repairs a violation; the rules keep the coarse "only an admin
  writes roles" gate. The Function checks durability against **Firebase Auth's real provider
  data** (`getUser(uid).providerData` via the Admin SDK), not the self-reported `authMethod`
  field on the member doc — that field is a UI convenience a member could set to anything.
  Document this as a known soft spot: a determined member editing requests directly could
  momentarily break the invariant before the Function repairs it, but the trust model (§2.2)
  already covers that.
- **Transfer** is just promote-then-demote (or promote-then-leave). There's no dedicated
  "transfer ownership" action because there's no single "owner" — only the admin set.

### 4.5 Stranded anonymous members

The failure `architecture.md` §11 flags: an anonymous member reinstalls or resets their
device, loses the uid, and their `members/{uid}` entry now belongs to an identity **no
living device can authenticate as**. It's a dangling seat, not a security hole (that ghost
uid can't actually do anything), but it's untidy and confusing.

- **Prevention first:** §3.2 guarantees any household with more than one member has at least
  one durable admin, so a stranded anonymous uid can never be the *only* way back into roster
  management; the §3.3 nudge pushes the other anonymous members toward linking too.
- **Detection (soft):** each member's `members/{uid}` doc carries a `lastActiveAt`,
  updated (throttled to ~once/day) when the app opens. The admin member list can then
  surface "hasn't opened the app in 90+ days" as a hint. We do **not** auto-remove on this —
  a real person on vacation looks identical to a stranded uid.
- **Cleanup:** an admin removes the stale member the same way as any other removal (§4.3).
  Because a stranded anonymous user can't protest and can't be "the last durable admin,"
  this is always safe.
- **If a linked (Google/Apple) member loses access** — they don't. That's the whole point
  of §3.2 / §3.3: signing in again anywhere returns the same uid.

## 5. Data handling & storage

### 5.1 In transit

All Firebase traffic (Firestore, Auth, FCM, any Cloud Function calls) is TLS. No app-level
plaintext channels. The device-to-device attachment sharing (§5.4) rides Apple/Google
transports (AirDrop, Nearby Share, Messages) whose transport security is theirs, not ours.

### 5.2 At rest, in the cloud

Firestore encrypts documents at rest with Google-managed keys. This is **not** end-to-end —
Google can technically read the structured record, and so could anyone who compromised the
Firebase project's credentials. For a family's pet-health notes this is an accepted
limitation (§2.3); the mitigation we *do* invest in is keeping the highest-sensitivity
asset class — photos and video — out of that cloud entirely (§5.4).

### 5.3 At rest, on the device

FlutterFire's offline persistence is a local SQLite database (`architecture.md` §2). It is
**not encrypted** beyond the OS's own full-disk encryption (standard on modern iOS/Android).
Anyone with the unlocked device, or filesystem-level access to an unlocked/rooted device,
can read the cached household data. The mitigation is the optional app lock in §6. Local
DataStore/prefs equivalents (household id, display name, app-lock setting) are per-device
and never synced.

### 5.4 Media — local-only

Photos and video never touch any cloud we operate. Firestore stores only a tiny reference
(who captured it, type). Sharing a file to another member or a vet is a manual action
through the OS share sheet. Full rationale and trade-offs in `architecture.md` §8. The
privacy win, restated in this doc's terms: for media there is **no provider-can-read
question at all**, because the file never leaves the devices that hold it. The cost — an
attachment isn't automatically on every member's device — is accepted in §8 there.

### 5.5 Third parties and telemetry

- The only data processors are **Google/Firebase** (backend, auth) and **Apple** (Sign in
  with Apple only).
- **No analytics, crash-reporting, advertising, or attribution SDK in v1.** If crash
  reporting is ever added, it must carry no pet data, no observation content, and no member
  names — stack traces only.
- No data is sold, shared, or used for any purpose other than running the household's diary.
  This is short enough to *be* the privacy policy, near-verbatim.

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
| Delete an **observation** | Hard delete of the Firestore doc | Last-write-wins, no soft-delete/tombstone in v1 (`architecture.md` §4). Recoverable only from a prior export. Any local-only attachment is a separate manual file delete on whichever devices hold it |
| Discontinue a **medication** | `active: false` + `endDate` set — **not** a delete | Deliberate: preserves history a vet may ask about (`architecture.md` §3) |
| Remove a **member** | Profile doc + `members` entry deleted; their observations remain | §4.3. Prompt to rotate the code |
| Delete a **household** | Admin-initiated, confirmation-gated; a **Cloud Function** recursively deletes all subcollections (pets, vets, observations, members, exportLog) and the `codeIndex` doc | Firestore never cascades, and a client can't be trusted to finish a multi-hundred-doc delete. Client rule stays `allow delete: if false` on the household doc — only the Function (Admin SDK) does it. Members are notified before it happens |
| Delete my **account** | Anonymous: stop using it (the uid is already ephemeral). Google/Apple: unlink in settings, then Firebase Auth user deletion | Authored observations stay (household data). A member who wants their *name* scrubbed from history is a manual maintainer action in v1 — noted in §10. A solo anonymous creator who just stops leaves an orphaned household doc (no code, no other members); harmless at this scale, no cleanup mechanism in v1 |

**Retention:** no automatic expiry. The whole value of the record is its longevity — a
seizure-frequency trend over two years is the point. Data lives until a member deletes it or
the household is torn down.

## 8. Security Rules changes this document implies

For `firestore.rules` and `architecture.md` §6 to be updated to match §§3–7:

1. **`members/{memberUid}` — role writes.** Today: create/update allowed only if
   `request.auth.uid == memberUid`. Needed: a member may still write **their own**
   `displayName` / `authMethod` / `lastActiveAt`; but the `role` field may be written by
   **any current admin** (an admin is a member whose own `members` doc has
   `role == "admin"`). Requires a `get()` of the caller's own member doc. The last-durable-
   admin invariant (§4.4) is *not* expressed here — client + Function enforce it.
2. **`members/{memberUid}` — delete restricted to admins.** Today: any member may delete any
   member doc. Needed: only an admin (or the uid itself, for self-leave).
3. **`households/{id}` — `memberIds` array shrink restricted to admins.** Today the update
   rule allows any member to write the doc (and the array is still called `members`). Needed:
   removing a uid from `memberIds` (array size decreases, or a uid other than the caller's
   disappears) requires the caller be an admin. The existing "a new member adds only
   themselves" join clause stays.
4. **`codeIndex/{code}` — update & delete by household members.** Today: `if false`. Needed:
   a member of the household the code points to may delete it (rotation) and create the
   replacement. Consider restricting to admins to match §4.1.
5. **`codeIndex/{code}` — document shape.** Now `{ householdId, householdName }` (§4.2
   preview). `get` stays "any signed-in by exact id"; `list` stays `false`.
6. **`codeIndex/{code}` create — require a non-anonymous caller.** Belt-and-braces for the
   §3.2 gate: `allow create` also asserts the caller is a durable identity — check the
   `request.auth.token` for a non-anonymous provider (via `firebase.identities` /
   `firebase.sign_in_provider`, or simply `token.email != null` for Google/Apple); the exact
   claim is a rules-implementation detail, and note that `sign_in_provider` can lag a
   `linkWithCredential` until the session re-authenticates. Effect: a join code can't be
   published by an identity that could later strand the household. The client enforces the
   same thing earlier and more gently (the link prompt); this just stops a hand-crafted
   request from skipping it.
7. **`households/{id}` delete stays `if false`** — household teardown is Cloud-Function-only
   (§7).
8. New polymorphic **`observations`** collection (replacing today's separate `seizures` /
   `healthNotes`) keeps the same member-gated `read, write` rule those have now
   (`architecture.md` §3).

## 9. Cloud Functions this document implies

Adding to the list in `architecture.md` §9:

- **`memberIds` cache sync** — already planned; trigger on `members` writes.
- **Last-durable-admin invariant re-check** — trigger on `members` writes; flag or repair a
  household left with zero admins / zero durable admins (§4.4).
- **Join / removal notifications to admins** — trigger on `members` create/delete; ties into
  the notification design (`architecture.md` §5). Includes the "you should rotate the code"
  hint on a removal.
- **Recursive household delete** — callable, admin-only, does the cascade in §7.
- (Later, if per-invite codes land — §10) invite-token issue/redeem/expire.

## 10. Open items / deferred

- **Per-invite single-use codes** with a short TTL, as the upgrade from the shared rotating
  code if leaked-code joins ever become a real problem (§4.2). Needs the invite Cloud
  Functions above and a small "pending invites" UI. Not v1.
- **Self-serve "scrub my name from history"** for a departed member (§7). Manual maintainer
  action in v1; revisit if anyone asks.
- **Exact UX of the force-link-before-invite gate** (§3.2): where the link prompt sits in
  the invite flow, what the solo-creator nudge looks like, and whether "share code" and "add
  member" are one action or two. For the Flutter onboarding design pass.
- **A written privacy policy / data-handling statement** for anyone outside Tom's household
  who ends up using this — §5.5 is most of it already; needs to be an actual document if the
  app is ever distributed beyond family.
- **Formalizing the app-lock fallback behavior** (§6) once the logging flow is designed in
  Flutter — it must be verified that a biometric failure can never block or delay a save.
- **Rate-limiting / abuse** on `codeIndex` get (brute-force join attempts, §2.3) — relying
  on Firebase's built-in limits for v1; revisit only if there's evidence of probing.
