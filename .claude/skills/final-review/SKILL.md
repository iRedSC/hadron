---
name: final-review
description: Use when a Hadron PR touches a path in .github/council-paths.txt (its Council check is red), or when the user says "final review".
---

# Final review

Every PR touching a path in `.github/council-paths.txt` needs it: the Council check reads one line in the PR
description. The author (the agent running this on its own PR) fixes, pushes, and runs it again until it passes.

Start the `role = "final"` member of `.github/council.toml` with `delegate_task`, its `provider`, `model` and option.
If it can't start, say so and stop; the owner changes the seat. Give it the PR URL, the full head SHA, the worktree
path, the diff command (`git diff <base-sha>...<head-sha>`, where `<base-sha>` is
`gh pr view <n> --json baseRefOid -q .baseRefOid`), what the change is for, and:

> READ ONLY: never edit, commit or push; probe with copies under /tmp. Read AGENTS.md and docs/coding-standards.md
> first. Review this change as it stands. List each finding with a priority by docs/coding-standards.md
> ("Priority"), the file and line, what goes wrong, and its layer: `core` when the fix needs a fact an event
> should have carried (where something really ends up, who is behind it, what it touches) or a missing event,
> otherwise `plugin`. You may run builds and tests. End with one line:
> `Found: <n>, top P<k>`, where P<k> is your most serious finding (`Found: 0, top P4` if none).

If it finds anything more serious than `stop_at`, fix it, push, and run another pass on the new head. Fix a `core`
finding in core or the bridge, in its own PR merged first, never by teaching the plugin Minecraft; if `core` findings
keep coming, stop and tell the owner the events need a design change rather than running more passes; a finding you
disagree with, answer in the PR with evidence, and tell the next pass it's answered. When a pass on the head finds
nothing more serious than `stop_at`, put exactly one line in the PR description, replacing any earlier one:

```
Final review: GPT-6.1 Sol (final), <full head SHA>, <n> found, top P<k>
```

Any later push needs another pass.
