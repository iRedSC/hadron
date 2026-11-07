---
name: explain
description: Use when the user says "explain", or as the step after the council's last round (.claude/skills/council/SKILL.md).
---

# Explain

After the council converges, small models explain the change from the code alone, as an agent meeting it for the first
time would. Where an explanation is wrong, the code is likely unclear; and where it matches what the author believed
but not what the code does, one of the two is wrong. The author is the agent running this on its own PR.

**One pass.** Each explainer is asked once; don't send it follow-ups or a second round.

For each `role = "explain"` member of `.github/council.toml`, make a snapshot of the head with no history, so the
explainer can't read the author's account of the change (commits, PR, purpose); the code and its docs stay in:

```bash
dir=$(mktemp -d /tmp/council-explain-XXXX)
git archive <head-sha> | tar -x -C "$dir"
git diff <base-sha>...<head-sha> > "$dir.patch"   # <base-sha>: gh pr view <n> --json baseRefOid -q .baseRefOid
```

Start one delegate per explainer, in parallel, with its `provider`, `model` and option, and only:

> In <dir> is a codebase, and <dir>.patch is a change to it. Don't run git or gh, and don't read anything outside
> <dir> and <dir>.patch. From the code alone, explain what changed and how it works: what each changed piece does,
> how the pieces fit, and what happens step by step when it runs. Name files, functions and the exact conditions.
> Don't judge, score or suggest changes. Where you're unsure, say what you think it does and that you're unsure.

## Clean up

Compare each explanation with what the code does. For each place it's wrong:

- **The code caused it** (an unclear name, logic in a surprising place, a stale comment): fix the code, in the
  questions skill's order (impossible, rename, checked comment).
- **The model's own limit**: say why in the PR under "Misreadings", quoting it.

Also fix the P3 findings the council left. Commit, push, and note the explainers in the PR. Then the final review
(.claude/skills/final-review/SKILL.md).
