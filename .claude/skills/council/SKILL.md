---
name: council
description: Use when the user says "council", or when a Hadron PR touches a path in .github/council-paths.txt and its Council check is red.
---

# Council

Adapted from the council skill in Theo Browne's tstack (github.com/t3dotgg/tstack, MIT). A PR touching a path in
`.github/council-paths.txt` merges only once its description holds a finished record, which the Council check
(`.github/scripts/council_gate.py`, run from main's copy) reads. The **author** is the agent running this on its own
PR; the **owner** is the person it works for, who decides anything that changes the PR's purpose.

The order, with every number in `.github/council.toml`:

1. **Questions**: one pass (`.claude/skills/questions/SKILL.md`).
2. **Council rounds**, below, until a round finds nothing above `stop_at`, or `max_rounds`.
3. **Explain and clean up** (`.claude/skills/explain/SKILL.md`), with a final review if the cleanup was large.

Before each step, fix what you already know, run the checks the change needs, commit and push.

## A round

Start one delegate per `role = "council"` member, in parallel, with `delegate_task` and the member's `provider`, `model`
and option. If one can't start, say so and stop; the owner changes the seat. They are read-only. Give each the PR URL,
the head SHA, the worktree path, the diff command (`git diff origin/main...<sha>`), what the change is for, and:

> Read AGENTS.md, docs/design.md and docs/coding-standards.md first. Find at least <min_findings> issues in this
> change, ideally more, and give each a priority by the table in docs/coding-standards.md ("Priority"): the highest
> level whose test it meets. Each names a file and line, what goes wrong, and a suggested fix. You may run builds and
> tests. If you can't find <min_findings> real issues above P4, list P4s to make up the number. End with one line:
> `Found: <n>, top P<k>`.

Record each member's `<n> found, top P<k>` in the round's column. Then, highest priority first:

- **Fix P0 to P2.** Leave P3 for the cleanup and drop P4.
- **A fix must not make the code more complex.** A fix that adds net non-test lines, a new branch or a new concept
  (a rule, a file, an option) is flagged under "Flagged complexity" in the PR and goes in its own PR instead, unless it
  fixes a P0 or P1. Then it's fixed here and flagged.
- **A finding you disagree with**: say why in the PR, with evidence.

Commit, push, and start the next round on the new head. Rounds must not grow the change: an idea outside the PR's
purpose goes in the PR as a follow-up.

**The council stops** after the first round in which no member finds anything above `stop_at`. Its head is the
`Council commit`.

**If it hasn't stopped after `max_rounds`, stop changing code.** The findings keep coming because the design is
wrong. Write "Architecture reconsidered" in the PR: every finding grouped by the part of the design it came from, the
root cause they share, at least two designs that would remove it with what each costs (one may be "don't build
this"), and a recommendation. Then wait for the owner.

## The record

Put this in the PR description, before the model and harness line. Edit the description; don't post a comment. Every
line is what the delegates returned; never invent a result.

```markdown
## Council review

Council commit: <full SHA of the last round's head>
Questions: Claude Haiku 5.5, GPT-6.1 Terra
Explained: Claude Haiku 5.5 (explainer), GPT-6 Luna

| Member | Round 1 | Round 2 |
| --- | --- | --- |
| GPT-6.1 Sol | 5 found, top P1 | 3 found, top P3 |
| Claude Opus 5.5 | 4 found, top P2 | 4 found, top P4 |
```

Names must match `council.toml`. After a large cleanup, the explain skill adds the `Final review:` line.
