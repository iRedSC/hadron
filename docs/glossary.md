# Glossary

One meaning per word, in code, docs and reviews. A new concept gets a line here in the same change that introduces it. Each line links to where the idea is decided.

## Pieces

- **Plugin**: a jar in `plugins/` written against Minecraft plus Hadron's `api`, reloaded while the server runs. Never adds blocks or items. ([design](design.md))
- **Write-once plugin**: a plugin that touches no loader or mod classes, so it runs on every loader Hadron supports. The default.
- **Mod**: content loaded by the loader (NeoForge, Fabric), not by Hadron. Mods are what plugins act on.
- **Loader**: NeoForge or Fabric. Only a port knows which one is running.
- **Port**: Hadron's code for one loader (`neoforge/`): a `Platform`, translation from the loader's events to Hadron's, and the mod class. The only part rewritten per loader.
- **`Platform`**: the interface through which `common` asks what only the loader knows.
- **Bridge**: a mod that makes one other mod speak Hadron's events, by attributing its actions and routing its effects that skip vanilla. ([bridges](bridges.md))
- **Claim**: a bridge's `Bridge.claim` on the mod ids it translates, made in its mod's constructor. Each mod id has one claim at most, and a bridge fires events only through its claim. ([bridges](bridges.md#claiming-a-mod))
- **`bridge-depend:`**: a plugin that needs a mod's bridge installed, for that bridge's API. Stays write-once.
- **Bridge API**: a loader-neutral interface or event a bridge publishes for extras, such as a network's contents. Official plugins use it with `bridge-depend:` and stay write-once.
- **Promotion**: adding a core event for what two official bridges' events, or their proposed leaves, share, made the superclass of each so no plugin depending on those events breaks. The exception is a data kind moving from `EntityDataChangeEvent` to a new leaf, which plugins listening for that kind must follow. ([bridges](bridges.md#promotion-when-bridges-events-become-core))
- **`mod-depend:`**: a plugin that compiles against a mod directly. It must declare `loader:`, and Hadron refuses it on any other loader.
- **`api-depend:`**: a plugin that uses only a mod's pure-Java API (LuckPerms' `net.luckperms.api`). The mod must be installed; the plugin stays write-once.
- **Helper**: API for something Minecraft makes hard (NPCs, bulk block edits, packets) that takes and returns Minecraft's own types and keeps the topic's version-specific code inside Hadron. Sits beside raw access, never wraps it. ([design](design.md#minecraft-versions))
- **Rename table**: the classes, methods and fields Minecraft renamed in one version jump. Hadron rewrites an older plugin's references through it as the plugin loads.
- **Link check**: Hadron resolving every Minecraft reference in a plugin jar at load, refusing the jar and naming each one that's missing.

## Events and actors

- **Core event**: an event every port fires at a vanilla chokepoint. ([events](events.md#rules))
- **Bridge event**: an event with no vanilla operation behind it (mana, claim trust), so only bridges fire it.
- **Chokepoint**: a vanilla method every mod passes through, such as `Level.setBlock`, where core intercepts.
- **Parent event**: an event higher in the hierarchy. A plugin listening to it hears every event below it, including ones added later; a bridge with no fitting event fires the nearest parent.
- **Actor**: who or what caused an action: `Player`, `FakePlayer`, `Machine`, `EntityActor`, `Server`, `Nature` or `Unknown`. ([events](events.md#actors))
- **Responsible**: the person who answers for an actor, such as a machine's owner or a gun's shooter.
- **Controller**: the person directing an actor right now, if different, such as a driver or a charmer. Protection uses the controller first.
- **Authority**: whose permissions an actor's commands run with.
- **Acting player**: `getActingPlayer()`, the online controller, otherwise the online responsible player. `Optional`, because a machine may have neither.
- **Operation**: related events from one action, sharing an id and actor. ([events](events.md#operations-and-cancelling))
- **Opening event**: the cancellable event fired before an operation's first change. Cancelling it stops the whole action and refunds its cost.
- **Per-target event**: one target of an operation. Cancelling it spares only that target.
- **After event**: an expiry or revocation, marked *(after)*. It can be observed but not cancelled.
- **Simulation**: a mod testing an action before doing it. Never fires an event.
- **Per-player copy**: one player's own contents of an inventory at a block or entity (a Lootr chest), held by `InventoryHolder.PerPlayer`, so two players' copies are never one inventory. ([events](events.md#containers-inventoryevent))
- **Data kind**: the id on `EntityDataChangeEvent` saying whose data changed, in the mod's namespace, such as `minecolonies:job`.

## Gaps and coverage

- **Watched area**: a box a plugin named with `Areas.watch`; a block change with nobody behind it fires there as `Unknown` instead of not at all. ([bridges](bridges.md#the-chokepoint-cost))
- **Attribution gap**: an action Hadron sees but can't tie to a person (`Unknown`, or no owner). Fails closed: protection treats it as an outsider. ([bridges](bridges.md#two-kinds-of-gap))
- **Interception gap**: an effect that skips every chokepoint, so no event fires and nothing can deny it. Only a bridge closes it.
- **Fail closed**: an uncertain actor is denied where outsiders are denied, so a missing bridge shows up as a machine that won't work, never as silent griefing.
- **Acceptance kit**: `t.acceptance(box)`, the bridge acceptance test as an in-game helper: a protection plugin and a logger that know no mod, and assertions that name each interception or attribution gap they find. ([bridges](bridges.md#the-acceptance-kit))
- **Acceptance kit**: `t.acceptance(box)`, the bridge acceptance test as an in-game helper: a protection plugin and a logger that know no mod, and a record of the box. A refused step must change nothing, which proves interception; `refuse(kind)` lets a trusted machine run while one kind is refused. ([bridges](bridges.md#the-acceptance-kit))
- **Coverage report**: Hadron's list, at startup and on `/hadron coverage`, of installed mods without a bridge and what that leaves uncovered. Not the same as `coverageCheck`.
- **Trace**: `/hadron trace`, which prints events live as they fire, with actor, operation and outcome.

## Permissions

- **Node**: a permission string such as `hadron.trace` or `use.ae2.wireless_terminal`. ([design](design.md#permissions))
- **Reserved root**: a node root Hadron owns (`use`, `place`, `break`, `open`, `attack`, `command`, `packet`, `hadron`). A plugin declaring a node under one fails to load.

## Testing

- **Fast tier**: JUnit tests with Minecraft's registries and no server, for anything that decides. `./gradlew test`. Runs on every PR. ([testing](testing.md))
- **In-game tier**: `@HadronTest`s in a headless server with real players over an in-memory connection, for anything that acts. `hadronTest`. Runs before a release.
- **Suite**: one plugin's in-game tests. Suites share a server, but only the running suite's plugin is enabled.
- **Self-tests**: Hadron's own in-game suite, in `selftest/`.
- **Warm server**: a test server kept running by `hadronWatch`, so `hadronRerun` reruns a suite without a boot.
- **`@Covers`**: what a test declares it proves. `coverageCheck` fails the build if any shipped event or public API method has none.
- **`@FiredBy`**: on every event, who fires it: core on every path (`FULL`), core for vanilla's paths and bridges for their mods' (`PARTIAL`), or only bridges (`NONE`). `bridgeHooksCheck` holds it to what core constructs and generates [bridge-hooks.md](bridge-hooks.md)'s tables from it.
- **Contract bundle**: the output of `contractBundle`: API stubs, the spec docs without implementation detail, and prebuilt jars, for writing tests blind.
- **Blind test**: a test written from the contract bundle alone. Its author never edits an assertion to match what they observed.
- **Triage**: a sighted agent's sorting of a blind test's failure into a Hadron bug, a test bug or a contract gap.
- **Mutation testing**: breaking Hadron on purpose to count how many breaks the tests catch.

## Review

- **Final review**: the review every PR touching `.github/council-paths.txt` needs to merge: a large model reviews the head, in as many passes as it takes, until it finds nothing more serious than `stop_at` (`.github/council.toml`). The `Council` check reads its one line in the PR description.
- **Council**: the fuller review for sizable changes whose effect is hard to measure, run by the author's judgement before the final review: one pass of dumb questions, council rounds of prioritised findings (P0 to P4, in coding-standards.md) until a round finds nothing more serious than `stop_at` or `max_rounds` pass (then the owner decides), and one pass of explanations from a history-free snapshot.
