---
name: questions
description: Use when the user says "questions", or as the first step of a council (.claude/skills/council/SKILL.md).
---

# Questions

One pass of dumb questions before the council, to pick the author's brain: the author is the agent running this on its
own PR. Questions surface what's only in the author's head, and make the author think outside the box before the code
is reviewed in earnest.

Start one delegate per `role = "questions"` member of `.github/council.toml`, in parallel, with `delegate_task` and the
member's `provider`, `model` and option. If one can't start, say so and stop; the owner changes the seat. They are
read-only. Give each the PR URL, the head SHA, the worktree path, the diff command (`git diff <base-sha>...<head-sha>`, where `<base-sha>` is
`gh pr view <n> --json baseRefOid -q .baseRefOid`, the commit the gate also diffs from),
what the change is for, and:

> Ask dumb questions about why the code was done this way. You are the newcomer who doesn't know the history: why this
> and not the obvious alternative, why this name, why this is here and not there, what happens if this is empty or
> missing, why this proves what it says. Each question names a file and line. Don't score and don't propose fixes. At
> most 12, most important first.

Settle every question, taking the first that fits:

1. **Make it impossible to ask**: a type, a check or a test. This fits questions about behaviour, not about why.
2. **Rename** so the answer is obvious from the code.
3. **A comment**, only for a why the code can't show, and only after checking it's true against the code.
4. **An answer in the PR** under "Questions answered", for history or a choice.

Never answer with a comment that only restates the question. Then commit, push, and record the askers on the
`Questions:` line of the PR's "Council review" section (.claude/skills/council/SKILL.md).
