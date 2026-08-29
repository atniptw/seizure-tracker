# Seizure Tracker

A small Android app for logging a household's pets' health events — seizures in detail (what
happened, how long it lasted, recovery time, rescue meds given), plus lightweight health
notes for anything else worth telling the vet — and sharing a clean PDF/CSV report. It also
keeps each pet's profile, maintenance medications, and a shared vet directory. Multiple
people (you, a partner, a petsitter) can log from their own phones; everything syncs through
a free Firebase backend and also works offline (entries sync once you're back online).

This is source code, not an installable app — you'll need to build it yourself in Android
Studio and set up a free Firebase project first. Both are one-time, ~20 minute setups. Full
steps below.

## 1. Create a Firebase project (free)

1. Go to [console.firebase.google.com](https://console.firebase.google.com) and sign in with
   your Google account.
2. Click **Add project**, name it something like "Seizure Tracker", and finish the wizard
   (you can disable Google Analytics — not needed here).
3. In the left sidebar, click **Build → Authentication → Get started**. Under the "Sign-in
   method" tab, enable both **Google** and **Anonymous**. Google sign-in is the default —
   each person's identity is tied to their Google account, so it survives the phone being
   wiped or reset. Anonymous is there as a fallback for anyone who'd rather not attach a
   Google account (e.g. a petsitter); that identity is lost if the app is reinstalled or its
   data cleared.
4. In the left sidebar, click **Build → Firestore Database → Create database**. Choose
   **Start in production mode**, and pick a region close to you.
5. Still in Firestore, go to the **Rules** tab and replace the contents with everything in
   [`firestore.rules`](./firestore.rules) from this project, then click **Publish**. This is
   what keeps one household's data private from another — only devices that know the join
   code can read or write a given dog's data.
6. Click the gear icon next to "Project Overview" → **Project settings**. Under "Your apps",
   click the Android icon to register a new Android app.
   - Android package name: `com.atnip.seizuretracker` (must match exactly — this comes from
     `app/build.gradle.kts` in this project)
   - Nickname: anything, e.g. "Seizure Tracker Android"
   - Add a SHA-1 fingerprint (required for Google Sign-In) — for the debug build, get it by
     running `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey
     -storepass android -keypass android` and pasting the `SHA1:` value. You'll need to add
     another fingerprint later for release builds signed with a different key.
7. Download the `google-services.json` file it offers you, and place it at:
   ```
   SeizureTracker/app/google-services.json
   ```
   (right next to `app/build.gradle.kts`). The project won't build without this file — it's
   what tells the app which Firebase project to talk to.

## 2. Build the app in Android Studio

1. Install [Android Studio](https://developer.android.com/studio) if you don't have it.
2. Open Android Studio → **Open** → select the `SeizureTracker` folder (the one containing
   `settings.gradle.kts`).
3. Let Gradle sync (first sync downloads dependencies, can take a few minutes). If Android
   Studio prompts you to upgrade the Android Gradle Plugin or Gradle version, accepting the
   upgrade is fine.
4. Plug in your phone via USB with **USB debugging** enabled (Settings → About phone → tap
   "Build number" 7 times to unlock Developer options → enable USB debugging), or use an
   emulator.
5. Click the green **Run** button (or Shift+F10). The app installs and launches.

To install on a second phone (partner, petsitter), repeat step 2.4–2.5 with that phone
connected — or build an APK (**Build → Build App Bundle(s) / APK(s) → Build APK(s)**) and
send it to them directly to sideload.

## 3. First run

- The first person opens the app, taps **Set up a household**, enters a household name and
  their own name. This creates the shared household and generates a 6-character join code.
  Add pets afterward from **Manage pets**.
- Anyone else taps **Join with a household code**, enters that code and their own name. Their
  device is now linked to the same household's data.
- The join code is shown on the **Household** screen (copy or share it from there) so you can
  pass it to someone.

## What it does

- **Log a seizure**: date/time, duration, seizure type, a symptom checklist, signs before
  onset, possible triggers, recovery time and notes, whether a rescue medication was given
  (and details), free-text notes, and who logged it.
- **Log a health note**: a lightweight entry for anything else worth telling the vet — free
  text, when it started, notes, an optional photo, and a flag-for-vet toggle.
- **History**: every past entry, most recent first, tap through to view or edit.
- **Pet profiles**: one per pet (name, species, breed, weight, birth date, photo) with that
  pet's list of maintenance medications (name/dose/frequency) — kept separate from the
  "rescue med given during a seizure" field on each entry.
- **Vets**: one shared vet directory per household, with each vet linked to specific pets and
  a role label (general / emergency / neuro / other).
- **Export for vet**: pick which pet, a date range (last 30 / 90 days, all time, or a custom
  range), and whether to include health notes, then share a formatted PDF or a CSV (opens
  fine in Excel/Sheets) straight from your phone's share sheet — text, email, whatever's
  easiest.
- **Offline-friendly**: Firestore caches data locally, so logging works without signal; it
  syncs automatically once you're back online.

## What's intentionally left out (v1)

- **Medication reminders/notifications** — the current-medications list is there for
  reference and for the exported report, but there's no scheduled reminder yet. Could be
  added later with WorkManager if useful.
- **Cloud-stored attachments** — a health note or pet profile can carry one photo, but it's
  kept as a local device reference, not uploaded to Firebase Storage (which needs a billing
  account). A photo therefore isn't automatically on other members' devices. Video isn't
  supported at all.
- **Roles / permissions** — there's no admin vs. member distinction yet. Any member can edit
  anything and remove any other member. The `planning/` docs describe a role split as a
  future change.
- **Rotating or revoking the join code** — removing a member (**Household** screen) drops
  their access immediately, but the join code itself can't be regenerated in-app yet, so a
  removed member who kept the code could re-join. Changing it means editing the `codeIndex`
  and household docs in the Firebase console for now.

## Costs

Firebase's free "Spark" tier comfortably covers this use case (a handful of users, a few
hundred seizure entries) — you shouldn't hit any billing at all for personal use.

## CI/CD

Two GitHub Actions workflows live in `.github/workflows/`:

- **`ci.yml`** — on every push to `main` and every PR: runs the Firestore security rules suite
  (`firestore-tests/`, Node + Jest) and the Kotlin test suite (`app/src/test` — pure unit tests,
  plus repository/ViewModel/Compose UI tests against the Firebase Local Emulator Suite) via
  `firebase emulators:exec`, then `./gradlew build` (compile + lint + assemble). See CLAUDE.md's
  "Tests" section for how these suites are organized and how to run them locally.
- **`release.yml`** — on a pushed tag matching `v*.*.*` (or manually via "Run workflow"),
  builds a release APK and pushes it to Firebase App Distribution's `household` group. The
  release build is currently debug-signed (no release keystore yet) — fine for sideloading
  to testers, not for the Play Store.

Both workflows need `app/google-services.json` and `app/debug.keystore` (both gitignored, so
neither is in the repo) supplied as secrets. The debug keystore secret matters because
GitHub-hosted runners are ephemeral: without a pinned keystore, AGP would generate a fresh
`~/.android/debug.keystore` (and thus a new signing cert) on every run, breaking installs over
prior Firebase App Distribution builds and invalidating Google Sign-In's SHA-1 registration. The
release workflow additionally needs Firebase App Distribution credentials. Set these once as
repo secrets — **Settings → Secrets and variables → Actions**, or via `gh`:

```bash
# contents of your app/google-services.json, used by both workflows
gh secret set GOOGLE_SERVICES_JSON < app/google-services.json

# your local debug keystore, base64-encoded, used by both workflows
gh secret set DEBUG_KEYSTORE_BASE64 --body "$(base64 -i ~/.android/debug.keystore)"

# Firebase console → Project settings → Your apps → App ID (format: 1:...:android:...)
gh secret set FIREBASE_APP_ID

# a service account key (JSON) with the "Firebase App Distribution Admin" role —
# create one under Firebase console → Project settings → Service accounts
gh secret set FIREBASE_SERVICE_ACCOUNT_JSON < path/to/service-account-key.json
```

The `household` group referenced in `release.yml` must exist in Firebase App Distribution
(console → App Distribution → Testers & Groups) before the first release run.
