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

- **Tests live in the plugin's `src/test/java`**, as methods annotated `@HadronTest` taking a `TestContext`. No other setup.
- **Each test gets its own space:** a fresh void world made with `Worlds.create`, removed afterwards, so tests never see each other's blocks or entities. One server runs all of a plugin's tests, for speed.
- **Test players are real players** (`TestPlayer`, not a fake player: Hadron sees them as `Actor.Player`). Each is a `ServerPlayer` joined through the real login path over an in-memory connection, so everything a client would cause goes through the code it would: vanilla's packet handlers, Hadron's events, the plugin's listeners, other mods. Their actions are sent as the packets a client sends (break, place, use, attack, click a slot, drop, chat, run a command, move), and everything the server sends them is recorded.
- **Observing** is reading the world and the recorded packets: block states, inventories, balances, persistent data, the chat lines a player received, whether a player was sent an entity at all (so vanish is testable), what the tab list holds.
- **Time** is explicit: `t.tick(n)` runs server ticks; `t.await(condition, ticks)` waits up to a limit.
- **Assertions say the fix:** a failure names what was expected, what was there, and where.

## What a run reports

One line per test (`PASS shop.buyingTakesMoney`), the failures in full with their message and the line in the test, and a summary. The task fails the build when any test fails, so an agent can't miss it. A test that throws, hangs past its limit, or leaves the server unhealthy fails with that said plainly.

## What Hadron's own tests cover

The paths no real client has exercised yet, first:

- **Cancels fully undo:** for each cancellable event, cancel it and check nothing changed and nothing was spent.
- **Changes apply:** for each event with setters, change it and check the world shows the change (damage dealt, drops, messages, teleport destination, craft result, MOTD).
- **Actors:** each action is attributed to the right player, machine, entity or `Server`.
- **Platform APIs:** visibility between two fake players (spawn, tab list, sounds, `/list`), name tags, packet listeners, persistent data, the economy, worlds.
