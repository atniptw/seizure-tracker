---
description: Reconcile the GitHub backlog against the planning docs.
---

Spawn the `backlog-owner` subagent to reconcile GitHub Issues against `planning/*.md`:

- new issues implied by the docs that aren't tracked
- open issues that contradict or lag the current docs
- epics that need splitting
- milestone / label corrections

If issue creation is on hold (Tom still reviewing plans), it returns a **proposed** issue list
rather than creating anything. Relay its report and the proposed changes.
