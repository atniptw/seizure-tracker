---
name: run
description: Build, install, and launch the SeizureTracker Android app on a connected device or emulator, and tail its logs. Use whenever asked to run, start, install, or screenshot the app, or to verify a change works in the real app rather than just compiling.
---

# Running SeizureTracker

Single-module Android app (`:app`), package `com.atnip.seizuretracker`, package name doubles as
the applicationId. Compose UI, no XML activities beyond `MainActivity`.

## Prerequisites

The build **requires `app/google-services.json`**, which is gitignored and not present in a
fresh clone. If it's missing, `./gradlew` commands fail at configuration time with a Google
Services plugin error — tell the user to follow README.md section 1 (create a Firebase project,
download the file) rather than trying to work around it.

## 1. Confirm a target device/emulator is available

```bash
adb devices
```

If nothing is listed: start an emulator (`emulator -list-avds` to see options, then
`emulator -avd <name> &`) or ask the user to plug in a device with USB debugging enabled and
re-run `adb devices` until it shows `device` (not `unauthorized`/`offline`).

## 2. Build and install

```bash
./gradlew installDebug
```

This compiles and installs in one step — no separate `assembleDebug` needed unless you only want
the APK without installing.

## 3. Launch

```bash
adb shell am start -n com.atnip.seizuretracker/.MainActivity
```

## 4. Watch logs

```bash
adb logcat --pid=$(adb shell pidof -s com.atnip.seizuretracker)
```

Useful filters while iterating on Firebase/auth code: add `| grep -iE "seizuretracker|firebase|firestore"` since Firebase's own SDK logs are verbose.

## 5. Screenshot (for visual verification)

```bash
adb exec-out screencap -p > /tmp/screen.png
```

## Uninstall / reset local state

`adb uninstall com.atnip.seizuretracker` — useful when testing the first-run/welcome flow, since
household id and display name are stored in on-device DataStore (`UserPrefs`), not reset by a
plain reinstall over the top.
