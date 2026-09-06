---
description: (Phase 2) Build, test, tag, and release to the tester groups.
---

**Phase 2 only.** Requires the `release-manager` persona and the CI signing setup (see memory
`ios-signing-checklist`: GitHub Actions `macos-26`, ASC API key, dart-define flavors). Until
Flutter work starts this is a placeholder — say so.

When active: spawn `release-manager` to run the full build + test, bump the version, tag, push,
and watch CI deploy to Firebase App Distribution (Android, `household` group) and TestFlight
(iOS, internal testers) to completion.
