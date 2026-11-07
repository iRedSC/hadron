---
name: council
description: Use when the user says "council", or before the final review of a sizable change whose effect is hard to measure.
---

# Council

Adapted from the council skill in Theo Browne's tstack (github.com/t3dotgg/tstack, MIT). The **author** is the agent
running this on its own PR; the **owner** is the person it works for, who decides anything that changes the PR's
purpose.

**Run it only for sizable changes whose effect is hard to measure**: a new subsystem, a cross-cutting refactor, or
behaviour the tests can't fully show. Say in the PR why it was or wasn't run. Every protected PR needs the final review
(`.claude/skills/final-review/SKILL.md`) either way, and that's all the Council check reads.

The order, with every number in `.github/council.toml`:

1. **Questions**: one pass, no follow-ups (`.claude/skills/questions/SKILL.md`).
2. **Council rounds**, below, back and forth until a round finds nothing more serious than `stop_at`, at most
   `max_rounds`.
3. **Explain and clean up**: one pass, no follow-ups (`.claude/skills/explain/SKILL.md`).
4. **Final review** (`.claude/skills/final-review/SKILL.md`).

Before each step, fix what you already know, run the checks the change needs, commit and push.

## A round

Start one delegate per `role = "council"` member, in parallel, with `delegate_task` and the member's `provider`, `model`
and option. If one can't start, say so and stop; the owner changes the seat. They are read-only. Give each the PR URL,
the head SHA, the worktree path, the diff command (`git diff <base-sha>...<head-sha>`, where `<base-sha>` is
`gh pr view <n> --json baseRefOid -q .baseRefOid`, the commit the gate also diffs from), what the change is for, the
"Deferred" and "Answered" lists if there are any, and:

> Read AGENTS.md, docs/design.md and docs/coding-standards.md first. Find at least <min_findings> issues in this
> change, ideally more, and give each a priority by the table in docs/coding-standards.md ("Priority"): the highest
> level whose test it meets. Each names a file and line, what goes wrong, and a suggested fix. You may run builds and
> tests. If you can't find <min_findings> real issues more serious than P4, list P4s to make up the number. Findings
> on the "Deferred" list are known and going to their own PR, and those on the "Answered" list were disputed with
> evidence: don't count either unless you can show the answer is wrong. End with one line:
> `Found: <n>, top P<k>`, where P<k> is your most serious finding.

Record each member's `<n> found, top P<k>` in the round's column. Then, highest priority first:

- **Fix P0 to P2.** Leave P3 for the cleanup and drop P4.
- **A fix must not make the code more complex.** A fix that adds net non-test lines, a new branch or a new concept
  (a rule, a file, an option) is flagged. For a P3, it goes under "Deferred" in the PR, to its own PR, and reviewers
  are told it's known. A P0 to P2 is fixed here even so, and listed under "Complex fixes" so the growth is visible;
  reviewers keep checking it.
- **A finding you disagree with**: say why in the PR, with evidence, and add it to the "Answered" list.

Commit, push, and start the next round on the new head. Rounds must not grow the change: an idea outside the PR's
purpose goes in the PR as a follow-up.

**The council stops** after the first round in which no member finds anything more serious than `stop_at` (P0 is the
most serious). Note each round's results in the PR (`<member>: <n> found, top P<k>`) so the owner can follow it.

**If it hasn't stopped after `max_rounds`, stop changing code.** The findings keep coming because the design is
wrong. Write "Architecture reconsidered" in the PR: every finding grouped by the part of the design it came from, the
root cause they share, at least two designs that would remove it with what each costs (one may be "don't build
this"), and a recommendation. Then wait for the owner. Once the owner picks a design, record the choice in the PR,
build it, and start a new council from Round 1.
