# Working on Hadron

Read [docs/design.md](docs/design.md) first. It holds the decisions and why.

- `core/`: the NeoForge mod. `dev.iredsc.hadron.api` is what plugins build against; everything else is internal. Build with `./gradlew build`; `./gradlew :core:runServer` starts a dev server.
- Hadron's API takes Minecraft's own types; never wrap them. Any change to the API that Minecraft forces goes in that version's port guide in `docs/porting/`.
- Java 21. If `java` isn't on the PATH, point `JAVA_HOME` at a JDK 21.

Hadron's real users are agents writing plugins for people who don't code. When adding API:

- Name it what an agent would guess, which usually means Bukkit's name when the meaning matches.
- Prefer a build-time check over documentation. A mistake the build refuses can't ship.
- Write every error message so the reader knows the fix without opening the source.
