# Hadron design

Hadron is a Bukkit-shaped plugin host for NeoForge servers. Plugins are jars in `plugins/` that reload while the server runs, written against Minecraft's and NeoForge's own classes plus Hadron's API. It does not run Bukkit plugins.

The first target is NeoForge 21.1 (Minecraft 1.21.1).

## Who it is for

Someone with no coding background asks their agent "make me a Hadron plugin for X", and gets a working jar to drop into `plugins/`. Every design choice is judged by whether it makes that request succeed without a human debugging it.

Plugin authors who code get the same benefits: hot reload, checked configs, and nothing to clean up on disable.

## Layers

```
plugins (reloadable jars)    Minecraft, NeoForge and Hadron classes directly
bridges (mods)               one per third-party mod: mixins into it, turned into Hadron events
core (mod)                   plugin host, reload and undo, Hadron's API, vanilla events
client companion (mod)       later: lets plugins draw UI on clients that have it
```

- **Core** never contains mod-specific hooks. A bridge owns the events for exactly one mod id, so two bridges can't compete over the same mod.
- **Bridges** exist because a reloadable plugin can never add a mixin or a registry entry. Anything that needs one is a mod.

## Versions

There is no wrapper layer. Plugins use `ServerPlayer`, `CommandSourceStack` and the rest directly, and Hadron's API (`dev.iredsc.hadron.api`, public, in `core`) takes and returns those types. A wrapper would hold plugins back to what it covers, and porting raw code is cheap for an agent: the compiler points at every break.

A plugin is built for one Minecraft version. Porting it is the agent's job, and Hadron makes that job small:

1. **One line picks the version.** `hadron { minecraft = "1.21.4" }` in `build.gradle.kts`; the Gradle plugin brings the matching NeoForge and Hadron.
2. **A plugin can't run on the wrong version.** The build writes the Minecraft version into `plugin.yml`, and the host refuses a jar built for another one, saying which version it needs. It never loads and then crashes mid-game.
3. **A port guide per version jump** (`docs/porting/<from>-to-<to>.md`) lists every change that touches plugins, with before and after code. It covers what the agent's training doesn't know about the newer version. Each guide is written while porting Hadron itself, which hits the same changes first.
4. **Behavior tests move with the plugin.** The headless test run catches code that compiles on the new version but acts differently there.
5. **Plugin data lives in Hadron's config and storage**, in plain YAML or JSON, never in NBT layouts. A port then never migrates saved data, the one part of a port that could lose players' work.

Hadron's own classes change only when Minecraft forces them to, and each such change goes in the port guide like any other.

## What makes "make me a plugin for X" work

The agent is working in the user's folder, not this repo. So everything it needs ships with the author kit, and each step has a check the agent can run itself.

1. **Discovery.** A skill (Claude Code plugin, Codex skill, and an `AGENTS.md` in every new project) tells the agent Hadron exists, when to use it, and the workflow below. Installed once, it triggers on "Hadron plugin" in an empty folder.
2. **Setup.** A new project is three files: `settings.gradle.kts`, a `build.gradle.kts` applying the Hadron Gradle plugin, and the main class. The Gradle plugin owns versions, NeoForge setup, the `plugin.yml` check and the dev server. Java 21 is the one prerequisite the agent must check for, since the Gradle wrapper needs a JVM to start.
3. **Writing.** Names match Bukkit's wherever the meaning matches, so the agent's first guess compiles. The API sources and docs ship in the sources jar, so the agent reads the real API instead of remembering a different one.
4. **Checking.** `./gradlew build` fails on every mistake that would only show at runtime, with a message that says the fix: a missing main class, a direct registration on NeoForge's buses, a command missing from `plugin.yml`.
5. **Testing.** `./gradlew hadronTest` runs the plugin in a headless server with scripted fake players (built on NeoForge's GameTest) and reports pass or fail. The agent proves the plugin works before claiming it does.
6. **Trying it.** `./gradlew runServer` starts a dev server with the plugin loaded and reloads it on every build. Client MCP lets the agent join that server and play through the feature.
7. **Delivering.** The build ends by printing the jar's path and the one step for the user: put it in the server's `plugins/` folder, with Hadron installed.

Every error a plugin hits at runtime (a failed load, an exception in a listener) is written to say what went wrong and where, since an agent fixes what the message tells it.

## Bundled plugins

Hadron ships lightweight plugins server owners expect: essentials-style commands, ranks and permissions, regions, placeholders, holograms, chat formatting. They are written the way the skill tells agents to write plugins, so they double as its examples, and each Hadron port ports them first, which is how the port guides get written.

## Out of scope

- Running Bukkit plugins.
- Plugins adding blocks, items or other registry content (needs a mod).
- FTAM's serials and anti-dupe system (stays private).

## Order of work

1. ~~Plugin host~~: done. Loads, enables, disables and hot-reloads jars, each with its own class loader, and undoes everything a plugin registered (listeners, commands, tasks, services, permission nodes). Ported from FTAM's Foundation.
2. Hadron's API, first slice: commands, scheduler, config and storage, permissions, services, placeholders.
3. Gradle plugin and project template, with the build checks.
4. The skill, written against what 1 to 3 actually do, then tested by asking fresh agents for real plugins and fixing whatever they trip on.
5. Headless test runner.
6. Bundled plugins, then bridges and the client companion.
