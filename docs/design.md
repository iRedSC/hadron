# Hadron design

Hadron is a Bukkit-shaped plugin host for modded Minecraft servers. Plugins are jars in `plugins/` that reload while the server runs, written against Minecraft's own classes plus Hadron's API. It does not run Bukkit plugins.

**The goal: a server owner installs or makes a plugin, and it acts on every mod's content without being edited, as long as the mods' bridges are installed.** A regions plugin protects against a mod's quarry, turret and drone the day that mod's bridge is installed, and Hadron tells the owner which bridges are missing. Everything below serves that, or the agent writing the plugin.

The first target is NeoForge 21.1 (Minecraft 1.21.1).

## Who it is for

Someone with no coding background asks their agent "make me a Hadron plugin for X", and gets a working jar to drop into `plugins/`. Every design choice is judged by whether it makes that request succeed without a human debugging it.

Plugin authors who code get the same benefits: hot reload, checked configs, and nothing to clean up on disable.

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

On Minecraft 1.21.1 Fabric runs with different class names than NeoForge, so a Fabric port renames each plugin jar's Minecraft references as it loads, as Paper does for Spigot plugins. If 26.1 and later ship unobfuscated, that step goes away.

### Mods: two paths

- **Official plugins use bridge APIs** (`bridge-depend: [ae2]` in plugin.yml): loader-neutral interfaces and events a bridge publishes. They stay write-once.
- **Anyone can use a mod directly** (`mod-depend: [ae2]`) for their own instance. That compiles against the mod and the loader, so it ties the plugin to one loader, and plugin.yml must say which (`loader: neoforge`). Hadron refuses it on another loader and says why. The build will fill `loader` in, as it does `minecraft`.

Pure-Java libraries that are the same everywhere (LuckPerms' API, WorldEdit's core API) are allowed in write-once plugins when plugin.yml declares them.

See [bridges.md](bridges.md) for what a bridge is for, how one is judged done, and the coverage report that tells owners which bridges they're missing.

## Minecraft versions

There is no wrapper layer. Plugins use `ServerPlayer`, `CommandSourceStack` and the rest directly. A wrapper would hold plugins back to what it covers, and porting raw code is cheap for an agent: the compiler points at every break.

A plugin is built for one Minecraft version. Porting it is the agent's job, and Hadron makes that job small:

1. **One line picks the version.** `hadron { minecraft = "1.21.4" }` in `build.gradle.kts`; the Gradle plugin brings the matching Hadron.
2. **A plugin can't run on the wrong version.** The build writes the Minecraft version into `plugin.yml`, and the host refuses a jar built for another one, saying which version it needs. It never loads and then crashes mid-game.
3. **A port guide per version jump** (`docs/porting/<from>-to-<to>.md`) lists every change that touches plugins, with before and after code. It covers what the agent's training doesn't know about the newer version. Each guide is written while porting Hadron itself, which hits the same changes first.
4. **Behavior tests move with the plugin.** The headless test run catches code that compiles on the new version but acts differently there.
5. **Plugin data lives in Hadron's config and storage**, in plain YAML or JSON, never in NBT layouts. A port then never migrates saved data, the one part of a port that could lose players' work.

Hadron's own classes change only when Minecraft forces them to, and each such change goes in the port guide like any other.

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
4. **Checking.** `./gradlew build` fails on every mistake that would only show at runtime, with a message that says the fix: a loader class in a write-once plugin, a missing main class, a command missing from `plugin.yml`, a node under a reserved root.
5. **Testing.** `./gradlew hadronTest` runs the plugin in a headless server with scripted fake players and reports pass or fail. The agent proves the plugin works before claiming it does.
6. **Trying it.** `./gradlew runServer` starts a dev server with the plugin loaded and reloads it on every build. Client MCP lets the agent join that server and play through the feature.
7. **Delivering.** The build ends by printing the jar's path and the one step for the user: put it in the server's `plugins/` folder, with Hadron installed.

Every error a plugin hits at runtime (a failed load, an exception in a listener) is written to say what went wrong and where, since an agent fixes what the message tells it.

## Bundled plugins

Hadron ships lightweight plugins server owners expect: essentials-style commands, regions, placeholders, holograms, chat formatting, and the LuckPerms provider. They are write-once plugins, written the way the skill tells agents to write plugins, so they double as its examples, and each Hadron port runs them first, which is how the port guides get written.

**Regions** are Hadron's own, written from scratch under MIT, using FTAM's WorldGuard plugin only as a description of what it should do (commands, flags, nesting), never as code. It has its own selection wand and protects through core's events and the actor stack, so it covers every mod a bridge covers. WorldEdit selections come from a small separate plugin, licensed GPL like WorldEdit, so no GPL code reaches Hadron or the regions plugin.

## Out of scope

- Running Bukkit plugins.
- Plugins adding blocks, items or other registry content (needs a mod).
- A rank system (LuckPerms has one).
- FTAM's serials and anti-dupe system (stays private).

## Order of work

1. ~~Plugin host~~: done. Loads, enables, disables and hot-reloads jars, each with its own class loader, and undoes everything a plugin registered (listeners, commands, tasks, services, permission nodes). Ported from FTAM's Foundation.
2. ~~Loader-neutral split~~: done. `api` and `common` on vanilla Minecraft, `neoforge` as the port, Hadron's own event bus, `loader:` for plugins that use mods directly, reserved node roots.
3. Hadron's API, first slice: config and storage, placeholders, the LuckPerms provider.
4. The actor stack and the events in [events.md](events.md) at their chokepoints, with a benchmark for the chokepoint cost; then the coverage report ([bridges.md](bridges.md)).
5. Gradle plugin and project template, with the build checks.
6. The skill, written against what exists, then tested by asking fresh agents for real plugins and fixing whatever they trip on.
7. Headless test runner.
8. Bundled plugins; pilot bridges (Mekanism, AE2, Superb Warfare); the client companion.
