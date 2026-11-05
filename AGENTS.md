# Working on Hadron

Read [docs/design.md](docs/design.md) first. It holds the decisions and why.

- `api/`: what plugins compile against, built on vanilla Minecraft only, so nothing in it can name a loader class. `common/`: the plugin host, also vanilla only. `neoforge/`: the NeoForge port, the only part rewritten per loader (a `Platform`, event translation, the mod class). Build with `./gradlew build`; `./gradlew :neoforge:runServer` starts a dev server.
- Hadron's API takes Minecraft's own types; never wrap them, and never let a loader type into `api` or `common`. Any change to the API that Minecraft forces goes in that version's port guide in `docs/porting/`.
- `examples/hello/`: a plugin built the way authors will. `./gradlew :examples:hello:deploy` drops it into the running dev server, which reloads it within about two seconds. `python3 scripts/rcon.py "plugins" "hello"` runs console commands on the dev server, so an agent can test without a player.
- Java 21. If `java` isn't on the PATH, point `JAVA_HOME` at a JDK 21.

Hadron's real users are agents writing plugins for people who don't code. When adding API:

- Name it what an agent would guess, which usually means Bukkit's name when the meaning matches.
- Prefer a build-time check over documentation. A mistake the build refuses can't ship.
- Write every error message so the reader knows the fix without opening the source.
