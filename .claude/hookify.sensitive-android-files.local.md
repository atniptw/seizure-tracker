---
name: warn-sensitive-android-files
enabled: true
event: file
action: warn
conditions:
  - field: file_path
    operator: regex_match
    pattern: google-services\.json$|GoogleService-Info\.plist$|\.jks$|\.keystore$|key\.properties$|\.p12$|\.p8$|\.mobileprovision$|ExportOptions\.plist$
---

**Signing / platform config file detected**

This file (Firebase config, release keystore, `key.properties`, or an iOS signing artifact —
`GoogleService-Info.plist`, an App Store Connect API key `.p8`, a `.mobileprovision`,
`ExportOptions.plist`) must never be committed:

- Confirm it's covered by `.gitignore` (it already lists `app/google-services.json`,
  `local.properties`, keystore/`key.properties`/`.p12` patterns, and the iOS patterns —
  extend it if this is a new shape)
- Never paste keystore passwords, `key.properties`, or API-key contents into chat or commit messages
- If a release keystore is lost, the app can never be updated on the Play Store under the same
  listing — back it up outside this git repo (password manager, separate encrypted storage). The
  ASC API key `.p8` downloads only once from Apple; keep a backup the same way.
