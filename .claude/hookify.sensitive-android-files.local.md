---
name: warn-sensitive-android-files
enabled: true
event: file
action: warn
conditions:
  - field: file_path
    operator: regex_match
    pattern: google-services\.json$|\.jks$|\.keystore$|key\.properties$|\.p12$
---

**Signing/Firebase config file detected**

This file (Firebase config, release keystore, or `key.properties`) must never be committed:

- Confirm it's covered by `.gitignore` (it should already list `app/google-services.json`,
  `local.properties` — add keystore/`key.properties` patterns if this is a new one)
- Never paste keystore passwords or `key.properties` contents into chat or commit messages
- If a release keystore is lost, the app can never be updated on Play Store under the same
  listing — back it up somewhere outside this git repo (password manager, separate encrypted
  storage), not just on this machine
