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
they match what you handed it. Then check it mechanically rather than taking the report's word
for it — the recorded verdict carries the checkout it was written from:

```bash
tail -5 "$(.claude/hooks/team-marker.sh path)/review-verdict.md"
```

`Checkout:` must be the worktree you briefed and `Commit:` the tip you expected. If either is
wrong, the verdict is void — re-run it. This is the cheap version of the issue-#4 catch: a review
that ran in the main checkout records main's path and main's commit, and says so here.

Report the `Status:` line and any `[blocking]` findings. **Do not merge** — merging is a separate
Tech Lead step, allowed only once the verdict is `PASS` *and* `.claude/team/last-green` covers the
same code. `.claude/hooks/team-marker.sh status` tells you where both stand; the merge-gate hooks
enforce it on `git push`.
