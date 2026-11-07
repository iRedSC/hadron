# Hadron design

Hadron is a Bukkit-shaped plugin host for modded Minecraft servers. Plugins are jars in `plugins/` that reload while the server runs, written against Minecraft's own classes plus Hadron's API. It does not run Bukkit plugins.

**The goal: a server owner installs or makes a plugin, and it acts on every mod's content without being edited, as long as the mods' bridges are installed.** A regions plugin protects against a mod's quarry, turret and drone the day that mod's bridge is installed, and Hadron tells the owner which bridges are missing. Everything below serves that, or the agent writing the plugin.

The first target is NeoForge 21.1 (Minecraft 1.21.1).

## Who it is for

Someone with no coding background asks their agent "make me a Hadron plugin for X", and gets a working jar to drop into `plugins/`. Every design choice is judged by whether it makes that request succeed without a human debugging it.

Plugin authors who code get the same benefits: hot reload, checked configs, and nothing to clean up on disable.

## Built to be verified

Hadron makes promises (a cancel fully undoes, a machine's break is its owner's, a vanished player is never sent), and its users are agents who can't watch a server to see whether they hold. So every promise is something a machine can check, and the checks are enforced, not hoped for.

- **Two tiers of tests.** Fast tests run on the JVM with Minecraft's registries but no server or world, in milliseconds, for everything that decides rather than acts. In-game tests run in a headless server with real players over an in-memory connection, for everything that touches the world, a player or a packet ([testing.md](testing.md)). A warm test server reruns a plugin's in-game tests in about a second after each build, so iterating doesn't pay a server boot each time. There is no fake Minecraft: a mock that disagrees with the real game is a test that passes while the plugin is broken.
- **Logic in the middle, Minecraft at the edges.** Decisions (who is the actor, which event fits, refuse or allow, the new balance) live in plain functions the fast tier tests; mixins and listeners only translate between Minecraft and those functions.
- **Every claim has a test, and the build checks it.** A test declares what it proves with `@Covers`. Every shipped event, bridge contract and public API method must be covered, or the build fails and names what isn't: an event ships only with its producer and a test.
- **Deterministic.** Time is server ticks, randomness can be seeded, and nothing in Hadron is timed by the wall clock where a test can't control it.
- **Results machines read.** Test reports come as JSON as well as text, so an agent reads exactly which test failed and why.
- **Tests written blind to the implementation.** `contractBundle` writes a folder with only the contract: API stubs (signatures and Javadoc, no bodies), the spec docs without their implementation detail, and a test project wired to prebuilt Hadron jars. A test agent works only there, so its tests come from what Hadron promises, not from what the code happens to do, and a contract too vague to test from shows up as a gap, the same gap a plugin-writing agent would fall into. The blind agent never changes an assertion to match what it observed: a failure goes to a sighted triage agent, which decides whether Hadron is wrong (fix the code), the test misread the contract (fix the test), or the contract is unclear (fix the docs, then the test), and lists every docs change it made.
- **Strictness is measured.** Mutation testing breaks Hadron on purpose and counts how many breaks the tests catch: PIT on the fast tier, and sampled mutants of the riskiest code (cancels, attribution, suppression) in game. A break no test notices is a test to write.
- **Verifiable live, too.** `/hadron trace` shows events as they fire on a real server, with actor, operation and outcome, so an owner or agent can see that a mod's machines are covered, alongside the coverage report for mods without bridges.

## Write a plugin once

A plugin is written once and runs on every loader Hadron supports. Only Hadron and its bridges are ported to a new loader.

```
plugins (reloadable jars)    Minecraft + Hadron's api; written once
bridge APIs                  one per third-party mod, loader-neutral; what official plugins use to reach a mod
─────────────── loader-neutral above, ported per loader below ───────────────
bridges (mods)               the bridge API on one loader: mixins and glue into that mod
hadron (mod)                 common (shared host) + the loader port (Platform, event translation, mod class)
client companion (mod)       later: lets plugins draw UI on clients that have it
```

The modules make this a compile error rather than a rule:

- **`api`** is what plugins compile against. It is built on vanilla Minecraft only (NeoForm, no NeoForge), so nothing in it can name a loader class, and a plugin built against it can't either: a NeoForge import doesn't compile.
- **`common`** is the plugin host: class loading, reload and undo, scheduler, services, Brigadier commands, Hadron's event bus. Also vanilla only. What only a loader knows comes through `Platform`.
- **`neoforge`** is the port: `Platform`, the translation from NeoForge's events to Hadron's, and the mod class. A Fabric port rewrites only this.

Hadron owns its events (`dev.iredsc.hadron.api.event`), named after Bukkit's: `Listener`, `@EventHandler(priority, ignoreCancelled)`, `Cancellable`, `PlayerJoinEvent`, `BlockBreakEvent`. They carry Minecraft's own types, and every action carries an actor saying who is behind it. They are the vocabulary plugins and bridges share, so the whole set is designed up front in [events.md](events.md): an event added late is one every existing plugin was blind to. Each port fires them from its loader's events where it has them, and from mixins where it doesn't; an event ships only once every port fires it.

On Minecraft 1.21.1 Fabric runs with different class names than NeoForge, so a Fabric port renames each plugin jar's Minecraft references as it loads, as Paper does for Spigot plugins. If 26.1 and later ship unobfuscated, that remapping goes away; the same load step still applies renames between Minecraft versions ([Minecraft versions](#minecraft-versions)).

### Mods: two paths

- **Official plugins use bridge APIs** (`bridge-depend: [ae2]` in plugin.yml): loader-neutral interfaces and events a bridge publishes. They stay write-once.
- **Anyone can use a mod directly** (`mod-depend: [ae2]`) for their own instance. That compiles against the mod and the loader, so it ties the plugin to one loader, and plugin.yml must say which (`loader: neoforge`). Hadron refuses it on another loader and says why. The build will fill `loader` in, as it does `minecraft`.

Pure-Java libraries that are the same everywhere (LuckPerms' API, WorldEdit's core API) are allowed in write-once plugins when plugin.yml declares them.

See [bridges.md](bridges.md) for what a bridge is for, how one is judged done, and the coverage report that tells owners which bridges they're missing.

## Minecraft versions

There is no wrapper layer. Plugins use `ServerPlayer`, `CommandSourceStack` and the rest directly. A wrapper hides whatever it doesn't cover: when popular Bukkit plugins reach past Bukkit's API, it is for NPCs, bulk block edits, packets, advancements and player profiles, often by reflection on class names, because Bukkit gave them no way in. And the code that reaches Minecraft directly barely changes between versions: Citizens keeps a 34,662-line module per Minecraft version that differs by 2 lines from 26.2 to 26.3, and WorldEdit's adapters differ by 2 to 4 lines a jump. What does change, Hadron absorbs once, so a plugin needs no wrapper to survive a version bump.

A plugin is built for one Minecraft version and keeps loading on later ones until Minecraft changes something it uses:

1. **One line picks the version.** `hadron { minecraft = "1.21.4" }` in `build.gradle.kts`; the Gradle plugin brings the matching Hadron.
2. **Renames are absorbed once, in Hadron.** Each version jump has a rename table beside its port guide, listing the classes, methods and fields Minecraft renamed. A plugin built for an older version has its Minecraft references rewritten through every table between its version and the server's as it loads, by the same step a Fabric port uses for class names. A tool proposes the entries by matching both versions' names; each is confirmed while porting Hadron, which hits every rename first.
3. **A plugin that doesn't link never loads.** After renaming, Hadron resolves every Minecraft class, method and field the jar references against the running server, and refuses the jar if any is missing, listing each one with the port guide entry that covers it. Java links a call only when it first runs, so without this check the jar would load and then crash the first time a player reached the broken path. A jar built for a newer version than the server is refused outright. The build runs the same check against every newer version Hadron supports and prints where the plugin breaks (`breaks on 1.21.4: ServerPlayer.getStats()`), so an agent sees it before anyone deploys.
4. **A port guide per version jump** (`docs/porting/<from>-to-<to>.md`) lists every change renaming can't absorb (a changed signature, a removed method, new behavior), with before and after code. It covers what the agent's training doesn't know about the newer version, and is written while porting Hadron itself.
5. **Behavior tests move with the plugin.** The headless test run catches code that links on the new version but acts differently there, the one kind of break neither renaming nor the link check can see.
6. **Plugin data lives in Hadron's config and storage**, in plain YAML or JSON, never in NBT layouts. A port then never migrates saved data, the one part of a port that could lose players' work.

**Helpers fill what Minecraft makes hard, once.** Where plugins elsewhere reach into Minecraft's internals because their API left a gap (NPCs, bulk block edits, packets), Hadron adds a helper: API that takes and returns Minecraft's own types and keeps that topic's version-specific code inside Hadron. A helper sits beside raw access and never replaces it, so nothing is hidden; a version change in its topic is fixed in the helper, for every plugin at once.

Hadron's own classes change only when Minecraft forces them to, and each such change goes in the port guide like any other.

Today the host still refuses any jar built for another Minecraft version; the rename tables and link check come with Hadron's first port to a second version.

## Permissions

- **Ranks come from LuckPerms**, which has a NeoForge build, through a provider plugin on its API. Hadron doesn't ship a rank system of its own. Without LuckPerms, every node falls back to its default (everyone, ops, or nobody).
- **Mods using NeoForge's PermissionAPI** reach LuckPerms directly when it's installed; Hadron registers its own handler only when it isn't, so there is always one source of truth.
- **Hadron makes nodes for everything a mod adds**, with no bridge: `use.<modid>.<path>`, `place.`, `break.`, `open.`, `attack.` by registry id, `command.<name>` for every command, `packet.<modid>.<payload>` for mods' network messages. A server owner can write `use.ae2.*: false` on day one.
- **Those roots are reserved**: `use`, `place`, `break`, `open`, `attack`, `command`, `packet`, `hadron`. A plugin declaring a node under one fails to load, with the fix in the message.

The reserved roots are enforced now; the registry-id nodes, the PermissionAPI handler and the LuckPerms provider are still to build.

## What makes "make me a plugin for X" work

The agent is working in the user's folder, not this repo. So everything it needs ships with the author kit, and each step has a check the agent can run itself.

1. **Discovery.** A skill (Claude Code plugin, Codex skill, and an `AGENTS.md` in every new project) tells the agent Hadron exists, when to use it, and the workflow below. Installed once, it triggers on "Hadron plugin" in an empty folder.
2. **Setup.** A new project is three files: `settings.gradle.kts`, a `build.gradle.kts` applying the Hadron Gradle plugin, and the main class. The Gradle plugin owns versions, the `plugin.yml` check and the dev server. Java 21 is the one prerequisite the agent must check for, since the Gradle wrapper needs a JVM to start.
3. **Writing.** Names match Bukkit's wherever the meaning matches, so the agent's first guess compiles. The API sources and docs ship in the sources jar, so the agent reads the real API instead of remembering a different one.
4. **Checking.** `./gradlew build` fails on every mistake that would only show at runtime, with a message that says the fix: a loader class in a write-once plugin, a missing main class, a command missing from `plugin.yml`, a node under a reserved root, Error Prone's errors, static mutable state (which a reload strands), and a test that sleeps, reads the wall clock or uses unseeded randomness. The last two are short deny-lists, not proofs: they refuse the common mistakes (a non-final static field; a static final collection, array, atomic, ThreadLocal or StringBuilder; a stateful enum constant; Thread.sleep, System.nanoTime, now(), unseeded Random and the like), never refuse valid code, and don't catch other ways to hide state or read the clock. Each check's Javadoc lists exactly what it refuses.
5. **Testing.** `./gradlew hadronTest` runs the plugin in a headless server with scripted players and reports pass or fail. The agent proves the plugin works before claiming it does ([testing.md](testing.md)).
6. **Trying it.** `./gradlew runServer` starts a dev server with the plugin loaded and reloads it on every build. Client MCP lets the agent join that server and play through the feature.
7. **Delivering.** The build ends by printing the jar's path and the one step for the user: put it in the server's `plugins/` folder, with Hadron installed.

Every error a plugin hits at runtime (a failed load, an exception in a listener) is written to say what went wrong and where, since an agent fixes what the message tells it.

## Bundled plugins

Hadron ships lightweight plugins server owners expect: essentials-style commands, regions, placeholders, holograms, chat formatting, and the LuckPerms provider. They are write-once plugins, written the way the skill tells agents to write plugins, so they double as its examples, and each Hadron port runs them first, which is how the port guides get written.

**Regions** are Hadron's own, written from scratch under MIT, using FTAM's WorldGuard plugin only as a description of what it should do (commands, flags, nesting), never as code. It has its own selection wand and protects through core's events and the actor stack, so it covers every mod a bridge covers. WorldEdit selections come from a small separate plugin, licensed GPL like WorldEdit, so no GPL code reaches Hadron or the regions plugin.

## Out of scope

- Running Bukkit plugins.
- Plugins adding blocks, items or other registry content (needs a mod). Worlds are the exception: a plugin can create, load and unload worlds while the server runs, from the dimension types and generators the server already has ([platform.md](platform.md)).
- A rank system (LuckPerms has one).
- FTAM's serials and anti-dupe system (stays private).

## Order of work

1. ~~Plugin host~~: done. Loads, enables, disables and hot-reloads jars, each with its own class loader, and undoes everything a plugin registered (listeners, commands, tasks, services, permission nodes). Ported from FTAM's Foundation.
2. ~~Loader-neutral split~~: done. `api` and `common` on vanilla Minecraft, `neoforge` as the port, Hadron's own event bus, `loader:` for plugins that use mods directly, reserved node roots.
3. Hadron's API, first slice: config and storage, placeholders, the LuckPerms provider.
4. The actor stack and the events in [events.md](events.md) at their chokepoints, with a benchmark for the chokepoint cost; then the coverage report ([bridges.md](bridges.md)).
5. Gradle plugin and project template, with the build checks.
6. The skill, written against what exists, then tested by asking fresh agents for real plugins and fixing whatever they trip on.
7. ~~Headless test runner~~: done ([testing.md](testing.md)). `hadronTest` for `examples/hello` and `selftest`; moves into the Gradle plugin with step 5.
8. Bundled plugins; pilot bridges (Mekanism, AE2, Superb Warfare); the client companion.
9. Helpers for NPCs, bulk block edits and packets.
10. A second Minecraft version: its rename table, the load-time link check, and the first port guide.
