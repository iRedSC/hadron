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

1. **Questions**: one pass, no follow-ups (`.claude/skills/questions/SKILL.md`).
2. **Council rounds**, below, back and forth until a round finds nothing more serious than `stop_at`, at most
   `max_rounds`.
3. **Explain and clean up**: one pass, no follow-ups (`.claude/skills/explain/SKILL.md`).
4. **Final review** of the head, below: as many passes as it takes.

Before each step, fix what you already know, run the checks the change needs, commit and push.

## A round

Start one delegate per `role = "council"` member, in parallel, with `delegate_task` and the member's `provider`, `model`
and option. If one can't start, say so and stop; the owner changes the seat. They are read-only. Give each the PR URL,
the head SHA, the worktree path, the diff command (`git diff <base-sha>...<head-sha>`, where `<base-sha>` is
`gh pr view <n> --json baseRefOid -q .baseRefOid`, the commit the gate also diffs from), what the change is for, the
"Deferred" list if there is one, and:

> Read AGENTS.md, docs/design.md and docs/coding-standards.md first. Find at least <min_findings> issues in this
> change, ideally more, and give each a priority by the table in docs/coding-standards.md ("Priority"): the highest
> level whose test it meets. Each names a file and line, what goes wrong, and a suggested fix. You may run builds and
> tests. If you can't find <min_findings> real issues more serious than P4, list P4s to make up the number. Findings
> on the "Deferred" list are known and going to their own PR: don't count them. End with one line:
> `Found: <n>, top P<k>`, where P<k> is your most serious finding.

Record each member's `<n> found, top P<k>` in the round's column. Then, highest priority first:

- **Fix P0 to P2.** Leave P3 for the cleanup and drop P4.
- **A fix must not make the code more complex.** A fix that adds net non-test lines, a new branch or a new concept
  (a rule, a file, an option) is flagged. For a P3, it goes under "Deferred" in the PR, to its own PR, and reviewers
  are told it's known. A P0 to P2 is fixed here even so, and listed under "Complex fixes" so the growth is visible;
  reviewers keep checking it.
- **A finding you disagree with**: say why in the PR, with evidence.

Commit, push, and start the next round on the new head. Rounds must not grow the change: an idea outside the PR's
purpose goes in the PR as a follow-up.

**The council stops** after the first round in which no member finds anything more serious than `stop_at` (P0 is the
most serious). Its head is the `Council commit`.

**If it hasn't stopped after `max_rounds`, stop changing code.** The findings keep coming because the design is
wrong. Write "Architecture reconsidered" in the PR: every finding grouped by the part of the design it came from, the
root cause they share, at least two designs that would remove it with what each costs (one may be "don't build
this"), and a recommendation. Then wait for the owner. Once the owner picks a design, record the choice in the PR,
build it, and start a new council: its record replaces the old table, from Round 1.

## Final review

Once the cleanup is pushed, start the `role = "final"` member on the head with the PR URL, the head SHA, the worktree,
the diff from the `Council commit` to the head, and:

> Read AGENTS.md and docs/coding-standards.md first. This is the last look at a change after a council review and a
> cleanup. Check the change as it stands, and especially that the cleanup changed no behaviour the council reviewed.
> List any findings with a priority by docs/coding-standards.md ("Priority"), file and line. End with one line:
> `Found: <n>, top P<k>`, where P<k> is your most serious finding (`Found: 0, top P4` if none).

If it finds anything more serious than `stop_at`, fix it, push, and run another pass on the new head. Record the last
pass: `Final review: <name>, <full head SHA>, <n> found, top P<k>`.

## The record

Put this in the PR description, before the model and harness line. Edit the description; don't post a comment. Every
line is what the delegates returned; never invent a result.

```markdown
## Council review

Council commit: <full SHA of the last round's head>
Questions: Claude Haiku 5.5, GPT-6.1 Terra
Explained: Claude Haiku 5.5 (explainer), GPT-6 Luna
Final review: GPT-6.1 Sol (final), <full head SHA>, 1 found, top P4

| Member | Round 1 | Round 2 |
| --- | --- | --- |
| GPT-6.1 Sol | 5 found, top P1 | 3 found, top P3 |
| Claude Opus 5.5 | 4 found, top P2 | 4 found, top P4 |
```

Names must match `council.toml`.
