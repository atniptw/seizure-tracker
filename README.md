# Seizure Tracker

A small Android app for logging your dog's seizures — what happened, how long it lasted,
recovery time, medications given — and sharing a clean PDF/CSV report with your vet. Multiple
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
   method" tab, enable **Anonymous**. This is the only auth method the app uses — nobody has
   to create an account or password; each phone is just recognized as "a device that knows
   the household code."
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
   - You can skip the SHA-1 field — it's not needed since this app doesn't use Google Sign-In.
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

- The first person opens the app, taps **Set up a new dog**, enters the dog's name and their
  own name. This creates the shared household and generates a 6-character join code.
- Anyone else taps **Join with a household code**, enters that code and their own name. Their
  device is now linked to the same dog's data.
- The join code is always visible on the Dashboard and on the Dog profile screen, so you can
  read it out or copy it to send to someone.

## What it does

- **Log a seizure**: date/time, duration, seizure type, a symptom checklist, signs before
  onset, possible triggers, recovery time and behavior, whether a rescue medication was given
  (and details), free-text notes, and who logged it.
- **History**: every past entry, most recent first, tap through to view or edit.
- **Dog profile**: breed, weight, vet contact info, and a list of current maintenance
  medications (name/dose/frequency) — kept separate from the "rescue med given during a
  seizure" field on each entry.
- **Export for vet**: pick Last 30 days / Last 90 days / All time, then share a formatted PDF
  or a CSV (opens fine in Excel/Sheets) straight from your phone's share sheet — text, email,
  whatever's easiest.
- **Offline-friendly**: Firestore caches data locally, so logging works without signal; it
  syncs automatically once you're back online.

## What's intentionally left out (v1)

- **Medication reminders/notifications** — the current-medications list is there for
  reference and for the exported report, but there's no scheduled reminder yet. Could be
  added later with WorkManager if useful.
- **Video/photo attachments** — not included, to keep the free Firebase tier simple (video
  would need Firebase Storage, which has its own quota).
- **Removing a household member** — anyone with the code can join, but there's currently no
  in-app way to revoke a device's access. If that's ever needed, it'd mean editing the
  `members` list directly in the Firebase console for now.

## Costs

Firebase's free "Spark" tier comfortably covers this use case (a handful of users, a few
hundred seizure entries) — you shouldn't hit any billing at all for personal use.
