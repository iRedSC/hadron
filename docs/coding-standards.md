# Coding standards

For whoever reviews a change, not whoever writes it. The writer works from [AGENTS.md](../AGENTS.md) and the design docs; the reviewer reads this with a fresh context and checks the change against it. Fix what you find and commit it; leave a comment only for a question the code can't answer.

A rule that a build check could enforce doesn't belong here. Write the check instead (see "Growing this file").

## How to review

- **Start the summary with a count**: "4 findings". Then one line per finding, highest priority first.
- **Every finding has a priority** (below), cites a file and line, and says what goes wrong, with evidence from the code, never a guess from a name.
- **Mark pre-existing problems as such.** A bug the PR didn't introduce is "pre-existing", so it doesn't read as the author's fault.
- **Name the class, not the instance.** When a mistake can recur, say so ("every listener that keeps the live stack"), so one round fixes all of them.
- **Skip** generated files, `build/`, and test reports.

### Priority

Give each finding the highest level whose test it meets. A council round, and the final review, pass once nothing
is more serious than `stop_at` in `.github/council.toml`.

| Priority | Test | Examples |
|---|---|---|
| **P0** | Loses data, breaks security, or lets an unreviewed change merge | a saved format that loses data, a packet that crashes the server, a gate bypass |
| **P1** | Breaks a promise in the docs or API, or passes while wrong | a cancel that leaves residue, a check that never fails |
| **P2** | Correct, but likely to mislead a reader into a bug | a missing test for a promise, an error message without the fix, a slower hot path |
| **P3** | Correct and safe, but unclear | a bad name, logic in the wrong place, a stale comment |
| **P4** | Taste | style, wording |

What counts as a failure depends on where the change is:

| Where | It fails when |
|---|---|
| `api/` | an agent's first guess compiles but does the wrong thing, or a promise in the Javadoc isn't kept |
| `common/`, `neoforge/` | a cancel leaves something behind, the wrong actor is blamed, or a hot path gets slower with nobody listening |
| `neoforge/**/mixin/net/` | a client's packet can crash, stall or fool the server |
| tests | the test could pass with the feature broken |
| docs | they lie: they promise coverage, behaviour or a command that isn't there |

## Tests

A test must be able to fail. Reject:

- **Tautological tests**, which restate the code: asserting a constant equals itself, or computing the expected value the same way the code does.
- **Structure-sensitive tests**, which check how the code is built rather than what it does: private fields, call order, a class's shape. Test at the event, the API method or the command, the way a plugin would.
- **Over-mocked tests**, which mock the thing under test until nothing real runs. Hadron has no fake Minecraft by design; anything that touches the world goes in the in-game tier.
- **Tests that pass by luck**: a test that relies on an earlier test's state. (The build refuses the common forms of sleeps, the wall clock and unseeded randomness; other forms aren't caught, so look for them.)
- **An exemption that doesn't hold**: a `@StaticState` or `@Nondeterministic` whose reason the code doesn't bear out.
- **A `@Covers` the test doesn't earn.** The test must fire or call what it names and check the result, not merely mention it.
- **An assertion loosened to make a failure go away.** A red test is a Hadron bug, a test bug or a contract gap; say which.

## Code

- **Decisions in plain functions, Minecraft at the edges.** A mixin or listener that decides something (who the actor is, which event fits, allow or refuse) instead of handing it to a fast-testable function goes back.
- **Invalid states can't be built.** Prefer a sealed type, a record that validates in its constructor, or an `Optional` to a nullable field and a comment saying when it's set.
- **A cancel undoes everything it covers**: no item consumed, no durability lost, no half-moved stack, no cost kept. A cancel test checks the world after, not just the flag.
- **Every error message says the fix**, naming the file, the line or key, and what to write instead.
- **Names are what an agent would guess**, which is Bukkit's where the meaning matches. A rename from Bukkit needs a reason in [events.md](events.md) rule 6.
- **Hot paths cost one check when nobody listens.** Code on `setBlock`, `setDeltaMovement`, ticking or packet handling allocates nothing and returns at once without a listener.
- **Words mean what [glossary.md](glossary.md) says.** A new concept gets a glossary line in the same change.

## Docs

- An event's `@FiredBy` says what really fires it. The build checks `NONE` against the code and keeps [bridge-hooks.md](bridge-hooks.md) in step, but only a reviewer can tell `FULL` from `PARTIAL` or check the texts.
- An API change Minecraft forced goes in that version's port guide in `docs/porting/`.

## Growing this file

After each merged PR, look at every fix made during its review, its test runs or its triage, and ask which check would have caught it first. A bug fix also needs a test that fails without the fix, so the bug can't return unnoticed. It adds its entry to `regressions/regressions.toml`, with the patch that undoes it, so `regressionReplay` proves that test still fails without the fix ([testing.md](testing.md), "Regression replay"). In order of preference:

1. **A type or API that can't be misused.** Then the mistake can't be written.
2. **A build check** (like `coverageCheck` or the `plugin.yml` checks) that fails and says the fix.
3. **A line here**, when only judgement can catch it.

Never write the same review comment twice: the second time, it becomes one of the three. Remove a line here when a check takes it over, so the file stays short enough to read in full.
