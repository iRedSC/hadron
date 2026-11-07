# Testing

`./gradlew hadronTest` runs a plugin in a headless server with scripted players and reports pass or fail, so an agent proves a plugin works before claiming it does. Hadron tests itself the same way: every event, cancel, change and API is covered by tests written with the harness plugin authors use.

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
- A test player's client is an in-memory connection to the server's memory channel, with the vanilla client's half of the protocol: login, the configuration phase (as an unmodded client), keep-alives, teleport confirmations followed by a position report, chunk-batch acknowledgements, a position reminder every 20 ticks. After every action and tick, the harness lets packets travel both ways until neither side has anything left, so the next line sees the result.
- Suites requested in one Gradle invocation share one server, but never each other's listeners: while one suite runs, every other suite's plugin is disabled. The shared task lives in `gradle/hadron-test.gradle.kts` until the Hadron Gradle plugin takes it over.

## What it can't do yet

- **Players have no physics**: they don't fall or get pushed; their client reports wherever the server has them. Moves are split into steps the server accepts as one client's.
- **The client is unmodded**: it never answers a mod's configuration task, so a mod that requires its client mod blocks joins (the join fails after 200 ticks and says so), and mod payloads are recorded, never answered.
- **Not every client habit is copied**: a right-click sends only the main-hand packet, without the off-hand or use-in-air follow-ups a client sends when the first does nothing. There is no action yet for closing a window, the enchanting table's buttons, anvil renaming, sign or book editing, respawning, sneaking, sprinting, flying, swapping hands, leaving a bed, releasing a bow, looking somewhere, or riding input; tests send those packets to the server's handler directly, which skips the per-packet attribution. Chat is unsigned, and a mod's own network messages can't be sent.
- **After a respawn, `TestPlayer.player()` still returns the old, dead player.**
- **Console commands run in the overworld**: use `server().createCommandSourceStack().withLevel(t.level())` to run one in the test's world.
- **Weather, time and the random tick speed are shared with the overworld**, so a test that changes them puts them back.
- **Not covered by Hadron's own tests yet:** fishing bites and catches (they need open water and a random wait), `PlayerModPacketEvent`, mobs throwing, picking up or equipping items, hopper minecarts, `ChunkUnloadEvent`, and a portal lit by fire (test worlds aren't the overworld or the nether).
- The run is wired into `examples/hello` and `selftest` with `apply(from = ...)`; other projects get it from the Gradle plugin.

## What Hadron's own tests cover

`./gradlew :selftest:hadronTest` runs 245 tests, all passing, in `selftest/src/test/java`, by family: blocks and world (81), entities and players (91), items (31), inventories (5), platform APIs (29: persistent data, economy, visibility, display names and packets, worlds), and the first ten in `EventTests` and `PlatformTests`. For every shipped event that a test can drive, they check that:

- **it fires with the right actor:** player, machine, entity, `Server` or nature;
- **a cancel fully undoes:** nothing changed and nothing spent, no item lost or duplicated, no durability gone;
- **changes apply:** damage dealt, drops, messages other players see, teleport destinations, craft results, the server list's MOTD;
- **one effect is one event** where firing twice is a risk;
- **attribution lasts:** an arrow's hit is still its shooter's after flight, a logout and a reload.

Writing them found 11 Hadron bugs, all fixed: crafting results and the crafter ignoring a plugin's result, a cancelled cursor drop falling anyway, a hopper's pickup blamed on the item, dimension suggestions never asking the server, a live stack handed to `EntityUseEvent`, `EntityVelocityEvent` never firing, a cancelled TNT lighting setting fire anyway, a dispenser leaving both TNT and primed TNT, sticky pistons' pulls reported as nothing, and cancelled placements costing the tool its durability.
