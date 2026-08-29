---
name: firestore-rules-tests
enabled: true
event: file
action: warn
conditions:
  - field: file_path
    operator: regex_match
    pattern: (^|/)firestore\.rules$
---

**`firestore.rules` changed — exercise the rules suite before pushing**

This file is the entire security boundary for shared household health data (who can read/write
a household, its `seizures` subcollection, and the `codeIndex` join-code mapping). CI runs the
suite on push, but catch regressions locally first:

```
cd firestore-tests && firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore "npm test"
```

If you added or reshaped a collection/field path, add both a positive test (a member can
read/write) and a negative test (a non-member is denied) in `firestore-tests/rules.test.js`
before pushing. Also check whether the household/seizure document shape or the join flow in the
app needs a matching change (see CLAUDE.md → "Household data model & security rules").
