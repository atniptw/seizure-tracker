# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Android app (Kotlin + Jetpack Compose) for logging a household's pets' health
events — detailed seizure entries plus lightweight health notes — alongside per-pet profiles,
maintenance medications, and a shared vet directory, and sharing a PDF/CSV report with a vet.
Multiple people share one household's data via Firebase (Auth + Firestore), with offline
support via Firestore's local cache. See `README.md` for the full user-facing setup flow
(creating a Firebase project, enabling Google + Anonymous auth, publishing `firestore.rules`,
adding `google-services.json`).

The `planning/` docs (`architecture.md`, `product-spec.md`, `security-privacy.md`) describe a
larger target state — a Flutter rewrite, a polymorphic `observations` collection, admin/member
roles — that is **not** built. `architecture.md §0` lists the gaps. Two sequenced plans cover
getting there: `migration.md` (move the current Kotlin app onto the target Firestore
shape/rules) then `flutter-migration.md` (re-platform the client to Flutter/Dart for iOS +
Android). Notifications and photo/video attachments are backlogged out of the next release.
Treat all of these as design intent, not a description of the current code.

## Build & run

No lint config beyond Android Gradle Plugin defaults.

```bash
./gradlew assembleDebug          # compile the debug APK
./gradlew installDebug           # build + install on a connected device/emulator
./gradlew build                  # full build (compile + lint + assemble + test)
```

The build **requires `app/google-services.json`** (gitignored, not present in a fresh clone) —
without it, Gradle sync/build fails. See README.md section 1 to generate one against a real or
throwaway Firebase project.

If `java`/`./gradlew` can't find a JDK (some sandboxed shells have no `java` on `PATH` even with
Android Studio installed), point `JAVA_HOME` at Android Studio's bundled JBR:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (macOS path;
adjust for other platforms) and prepend `$JAVA_HOME/bin` to `PATH`.

## Tests

All under `app/src/test` (JVM/Robolectric, no device or AVD needed) except the Firestore rules
suite, which is Node-based since rules can't be exercised from the JVM:

- **Pure unit tests** (`util/`, `ui/ViewModelKeyCollisionTest.kt`) — no Android/Firebase
  dependency, run in milliseconds.
- **Repository/ViewModel integration tests** (`data/repository/`, `ui/session/`,
  `ui/household/`, `ui/seizure/`) — exercise the real repositories/ViewModels (they're `object`
  singletons with no DI seam, so mocking isn't a good fit) against the Firebase Local Emulator
  Suite via Robolectric. See `testutil/FirebaseEmulatorRule.kt`.
- **Compose UI tests** — one per screen package, driven through the real screens on
  emulator-backed ViewModels with Robolectric's `@GraphicsMode(NATIVE)` Compose support:
  `ui/seizure/AddEditSeizureScreenTest.kt` & `SeizureListViewModelTest.kt`,
  `ui/entry/EntryHistoryScreenTest.kt` & `QuickAddSheetTest.kt`, `ui/welcome/WelcomeScreenTest.kt`,
  `ui/dashboard/DashboardScreenTest.kt`, `ui/healthnote/`, `ui/household/`, `ui/pet/`,
  `ui/vet/`, `ui/export/`, `ui/settings/SettingsHubScreenTest.kt`. (Run `find app/src/test -name '*ScreenTest.kt'`
  for the current list.)
- **`firestore-tests/`** (Node + Jest + `@firebase/rules-unit-testing`) — tests `firestore.rules`
  itself against the emulator: household membership, the join-by-code boundary cases, `codeIndex`
  get/list/create/update/delete permissions.

The emulator-backed suites need the Firebase Local Emulator Suite running (`firebase.json` at the
repo root configures it: Firestore on 8080, Auth on 9099) — wrap test runs in
`firebase emulators:exec`, which starts the emulator, runs the command, and tears it down:

```bash
./gradlew test                                                                    # NOT this on its own — fails without the emulator running
firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore,auth "./gradlew test --stacktrace"
cd firestore-tests && npm ci && firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore "npm test"
```

A plain `./gradlew build` right after either of those succeeds without the emulator (Gradle marks
the test tasks UP-TO-DATE rather than re-running them) — that's what CI relies on; see
`.github/workflows/ci.yml`. Emulator-backed tests use a hand-built `FirebaseOptions` (project id
`demo-seizuretracker-rules-test`), not the real `app/google-services.json`, so they need zero
secrets and can't touch a real Firebase project.

The emulator-backed suite runs sequentially in one JVM fork doing real Firestore round-trips, so
on a contended CI runner the slowest few tests occasionally overshoot their `withTimeout` budget
— always a `TimeoutCancellationException`, never a real assertion failure, a different test each
run. `app/build.gradle.kts` handles this with the `org.gradle.test-retry` plugin (retries a
failed test up to 3×, **CI only** — gated on the `CI` env var — so a local flake stays visible)
plus a larger test-fork `maxHeapSize`. If a genuine bug slips through, `maxFailures` (5) still
fails the build fast rather than retrying a real regression 3× per test. Prefer this over
widening the timeout constant again.

## Architecture

Single Gradle module (`:app`), package `com.atnip.seizuretracker`, min/target/compile SDK
26/34/37. No dependency injection framework — repositories are Kotlin `object` singletons called
directly from ViewModels.

```
data/model/       Firestore-mapped data classes — Household, Seizure, HealthNote, Pet,
                   Medication (embedded on Pet), Vet, PetVetLink, MemberProfile
data/repository/  Firestore/Auth access — Auth, Household, Member, Seizure, HealthNote, Pet,
                   Vet, PetVetLink repositories (all Kotlin `object` singletons)
data/local/       UserPrefs (DataStore) — per-device household id + display name, NOT synced
ui/session/       SessionViewModel — resolves auth + local prefs into a SessionState
ui/<feature>/     One package per screen (dashboard, entry, seizure, healthnote, pet, vet,
                   household, export, settings, welcome, accessibility; shared bits in common),
                   each typically a Screen composable + a ViewModel with its own factory
util/             HouseholdCode, DateTimeUtils, PdfExporter, CsvExporter, ExportFilenames
```

### Session/auth flow

`SessionViewModel` (`ui/session/SessionViewModel.kt`) is the app's entry-point state machine,
built in `MainActivity` and consumed by `AppRoot` (`ui/AppRoot.kt`):

- `SessionState.Loading` → checking Firebase auth + local prefs
- `SessionState.NeedsSetup` → not signed in, or signed in but no household/display name saved
  locally yet → shows `WelcomeScreen` (sign in, then create or join a household)
- `SessionState.Ready(householdId, uid, displayName)` → main app (`MainNavHost` in `AppRoot.kt`)

Two sign-in paths in `AuthRepository`, both landing on a Firestore-rules-checked uid: Google
(via Credential Manager, `R.string.default_web_client_id` from `google-services.json`) and
Anonymous. Anonymous uids don't survive a reinstall; Google uids do.

### Household data model & security rules

A "household" is the shared unit for one family's pets (`data/model/Household.kt`).
`firestore.rules` enforces: only uids listed in a household's `members` array can read/write
that household or any of its subcollections (`seizures`, `healthNotes`, `pets`, `vets`,
`petVetLinks`, and the `members` metadata subcollection); anyone signed in can create a
household or add *themselves* (and only themselves) to `members` (the join flow). There are no
roles — every member has identical write access, and any member may delete another member's
profile doc (the remove-member flow). A separate top-level `codeIndex/{code}` collection maps
a 6-char human-typed join code (`util/HouseholdCode.kt`) to a household id — it's readable by any
signed-in device by exact document id (never by list/query) so a device can resolve a code before
it has joined and thus before it can read the household doc itself. If you change any household
subcollection's document shape, or the join flow, check whether `firestore.rules` needs a matching
change — Firestore rejects writes that don't satisfy the deployed rules regardless of what the
app code does.

### ViewModel scoping gotcha

`HouseholdViewModel` and `SeizureListViewModel` are created in `AppRoot` with an explicit
`viewModel(key = ..., factory = ...)`. The key **must be unique per ViewModel type**, not just
per household — e.g. `"household_$householdId"` vs `"seizureList_$householdId"`. Two
`viewModel()` calls sharing a key collide in the same `ViewModelStore`: the second call's `put()`
silently clears whatever the first stored under that key. (This caused a real bug — household
data never loading — see git history.) Follow the same per-type-prefixed key pattern for any new
ViewModel added at this level.

### Data flow pattern

Repositories expose Firestore state as `Flow` (via `callbackFlow` + `addSnapshotListener`) for
live data, or `suspend fun` for one-shot reads/writes. ViewModels turn the live flows into
`StateFlow` with `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)` and
expose plain functions (`addSeizure`, `updateDogProfile`, etc.) that launch a coroutine in
`viewModelScope` and call the repository. Screens are stateless composables that take a
ViewModel and a `NavController`; navigation routes/args live in `ui/navigation/Destinations.kt`.

### Export

`util/PdfExporter.kt` renders a paginated PDF by hand with Android's built-in `PdfDocument` (no
third-party PDF library, deliberately — see comment in that file). `util/CsvExporter.kt` handles
the CSV path. Both write to `context.cacheDir` and return a `content://` URI via the
`FileProvider` declared in `AndroidManifest.xml` (`res/xml/file_paths.xml`), for handing off to
the share sheet.

## Notes on dependency versions

`app/build.gradle.kts` declares dependency versions directly (no version catalog usage) —
`gradle/libs.versions.toml` exists but is a leftover from the original project scaffold and is
largely unused; don't assume it reflects actual dependency versions in use.
