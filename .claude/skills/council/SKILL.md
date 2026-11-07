---
name: council
description: Use when the user says "council", or when a Hadron PR touches a path in .github/council-paths.txt and its Council check is red.
---

# Council

Adapted from the council skill in Theo Browne's tstack (github.com/t3dotgg/tstack, MIT). A council reviews a change in
place of a person: deep reviewers find what's wrong, and small models ask why things were done the way they were.
In Hadron the Council check (`.github/workflows/council.yml`) blocks a PR touching a path in
`.github/council-paths.txt` until the PR description holds a passing review of its head commit.

## Before a round

Fix what you already know about, answer open PR comments, and run the checks the change needs (`./gradlew build`, and
the in-game suites if runtime code changed). Commit and push: the council reviews the PR's head commit, and the gate
accepts a review only for the commit it names.

## Members

Read `.github/council.toml`. Start one delegate per `[[member]]`, all in parallel, with `delegate_task` and that
member's `provider`, `model` and its option (`effort`, `reasoningEffort` or `thinking`, passed as given; call
`orchestrator_capabilities` first). If a member can't be started,
say so and stop; never swap in a different model. Every delegate is read-only: it reads, runs builds and tests, and
never edits, commits or pushes.

Give every delegate: the PR number and URL, the head SHA, the diff command (`git diff origin/main...<sha>`), the
worktree path to read, and what the change is for. Tell deep reviewers to read `AGENTS.md`, `docs/design.md` and
`docs/coding-standards.md` ("How to review") first. Don't tell anyone the passing score: knowing the target inflates
scores.

**Deep reviewers** (`role = "deep"`): review the whole change for correctness, broken promises in the docs, and what
could appear to work while wrong. They may run tests. They report findings as `docs/coding-standards.md` asks
(count first, file and line, worst first, no nits), then a score out of 10 for the change as it stands.

**Question askers** (`role = "questions"`): tell them, in these words, to "ask dumb questions" about why the code was
done this way. They are the newcomer who doesn't know the history: why this and not the obvious alternative, why
this name, why this is here and not there, what happens if this is empty, why this test proves what its name says.
Each question names a file and line. They don't score and don't propose fixes. A question that only has an answer
in the author's head is the point.

**Explainers** (`role = "explain"`): old, small models that know nothing of Hadron's history, as a plugin-writing agent
won't. Give them only the diff command and the worktree, not what the change is for, and ask them to explain what
changed and how it works, from the code alone: what each changed piece does, how they fit, and what happens when
it runs. They don't judge or score. Their explanation tests whether the change is clear, and whether it does what the
author believes it does.

## After a round

Summarize the useful findings, disagreements and questions. Then for each:

- **A finding you agree with:** fix it.
- **A finding you disagree with:** say why in the PR, with evidence.
- **A question:** settle it in this order, taking the first that fits:
  1. **Make it impossible to ask**: a type, a check or a test that answers it for good.
  2. **Rename** so the answer is obvious from the code.
  3. **A comment**, only for a why the code can't show, and only after checking the comment is true against the code.
  4. **An answer in the PR** under "Council questions", when the question is about history or a choice, not the code.

  Don't answer a question with a comment that only restates it. A question is open until it is settled one of these
  ways.
- **An explanation:** compare it with what the code really does, and list each place it's wrong under "Council
  misreadings". A misreading the code caused (an unclear name, logic in a surprising place, a stale comment) is fixed
  in the code, in the same order as a question. A misreading from the model's own limits is answered in the PR with
  the reason. An explanation that matches what you believed but not what the code does is a bug in the code or in your
  belief: find out which before going on. A misreading is open until fixed or answered.

Commit, push, and start a fresh round on the new head. Each full pass is one round.

**Keep scope small.** Rounds must not grow the change. A good idea outside the PR's purpose goes in the PR as a
follow-up, not into the code. After 3 rounds without passing, stop and tell the owner the approach may be wrong,
as `docs/coding-standards.md` says.

## Recording it

When the last round passes (every deep reviewer at or above `min_score`, no question or misreading open), put this
section in the
PR description, before the model and harness line at the end. Edit the description; don't post a comment. The
`Reviewed commit` is the full head SHA the last round reviewed.

```markdown
## Council review

Reviewed commit: 0123456789abcdef0123456789abcdef01234567
Rounds: 2

| Delegate | Round 1 | Round 2 |
| --- | --- | --- |
| GPT-6.1 Sol | 7/10 | 9/10 |
| Claude Opus 5.5 | 8/10 | 9/10 |
| Claude Haiku 5.5 | 6 asked, 3 open | 7 asked, 0 open |
| Claude Haiku 4.5 | 3 misread, 2 open | 1 misread, 0 open |
```

Names must match `council.toml`. A deep reviewer that gives no score is `no score`; never invent one. Write only what
the delegates actually returned: this table is what the gate trusts in place of a person.

If there is no PR, give the same results in the final reply.
