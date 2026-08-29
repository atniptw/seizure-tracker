---
name: protect-main-history
enabled: true
event: bash
action: block
conditions:
  - field: command
    operator: regex_match
    pattern: git\s+push\b.*(\s-f\b|\s--force\b|\s--force-with-lease\b|\s\+(refs/heads/)?(main|master)\b|\s:(refs/heads/)?(main|master)\b)|git\s+branch\s+-D\s+(main|master)\b
---

**Blocked: rewriting or deleting `main` history**

This command force-pushes, force-updates, or deletes `main` (or `master`). `main` is the
shared, CI-gated, release-tagged branch for this repo — a force-push there rewrites history
other clones and the release pipeline depend on, and branch protection won't stop a force-push
issued from the CLI.

If you genuinely need to undo a bad commit on `main`, prefer a forward fix: `git revert <sha>`
then a normal `git push origin main`. If a history rewrite is truly required, do it by hand
outside Claude after confirming with the user.
