---
description: Break a feature request into issues + a worktree, ready to hand to flutter-dev.
argument-hint: <short description of the feature or fix>
---

Plan the work for: **$ARGUMENTS**

Acting as Tech Lead:

1. Spawn the `Plan` subagent for an implementation breakdown against the current codebase and the
   relevant `planning/*.md` section.
2. Turn the breakdown into a proposed issue set (parent + sub-issues) with `type:` / `area:`
   labels and a milestone per `planning/claude-dev-team.md`.
   Spawn `backlog-owner` to create them.
3. Create a worktree for the top slice — `EnterWorktree` with name `issue-<n>-<slug>`.
4. Write the brief for `flutter-dev`: scope, acceptance criteria, the planning-doc pointer, and
   any decisions already made. **Open it with the absolute worktree path and the branch name**,
   and require the agent to confirm both back in its report. A subagent inherits your cwd, not
   the worktree, so "work in the worktree" without a path lands it in the main checkout — the
   failure that voided the `/code-review` pass on issue #4.

Then stop. Do not start implementation until Tom says go.
