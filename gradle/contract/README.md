# Hadron contract bundle

You are writing tests of Hadron, a Bukkit-shaped plugin host for modded Minecraft servers (NeoForge @NEO_VERSION@,
Minecraft @MINECRAFT_VERSION@, Java 21, Mojang names), from what it promises, not from what its code does. This folder
is everything you get: Hadron's contract, and a test project wired to the real Hadron.

## What you may read

Only this folder, and only these parts of it:

- `contract/src/api/`: every public type of Hadron's plugin API, with real signatures and Javadoc. Bodies are stubs.
- `contract/src/test-api/`: the test harness tests compile against: `@HadronTest`, `TestContext`, `TestPlayer`,
  `@Covers`, `@WithMinecraft`.
- `contract/docs/`: the spec. `events.md` (every event and the rules they follow), `platform.md` (persistent data,
  economy, packets and visibility, worlds), `bridge-hooks.md` (what mods' bridges must do, and what core already
  does), `testing.md` (writing and running tests, both tiers, `@Covers`, reports, the harness's limits).
- `tests/`: your test project. Write tests in `tests/src/test/java`.

Minecraft's own classes (`ServerPlayer`, `BlockPos`, `Blocks`, ...) are vanilla 1.21.1 with Mojang names; use what you
know of them.

## What you must not do

- **Don't read Hadron's implementation**: don't open, list, unzip or decompile anything in `tests/libs/`, and don't go
  looking for Hadron's sources anywhere else. Those jars are there only so the tests run against the real thing.
- **Don't change an assertion to match what you observed.** A test states what the contract promises. When it fails,
  report it (below); never adjust, weaken, skip or delete it to make the run pass. A failing test is a finding.
- **Don't guess past the contract.** If the contract doesn't say what should happen, don't test what you think
  Hadron does: report the gap as "unclear contract" with the text you were reading.

## Running

From `tests/` (needs a JDK 21; set `JAVA_HOME` if `java` isn't one):

```
./gradlew test                         # fast tier: JUnit @Test, no server, seconds
./gradlew hadronTest                   # in-game tier: @HadronTest in a fresh headless server (boots in ~15-30 s)
./gradlew hadronTest --tests 'BreakTests.*'    # some tests only; see contract/docs/testing.md
./gradlew hadronWatch                  # a test server that stays up (own terminal or background) ...
./gradlew hadronRerun                  # ... then rerun on it after each change, in seconds
./gradlew hadronWatchStop
./gradlew coverageCheck                # lists every event and API method no test here @Covers
```

Results: the fast tier's are in `build/test-results/test/*.xml`; the in-game tier prints its report and writes
`server/build/hadron-test/report.txt` and `report.json` (the format is in `testing.md`). The server's log is
`server/build/hadron-test/run/logs/latest.log`. The first run downloads and sets up Minecraft, which takes a few
minutes; later runs don't.

Mark each test with `@Covers` for what it proves (`testing.md`, "Coverage"). `coverageCheck` lists what no test covers
yet; it is part of `./gradlew check`, which fails until everything is covered.

`ExampleFastTest` and `ExampleTests` show one test of each tier. Keep them or delete them.

## Reporting a failure

For every failing test, and every place the contract was too vague to test, report:

1. **The test**: class and method, and whether it failed in `test` or `hadronTest`.
2. **What it expected and what happened**: the assertion's message from the report.
3. **The contract it relied on**: quote the text, and say where it is: a doc and its section
   (`events.md`, "Operations and cancelling"), or a type and member (`BlockBreakEvent`, class Javadoc;
   `TestContext.assertBlock`).

Then leave the test as it is. Someone who can see the implementation decides whether Hadron, the test, or the
contract is wrong.
