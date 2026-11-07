---
name: council
description: Use when the user says "council", or when a Hadron PR touches a path in .github/council-paths.txt and its Council check is red.
---

# Council

Adapted from the council skill in Theo Browne's tstack (github.com/t3dotgg/tstack, MIT). A council reviews a change in
place of a person: deep reviewers find what's wrong, and small models ask why things were done the way they were.
In Hadron the Council check (`.github/workflows/council.yml`) blocks a PR touching a path in
`.github/council-paths.txt` until the PR description holds a passing review of its head commit.

In this skill, **you are the author**: the agent running the council on its own PR. You fix, answer and record. The
**owner** is the person the agent works for, who decides only what changes a PR's purpose.

## Before a round

Fix what you already know about, answer open PR comments, and run the checks the change needs (`./gradlew build`, and
the in-game suites if runtime code changed). Commit and push: the council reviews the PR's head commit, and the gate
accepts a review only for the commit it names.

## Members

Read `.github/council.toml`. Start one delegate per `[[member]]`, all in parallel, with `delegate_task` and that
member's `provider`, `model` and its one option (`effort`, `reasoningEffort` or `thinking`, passed as given; call
`orchestrator_capabilities` first). Every member runs in every round, so the table's last column is one pass for all of
them. If a member can't be started, say so and stop; never swap in a different model. The owner changes the seat in
`council.toml`. Every delegate is read-only: it reads, runs builds and tests, and never edits, commits or pushes.

Don't tell anyone the passing score: knowing the target inflates scores. Each role gets its own brief, below, with the
placeholders filled in. Write the brief out; don't paraphrase the quoted parts.

**Deep reviewers** (`role = "deep"`) get the PR number and URL, the head SHA, the worktree path, the diff command
(`git diff origin/main...<sha>`), what the change is for, and:

> Read AGENTS.md, docs/design.md and docs/coding-standards.md ("How to review") first. Review the whole change for
> correctness, broken promises in the docs, and anything that could appear to work while wrong. You may run builds
> and tests. Report findings as coding-standards.md asks: a count first, worst first, file and line, the concrete
> failure, a suggested fix; no nits. Then `Score: N/10` on its own line.

**Question askers** (`role = "questions"`) get the same context, and:

> Ask dumb questions about why the code was done this way. You are the newcomer who doesn't know the history: why this
> and not the obvious alternative, why this name, why this is here and not there, what happens if this is empty or
> missing, why this proves what it says. Each question names a file and line. Don't score and don't propose fixes.

Label each asker's questions by asker in the PR's "Council questions" section (for example S1, S2 for one, T1, T2 for
the other). From round 2 on, give each asker its own earlier questions with their answers, and add: "First, for each
of your earlier questions, say settled or open, and why." So a question stays open across passes until the asker who
asked it agrees it's settled, and each tally counts only that asker's questions. Ask each to end with
`Tally: <asked> asked, <open> open`.

**Explainers** (`role = "explain"`) stand in for an agent meeting this code for the first time, so they must not hear
the author's account of the change: no commit messages, no PR, no purpose. What the code and its docs say about
themselves is part of the code, and stays in. Give them a snapshot with no history, not the worktree:

```bash
dir=$(mktemp -d /tmp/council-explain-XXXX)
git archive <head-sha> | tar -x -C "$dir"            # the code at the head, without .git, commit messages or the PR
git diff origin/main...<head-sha> > "$dir.patch"     # what changed
```

and only:

> In <dir> is a codebase, and <dir>.patch is a change to it. Don't run git or gh, and don't read anything outside
> <dir> and <dir>.patch. From the code alone, explain what changed and how it works: what each changed piece does,
> how the pieces fit, and what happens step by step when it runs or is used. Name files, functions and the exact
> conditions. Don't judge, score or suggest changes. Where you're unsure, say what you think it does and that you're
> unsure.

## After a round

Summarize the useful findings, disagreements and questions. Then for each:

- **A finding you agree with:** fix it.
- **A finding you disagree with:** say why in the PR, with evidence.
- **A question:** settle it in this order, taking the first that fits:
  1. **Make it impossible to ask**: a type, a check or a test that answers it for good. This fits questions about
     behaviour ("what happens if this is empty?"), not about why a design was chosen.
  2. **Rename** so the answer is obvious from the code.
  3. **A comment**, only for a why the code can't show, and only after checking the comment is true against the code.
  4. **An answer in the PR** under "Council questions", when the question is about history or a choice, not the code.

  Don't answer a question with a comment that only restates it. A question is open until it is settled one of these
  ways and its asker agrees.
- **An explanation:** compare it with what the code really does, and list each place it's wrong under "Council
  misreadings", quoting the explanation. Counting misreadings is your judgement, not the explainer's, so the quotes let
  anyone check it. A misreading the code caused (an unclear name, logic in a surprising place, a stale comment) is
  fixed in the code, in the same order as a question. A misreading you put down to the model's own limits is answered
  in the PR with the reason; if the next round's explainers misread the same thing, it was the code after all, and it's
  fixed in the code. An explanation that matches what you believed but not what the code does is a bug in the code or
  in your belief: find out which before going on. A misreading is open until fixed or answered.

Commit, push, and run the next pass on the new head.

**Keep scope small.** Rounds must not grow the change. A good idea outside the PR's purpose goes in the PR as a
follow-up, not into the code.

## Three rounds at most, then reconsider the architecture

A council gets at most 3 rounds, and the gate refuses a table with more. Findings that keep coming are a sign the
design is wrong, not that it needs one more patch.

- **Before round 3**, look at what rounds 1 and 2 found. If the findings cluster on one mechanism (the same function,
  the same kind of check, fixes that each needed another fix), that mechanism is the problem: change its design so the
  whole class of finding can't happen, rather than fixing the instances. A redesign within the PR's purpose is yours to
  make; say in the PR which findings it removes and why. One that changes the purpose is the owner's call.
- **A Confirm pass** may follow the last round, once, when that round's deep reviewers all passed and only questions
  or misreadings were left. Settle them, push, and run the whole council again as "Confirm", with a narrower brief:
  deep reviewers re-score the change; askers rule settled or open on their listed questions and ask nothing new;
  explainers explain again, and you check only whether the misreadings already listed came back. New questions or
  misreadings in Confirm aren't counted (put them in the PR as follow-ups), so the gate refuses a Confirm whose totals
  differ from the round before it. A listed misreading that comes back, a question still open, or a deep score below
  the bar means the architecture gets reconsidered, below.
- **If the last round doesn't pass and can't take a Confirm, or its Confirm doesn't pass, there is no round 4.** Stop changing code and reconsider the
  architecture:
  1. List every finding across the rounds, grouped by the part of the design it came from.
  2. Name the root cause: the assumption or structure those findings share.
  3. Describe at least two different designs that would remove that root cause, with what each costs (size,
     speed, what it can't do) and which findings each makes impossible. One may be "don't build this".
  4. Recommend one.

  Put this in the PR under "Architecture reconsidered", and tell the owner. A redesign changes the PR's scope, so it's
  the owner's call; a new council starts on the redesigned change, from round 1.

## Recording it

When the last pass passes (every deep reviewer at or above `min_score`, no question or misreading open), put this
section in the PR description, before the model and harness line at the end. Edit the description; don't post a
comment. `Reviewed commit` is the full head SHA the last pass reviewed. `Rounds` counts the Round columns, not Confirm.

```markdown
## Council review

Reviewed commit: 0123456789abcdef0123456789abcdef01234567
Rounds: 2

| Delegate | Round 1 | Round 2 | Confirm |
| --- | --- | --- | --- |
| GPT-6.1 Sol | 7/10 | 9/10 | 9/10 |
| Claude Opus 5.5 | 8/10 | 9/10 | 9/10 |
| Claude Haiku 5.5 | 6 asked, 3 open | 7 asked, 1 open | 7 asked, 0 open |
| Claude Haiku 5.5 (explainer) | 3 misread, 2 open | 1 misread, 1 open | 1 misread, 0 open |
```

Names must match `council.toml`, and every member has a result in every column. A deep reviewer that gives no score
is `no score`; never invent one. Scores and questions are what the delegates returned; misreadings are your count
from their explanations, with the quotes under "Council misreadings". This table is what the gate trusts in place of a
person.

If there is no PR, give the same results in the final reply.
