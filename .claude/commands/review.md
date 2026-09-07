---
description: Run the pre-merge review on the current worktree and record the verdict.
---

Spawn the `reviewer` subagent against the worktree's diff versus `origin/main`.

Give it, explicitly:

- **The absolute worktree path** (`.claude/worktrees/issue-<n>-<slug>`) and **the branch name**.
  Not "the current worktree" — a subagent starts in *your* cwd, not the worktree, so an implicit
  reference silently points it at the main checkout. That is not hypothetical: on issue #4 the
  mandated `/code-review` pass ran in the main checkout, found `origin/main...HEAD` empty, fell
  back to the tip commit, and reviewed the *previous* change. It never saw the diff under review.
- The issue / brief this change implements (if known) and the planning-doc section to check
  against.

When it returns, check its report states the toplevel and branch it actually reviewed, and that
they match what you handed it. If they don't, the verdict is void — re-run it.

When it returns, report the `Status:` line from `.claude/team/review-verdict.md` and any
`[blocking]` findings. **Do not merge** — merging is a separate Tech Lead step, allowed only once
the verdict is `PASS` *and* `.claude/team/last-green` is fresh (both newer than `HEAD`). The
merge-gate hooks enforce this on `git push`.
