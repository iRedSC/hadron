# Working on Hadron

Read [docs/design.md](docs/design.md) first. It holds the decisions and why. Words mean what [docs/glossary.md](docs/glossary.md) says. Reviewing a change? Check it against [docs/coding-standards.md](docs/coding-standards.md). A PR touching a path in [.github/council-paths.txt](.github/council-paths.txt) merges only after a final review of its head ([.claude/skills/final-review/SKILL.md](.claude/skills/final-review/SKILL.md)); sizable changes whose effect is hard to measure get the full council first ([.claude/skills/council/SKILL.md](.claude/skills/council/SKILL.md)). Write only what the reviewer returned: the gate trusts that line in place of a person.

- `api/`: what plugins compile against, built on vanilla Minecraft only, so nothing in it can name a loader class. `common/`: the plugin host, also vanilla only. `neoforge/`: the NeoForge port, the only part rewritten per loader (a `Platform`, event translation, the mod class). Build with `./gradlew build`; `./gradlew :neoforge:runServer` starts a dev server.
- Hadron's API takes Minecraft's own types; never wrap them, and never let a loader type into `api` or `common`. Any change to the API that Minecraft forces goes in that version's port guide in `docs/porting/`.
- `test-api/`: what plugins' tests compile against (`@HadronTest`, `TestContext`, `TestPlayer`), vanilla only; the harness behind it is `Test*` in `neoforge/`. `./gradlew :examples:hello:hadronTest` and `./gradlew :selftest:hadronTest` (Hadron's own tests) each run a suite in a fresh headless server and fail the build on any failure; see [docs/testing.md](docs/testing.md). Add a self-test when you change an event or API. Run one suite at a time on a strained machine: each boots a server.
- `examples/hello/`: a plugin built the way authors will. `./gradlew :examples:hello:deploy` drops it into the running dev server, which reloads it within about two seconds. `python3 scripts/rcon.py "plugins" "hello"` runs console commands on the dev server, so an agent can test without a player.
- Java 21. If `java` isn't on the PATH, point `JAVA_HOME` at a JDK 21.

Hadron's real users are agents writing plugins for people who don't code. When adding API:

- Name it what an agent would guess, which usually means Bukkit's name when the meaning matches.
- Prefer a build-time check over documentation. A mistake the build refuses can't ship.
- Write every error message so the reader knows the fix without opening the source.
- When what fires an event changes, update its `@FiredBy` and run `./gradlew bridgeHooks`, which rewrites [docs/bridge-hooks.md](docs/bridge-hooks.md).
