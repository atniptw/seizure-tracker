# iOS Release, Distribution & CI — Pet Health Diary (v2 / Flutter)

**Status:** decided 2026-09-06 · **Last updated:** 2026-09-06
**Companion docs:** `flutter-migration.md` (the client re-platform — §8 Phase 0 and §10 are
amended by this doc), `architecture.md` (§10 deployment), `security-privacy.md` (§3.1 Apple
sign-in, §7 deletion), `migration.md` (backend move — lands first)

This document closes the "open iOS questions" that `flutter-migration.md §10` and `§11` left as
the largest unknown in the re-platform. It is **decisions + a setup checklist**, not a
description of anything built.

## 0. Decisions at a glance

| # | Question | Decision |
|---|---|---|
| 1 | Sign in with Apple required? | **Deferred.** Guideline 4.8 is enforced via App Review; ad hoc distribution never goes through review. Becomes mandatory only at App Store submission. |
| 2 | In-app account deletion (5.1.1(v))? | **Deferred**, same reason. Flow designed in §8 so it can be built into Phase 5 rather than retrofitted. |
| 3 | Privacy policy URL / `PrivacyInfo.xcprivacy`? | Policy URL **not needed** until App Store. A minimal app-level privacy manifest **is** needed — manifest validation is an *upload-time* check. See §7. |
| 4 | iOS CI? | **GitHub Actions, pinned `macos-26`.** Free and unmetered because the repo is public. See §5. |
| 5 | iOS deployment target? | **15.0**, forced by firebase-ios-sdk 12.x. Flutter 3.47's template already emits it. See §6. |
| 6 | Flavors for dev/prod? | **No Xcode flavors.** One bundle id; `--dart-define` selects emulator vs prod. Dev runs against the **local emulator suite** with a `demo-` project id. See §4. |
| 7 | Distribution channel? | **Firebase App Distribution (ad hoc) on both platforms.** Not TestFlight. See §2. |

## 1. Scope

The near-term target is **two household users on a closed test track**, both installing through
the **Firebase App Tester** app. There is no App Store presence and no external tester
programme. A manual, separate path to the App Store exists as a stub (§5.3) for if that ever
becomes a thing.

Everything Apple's App Review Guidelines gate is therefore **out of scope for now** and
deliberately re-listed in §9 so deferring them stays a decision rather than an oversight.

## 2. Distribution: Firebase App Distribution ad hoc, both platforms

**Chosen over TestFlight internal.** The deciding factor is not technical: TestFlight internal
testers must be **App Store Connect users on the team**, which grants a household member real
account access (billing, agreements, app metadata) rather than tester-only access. App
Distribution testers are plain email invites.

| | Firebase App Distribution (chosen) | TestFlight internal (rejected) |
|---|---|---|
| Installer app | **App Tester — same on iOS and Android** | TestFlight app (iOS), App Tester (Android) |
| Tester identity | Email invite | **Must be an App Store Connect team user** |
| Apple review | None | None (internal only) |
| App Store Connect app record | **Not required** | Required |
| Device handling | **UDID must be registered per device** | Any device |
| Build expiry | None | 90 days |
| Recurring chore | Provisioning profile renewal (annual) | Build re-upload every 90 days |

**What ad hoc costs, concretely.** Before a tester can install, App Distribution asks them to
register the device: they install a Firebase profile, Firebase collects the UDID, and emails it
to the project's Owners/Editors. You then add that UDID to the ad hoc provisioning profile,
regenerate it, and **rebuild** — a UDID added after a build was signed does not retroactively
work. At two devices this is a one-time cost; budget it again whenever someone gets a new phone.

- UDIDs can be bulk-exported from the App Distribution console (**Testers & Groups → All testers
  → Export Apple UDIDs**) or via the Firebase CLI.
- The fastlane App Distribution plugin can automate register-UDID-to-profile if the manual loop
  becomes annoying. Not worth wiring for two devices on day one.
- Apple allows **100 registered iPhones per membership year**; removals only take effect at
  renewal. Irrelevant at this scale.

**Android is unchanged** — `release.yml` already uploads to the `household` group. This decision
brings iOS onto the same channel rather than introducing a second one.

## 3. Apple account & signing

A paid **Apple Developer Program** membership ($99/yr, enrol as an **individual** to skip the
D-U-N-S requirement) is still mandatory. Ad hoc removes the App Store Connect *app record*, not
the account, the certificate, or the profile — **there is no unsigned path onto an iOS device.**

Needed:

- **App ID** — explicit bundle id `com.atnip.seizuretracker`. No capabilities (push is deferred;
  Sign in with Apple is deferred, §9).
- **Apple Distribution certificate** — the same certificate type as App Store; ad hoc is an
  export *method*, not a different signing identity. Valid 3 years.
- **Ad hoc provisioning profile** containing the registered device UDIDs. **Valid 1 year** —
  this is the recurring chore that replaces TestFlight's 90-day expiry.
- **App Store Connect API key** (`.p8`, Issuer ID, Key ID) — still worth creating even without an
  app record: it is what lets CI manage signing assets unattended, and it is the only route to
  the App Store lane later. **Downloadable exactly once.**

**Signing approach.** Prefer **cloud signing** — an ASC API key plus
`xcodebuild -allowProvisioningUpdates`, letting Xcode create and fetch the certificate and
profile itself, with no private cert repository to maintain. This is **unverified in a GitHub
Actions container** and must be proven in Phase 0. The fallback is manual: generate the CSR with
`openssl` (no Keychain Access GUI required — relevant because the Xcode GUI is blocked on the
dev machine), upload it, download the `.cer`, convert to `.p12`, and store it as a repo secret.

**`fastlane match` is not recommended here** — it solves multi-developer cert sharing, which is
not a problem a single maintainer has, at the cost of another private repo to keep alive.

### 3.1 The Intel Mac — correction

An earlier conclusion in this workstream was that the Intel MacBook (Xcode capped at 16.4, the
last Intel-capable release) **cannot build iOS releases at all**. That is true *only for App
Store Connect uploads*: since **2026-04-28** Apple requires every binary uploaded to App Store
Connect — TestFlight included — to be built with **Xcode 26+ against the iOS 26 SDK**, and Xcode
16.4 ships the iOS 18.5 SDK.

**Ad hoc distribution never uploads to App Store Connect, so that mandate does not apply.** An
IPA built with the iOS 18.5 SDK installs and runs fine on an iOS 26 device — iOS is forward
compatible. So under this document's distribution choice the Intel Mac **is** a viable build
host, and is worth keeping in mind as an emergency fallback if CI is unavailable.

Two caveats keep it off the primary path:

- **CI is free and reproducible** (§5), so there is no reason to build releases by hand.
- **Debugging on a physical iOS 26 device from Xcode 16.4 will not work** — Xcode needs matching
  device-support files and 16.4 predates iOS 26. Building an IPA is fine; `flutter run` onto a
  modern phone is not. This is why §4 puts the dev loop on simulators and emulators.

The Intel Mac still cannot serve the **App Store** lane (§5.3) whenever that arrives.

## 4. Dev environment: emulators, not a second cloud project

**There is one Firebase project.** An earlier draft proposed a second cloud "dev" project; that
is dropped. `architecture.md §10`'s "no staging backend — a single Firebase project" stands
unamended.

Local development and all automated tests run against the **Firebase Local Emulator Suite**
(already configured in `firebase.json`: Firestore 8080, Auth 9099) using a **`demo-` prefixed
project id**. The Firebase SDKs treat any `demo-*` project as emulator-only, so such a build is
*structurally incapable* of reaching production — a stronger guarantee than a separate cloud
project, which can always be mis-pointed. The existing `demo-seizuretracker-rules-test` id in
the test suite already relies on this.

### 4.1 The switch

No Xcode schemes, no build configurations, no Android product flavors — a **single bundle id**,
because a phone holds either a dev build or a prod build and never both.

```
--dart-define=FLAVOR=local  → firebase_options_local.dart (demo-seizuretracker) + emulator wiring
--dart-define=FLAVOR=prod   → firebase_options.dart (the real project)
```

FlutterFire initialises from explicit Dart `FirebaseOptions`, so `GoogleService-Info.plist` /
`google-services.json` stop being the configuration authority. Consequences:

- **One `REVERSED_CLIENT_ID`, one URL scheme** in `Info.plist`. The `local` flavor needs no
  Google Sign-In configuration at all — the Auth emulator fakes the provider.
- `flutter_flavorizr`, the `xcodeproj` Ruby-gem approach, and hand-edited `project.pbxproj`
  build configurations are all **not needed**. This removes the single ugliest piece of iOS
  setup and, with it, the dependency on the blocked Xcode GUI.

### 4.2 Emulator host resolution — three different answers

The most common way this setup wastes an afternoon. Write it once as a helper:

| Client | Host |
|---|---|
| iOS Simulator | `localhost` (shares the Mac's network stack) |
| Android emulator | `10.0.2.2` (alias for host loopback) |
| A physical phone on the LAN | the Mac's LAN IP — **and** `firebase.json` must bind the emulators to `0.0.0.0`, not localhost |

The third row is what a cheap test phone needs.

### 4.3 What the emulator cannot cover

Naming these so "CI is green" is never mistaken for "verified":

- **The real Google OAuth flow** — the Auth emulator fakes it entirely.
- **Offline sync and rejected-write behaviour** — `flutter-migration.md §11` requires
  airplane-mode verification on a real device in Phase 3. Rules are not evaluated against the
  local cache (`architecture.md §4`), so a rejected write reverts silently; this is exactly the
  class of bug automated tests miss.
- **PDF rendering fidelity** — needs eyes (`flutter-migration.md §8`, Phase 6).

Changes touching auth, offline behaviour, or export therefore need a human with a phone,
regardless of pipeline state. That is a policy, not a pipeline feature.

## 5. CI/CD

### 5.1 Runner

**GitHub Actions, `macos-26`, pinned explicitly.** `atniptw/seizure-tracker` is a **public**
repository, and GitHub standard runners — macOS included — are free and *unmetered* on public
repos. The 10× macOS multiplier applies only to included-minute consumption on private repos.

- **Never `macos-latest`.** It only moved to `macos-26` in June 2026; older images shipped Xcode
  16.4 and silently produced binaries Apple rejects. An implicit label is a time bomb.
- **Never attach a self-hosted runner to this repo.** On a public repository any forker can open
  a PR that executes arbitrary code on the runner — which would be holding the signing
  credentials. GitHub advises against it explicitly.
- Fallbacks if Actions becomes unsuitable: **Xcode Cloud** (25 compute h/month, already included
  with the $99/yr membership) or **Codemagic** (500 M2 min/month, personal accounts only).

### 5.2 Release must not run unless CI is green

Implemented as **dependent jobs inside one workflow**, not a separate `workflow_run`-triggered
workflow:

```
ci.yml
  ├─ analyze         flutter analyze + riverpod_lint
  ├─ test            flutter test (headless)
  ├─ rules           firestore-tests (Node/Jest, emulator)
  ├─ integration     integration_test on a booted emulator
  ├─ smoke           release build boots → reaches SessionState.Ready → screenshot
  └─ distribute      needs: [analyze, test, rules, integration, smoke]
                     if: github.event_name == 'push' && github.ref == 'refs/heads/main'
```

`needs:` makes the gate atomic — same run, same commit, no cross-workflow plumbing and no race.
A `workflow_run` trigger would work but runs against the default branch's workflow definition
and does not check out the triggering commit by default, which is a well-known footgun.

**Two things to get right:**

- **Concurrency.** `ci.yml` currently sets `cancel-in-progress: true`. A cancelled run
  mid-upload is bad. Give `distribute` its own concurrency group with
  `cancel-in-progress: false`, or exclude `main` pushes from cancellation.
- **Build numbers** must increase monotonically — drive them from `github.run_number`.

**The `smoke` job is the highest-value addition for this model.** Unit tests never catch a broken
`Info.plist`, a missing URL scheme, bad `firebase_options`, or a flavor misfire — precisely the
failures that reach a phone silently. Booting the release build in a simulator and asserting it
reaches `Ready` costs minutes and covers the gap.

### 5.3 The App Store lane is manual and separate

`store-release.yml`, **`workflow_dispatch` only**. It does not exist yet and does not need to.
Keeping it separate means every Apple-review-gated obligation in §9 stays out of the automatic
path — they become prerequisites for the *contents* of that workflow, not blockers on daily work.

### 5.4 iOS build specifics

- **`exportOptionsPlist` must use `method: release-testing`, not `ad-hoc`.** Apple deprecated the
  `ad-hoc` name in Xcode 15.4 and **Xcode 26+ hard-rejects it**:
  `exportArchive exportOptionsPlist error for key "method" ... but found ad-hoc`. Since CI runs
  Xcode 26.6, the legacy value fails the first build.
- **`ITSAppUsesNonExemptEncryption = false`** in `Info.plist`. The app uses only HTTPS/TLS.
  Without it, uploads stall waiting for a manual export-compliance answer — a classic CI blocker.
  (Less critical on the ad hoc path than the App Store one, but free to set correctly now.)
- CocoaPods must be available on the runner; `printing` requires `use_frameworks!` (§6).
- Secrets extend the existing `GOOGLE_SERVICES_JSON` pattern: `APP_STORE_CONNECT_ISSUER_ID`,
  `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_P8` (base64), `GOOGLE_SERVICE_INFO_PLIST`
  (base64), and the `.p12` + password if manual signing is used.
- **Public-repo hygiene:** keep deploy jobs off `pull_request`, never use `pull_request_target`,
  never echo secrets.

### 5.5 Auto-deploy needs a backup first

`security-privacy.md §2.4` is explicit: **no cloud backup, no PITR** — an accidental destructive
write is recoverable only from a prior export. Pointing automatic deployment at two years of a
sick animal's seizure history with no undo is the one part of this design that warrants a guard.

**Recommendation: land a scheduled Firestore dump before enabling auto-deploy.** `migration.md
§5` already specifies a one-off JSON dump script; run it on a nightly schedule instead. It is an
afternoon's work and it is the difference between a recoverable mistake and a permanent one.

### 5.6 Rules and app versions drift — loosen first, tighten later

`firestore.rules` deploys independently of the app, and a phone cannot be forced to update. A
rules change can therefore land while a device still runs the previous build.

**Standing rule: rules must stay backward-compatible with the app version before them.** Loosen,
ship the app, then tighten in a later deploy — never tighten in the same step. This is the same
windowed shape `migration.md §6` uses for the legacy collections; under continuous deployment it
becomes permanent policy rather than a migration tactic.

Keep rules deployment a **separate, deliberate** job from app distribution, gated on
`firestore-tests` passing.

## 6. iOS deployment target: 15.0

**Binding constraint is Firebase.** `firebase-ios-sdk` 12.x (pinned at 12.18.0 by FlutterFire)
has a hard iOS 15.0 floor, and `firebase_core` / `firebase_auth` / `cloud_firestore` all declare
`s.ios.deployment_target = '15.0'`.

**Flutter's own floor caught up in the same place.** Flutter 3.47 (Aug 2026) raised its minimum
from iOS 13 to **iOS 15**, and `flutter create` emits `IPHONEOS_DEPLOYMENT_TARGET = 15.0`. So the
scaffold default and the requirement are the same number.

| Package | Min iOS |
|---|---|
| firebase_core / firebase_auth / cloud_firestore | **15.0** ← binding |
| google_sign_in_ios, share_plus, path_provider, file_selector, shared_preferences, local_auth | 12.0–13.0 |
| pdf | pure Dart |
| flutter_riverpod | pure Dart |

Consequences:

- **No Podfile `post_install` deployment-target override needed** — that workaround is only for
  older Flutter templates emitting 12.0/13.0. Scaffold on 3.47+ and skip it.
- **`printing` needs `use_frameworks!`** (usually with `use_modular_headers!`) — a Swift-linkage
  requirement, not a version one. Bites in Phase 6, not Phase 0.
- Flutter also stopped hard-coding `MinimumOSVersion` into `AppFrameworkInfo.plist`, which used
  to force 13.0 into `App.framework` and get archives rejected. Another reason not to scaffold on
  an old Flutter.
- **What iOS 15 excludes:** iPhone 6 / 5s and older — 2014 hardware. Nothing that matters.

`google_sign_in` 7.x additionally needs `GIDClientID` and the `REVERSED_CLIENT_ID` URL scheme in
`Info.plist` (§4.1). Its `initialize()` / `authenticate()` API is current.

## 7. Privacy manifest — the one obligation that binds now

Everything else Apple requires is review-gated and therefore deferred (§9). **Privacy-manifest
and required-reason-API validation runs at *upload*, not at review**, so it applies to any build
that goes through Apple's pipeline.

The dependencies already handle themselves:

- **The Flutter engine ships its own manifest** since Flutter 3.19, declaring `FileTimestamp`
  (C617.1), `SystemBootTime` (35F9.1) and `DiskSpace` (E174.1). Flutter 3.27+ asserts at build
  time that the artifact contains it.
- **Firebase iOS SDKs** ship manifests from 10.22–10.24; FlutterFire's bundling/code-signing
  plumbing landed alongside.
- **`google_sign_in`** is covered via `GoogleSignIn-iOS` ≥ 7.1.0. Watch `GTMSessionFetcher` — a
  stale transitive pin surfaces as an `ITMS-91061` warning.

**An app-level manifest is only strictly required if first-party native code calls a
required-reason API**, which a pure-Dart Flutter app does not. Add a minimal
`PrivacyInfo.xcprivacy` anyway (`NSPrivacyTracking = false`, empty collected-data array, no
required-reason APIs) — it is free, it is where app-level declarations will live later, and it
feeds the App Privacy label if the App Store lane ever opens. **Do it in Phase 0.**

Note the checks are inconsistent: some failures are hard errors, others are `ITMS-9105x` warning
emails that do not block testing but will block App Store review. A successful upload is not
proof the manifest is clean.

## 8. Account deletion — designed now, built later

Not required on this distribution path (§9), but designed here so it can be built into
**Phase 5 (settings)** rather than retrofitted, and because it resolves the open item in
`security-privacy.md §10` ("self-serve scrub my name from history").

**The framing that resolves the shared-data tension:** the "account" is the Firebase Auth uid,
not the household's health record. Deleting your account removes *your identity and your
membership*. The pet's history is the household's shared record and stays.

**Ordering is load-bearing:**

1. **Require connectivity.** Gate the flow. Firestore writes queue offline and `user.delete()`
   needs the network; deleting the auth user with writes still queued means those writes are
   rejected on flush and silently dropped. This is the one place in the app where "works
   offline" is the wrong answer.
2. **Resolve the last-admin invariant without blocking.** A hard block is what 5.1.1(v) exists to
   prevent. Route it instead: sole admin *with* other members → inline member picker → promote →
   continue. Sole member entirely → proceed; the orphaned household is already accepted as
   harmless (`security-privacy.md §7`).
3. **Optional name scrub, before leaving.** Checkbox, **default off**. Must run first — once out
   of the `members` array there is no write access. A non-admin can edit only their own
   observations, which is exactly the set carrying their name, and `§8 item 6` permits it
   (`loggedByUid` unchanged, only `loggedByName` diffs).
4. **Self-leave in `§4.3` order:** `members` array write **first**, then delete own
   `members/{uid}` doc — the admin check `get()`s the caller's own member doc.
5. **Clear local prefs** → app returns to `NeedsSetup`.
6. **`user.delete()` last.** Firestore first, always. Expect **`requires-recent-login`** on a
   stale token: catch it, re-run Google sign-in as `reauthenticateWithCredential`, retry. This is
   the most commonly missed step. Anonymous users cannot reauthenticate — fall back to leave +
   `signOut` + clear prefs, which is equivalent since the uid is already ephemeral.

Apple's requirement explicitly covers **anonymous/guest accounts**, and deactivation is
explicitly insufficient — it must delete the account record.

## 9. Deferred — and what un-defers them

All of the following are enforced through **App Review**, which ad hoc distribution never enters.
They become hard prerequisites the moment `store-release.yml` (§5.3) is used for real.

| Item | Trigger | Note |
|---|---|---|
| **Sign in with Apple** (4.8) | App Store submission | Google Sign-In is named explicitly in 4.8. **An anonymous / "continue without an account" path does *not* exempt you — there is no such carve-out.** The guideline is now feature-based ("another login service with the following features"), not Apple-specific. |
| **In-app account deletion** (5.1.1(v)) | App Store submission | Design in §8. Covers anonymous accounts too. |
| **Privacy policy URL** | App Store submission | Also needs an in-app link (5.1.1(i)). `security-privacy.md §5.5` is already substantively the policy text; GitHub Pages off this public repo is a free host. |
| **App Privacy questionnaire** | App Store submission | Blocks submission, nothing earlier. |
| **App Store Connect app record** | App Store submission | Not needed for ad hoc (§2). App name must be globally unique. |

**Cost of deferring Apple sign-in is genuinely low**, which is why it stays deferred:

- **`firestore.rules` needs no change.** Every rule keys off `uid ∈ members` and
  `members/{uid}.role`; none reads the auth provider. `security-privacy.md §8 item 8`'s post-v1
  durable-creator assertion is already written to accept `apple.com`.
- **Firebase console config is minimal** — for a native iOS-only flow the Service ID, Team ID,
  Key ID and private key fields are left **empty**; those are only for the web/Android OAuth code
  flow.
- The `Sign in with Apple` capability is set on the App ID in the **web portal** plus the
  entitlements file — both CLI/browser-reachable, so the blocked Xcode GUI is not an obstacle.
- `MemberProfile.authMethod` gains `"apple"`; the durable-credential set (`§3.2`, `§4.4`) becomes
  `{google, apple}`.

Two traps for whenever it does ship:

- Apple returns the user's **full name only on the first authorization** ever for that Apple
  ID/app pair. The join flow has the user *type* `displayName`, so this is currently harmless —
  do not "improve" it into prefilling from the provider without knowing this.
- **Account deletion must then also revoke the Apple token** via Apple's REST API. Firebase
  supports this (`revokeTokenWithAuthorizationCode`), but it needs the `authorizationCode`
  captured at sign-in, and there are open FlutterFire reports of the call hanging. This argues
  for the `sign_in_with_apple` package (which exposes `authorizationCode` explicitly) over
  FlutterFire's `signInWithProvider(AppleAuthProvider())`.

Private relay emails are a non-issue — the app stores no email, only `displayName` and
`authMethod`.

## 10. Recurring chores this takes on

- **Ad hoc provisioning profile expires annually** — regenerate and rebuild.
- **Distribution certificate expires every 3 years.**
- **A new tester device means: register UDID → regenerate profile → rebuild.**
- Apple Developer Program renewal, $99/yr. Lapsing invalidates signing.

## 11. One-time setup checklist

**[YOU]** needs an Apple ID, 2FA, browser or payment. **[ME]** is repo work with no Apple
dependency — it can start immediately and in parallel.

### Stage A — Apple (sequential; blocks B)

1. **[YOU]** Enrol in the Apple Developer Program, **individual**. Expect identity verification;
   allow 24–48 h.
2. **[YOU]** Register the **App ID** — explicit bundle id `com.atnip.seizuretracker`, no
   capabilities.
3. **[YOU]** Create an **App Store Connect API key** — Users and Access → Integrations → Team
   Keys, role **App Manager**. Save the Issuer ID, Key ID, and the `.p8` — **downloadable once.**
4. **[YOU]** Create the **Apple Distribution certificate** (cloud signing may handle this — §3).
5. **[YOU]** Create the **ad hoc provisioning profile** — after step 6 supplies the UDIDs.

*No App Store Connect app record. No TestFlight setup. No internal-tester user accounts.*

### Stage B — testers

6. **[YOU]** Invite both people to the App Distribution `household` group; each installs the
   Firebase profile on their device so Firebase can collect the UDID. Add the UDIDs to the
   profile (step 5), then rebuild.

### Stage C — Firebase

7. **[YOU]** `flutterfire configure` against the existing project — registers the iOS app, emits
   `GoogleService-Info.plist` and `firebase_options.dart`.

*No second Firebase project (§4).*

### Stage D — secrets

8. **[YOU]** Add repo secrets: `APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_KEY_ID`,
   `APP_STORE_CONNECT_P8` (base64), `GOOGLE_SERVICE_INFO_PLIST` (base64), plus `.p12` + password
   if manual signing.

### Stage E — repo, all [ME], no Apple dependency

9. `flutter create` scaffold, Flutter 3.47+, org `com.atnip.seizuretracker`, deployment target
   15.0.
10. `--dart-define` flavor switch + `firebase_options_local.dart` + emulator wiring + the host
    resolution helper (§4.2).
11. `Info.plist`: `GIDClientID`, `REVERSED_CLIENT_ID` URL scheme,
    `ITSAppUsesNonExemptEncryption = false`, minimal `PrivacyInfo.xcprivacy`.
12. `exportOptionsPlist` with **`method: release-testing`** (§5.4).
13. Restructure `ci.yml` into the gated job graph (§5.2), including the `smoke` job.
14. Nightly Firestore dump workflow (§5.5) — **before** auto-deploy is switched on.

**Critical path:** 1 → 2 → 3 → 4 → 6 → 5. Stage E starts on day one. Stage C is independent of
Apple entirely.

## 12. Amendments to other docs

- **`flutter-migration.md §10`** — replace the TestFlight paragraph: distribution is Firebase App
  Distribution ad hoc on both platforms; there is no 90-day expiry chore; a distribution
  certificate *is* still mandatory; deployment target is 15.0; the macOS runner is free (public
  repo).
- **`flutter-migration.md §8` Phase 0** — drop "App Store Connect app record", "TestFlight
  internal testers"; add UDID registration, the privacy manifest, and
  `method: release-testing`.
- **`flutter-migration.md §1`** — Apple sign-in is deferred pending *App Store* distribution, not
  TestFlight; the reasoning line should say so.
- **`architecture.md §10`** — stands; the single-project decision is reaffirmed (§4).
- **`security-privacy.md §3.1`** — the Apple row's "TestFlight internal testing doesn't require
  it" reasoning is superseded: distribution is ad hoc, and the trigger is App Review generally.
- **`security-privacy.md §7`** — the "Delete my account" row can reference §8 here for ordering.
