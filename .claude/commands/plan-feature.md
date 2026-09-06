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
   - If issue creation is **not** on hold: spawn `backlog-owner` to create them.
   - If it **is** on hold: present the proposed set for Tom to approve.
3. Create a worktree for the top slice — `EnterWorktree` with name `issue-<n>-<slug>` (or a
   descriptive slug while issues are on hold).
4. Write the brief for `flutter-dev`: scope, acceptance criteria, the planning-doc pointer, and
   any decisions already made.

Then stop. Do not start implementation until Tom says go.
