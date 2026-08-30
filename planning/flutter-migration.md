# Flutter Client Migration Plan — Kotlin/Compose → Flutter/Dart

**Status:** draft for discussion · **Last updated:** 2026-08-30
**Companion docs:** `architecture.md` (target stack, §0 gap list), `migration.md` (backend /
data-model move — a prerequisite, see §2), `product-spec.md` (features), `security-privacy.md`
(roles, join mechanics)

## 1. Scope

Rewrite the shipped Android app (Kotlin + Jetpack Compose, single Gradle module, ~5,700 LOC
across 72 files) as a Flutter app targeting **iOS and Android** from one codebase, against the
**same Firebase project** and the **post-`migration.md` Firestore shape**. The backend, the
Security Rules, and `firestore-tests/` do not change here — this is a client-only rewrite.

**In scope:** every screen and feature the shipped app has today (§7 inventory), ported to
Flutter with feature parity, plus iOS as a new platform.

**Out of scope (unchanged from the other docs):**

- Photo/video attachments — backlogged (`architecture.md` §8).
- Household notifications — backlogged (`architecture.md` §5).
- Web dashboard — `architecture.md` §2 lists it as "if built"; not now.
- Any Firestore shape or rules change — that's `migration.md`, and it lands first.

## 2. Ordering: backend migration first, then this

`migration.md` moves the **current Kotlin app** onto the target Firestore shape
(`observations` collection, `members/{uid}.role`, `private/config` join code, medications
subcollection, export log). That work is designed so this rewrite is "a near-pure client
rewrite against a backend that already looks like its target" (`migration.md` §1).

**Sequence:**

1. Execute `migration.md` Phases 1–5 on the Kotlin app; verify on both phones.
2. Keep using the Kotlin app day-to-day while the Flutter app is built (weeks). It stays the
   working fallback.
3. Build the Flutter app against the migrated shape (this document).
4. Cut both phones over to Flutter (§8). Retire the Kotlin app.
5. **Then** run `migration.md` §8 cleanup (drop legacy `seizures`/`healthNotes` collections,
   the `code` field, embedded meds). Doing it earlier removes the Kotlin fallback.

Rewriting Flutter against the *old* shape and migrating data twice is the thing this ordering
avoids. The only cost is that `migration.md`'s Phase-3 app changes (the `Observation` model in
Kotlin) get thrown away ~2 months later — acceptable; they're the smallest way to de-risk the
data move, and they keep the Kotlin app usable in the interim.

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
| Join code | `HouseholdCode` (hand-rolled alphabet) | port the algorithm verbatim, keep its unit test |

## 4. Packages (intent — pin exact versions at scaffold time)

- **firebase_core, firebase_auth, cloud_firestore** — FlutterFire, configured via
  `flutterfire configure` (generates `firebase_options.dart`).
- **google_sign_in** — Google auth on both platforms.
- **flutter_riverpod** + **riverpod_annotation** / **riverpod_generator** (codegen for
  `.family` providers).
- **freezed** + **json_serializable** — immutable models, sealed `ObservationDetails` union,
  Firestore (de)serialization.
- **go_router** — routing.
- **shared_preferences** — per-device settings.
- **pdf** + **printing** — PDF report.
- **csv** — CSV report (optional).
- **share_plus** — hand a file to the vet / another app.
- **intl** — date/number formatting.
- Dev: **build_runner**, **fake_cloud_firestore** (unit-level repo tests),
  **flutter_test** / **integration_test**, **mocktail** if needed.

## 5. Data layer

**Models** (`lib/data/model/`) — one `freezed` class each, all with
`fromFirestore`/`toFirestore` via `withConverter`:

- `Household` — `name`, `memberIds`, `createdAt` (no `code` field post-migration).
- `MemberProfile` — `uid`, `displayName`, `authMethod`, `role` (`admin`|`member`),
  `joinedAt`, `lastActiveAt`.
- `Pet` — `name`, `species`, `breed`, `weightKg`, `birthDate` (the post-`migration.md`
  shape: no embedded `medications`, no `photoUri`). `architecture.md` §3 also lists
  `diagnosisDate` and `archived` on the target pet doc — neither is in the data today;
  add them here only if the product wants them, otherwise leave for later.
- `Medication` — now its own doc: `name`, `dose`, `frequency`, `notes`, `active`,
  `startDate`, `endDate`.
- `Vet`, `PetVetLink` — unchanged in spirit.
- **`Observation`** — the envelope: `id`, `type`, `petId`, `loggedByUid`, `loggedByName`,
  `occurredAt`, `createdAt`, `updatedAt`, `summary`, `details`.
- **`ObservationDetails`** — `freezed` union: `ObservationDetails.seizure(...)` (duration,
  type, symptoms, preSeizureSigns, triggers, recoveryMinutes, recoveryNotes, rescueMedGiven,
  rescueMedDetails, notes) and `ObservationDetails.note(description, notes)`. New `type`s add
  a union case, nothing else (`architecture.md` §3).

**The `Entry` sealed merge type is deleted.** Today `ui/common/Entry.kt` merges two
collections client-side because Firestore can't `ORDER BY` across them. Post-migration there
is one `observations` collection — `orderBy('occurredAt', descending: true)` server-side,
filter by `type` in Dart. This removes a whole class of client bookkeeping.

**Repositories** (`lib/data/repository/`) — one class per collection
(`ObservationRepository`, `PetRepository`, `MedicationRepository`, `VetRepository`,
`PetVetLinkRepository`, `MemberRepository`, `HouseholdRepository`, `AuthRepository`). Each
exposes `Stream<List<X>>` (from `.snapshots()`) for live data and `Future` methods for
writes. Exposed as `Provider`s; no singletons.

## 6. State layout (Riverpod)

- `authRepositoryProvider`, `firestoreProvider` — leaf providers.
- `sessionProvider` (`AsyncNotifier<SessionState>`) — mirrors `SessionViewModel`: resolves
  `FirebaseAuth` + `UserPrefs` into `Loading` / `NeedsSetup` / `Ready(householdId, uid,
  displayName)`. Holds the `signInWithGoogle` / `signInAnonymously` / `createHousehold` /
  `joinHousehold` / `signOut` actions.
- `householdIdProvider` — derived from `sessionProvider`, throws/guards if not `Ready`.
- Family stream providers keyed by household id:
  `observationsProvider(householdId)`, `petsProvider`, `medicationsProvider(petId)`,
  `vetsProvider`, `petVetLinksProvider`, `membersProvider`.
- `activePetProvider` — combines `petsProvider` + `UserPrefs.activePetId`, self-healing to
  the oldest pet (same logic as `PetListViewModel.activePet` today).
- `currentMemberProvider` / `isAdminProvider` — `membersProvider` + `uid` → the caller's
  `role`. Gates every management action and the admin-only UI (`security-privacy.md` §4.1).
- `a11yProvider` — independent of session, like `AccessibilityViewModel` today (applies to
  the welcome/loading screens too).

**The `ViewModelKeyCollisionTest` gotcha disappears.** `AppRoot.kt` needs hand-managed
`viewModel(key = "type_$householdId")` keys because two `viewModel()` calls with the same key
collide. Riverpod providers are identified by provider identity + family argument — there is
no shared `ViewModelStore` to collide in. Drop the workaround; don't port the test.

## 7. Screen inventory

20 screens + ~15 shared widgets. Complexity: **S** = layout + form, **M** = form with
pickers/derived state, **L** = significant logic.

| Area | Shipped screen | Flutter route | Notes | Size |
|---|---|---|---|---|
| Onboarding | `WelcomeScreen` | `/welcome` | Sign-in (Google/anon) → create or join household. `sessionProvider` drives it. | M |
| Dashboard | `DashboardScreen` | `/` | Days-since-last-seizure, count, recent entries, frequency trend, all-pets view. Reads `observationsProvider` + `activePetProvider`. (No "mention at next vet visit" — dropped.) | L |
| History | `EntryHistoryScreen` | `/history` | List grouped by month; filters (pet/type/date/logger) are a design-brief *new* item — match shipped behavior, then add. One collection now → simpler either way. | M |
| Quick add | `QuickAddSheet` | bottom sheet | Entry-type picker → seizure or note. **Must not add a tap to seizure logging** (`product-spec.md` §5). | S |
| Seizure | `AddEditSeizureScreen` | `/seizure/new`, `/seizure/:id/edit` | Biggest form: date/time, duration, type dropdown, symptom chips, recovery, rescue-med toggle, notes. | L |
| Seizure | `SeizureDetailScreen` | `/seizure/:id` | Read-only detail. ("Compare to similar past entries" is a design-brief *new* item, not shipped — port at parity, add later.) | S |
| Health note | `AddEditHealthNoteScreen` | `/note/new/:petId`, `/note/:id/edit` | Deliberately minimal: text, when, notes. | S |
| Pets | `ManagePetsScreen` | `/pets` | List + add + delete (shipped app hard-deletes; `architecture.md` §3 wants archive instead — product call). Admin-gated. | S |
| Pets | `AddEditPetScreen` | `/pets/new`, `/pets/:id/edit` | Profile form + medications (now a subcollection) + linked vets. Admin-gated. | M |
| Pets | `PetSwitcherSheet` | bottom sheet | Set `activePetId`. Not admin-gated (per-device pref). | S |
| Vets | `VetsDirectoryScreen` | `/vets` | Shared directory; client-side "which pets" filter. | S |
| Vets | `VetDetailScreen` | `/vets/new`, `/vets/:id`, `/vets/new-for-pet/:petId` | Add/edit vet; the `linkToPetId` one-flow variant. Admin-gated. | M |
| Vets | `LinkVetSheet` | bottom sheet | Link existing vet to a pet with a role. | S |
| Household | `HouseholdScreen` | `/household` | Member list (everyone); join code + rotate + remove-member (admin only). | M |
| Household | `RemoveMemberDialog` | dialog | Confirm + code-rotation prompt. | S |
| Settings | `SettingsHubScreen` | `/settings` | Hub: display name, active pet, links out. | S |
| Settings | `AccessibilityScreen` | `/settings/accessibility` | High-contrast / larger-text / reduce-motion toggles → `a11yProvider`. | S |
| Export | `ExportScreen` | `/export` | Pet, date range, include-types, format. Admin-gated (`architecture.md` §7). Writes an `exportLog` doc on success. | M |
| Export | `ExportReadyScreen` | `/export/ready` | Share / save the generated file. | S |
| — | `LoadingScreen` | — | `sessionProvider` loading state. | S |

**Shared widget library** (`lib/ui/common/`) — port first, in Phase 0: `EntryCard`,
`EntryTypeTag`, `RoleTag`, `AvatarInitial`, `PillButton`, `PillChipSelector`,
`SegmentedControl`, `SectionHeader`, `ListRow`, `LabeledTextField`, `ConfirmDialog`,
`AppBottomSheet`. Most are 20–40 lines and map cleanly to a Flutter widget.

## 8. Phasing

Each phase is a mergeable chunk. The Kotlin app keeps running throughout (§2).

- **Phase 0 — scaffold.** `flutter create` (org `com.atnip.seizuretracker`), add packages,
  `flutterfire configure` against the existing project, wire the Firebase emulator for tests,
  set up `flutter analyze` + CI (§10). Port the theme (`AppColors`, `Type`, `Shape`) and the
  shared widget library. `go_router` skeleton with placeholder screens. No Firestore yet.
- **Phase 1 — data layer, no UI.** All `freezed` models, all repositories + providers,
  exercised against the emulator with tests mirroring the current
  `data/repository/*RepositoryTest.kt` suite. This is the foundation; get it solid.
- **Phase 2 — auth + onboarding.** `sessionProvider`, `AuthRepository`, `WelcomeScreen`,
  the `Loading`/`NeedsSetup`/`Ready` gate. Reach a signed-in empty shell on both platforms.
- **Phase 3 — the logging core.** Dashboard, quick-add, add/edit seizure, add/edit health
  note, entry history, seizure detail. This is the app's reason to exist — do it first among
  features, and verify offline logging (`product-spec.md` §5) on a real device early.
- **Phase 4 — pets & vets.** Manage pets, add/edit pet + medications subcollection, pet
  switcher, vet directory, vet detail, link-vet sheet. Admin gating throughout.
- **Phase 5 — household & settings.** Household screen (member list, code, rotate, remove),
  settings hub, accessibility screen.
- **Phase 6 — export.** `pdf`/`printing` report, CSV, share/save, `exportLog` write. Expect
  the PDF to look different from the hand-rolled one — verify on-device (same as today; the
  current `PdfExporter` has no unit test for the same reason).
- **Phase 7 — parity sweep & cutover.** Walk `product-spec.md` §4 feature by feature against
  both apps. Build signed iOS + Android. Install on both phones, confirm history/dashboard/
  export. Retire the Kotlin app.
- **Phase 8 — cleanup.** Run `migration.md` §8 (drop legacy collections/fields). Archive the
  `app/` Gradle module (keep it in git history; move it to `legacy-android/` or delete).
  Update `README.md` and `CLAUDE.md` to describe the Flutter app as current.

## 9. Testing

Mirrors the current three-tier split (see `CLAUDE.md` "Tests"):

- **Pure Dart unit tests** — model (de)serialization (`freezed`/json round-trips), and the
  ported pure logic: `filterExportEntries`, `HouseholdCode`, `DateTimeUtils`,
  `ExportFilenames`. Port the existing assertions (`util/*Test.kt`) case-for-case.
- **Repository/provider tests against the Firebase emulator** — one-to-one with the current
  `data/repository/*RepositoryTest.kt` and the ViewModel tests. Wrap in
  `firebase emulators:exec --only firestore,auth "flutter test integration_test/..."`.
  `fake_cloud_firestore` covers the fast inner-loop cases; the emulator covers rules-adjacent
  behavior.
- **Widget tests** — key screens (add/edit seizure, welcome, dashboard, history, export,
  household) driven with a `ProviderScope` whose repository providers are overridden with
  emulator-backed or fake instances — the direct analogue of the current Robolectric
  `@GraphicsMode(NATIVE)` Compose tests, and easier because Riverpod gives a real override
  seam (the Kotlin `object` singletons don't — `CLAUDE.md` notes mocking "isn't a good fit").
- **`firestore-tests/`** — unchanged. Rules are language-independent; that Node/Jest suite
  stays exactly as-is and remains the authority on `firestore.rules`.

## 10. Build, CI, release

- **`firebase_options.dart`** from `flutterfire configure` becomes the config source;
  `google-services.json` / `GoogleService-Info.plist` are still emitted for the native
  sub-builds. CI writes all three from secrets (extend the current `GOOGLE_SERVICES_JSON`
  pattern in `ci.yml`).
- **CI (`ci.yml`)** — replace the Android-only `build` job: `flutter analyze`, `flutter
  test` (emulator-wrapped for the integration tier), `flutter build apk` and `flutter build
  ios --no-codesign`. **Keep the `firestore-tests` job unchanged.** iOS build steps need a
  `macos-latest` runner.
- **Release (`release.yml`)** — Android: `flutter build appbundle`/`apk` → keep Firebase App
  Distribution `household` group. iOS: `flutter build ipa` → TestFlight (internal testers).
  Same `v*.*.*` tag trigger.
- **iOS is new surface with real setup cost:** an Apple Developer account ($99/year), a
  bundle id, signing certs + provisioning profiles, and a macOS CI runner for builds. This
  is the one part of the plan that isn't just "translate existing code."

## 11. Risks & watch-items

- **iOS platform work** (above) — the largest unknown; nothing about it exists today.
- **Offline persistence parity.** "Logging works fully offline" is a hard requirement
  (`product-spec.md` §5, `architecture.md` §4). FlutterFire enables Firestore persistence by
  default on mobile, but verify the write-queue-and-optimistic-UI behavior on a real airplane
  -mode device in Phase 3, not at the end.
- **PDF fidelity.** Swapping the hand-rolled renderer for `pdf`/`printing` changes the
  output's look. Budget a design pass on the report in Phase 6.
- **Riverpod caching semantics.** `stateIn(WhileSubscribed(5000))` today keeps a stream warm
  5s after the last listener. The Flutter equivalent is `.autoDispose` + a `KeepAliveLink`
  with a timer, or a deliberate non-autoDispose provider. Pick per-provider; don't let
  snapshot listeners thrash on navigation.
- **`go_router` vs the deep-ish nav graph.** The current graph has parameterized routes and
  a couple of "same screen, different entry mode" cases (`ADD_VET_FOR_PET`, add-vs-edit).
  Model these as query/path params, not separate widgets.
- **Two-person cutover is easy** (both phones, in person — same as `migration.md` §2) — but
  there's no staged rollout, so Phase 7 parity checking has to be real.

## 12. What does NOT change

- The Firebase project, Firestore data, and `firestore.rules` (as left by `migration.md`).
- The `firestore-tests/` suite.
- `HouseholdCode`'s alphabet/algorithm, `ExportFilenames`' scheme, `DateTimeUtils`' format
  choices — port the logic verbatim and keep the unit tests green.
- The product: every feature in `product-spec.md` §4 ships at parity. This is a re-platform,
  not a redesign.
