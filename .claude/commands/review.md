---
description: Run the pre-merge review on the current worktree and record the verdict.
---

Spawn the `reviewer` subagent against the current worktree's diff versus `origin/main`.

Give it: the issue / brief this change implements (if known) and the planning-doc section to
check against.

When it returns, report the `Status:` line from `.claude/team/review-verdict.md` and any
`[blocking]` findings. **Do not merge** — merging is a separate Tech Lead step, allowed only once
the verdict is `PASS` *and* `.claude/team/last-green` is fresh (both newer than `HEAD`). The
merge-gate hooks enforce this on `git push`.
