# Testing

`./gradlew hadronTest` runs a plugin in a headless server with scripted players and reports pass or fail, so an agent proves a plugin works before claiming it does. Hadron tests itself the same way: every event, cancel, change and API is covered by tests written with the harness plugin authors use.

## Two tiers

| | Fast tests | In-game tests |
|---|---|---|
| What they check | anything that decides rather than acts: rules, maths, parsing, validation | anything that touches the world, a player or a packet |
| Run with | `./gradlew test` (`:api:test`, `:common:test`, a plugin's own `test`) | `./gradlew hadronTest`, or warm with `hadronWatch` and `hadronRerun` |
| Written as | JUnit 5 `@Test`, in `src/test/java` | `@HadronTest` taking a `TestContext`, in `src/test/java` |
| Minecraft | registries only: put `@WithMinecraft` on the class (or call `MinecraftBootstrap.ensure()`), then `ItemStack`, `BlockState`, components and `ResourceLocation` work; classes without it never boot Minecraft | a real headless server, real players over an in-memory connection |
| Cost | milliseconds a test; about 6 s once per JVM to boot the registries | about 14 s to boot a server, then about half a second a test; a warm rerun about 5 s |

Use the fast tier for every decision you can, and the in-game tier for every effect. Both tiers live in the same `src/test/java`; `hadronTest` runs only classes holding an `@HadronTest`, so a JUnit class never reaches the server. `examples/hello`'s `HelloFastTest` is the example to copy: it checks the plugin's packed `plugin.yml` against Hadron's own rules, so a bad one fails at `./gradlew test` instead of at server start.

## What a test looks like

```java
public final class ShopTests {
    @HadronTest
    void buyingTakesMoney(TestContext t) {
        TestPlayer alice = t.player("alice");
        Economy.get().deposit(alice.uuid(), new BigDecimal("10"));
        alice.useBlock(t.pos(0, 1, 0));                 // right-click the shop sign
        t.assertBalance(alice, "money", "5");
        t.assertHolding(alice, Items.DIAMOND, 1);
    }
}
```

- **Tests live in the plugin's `src/test/java`**, as methods annotated `@HadronTest` taking a `TestContext`, compiled against `test-api` (`@HadronTest`, `TestContext`, `TestPlayer`, vanilla only). No other setup. The plugin's main code can't see `test-api`, so a test player can't leak into a shipped jar.
- **Each test gets its own space:** a fresh void world made with `Worlds.create`, removed afterwards, so tests never see each other's blocks or entities. A bedrock floor under `pos(0, 0, 0)` gives players something to stand on, and the 5 by 5 chunks around it stay loaded and ticking with or without a player there, so machines, item entities and mobs run in any test. A test starts only once that area ticks entities, outside its tick limit, so a test never passes or fails by how busy the machine is. Players, their saved data, balances, op status and hidden state, and listeners the test registered with `t.registerEvents` are gone after it too. One server runs all of a plugin's tests, for speed.
- **Test players are real players** (`TestPlayer`, not a fake player: Hadron sees them as `Actor.Player`). Each is a `ServerPlayer` joined through the real login path over an in-memory connection, so everything a client would cause goes through the code it would: vanilla's packet handlers, Hadron's events, the plugin's listeners, other mods. Their actions are sent as the packets a client sends (break, place, use, attack, click a slot, drop, chat, run a command, move), and everything the server sends them is recorded.
- **Observing** is reading the world and the recorded packets: block states, inventories, balances, persistent data, the chat lines a player received, whether a player was sent an entity at all (so vanish is testable), what the tab list holds.
- **Time** is explicit: `t.tick(n)` runs server ticks; `t.await(condition, ticks)` waits up to a limit.
- **Assertions say the fix:** a failure names what was expected, what was there, and where.

## What a run reports

One line per test (`PASS ShopTests.buyingTakesMoney`), the failures in full with their message and the line in the test, and a summary. The task fails the build when any test fails, so an agent can't miss it. A test that throws, hangs past its limit, or leaves the server unhealthy fails with that said plainly.

```
Hadron test report

Hello (examples-hello.jar)
PASS HelloTests.helloGreetsWhoeverRunsIt  (2 ticks, 0.5 s)
FAIL HelloTests.secretsAreNeverSent  (HelloTests.java:41)

Failures

FAIL HelloTests.secretsAreNeverSent  (HelloTests.java:41)
    bob should not have received "secret", but got the line "<alice> the secret is 42". If a listener should
    have cancelled or changed it, check that it is registered and matches this message.

Summary
SUMMARY examples-hello.jar (Hello): 1 passed, 1 failed
1 passed, 1 failed in 3.1 s
RESULT: FAILED
```

`FAIL` is an assertion, an exception (with its stack), a test past its tick limit (`@HadronTest(timeout = 600)`, in ticks), a method the runner can't call (the message shows the right signature), or a cleanup that left something behind. `HUNG` is a test that never returned, such as a loop that never ticks: a watchdog writes the report with where the server thread was and ends the process. `BROKEN` is a suite none of whose tests could run, such as a plugin that didn't enable. The `SUMMARY` lines and the last `RESULT:` line keep their shape for tools.

## How a run works

- `./gradlew :examples:hello:hadronTest` builds the plugin jar plus its test classes and a list of them (`META-INF/hadron-tests.txt`), stages it, and runs `:neoforge:runHadronTest`: a dev server in `neoforge/build/hadron-test/run`, made fresh each run, listening on no fixed port (the game port is any free one; RCON and query are off), so it never collides with the dev server. The task then prints the report and fails unless this plugin's tests all passed. The report is `neoforge/build/hadron-test/report.txt`; the server log is `run/logs/latest.log` beside it.
- The server runs in test mode (`-Dhadron.test.report=<file>`). After its first tick it runs every suite's tests, one at a time, in a single task on the server thread, so a test touches any Minecraft state directly. `t.tick()` runs a whole server tick from inside the test; the server's own loop waits until the tests return, then the server stops and exits with 0 only if everything passed.
- A test player's client is an in-memory connection to the server's memory channel, with the vanilla client's half of the protocol: login, the configuration phase (as an unmodded client, or with `t.moddedPlayer` as a NeoForge client with the server's mods), keep-alives, teleport confirmations followed by a position report, chunk-batch acknowledgements, a position reminder every 20 ticks. After every action and tick, the harness lets packets travel both ways until neither side has anything left, so the next line sees the result.
- Suites requested in one Gradle invocation share one server, but never each other's listeners: while one suite runs, every other suite's plugin is disabled. The shared task lives in `gradle/hadron-test.gradle.kts` until the Hadron Gradle plugin takes it over.

## Running some tests

`--tests` limits a run, on `hadronTest`, `hadronWatch` and `hadronRerun` alike, as on Gradle's own test task: a class (`HelloTests`, or its full name), a method (`HelloTests.helloGreetsTheNameGiven`), or either with `*` (`HelloTests.hello*`, `*secret*`). Matching ignores case. Repeat it for more; a test runs when any pattern matches. The report says `Only tests matching ...: 3 of 6`, and a filter that matches nothing is a `BROKEN` suite that lists the tests it has, so a typo can't pass as an empty green run.

## Warm reruns (watch mode)

```
./gradlew :examples:hello:hadronWatch          # starts a test server that stays up; leave it running
./gradlew :examples:hello:hadronRerun          # after each change: rebuild, rerun, print the report, fail on failure
./gradlew -t :examples:hello:hadronRerun       # or rerun on every save
./gradlew :examples:hello:hadronWatchStop      # stop it (Ctrl+C on hadronWatch works too)
```

`hadronWatch` boots the test server once, in `neoforge/build/hadron-watch/run`, and runs the suite. Each `hadronRerun` builds the jar, drops it in the server's inbox and waits: between runs, the server unloads the old plugin, deletes its data folder, puts back the overworld state every test shares (game rules, time, weather, difficulty, ops, bans, whitelist, scoreboard) as it was at boot, and lets Hadron's hot reload load the new jar, so the rerun starts as a cold one would. Every test still gets its own world and players. For the hello suite on a busy machine: a cold `hadronTest` takes 25 s, a `hadronRerun` 5 s (4 s of it on the server, reload included).

- **A build never lands in a running test.** The server takes builds from its inbox only between runs; one staged meanwhile waits its turn. When several builds of one suite wait, the newest runs and the older ones' `hadronRerun` fails saying it was superseded. If something replaces a jar in `plugins/` directly mid-run, the run stops and says so rather than report on a plugin that's no longer loaded.
- **A stale server is refused:** if Hadron's own sources changed since the server started, `hadronRerun` fails and says to restart it, since the server still runs the old Hadron. Without a server, it says how to start one.
- **A hung test ends the server**, as in a cold run (the server thread can't be stopped): the `hadronRerun` waiting on it still gets the report, then says to start `hadronWatch` again.
- Each rerun's report is `reports/NNNN/report.txt` and `.json` (the last 20 are kept); `report.txt` and `report.json` beside them are the latest. The server log also prints each report.
- **What a rerun doesn't reset:** economy accounts of anyone but test players (theirs are emptied after every test, as before), worlds or boss bars a plugin made itself, and other mods' state.

## report.json

Beside every `report.txt` is a `report.json` with the same results for tools; `hadronTest` and `hadronRerun` print where it is. Every key is always present, with `null` where it doesn't apply. `schema` changes only when a field changes meaning or goes away. (The comments below are notes, not part of the file.)

```jsonc
{
  "schema": 1,
  "result": "FAILED",                  // PASSED only if every suite ran and every test passed
  "mode": "watch",                     // "cold" for hadronTest, "watch" for a warm server's rerun
  "run": 5,                            // the rerun's number on that server; 1 for a cold run
  "startedAt": "2026-11-07T02:44:03Z",
  "wallMillis": 3992,
  "summary": { "total": 2, "passed": 1, "failed": 1, "hung": 0, "broken": 0, "suites": 1 },
  "problems": [],                      // failures of the run itself, outside any test
  "suites": [{
    "suite": "examples-hello.jar", "plugin": "Hello",
    "broken": null,                    // why none of its tests could run, or null
    "filter": ["TmpTests"],            // the --tests patterns; empty for every test
    "available": 8,                    // tests in the suite before the filter
    "summary": { "total": 2, "passed": 1, "failed": 1, "hung": 0, "broken": 0 },
    "tests": [{
      "suite": "examples-hello.jar",
      "id": "TmpTests.fails",          // as the text report names it
      "class": "dev.iredsc.hadron.examples.hello.TmpTests",
      "method": "fails",               // null when the class itself couldn't load
      "status": "FAIL",                // PASS, FAIL or HUNG
      "ticks": 3, "wallMillis": 41,
      "message": "expected failure",   // null for a pass
      "source": "TmpTests.java:20",    // the test's first line, for every test
      "failedAt": "TmpTests.java:21",  // the test's own line nearest the failure, or null
      "stack": null                    // for an exception, or where a hung test's thread was; null for an assertion
    }]
  }]
}
```

## What it can't do yet

- **Players have no physics**: they don't fall or get pushed; their client reports wherever the server has them. Moves are split into steps the server accepts as one client's.
- **Mod clients are stand-ins**: `t.player` joins an unmodded client. `t.moddedPlayer` joins a NeoForge client with the server's own mods: it answers NeoForge's channel query claiming every channel the server registered, so the connection is a NeoForge one, and `sendModPacket` sends any play payload a mod registered for clients to send. Hadron's test mod (only on a test server) gives tests one: `TestModPacket`, `hadron:test_ping`, whose handler's calls are `t.modPacketsHandled()`. Neither client answers a mod's own configuration task, so a mod whose join waits on its client mod still blocks joins (the join fails after 200 ticks and says so), and payloads the server sends are recorded, never handled.
- **Not every client habit is copied**: a right-click sends only the main-hand packet, without the off-hand or use-in-air follow-ups a client sends when the first does nothing. There is no action yet for closing a window, the enchanting table's buttons, anvil renaming, sign or book editing, respawning, sneaking, sprinting, flying, swapping hands, leaving a bed, releasing a bow, looking somewhere, or riding input; tests send those packets to the server's handler directly, which skips the per-packet attribution. Chat is unsigned.
- **After a respawn, `TestPlayer.player()` still returns the old, dead player.**
- **Console commands run in the overworld**: use `server().createCommandSourceStack().withLevel(t.level())` to run one in the test's world.
- **Weather, time and the random tick speed are shared with the overworld**, so a test that changes them puts them back.
- **Not covered by Hadron's own tests yet:** fishing bites and catches (they need open water and a random wait), mobs throwing, picking up or equipping items, hopper minecarts, and a portal lit by fire (test worlds aren't the overworld or the nether).
- The run is wired into `examples/hello` and `selftest` with `apply(from = ...)`; other projects get it from the Gradle plugin.

## What Hadron's own tests cover

`./gradlew :selftest:hadronTest` runs every test in `selftest/src/test/java`, grouped in packages by event family and API (`blocks`, `world`, `entities`, `players`, `items`, `inventories`, `platform`, `trace`, and more). How many there are, and which passed, is in the run's `report.json` rather than here, where a hand-written count would go stale. For every shipped event that a test can drive, they check that:

- **it fires with the right actor:** player, machine, entity, `Server` or nature;
- **a cancel fully undoes:** nothing changed and nothing spent, no item lost or duplicated, no durability gone;
- **changes apply:** damage dealt, drops, messages other players see, teleport destinations, craft results, the server list's MOTD;
- **one effect is one event** where firing twice is a risk;
- **attribution lasts:** an arrow's hit is still its shooter's after flight, a logout and a reload.

Writing them found 11 Hadron bugs, all fixed: crafting results and the crafter ignoring a plugin's result, a cancelled cursor drop falling anyway, a hopper's pickup blamed on the item, dimension suggestions never asking the server, a live stack handed to `EntityUseEvent`, `EntityVelocityEvent` never firing, a cancelled TNT lighting setting fire anyway, a dispenser leaving both TNT and primed TNT, sticky pistons' pulls reported as nothing, and cancelled placements costing the tool its durability.

## Coverage: the build checks every claim has a test

A test says what it proves with `@Covers` (in `test-api`), on the test method, once per thing:

```java
@HadronTest
@Covers(BlockBreakEvent.class)                               // an event the test fires
@Covers(value = Economy.class, method = "transfer")          // an API method it calls; every overload of the name
void cancellingABreakKeepsTheBlock(TestContext t) { ... }
```

`coverageCheck` (part of `check`; `./gradlew :selftest:coverageCheck`) reads class files only, `api`'s and the compiled tests' (`selftest`, and `api`'s and `common`'s JUnit tests when there are any), so it needs no server and finishes in well under a second of work. It fails the build listing everything uncovered, each line saying the fix. The script is `gradle/hadron-coverage.gradle.kts`.

**What must be covered:**

- **Every concrete event:** each public, non-abstract class in `api` that extends `Event`, core and bridge events alike. A test covers it by firing it the way core does (a player, machine, command or nature causing it) and checking a listener receives it, or, for an event only bridges fire, the way a bridge must: its actor pushed, fired through `callEvent` before the change, the change made only if no listener cancelled ([bridge-hooks.md](bridge-hooks.md)). A listener for a parent class hearing it counts only where the test names the leaf.
- **Every public API method:** each public method (and protected, on classes that aren't final) declared by a type in `api` outside `dev.iredsc.hadron.api.event` that code outside can reach: the type and every class around it public. A test covers it by calling it and checking what it did. An override is the method it overrides: covering `MemorySection.getString` and covering `ConfigurationSection.getString` are the same claim.

**What isn't counted, and why:**

- **Constructors:** an instance method can't be tested without one, and the checks a constructor makes are tested where it refuses input.
- **What the compiler writes:** `equals`, `hashCode`, `toString`, record accessors, an enum's `values` and `valueOf`, bridge and synthetic methods. Nothing of Hadron's runs in them.
- **The non-event types in the event packages** (`Cancellable`, `BlockChange`, `Resource`, the holders, `EventHandler`): they are the events' vocabulary, and the event tests read them.
- **Annotation types.**
- **The port's and the host's wiring:** types named `Port`, `Lifecycle`, `Backend`, plus `NetBackend`, `Actors.Memory` and `PluginContext`, and what they nest. Their Javadoc says plugins never use them; the loader port installs them before any plugin runs, so every test runs through them.

There is no list of exceptions, and none should be added for something that simply lacks a test: write the test.

**Nothing can look covered that isn't.** The check fails on a `@Covers` that is on a method that isn't a test (`@HadronTest`, or JUnit's `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate`) or is on a `@Disabled` one; that names a type outside `api`'s public types; that names an abstract event (it lists the concrete ones to name instead); that names a type that isn't an event without a method; or that names a method the type and its API supertypes don't have (it lists the ones they do).

**Economy providers** are covered through Hadron's own JSON provider: a test registering an `EconomyProvider` of its own would switch the store for the rest of the run, since once a plugin has provided accounts Hadron refuses to fall back to its file.

## Blind tests

`./gradlew contractBundle` writes `build/contract/`: Hadron's contract with nothing of its implementation, so a test agent writes tests from what Hadron promises rather than from what its code does ([design.md](design.md), "Built to be verified"). The folder stands alone: copy it anywhere and point the agent at its `README.md`.

- **`contract/src/`**: stubs of every public type of `api` and `test-api`, with their real signatures, annotations, generics and Javadoc, and every body a `throw new UnsupportedOperationException("contract stub")`. Constants keep their values (literals and expressions of them); any other field initializer becomes a marked `null`, 0 or false. Left out: private and package-private members and types, initializer blocks, and the port's wiring, by the same names `coverageCheck` skips (types named `Port`, `Lifecycle`, `Backend`, `NetBackend`, `Memory`, `PluginContext`, and what they nest). A class with no public or protected constructor says so in a comment. A kept signature that names a left-out type fails the task, so the rule is widened or the type made public; nothing dangles.
- **`contract/docs/`**: `events.md`, `platform.md`, `bridge-hooks.md` and `testing.md`, stripped by named rules in `gradle/hadron-contract.gradle.kts`: `events.md`'s "Core chokepoint" column becomes "Fired by" (core, bridge, or both, naming no code), `testing.md` loses "How a run works", "What Hadron's own tests cover" and this section, and a short list of exact passages is reworded or removed. Every rule must still find its text, so a doc edit that a rule no longer fits fails the task until someone reviews it. Links to docs outside the bundle become plain text.
- **`tests/`**: a Gradle project with an example of each tier, compiled against `libs/hadron-api.jar` and `libs/hadron-test-api.jar`, with `libs/hadron.jar` (the real mod) installed in its test server's `mods/`. `./gradlew test`, `hadronTest`, `hadronWatch`/`hadronRerun` and `coverageCheck` run there as in a plugin, using copies of `gradle/hadron-test.gradle.kts` (rewired to the bundle's `:server` project) and `gradle/hadron-coverage.gradle.kts`. The jars are repacked without timestamps, so the same sources give the same bytes.

**`contractLeakCheck` runs before anything is assembled** and fails the task if `contract/` holds: a file other than stubs and docs; a body other than the stub throw; a private member, a package-private type or class member, or an initializer block; a field initializer that isn't a constant; an enum constant with arguments or a body; a port-only type; a package outside Hadron's `api` and `test` packages; anywhere, any top-level class name from `common` or `neoforge` (whole word; "Platform APIs" is the one reviewed English use) or the word "mixin"; or any text the doc rules remove, including every code name from the removed column. The README and example tests are scanned for names too. It is `gradle/contract/ContractLeakCheck.java`, written apart from the stub writer (`ContractStubs.java`) so one's bug can't hide a leak from the other.

The blind agent never changes an assertion to match what it saw: it reports the failing test, what it expected, and the contract text it relied on, and a sighted agent triages it ([design.md](design.md)). What a blind agent can't do from the bundle: test what only the implementation defines (log lines, internal timing), or anything needing a client mod the harness doesn't stand in for (see "What it can't do yet").
