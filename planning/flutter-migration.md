# Flutter Client Migration Plan — Kotlin/Compose → Flutter/Dart

**Status:** draft for discussion · **Last updated:** 2026-08-30
**Companion docs:** `architecture.md` (target stack, §0 gap list), `migration.md` (backend /
data-model move — a prerequisite, see §2), `product-spec.md` (features, **§4.0 "what the next
release contains"**), `security-privacy.md` (roles, join mechanics)

## 1. Scope

Rewrite the shipped Android app (Kotlin + Jetpack Compose, single Gradle module, ~5,700 LOC
across 72 files) as a Flutter app targeting **iOS and Android** from one codebase, against the
**same Firebase project** and the **post-`migration.md` Firestore shape**. The backend, the
Security Rules, and `firestore-tests/` do not change here — this is a client-only rewrite.

**In scope:** exactly what `product-spec.md §4.0` calls "the next release" — every screen and
feature the shipped Kotlin app has today (§7 inventory), ported at parity, **plus** what
`migration.md` adds (admin/member roles, the join-code relocation, the export log), **plus**
iOS as a new platform.

**Out of scope** (this is `product-spec.md §4.0`'s deferred list — the items §4 of that doc
describes as part of the target product but that don't ship next):

- **Apple sign-in** — with App Store distribution (`security-privacy.md §3.1`). TestFlight
  internal testing doesn't require it.
- In-progress seizure timer, voice dictation.
- Pet `archived` / `diagnosisDate`; history filters; compare-to-similar; the frequency-trend
  chart (dashboard + PDF) and the combined all-pets dashboard view.
- Join-code rotation, QR-code join.
- Photo/video attachments (`architecture.md §8`), household notifications
  (`architecture.md §5`), web dashboard (`architecture.md §2/§10`).
- App-lock / biometric (`local_auth`) — carried as a §4 package but only wired if the
  logging-path verification (§11) passes; otherwise it's deferred too.
- Any Firestore shape or rules change — that's `migration.md`, and it lands first.

## 2. Ordering: backend migration first, then this

`migration.md` moves the **current Kotlin app** onto the target Firestore shape
(`observations` collection, `members/{uid}.role`, `private/config` join code, medications
subcollection, export log). That work is designed so this rewrite is "a near-pure client
rewrite against a backend that already looks like its target" (`migration.md` §1).

**Sequence:**

1. Execute `migration.md §4` areas 1–5 on the Kotlin app; verify on both phones
   (`migration.md §5` verification gate).
2. Keep using the Kotlin app day-to-day while the Flutter app is built (weeks). It stays the
   working fallback — this is *why* `migration.md §7` cleanup is gated on this document's
   cutover (step 5), not on a calendar date.
3. Build the Flutter app against the migrated shape (this document).
4. Cut both phones over to Flutter (§8). Retire the Kotlin app.
5. **Then** run `migration.md §7` cleanup (drop legacy `seizures`/`healthNotes` collections,
   the `code` field, embedded meds) — with a fresh dump + the automated verify gate
   (`migration.md §5, §7`). Doing it earlier removes the Kotlin fallback.

Rewriting Flutter against the *old* shape and migrating data twice is the thing this ordering
avoids. The only cost is that `migration.md`'s area-3 app changes (the Kotlin `Observation`
model) get thrown away weeks later — acceptable; they're the smallest way to de-risk the data
move, and they keep the Kotlin app usable in the interim.

## 3. Stack mapping

| Concern | Shipped (Kotlin) | Flutter |
|---|---|---|
| UI toolkit | Jetpack Compose, Material 3 | Flutter widgets, Material 3 (`useMaterial3: true`) |
| State / VM | `ViewModel` + `StateFlow` + `stateIn(WhileSubscribed(5000))` | Riverpod (`riverpod` 2.x): `StreamProvider`, `NotifierProvider`, `AsyncNotifierProvider`, `.family`, `.autoDispose` |
| Live Firestore reads | `callbackFlow { addSnapshotListener }` | `cloud_firestore` `Query.snapshots()` → `StreamProvider` |
| Repositories | Kotlin `object` singletons, called directly | plain Dart classes exposed as `Provider<XRepository>` (Riverpod is the DI seam the Kotlin code lacks) |
| One-shot reads/writes | `suspend fun` + `await()` | `async`/`await` on the `cloud_firestore` futures |
| Local prefs | DataStore (`UserPrefs`) — household id, display name, active pet, a11y flags | `shared_preferences` (same key set), wrapped in a `UserPrefs` class + provider |
| Navigation | Navigation-Compose + `Destinations` route constants | `go_router` — typed routes, same URL-ish paths |
| Auth (Google) | Credential Manager + `googleid` | `firebase_auth` + `google_sign_in` |
| Auth (anonymous) | `FirebaseAuth.signInAnonymously` | `FirebaseAuth.instance.signInAnonymously()` |
| PDF export | hand-rolled `android.graphics.pdf.PdfDocument` | `pdf` + `printing` packages (`architecture.md` §7) |
| CSV export | hand-rolled string building | `csv` package (or keep it hand-rolled — it's ~40 lines) |
| Share / save file | `FileProvider` + `ACTION_SEND` / SAF `CreateDocument` | `share_plus` (share) + `file_selector`/`path_provider` (save) |
| Theme + a11y | `SeizureTrackerTheme(a11y=)` — high-contrast, larger-text, reduce-motion | `ThemeData` swap + `MediaQuery` `textScaler` override + an animations flag |
| Dates | `DateTimeUtils` (hand-rolled) | port verbatim; `intl` for formatting if useful |
| Join code | `HouseholdCode` (hand-rolled alphabet, `Random.Default`) | port the alphabet + length + unit test — but **swap the RNG to `Random.secure()`** (the shipped one is non-cryptographic; `security-privacy.md §2.3, §10`) |

## 4. Packages (intent — pin exact versions at scaffold time)

- **firebase_core, firebase_auth, cloud_firestore** — FlutterFire, configured via
  `flutterfire configure` (generates `firebase_options.dart`).
- **google_sign_in** — Google auth. Note ≥7.x has a breaking `initialize`/`authenticate` API
  and needs an iOS URL-scheme entry in `Info.plist` (Phase 0, §10).
- **flutter_riverpod** + **riverpod_annotation** / **riverpod_generator** (codegen for
  `.family` providers) + **riverpod_lint** / **custom_lint** (Phase 0, alongside `flutter analyze`).
- **freezed** + **json_serializable** — immutable models, sealed `ObservationDetails` union,
  Firestore (de)serialization.
- **go_router** — routing.
- **shared_preferences** — per-device settings.
- **pdf** + **printing** — PDF report.
- **csv** — CSV report (optional).
- **share_plus** — hand a file to the vet / another app. **path_provider** + **file_selector**
  — the cache dir and the "save a copy" picker (the SAF `CreateDocument` equivalent).
- **local_auth** — the optional app-lock (`security-privacy.md §6`). Wired only if the
  Phase-3 verification passes (§11); the package is listed now so the decision is explicit.
- **intl** — date/number formatting.
- Dev: **build_runner**, **fake_cloud_firestore**, **flutter_test** / **integration_test**,
  **mocktail** if needed.

**Deferred packages** (named so it's a decision, not a gap): **fl_chart** (or equivalent) —
the frequency-trend chart is post-v1 (§1); add it then, and note the PDF path is
`RenderRepaintBoundary.toImage` → `pw.MemoryImage`.

**Deliberately excluded:** crash reporting (`security-privacy.md §5.5` — not a Blaze issue,
still a no); `connectivity_plus` (Firestore's `SnapshotMetadata.isFromCache` /
`hasPendingWrites` already drives any "not synced" affordance); secure storage (Firebase Auth
keeps the refresh token in the platform keystore itself).

## 5. Data layer

**Models** (`lib/data/model/`) — one `freezed` class each, all with
`fromFirestore`/`toFirestore` via `withConverter`. **`migration.md §3` is normative for exact
field names and types** — where it and `architecture.md §3` disagree, `migration.md` wins.

- `Household` — `name`, `members` (array — the `memberIds` rename was dropped),
  `createdAtMillis`. No `code` field after `migration.md §7` cleanup.
- `MemberProfile` — `uid`, `displayName`, `authMethod`, `role` (`admin`|`member`),
  `joinedAtMillis`. **No `lastActiveAt`** — post-v1 (`migration.md §3`).
- `Pet` — `name`, `species`, `breed`, `weightKg`, `birthDateMillis`, `createdAtMillis`. No
  embedded `medications`, no `photoUri`, **no `diagnosisDate` / `archived`** (post-v1, §1).
- `Medication` — its own doc: `name`, `dose`, `frequency`, `notes`, `active`, `startDate`,
  `endDate` (`startDate`/`endDate` null on the backfilled docs).
- `Vet` — `name`, `phone`, `addressOrNotes` (the shipped shape — `architecture.md §3` note).
  `PetVetLink` — `petId`, `vetId`, `vetName`, `role` (a flat collection; the `linkedVets`
  array was dropped from the target).
- **Timestamps:** only `observations` fields are Firestore `Timestamp` (`occurredAt`,
  `createdAt`, `updatedAt`). Every other doc keeps epoch-millis `Long`/`int` — the model
  fields are named `...Millis` with an `int` converter, **not** `DateTime`. (`migration.md §3`
  scopes the conversion to observations only.)
- **`Observation`** — the envelope: `id`, `type`, `petId`, `loggedByUid`, `loggedByName`,
  `occurredAt`, `createdAt`, `updatedAt`, `summary`, `details`.
- **`ObservationDetails`** — `freezed` union: `.seizure(durationSeconds, seizureType,
  symptoms, preSeizureSigns, possibleTriggers, recoveryMinutes, recoveryNotes, rescueMedGiven,
  rescueMedDetails, notes)` and `.note(description, notes)`. Field names per `migration.md §3`
  — there is no `recoveryTime` / `recoveryBehavior` / `triggers` / `duration` / `type`.
- **`summary`** — the client may drop this field and format the feed line at render time from
  `type` + `details` (no cache to invalidate); or keep it and recompute on every write
  (`migration.md §3`). Render-time is simpler — recommend that.

**The `Entry` sealed merge type is deleted.** Today `ui/common/Entry.kt` merges two
collections client-side because Firestore can't `ORDER BY` across them. Post-migration there
is one `observations` collection — `orderBy('occurredAt', descending: true)` server-side,
filter by `type` in Dart. This removes a whole class of client bookkeeping.

**Repositories** (`lib/data/repository/`) — one class per collection: `ObservationRepository`,
`PetRepository`, `MedicationRepository`, `VetRepository`, `PetVetLinkRepository`,
`MemberRepository`, `HouseholdRepository`, `ExportLogRepository` (the past-exports view —
`architecture.md §7`, `security-privacy.md §8 item 10`), `AuthRepository`. Each exposes
`Stream<List<X>>` (from `.snapshots()`) for live data and `Future` methods for writes.
Exposed as `Provider`s; no singletons.

- `ObservationRepository` edits use `update()`, not `set()` (a stale offline edit must fail,
  not resurrect a deleted doc — `migration.md §4 area 3`).
- `VetRepository.deleteVet` deletes the vet + its matching `petVetLinks` in one `WriteBatch`
  (`architecture.md §3` — no Function for cascade).
- Reads are `orderBy('occurredAt')` + client-side filter by type/pet/logger — **no
  `where(...)` alongside the `orderBy`** unless `firestore.indexes.json` is added first
  (`migration.md §4 area 3`).

## 6. State layout (Riverpod)

- `authRepositoryProvider`, `firestoreProvider` — leaf providers.
- `sessionProvider` (`AsyncNotifier<SessionState>`) — mirrors `SessionViewModel`: resolves
  `FirebaseAuth` + `UserPrefs` into `Loading` / `NeedsSetup` / `Ready(householdId, uid,
  displayName)`. Holds the `signInWithGoogle` / `signInAnonymously` / `createHousehold` /
  `joinHousehold` / `signOut` actions.
- `householdIdProvider` — derived from `sessionProvider`, throws/guards if not `Ready`.
- Family stream providers keyed by household id:
  `observationsProvider(householdId)`, `petsProvider`, `medicationsProvider(petId)`,
  `vetsProvider`, `petVetLinksProvider`, `membersProvider`, `exportLogProvider`.
- `activePetProvider` — combines `petsProvider` + `UserPrefs.activePetId`, self-healing to
  the oldest pet (same logic as `PetListViewModel.activePet` today).
- `isAdminProvider` — `membersProvider` + `uid` → **is the uid in the `members` array AND is
  its `members/{uid}.role == "admin"`** (the conjunction, mirroring the `isAdmin()` rule —
  `security-privacy.md §8`; a missing `role` reads as `"member"`). Gates every management
  action and the admin-only UI.
- `a11yProvider` — independent of session, like `AccessibilityViewModel` today.

**go_router ↔ Riverpod.** Today `AppRoot` swaps the whole tree on `SessionState`. In
go_router that's a `redirect` that reads `sessionProvider` plus a `refreshListenable` bridging
the provider to the router (a small `ChangeNotifier` that fires on session-state change). This
is the fiddliest single piece of the port — build it in Phase 2, not Phase 0's placeholder
skeleton.

**Caching semantics.** `riverpod_generator`'s `@riverpod` is **autoDispose by default** —
the opposite of `stateIn(WhileSubscribed(5000))`, which keeps a stream 5 s past the last
listener. For the two providers that matter (`observationsProvider`, `petsProvider`) the
shipped app hoists them in `AppRoot` for the whole session — so `@Riverpod(keepAlive: true)`
is the closest analogue, not a timer. Use autoDispose for leaf/screen-scoped providers only;
don't let snapshot listeners thrash on navigation.

**The `ViewModelKeyCollisionTest` gotcha disappears** — Riverpod providers are identified by
provider identity + family arg, no shared `ViewModelStore` to collide in. Drop the workaround;
don't port the test.

## 7. Screen inventory

~20 screens (every screen in the shipped `ui/` is accounted for) + ~12 shared widgets, plus
the one new read surface (past-exports list). Complexity: **S** = layout + form, **M** = form
with pickers/derived state, **L** = significant logic.

| Area | Shipped screen | Flutter route | Notes | Size |
|---|---|---|---|---|
| Onboarding | `WelcomeScreen` | `/welcome` | Sign-in (Google / continue-without) → create or join household. `sessionProvider` drives it. | M |
| Dashboard | `DashboardScreen` | `/` | **Parity = days-since-last-seizure, count, recent entries, single active pet** (that's all the shipped screen does). Frequency-trend chart + combined all-pets view are *later* (§1). | M |
| History | `EntryHistoryScreen` | `/history` | **Parity = flat list, active pet, most-recent-first.** Month grouping *and* filters (pet/type/date/logger) are *later* (§1). One collection now → the eventual filters are trivial. | S |
| Quick add | `QuickAddSheet` | bottom sheet | Entry-type picker → seizure or note. **Must not add a tap to seizure logging** (`product-spec.md §4`). | S |
| Seizure | `AddEditSeizureScreen` | `/observation/seizure/new`, `/observation/:id/edit` | Biggest form: date/time, duration, type dropdown, symptom chips, recovery, rescue-med toggle, notes. Edits `update()` not `set()`. | L |
| Detail | `SeizureDetailScreen` → **`ObservationDetailScreen`** | `/observation/:id` | Read-only detail, per-type body (there's no note-detail screen today — unify rather than port the asymmetry). Compare-to-similar is *later* (§1). | S |
| Health note | `AddEditHealthNoteScreen` | `/observation/note/new/:petId`, `/observation/:id/edit` | Deliberately minimal: text, when, notes. | S |
| Pets | `ManagePetsScreen` | `/pets` | List + add + **hard-delete** (parity; archive-instead is *later*, §1). Admin-gated. | S |
| Pets | `AddEditPetScreen` | `/pets/new`, `/pets/:id/edit` | Profile form + **the medications-subcollection CRUD** (add / edit / discontinue via `active`+`endDate` — no shipped equivalent) + linked vets. Admin-gated. | **L** |
| Pets | `PetSwitcherSheet` | bottom sheet | Set `activePetId`. Not admin-gated (per-device pref). | S |
| Vets | `VetsDirectoryScreen` | `/vets` | Shared directory; client-side "which pets" filter. | S |
| Vets | `VetDetailScreen` | `/vets/new`, `/vets/:id/edit`, `/vets/new?linkPet=:petId` | Add/edit vet; the `linkToPetId` one-flow variant as a query param, not a separate route. Admin-gated. | M |
| Vets | `LinkVetSheet` | bottom sheet | Link existing vet to a pet with a role. | S |
| Household | `HouseholdScreen` | `/household` | Member list (everyone); show/share join code + remove-member (admin only). **No rotate** (*later*, §1). | M |
| Household | `RemoveMemberDialog` | dialog | Confirm. (Rotation prompt is *later*.) | S |
| Settings | `SettingsHubScreen` | `/settings` | Hub: display name, active pet, links out. | S |
| Settings | `AccessibilityScreen` | `/settings/accessibility` | High-contrast / larger-text / reduce-motion → `a11yProvider`. | S |
| Export | `ExportScreen` | `/export` | Pet, date range, include-types, format. Admin-gated. Writes an `exportLog` doc on success. PDF rendering is split into Phase 6 — that's why this is M not L. | M |
| Export | `ExportReadyScreen` | `/export/ready` | Share (`share_plus`) / save-a-copy (`file_selector`). | S |
| Export | **past-exports list** | `/export/log` (or a section of `/export`) | Read `exportLogProvider`; member-visible, no export button for non-admins (`security-privacy.md §8 item 10`). New surface — the shipped app has no read side. | S |
| — | `LoadingScreen` | — | `sessionProvider` loading state. | S |

Route shapes: literal segments (`/observation/seizure/new`) are declared **before** the
param route (`/observation/:id`) — go_router matches in declaration order, and the reverse is
a silent trap.

**Shared widget library** (`lib/ui/common/`) — port first, in Phase 0: `EntryCard`,
`EntryTypeTag`, `RoleTag`, `AvatarInitial`, `PillButton`, `PillChipSelector`,
`SegmentedControl`, `SectionHeader`, `ListRow`, `LabeledTextField`, `ConfirmDialog`,
`AppBottomSheet`. Most are 20–40 lines and map cleanly to a Flutter widget.

## 8. Phasing

Each phase is a mergeable chunk. The Kotlin app keeps running throughout (§2).

- **Phase 0 — scaffold + iOS platform setup.** `flutter create` (org
  `com.atnip.seizuretracker`), add packages, `flutterfire configure` (registers a Firebase
  iOS app, emits `GoogleService-Info.plist`), wire the Firebase emulator, `flutter analyze` +
  `riverpod_lint` + CI (§10). Port the theme (`AppColors`, `Type`, `Shape`) and the shared
  widget library. `go_router` skeleton. **And the iOS chores, because the first `flutter
  build ipa` is where the surprises live:** Apple Developer enrolment, bundle id, an App
  Store Connect app record, both people added as TestFlight internal testers (needs *their*
  Apple IDs), the Google-Sign-In reversed-client-id URL scheme in `Info.plist`, a minimum
  iOS deployment target at or above the Firebase SDK floor, CocoaPods in CI, and one green
  `flutter build ipa`. No Firestore yet.
- **Phase 1 — data layer, no UI.** All `freezed` models, all repositories + providers,
  exercised against `fake_cloud_firestore` and the emulator, mirroring the current
  `data/repository/*RepositoryTest.kt` suite. **Verify `fake_cloud_firestore` actually
  supports `withConverter` + the query shapes used** before the test strategy is locked — if
  not, keep the converter inside the repository body.
- **Phase 2 — auth + onboarding.** `sessionProvider`, `AuthRepository`, `WelcomeScreen`, the
  `Loading`/`NeedsSetup`/`Ready` gate, **and the go_router `redirect` + `refreshListenable`
  bridge** (§6). Reach a signed-in empty shell on both platforms.
- **Phase 3 — the logging core.** Dashboard, quick-add, add/edit seizure, add/edit health
  note, entry history, observation detail. Do it first among features. **Verify on a real
  airplane-moded device, this phase:** (a) offline logging round-trips; (b) a rules-rejected
  write (a demoted-role edit) surfaces *something*, not a silent revert (`architecture.md §4`).
- **Phase 4 — pets & vets.** Manage pets, add/edit pet + the medications-subcollection CRUD,
  pet switcher, vet directory, vet detail (+ batch delete-with-links), link-vet sheet. Admin
  gating throughout.
- **Phase 5 — household & settings.** Household screen (member list, show/share code, remove),
  settings hub, accessibility screen.
- **Phase 6 — export.** `pdf`/`printing` report, CSV, share/save, `exportLog` write + the
  past-exports list. Expect the PDF to look different — verify on-device and **budget a design
  pass on the report layout** (the shipped hand-rolled one has no unit test either).
- **Phase 7 — parity sweep & cutover.** Walk `product-spec.md §4.0` "next release contains"
  item by item against both apps. **Migrate per-device state:** the Flutter app's
  `shared_preferences` can't read Android DataStore, so on cutover both people land in
  `NeedsSetup` — have both join codes to hand, and confirm the re-join is a **merge** of
  `members/{uid}` (must not clobber `role`/`joinedAt`). Build signed iOS + Android, install on
  both phones, confirm history/dashboard/export. Retire the Kotlin app.
- **Phase 8 — cleanup.** Run `migration.md §7` (fresh dump + verify gate, then drop legacy
  collections/fields). Archive the `app/` Gradle module. Update `README.md` and `CLAUDE.md`
  to describe the Flutter app as current.

## 9. Testing

**Note the platform constraint:** `cloud_firestore` / `firebase_auth` are federated plugins
with **no Dart-VM implementation** — `flutter test` (the fast headless runner) cannot talk to
the Firestore emulator. So the tiers don't map one-to-one to the current Robolectric setup:

- **Headless — `flutter test`** (the bulk, runs like the current JVM tests):
  - Pure Dart: model round-trips (`freezed`/json), and the ported pure logic —
    `filterExportEntries` (from `ui/export/ExportViewModel.kt`, tested by
    `ExportViewModelTest.kt` — not `util/`), `HouseholdCode`, `DateTimeUtils`,
    `ExportFilenames` (+ `CsvExporterTest.kt`). Port the assertions case-for-case.
  - Repository / provider / notifier logic against **`fake_cloud_firestore`** +
    `mocktail` — the analogue of the current `data/repository/*RepositoryTest.kt` and
    ViewModel suites.
  - Widget tests for key screens, driven with a `ProviderScope` whose repository providers
    are overridden with fakes — easier than the Kotlin equivalent because Riverpod gives a
    real override seam the `object` singletons don't.
- **`integration_test` on a booted emulator/simulator** (slower, a separate CI job — this is
  a heavier job than today's `build`): the rules-adjacent paths that `fake_cloud_firestore`
  can't model — the admin/member write split, the join carve-out, authorship immutability.
  Wrap in `firebase emulators:exec --only firestore,auth "flutter test integration_test/..."`
  and run it on a `reactivecircus/android-emulator-runner` job.
- **`firestore-tests/`** — unchanged. Rules are language-independent; that Node/Jest suite is
  still the authority on `firestore.rules`, and it grows the new §8 carve-out cases as
  `migration.md` lands them.

## 10. Build, CI, release

- **`firebase_options.dart`** from `flutterfire configure` becomes the config source;
  `google-services.json` / `GoogleService-Info.plist` are still emitted for the native
  sub-builds. CI writes all three from secrets (extend the current `GOOGLE_SERVICES_JSON`
  pattern in `ci.yml`).
- **CI (`ci.yml`)** — today it's a *single `build` job* with `firestore-tests` as a step
  inside it. Split it: keep the `firestore-tests` step as-is (own job); replace the Android
  `./gradlew` steps with a Flutter job (`flutter analyze` + `riverpod_lint`, headless
  `flutter test`, `flutter build apk`); add a **separate emulator job** for the
  `integration_test` tier (§9); add a `macos-latest` job for `flutter build ios --no-codesign`
  + CocoaPods.
- **Release (`release.yml`)** — Android: `flutter build appbundle`/`apk` → keep Firebase App
  Distribution `household` group. iOS: `flutter build ipa` → TestFlight internal testers
  (no Beta App Review for internal). Same `v*.*.*` tag trigger.
- **iOS is new surface with real, recurring cost:** an Apple Developer account ($99/year); a
  bundle id + App Store Connect record; a **distribution certificate is mandatory from the
  first upload** (Android's release build is currently debug-signed — iOS has no such
  shortcut); a macOS CI runner; and **TestFlight builds expire after 90 days**, a
  rebuild-and-upload chore Android App Distribution doesn't have. Push entitlements are *not*
  needed while notifications are deferred — a deliberate skip, not a gap.

## 11. Risks & watch-items

- **iOS platform work** (§10) — the largest unknown; nothing about it exists today, and its
  cost is recurring, not one-off. Front-loaded into Phase 0.
- **The CI test-tier rebuild** — the plugin constraint (§9) forces a slower `integration_test`
  emulator job that today's `build` job doesn't have. Real cost, easy to under-budget.
- **Per-device state doesn't cut over** — DataStore → `shared_preferences` means both people
  re-onboard at cutover (Phase 7 handles it, but it's a sharp edge — the join code lives in
  admin-only `private/config` by then).
- **Offline persistence + rejected-write parity.** "Logging works fully offline" is a hard
  requirement (`product-spec.md §4`, `architecture.md §4`). Verify both the happy path *and*
  the silent-drop-on-rejection path on a real airplane-moded device in Phase 3.
- **PDF fidelity.** `pdf`/`printing` output differs from the hand-rolled renderer — design
  pass in Phase 6.
- **Riverpod caching.** `@riverpod` defaults to autoDispose, the opposite of
  `WhileSubscribed` — see §6. Get `keepAlive` right on `observationsProvider` / `petsProvider`
  or snapshot listeners thrash on navigation.
- **No design phase is budgeted.** The re-platform is a translation, but the admin/member
  read-only screen variants (design brief) and the past-exports list are new surfaces — fold
  their design into the phase that builds them.
- **Effort optimism.** §11's easiest line — "two-person cutover is easy" — is true and is
  *not* the long pole. The long poles are iOS setup and the CI rebuild.

## 12. What does NOT change

- The Firebase project, Firestore data, and `firestore.rules` (as left by `migration.md`).
- The `firestore-tests/` suite (it grows the new §8 cases, but the harness is untouched).
- `HouseholdCode`'s alphabet + length (**but not its RNG** — §3), `ExportFilenames`' scheme,
  `DateTimeUtils`' format choices — port the logic and keep the unit tests green.
- The product: everything in **`product-spec.md §4.0` "what the next release contains"** ships
  at parity — the shipped app's features plus roles, the join-code relocation, and the export
  log. This is a re-platform, not a redesign; the §4.0 "deferred" list is out of scope here.
