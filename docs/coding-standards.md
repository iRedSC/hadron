# Coding standards

For whoever reviews a change, not whoever writes it. The writer works from [AGENTS.md](../AGENTS.md) and the design docs; the reviewer reads this with a fresh context and checks the change against it. Fix what you find and commit it; leave a comment only for a question the code can't answer.

A rule that a build check could enforce doesn't belong here. Write the check instead (see "Growing this file").

## Tests

A test must be able to fail. Reject:

- **Tautological tests**, which restate the code: asserting a constant equals itself, or computing the expected value the same way the code does.
- **Structure-sensitive tests**, which check how the code is built rather than what it does: private fields, call order, a class's shape. Test at the event, the API method or the command, the way a plugin would.
- **Over-mocked tests**, which mock the thing under test until nothing real runs. Hadron has no fake Minecraft by design; anything that touches the world goes in the in-game tier.
- **Tests that pass by luck**: wall-clock sleeps, an unseeded random, a test that relies on an earlier test's state. Time is ticks (`t.tick()`), randomness is seeded.
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

- A change to an event's coverage or bridge contract updates [bridge-hooks.md](bridge-hooks.md) in the same change.
- An API change Minecraft forced goes in that version's port guide in `docs/porting/`.

## Growing this file

After each merged PR, look at every fix made during its review, its test runs or its triage, and ask which check would have caught it first. In order of preference:

1. **A type or API that can't be misused.** Then the mistake can't be written.
2. **A build check** (like `coverageCheck` or the `plugin.yml` checks) that fails and says the fix.
3. **A line here**, when only judgement can catch it.

Never write the same review comment twice: the second time, it becomes one of the three. Remove a line here when a check takes it over, so the file stays short enough to read in full.
