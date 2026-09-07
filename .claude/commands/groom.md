---
description: Reconcile the GitHub backlog against the planning docs.
---

Spawn the `backlog-owner` subagent to reconcile GitHub Issues against `planning/*.md`:

- new issues implied by the docs that aren't tracked
- open issues that contradict or lag the current docs
- epics that need splitting
- milestone / label corrections

`backlog-owner` creates and edits issues directly (the hold was lifted 2026-09-07). Relay its
report and the changes it made.
