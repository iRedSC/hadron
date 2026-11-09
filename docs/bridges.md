# Bridges

A bridge makes one mod speak Hadron's events. No bridge exists yet; this is the target the core and the first bridges are built toward. What is built: a bridge's claim on its mods and the firing that goes through it ([Claiming a mod](#claiming-a-mod)), and `bridge-depend:` for plugins.

## The goal

**A server owner installs a plugin, and it acts on every mod's content without being edited, as long as the mods' bridges are installed.** A server owner running Hadron's regions plugin never wonders whether a mod's quarry, turret or drone is covered: either its bridge is installed and it is, or Hadron has told them which bridge to get.

A bridge is done when it passes the acceptance test: run the mod's machines, weapons, tools, vehicles, storage and rituals inside a protected region, and a protection plugin denies what the region forbids while a logging plugin records the right player for each change, and an anti-dupe check finds every item and resource conserved. Neither plugin knows the mod exists. Cancelled actions must leave nothing behind: no consumed cost, no half-moved block entity, no fluid handed over while the block stays.

### The acceptance kit

A bridge runs the test as an in-game suite with its mod and itself in the test server (`hadronTest.mods`). `t.acceptance(box)` gives the protection plugin and the logger over one box, knowing no mod, and records the box to compare against:

```java
Acceptance area = t.acceptance(BoundingBox.fromCorners(t.pos(-3, 0, -3), t.pos(3, 4, 3)));
// bob's digital miner, working in the box; nobody is trusted yet
t.tick(200);
area.assertUnchanged("bob's miner where he isn't trusted");

area.trust(bob).mark();
t.tick(200);
area.assertEveryChangeSeen("bob's miner where he is trusted");
area.assertActingFor(bob, "bob's miner where he is trusted");
```

- **`assertUnchanged`**, for a refused step: no block, block entity, block inventory (on any face), entity or living entity's health in the box changed, and no test player's inventory did either. Each change is named with what explains it: a cancelled event whose cancel left it behind, an event that went through, or no event at all (an interception gap).
- **`assertEveryChangeSeen`**, for an allowed step: every block and block inventory that changed was covered by an event that went through, and so was every entity that appeared, left or changed health. A block event covers its block's inventory only when the block itself changed. Dropped items, experience orbs and falling blocks are exempt, because their contents come from a change reported elsewhere. Explosions count for the blocks they list, and a test player standing in the box counts like any entity. A step where nothing changed fails, since it proves nothing.
- **`assertActingFor`**: every event in the box acts for that player, except the server's and nature's, and at least one does. `Unknown` and ownerless actors are named as attribution gaps.

Protection denies every cancellable block, inventory, resource, explosion, item or entity event in the box unless the server or a trusted player is behind it. An entity's own motion and attention (moving, teleporting, velocity, knockback, targeting, air) is neither protected nor logged. The box is watched, so a change with nobody behind it fires as `Unknown`. `AcceptanceKitTests` in the self-tests checks that each assertion fails, naming the right gap, when a change skips every chokepoint, a cancel is ignored, or nobody is behind an action.

Not checked yet: whether items and resources are conserved in an allowed step (an anti-dupe ledger, which would also catch a dropped item made from nothing), a block entity's data besides its inventory, and the energy, fluids and other resources a machine holds. Both wait for the design of running resource flows ([design](design.md#open-designs)), which the Mekanism bridge will shape. Set up the box and join every player before calling `t.acceptance`: once the box is watched, the test's own writes have nobody behind them and are refused as `Unknown`, and a player who joins after a `mark` has nothing to be compared against, so the assertions refuse that step.

## Core intercepts, bridges translate

Per-mod events don't scale: plugins would have to learn every mod, and a protection plugin would only protect against the mods its author had heard of. So the work splits:

- **Core intercepts.** It owns the full event vocabulary ([events.md](events.md)), designed up front, and fires each event at the vanilla chokepoints every mod passes through. They are Hadron events, the same on every loader.
- **Bridges translate.** A bridge does two jobs. It **attributes**: when the mod's code starts acting for someone it pushes an **actor** onto a stack, and pops it after, so every event carries who is behind a machine, a turret, a projectile or a shared fake player, and who controls it right now ([events.md](events.md#actors)). And it **routes**: wherever the mod skips vanilla, the bridge fires the core event the chokepoint would have fired. A mod's blast that never calls `Level.explode` fires `ExplosionEvent`; AE2 moving items between cells fires `InventoryMoveItemEvent`.

**A bridge never expresses an effect only through its own event.** It may publish extras (an AE2 network's contents, a machine's progress), but anything that touches the world, players or items reaches plugins as a core event, or plugins that don't know the mod can't see it. When no event fits exactly, the bridge fires the nearest parent in the hierarchy (`BlockChangeEvent`, `InventoryChangeEvent`) with its full payload, and the missing leaf is proposed for core. A bridge also opens an operation before its mod's first mutation, so a cancel stops the whole action instead of its last step ([events.md](events.md#operations-and-cancelling)).

Foundation already did attribution for OpenModularTurrets: `TurretShots.firing()` marks the turret's owner while its shot lands, and the ordinary damage path reads it.

### What core attributes on its own

While a modded block entity or entity ticks, core makes it the actor, with no owner; while a player's packet is handled, that player is. Core also stores the causal actor on projectiles, summons, primed explosives and queued work, so attribution survives delays. So an unbridged mod's machine already appears as a `Machine` at its position, not as nobody. Bridges add owners and controllers core can't infer (a vehicle's driver, a drone's remote operator, a charmed mob's charmer).

### Two kinds of gap

- **Attribution gaps fail closed.** `Unknown` actors and actors with no owner are outsiders to protection: denied wherever a region or claim forbids outsiders. A missing bridge then shows up as a machine refusing to work in its owner's claim, which the owner notices and fixes, never as a grief nobody saw.
- **Interception gaps can't fail closed.** An effect that skips every vanilla chokepoint (a ship moving, a direct chunk write, mana drained) produces no event, so nothing can deny it. Only the bridge closes this gap, which is why the coverage report names it separately.

### The chokepoint cost

`Level.setBlock` and its neighbours run millions of times. An event fires only when someone listens and either an actor is on the stack or the position matters to a listener (a region, a logged area). Otherwise a server without bridges would get slower for nothing.

`./gradlew :bench:chokepointBench` measures it (`bench/src/test/java/.../ChokepointBench.java`). **A whole `setBlock` costs about 1.2 µs. Hadron's hook adds 20 to 30 ns (2 to 3%) when it fires nothing, and about 250 ns (20%) when an actor is on the stack and an event goes to a listener.** A listener without an actor costs nothing more than nobody listening, because nothing fires yet. That path is what areas of interest will add a position lookup to, so the lookup has to fit in tens of nanoseconds to keep an unbridged write under about 5%. The figures come from one shared, busy machine and move about 30% between runs; compare cases within one run, not runs with each other.

**Watched areas name the positions that matter.** A plugin calls `Areas.watch(plugin, level, box)` for each region it protects or area it logs. Inside one, a `setBlock`, `destroyBlock`, TNT lighting or golem build with nobody on the actor stack (an unbridged mod's queued task) fires as `Unknown`, which protection denies. Outside every area it still fires nothing. The areas live on their level, so a world nobody watches pays one field read per write. A watched world pays one hash probe per chunk, plus a check against each area wider than 1024 chunks: in the bench, an area elsewhere in the world adds about 8 ns (under 1% of a `setBlock`), and a write inside one costs what any fired event costs, about 250 ns.

Vanilla's own actorless writes stay silent even inside an area, because they are vanilla working, not a mod with a missing bridge: a vanilla block entity's tick (a furnace lighting, a piston finishing its move), a vanilla block's queued block event (a piston's push), a vanilla fluid's tick (water drying up), a sleeper getting up (in the morning or leaving the game), a player's own tick (Frost Walker's ice, an End gateway's exit, following the player into a new world mid-tick), a portal or End platform built for someone arriving, a respawn anchor used from another world, a container settling closed when its player leaves the game, a new chunk settling as it loads, and the dragon fight. A dragon respawn's writes fire nothing at all, whoever started it, because vanilla repeats them until they succeed. A mod's code that vanilla calls from inside one of these is silent too, so it needs a bridge for those writes. Where they matter, their own events report them already. A plugin's own scheduled tasks, `onEnable`, `onDisable`, and its handlers for events nobody is behind (an outsider's change included) run as `Actor.Server` named `plugin <name>`, so a plugin resetting its own arena isn't refused as an outsider. `AreaTests` fails if the block entity, block event, fluid, lava, sleeping, Frost Walker, arrival portal, End platform, respawn anchor or container closing exemptions regress; chunk settling and the dragon fight are exempted from reading vanilla's code, with no test yet.

## Coverage report

The owner never has to work out which bridges are missing. At startup, and on `/hadron coverage`, Hadron lists every installed mod that adds blocks, block entities, entities or items and has no bridge loaded, with:

- what that leaves uncovered, split by kind: attribution (its machines act with no owner, so they're denied in claims) and interception (effects that skip vanilla, which nothing sees until the bridge is installed);
- a link to the official bridge for that mod and version, when one exists.

The list of official bridges ships in Hadron's jar and is updated each release. It also records mods verified to need no bridge, because everything they do goes through vanilla chokepoints and core attributes them correctly; those are reported as covered.

### Checking a mod live with trace

The report says what should be covered; `/hadron trace` shows what is. An op runs `/hadron trace block by machine near 16` beside a mod's quarry (or `trace entity`, `trace all`, a single event such as `BlockBreakEvent`, filters `by <player|kind>`, `in <world>`, `near <blocks>`), and each event prints as it fires: event, actor, operation, where, and the outcome after every plugin ran, such as `BlockBreakEvent · Machine quarry for alice · op#812 · overworld 10 64 -3 · cancelled by Regions`. Silence while the machine works is an interception gap; `Unknown` or `no owner` is an attribution gap. Output goes to whoever ran it, at most 10 lines a second with the rest counted, and stops after 5 minutes, at `/hadron trace off`, or when the op leaves; from the console or RCON, `/hadron trace` replays the latest lines. Needs `hadron.trace` (ops by default).

## What core covers with no bridge

| Concern | Core, for every mod | A bridge adds |
|---|---|---|
| Permissions | `command.<name>` for every command; NeoForge's PermissionAPI answered by the one permissions source; `use.`, `place.`, `break.`, `open.`, `attack.` by registry id; `packet.<modid>.<payload>` for mods' network messages | Nodes for features that skip all of those, such as a keybind action checked only on the server |
| Interception | Every event in [events.md](events.md) at its vanilla chokepoint; fake players fire the ordinary events | The same core events, fired where the mod skips vanilla |
| Attribution | Players, fake players carrying a real player's id, and the ticking block entity or entity as an ownerless actor | Owners, shooters, drivers, remote operators, and shared fake players |
| Ownership and trust | Nothing | "Who owns this, and does it trust that player?", so claims and factions plugins can answer |
| State | Item handlers through capabilities; registry ids | What plugins want to read: an AE2 network's contents, a machine's progress |

## Plugin or bridge

- **The mod has an API** that does what's needed: write a **plugin** with `api-depend` (or `mod-depend` when the API is loader-specific). It reloads, and needs no mixins. LuckPerms and WorldEdit integrations are plugins.
- **The mod has no hooks** for it: write a **bridge** mod, with mixins.

A bridge has two parts:

- **Its API**: loader-neutral interfaces and events for extras, compiled against vanilla Minecraft and Hadron's `api`. Official plugins depend on it with `bridge-depend: [<mod id>]` and stay write-once.
- **Its implementation**: one small mod per loader. Mixin works the same on every loader, so a bridge into a mod that ships for several loaders shares its mixins and keeps only the per-loader glue separate.

[bridge-hooks.md](bridge-hooks.md) lists exactly what a bridge must hook: the events only bridges fire, and where core's coverage of an event stops.

### Claiming a mod

Each mod id has at most one bridge, so two bridges never compete to translate the same mod. A bridge may claim several, since one mod often ships as several mod ids (Mekanism's generators and tools are optional addons, each its own mod). It claims them once, from its mod's constructor, and keeps the handle. The first id must be installed; the rest are claimed when they are:

```java
BRIDGE = Bridge.claim("mekanism", "mekanismgenerators", "mekanismtools", "mekanismadditions");
```

The loader reports which mod is constructing, so the claim names the real bridge, and a plugin can't claim one once the server runs. A second claim for a mod id fails the load, naming both bridges. Every event a bridge fires goes through the handle (`BRIDGE.listens`, `BRIDGE.fire`), which refuses an `EntityDataChangeEvent` whose kind isn't in one of the claimed mods' namespaces. Attribution needs no handle: `Actors` and `Operation` are the same for bridges and core.

A plugin that needs a bridge lists it with `bridge-depend: [mekanism]`, and doesn't load without it, saying which bridge to add.

## Promotion: when bridges' events become core

A plugin hears a bridge API's events only if it names that mod. **When bridges for different mods say the same thing, core adds one event for it, so a plugin written once hears every mod that does it, including mods bridged later.** Two sources feed this:

- **Missing leaves.** A bridge that fires a parent because no leaf fits names the leaf it wanted; for an entity, the data kind on `EntityDataChangeEvent` is that proposal. Not built yet: a list that collects these proposals and groups them by meaning, since each is in its own mod's namespace (`minecolonies:job` and another mod's `:profession`, if they mean the same, are one repeat).
- **Overlapping extras.** Two bridge APIs publish events a plugin would handle the same way, such as AE2's and Refined Storage's finished autocrafts.

Promote when all of these hold:

- **Two official bridges, for unrelated mods, ask for it.** One bridge is one mod's habit, and its parent fallback already reaches plugins. Third-party bridges don't count, but a plugin author asking to treat several mods alike can tip a close case.
- **One listener handles them all correctly using only what they share.** If acting right needs to know which mod fired it, they only look alike: keep them apart.
- **The event can follow the rules in [events.md](events.md#rules)**: a payload a plugin can act on without knowing the mod, and producers in the same release, which here means the bridges that asked.

**Promotion never breaks a plugin that depends on a bridge's events.** Every event in a bridge API extends a Hadron event, so promoting adds a superclass between that event and theirs: each bridge's own event becomes a leaf under the new one, keeps its mod-only fields, and plugins that `bridge-depend` on it still compile. The one exception: a data kind moving from `EntityDataChangeEvent` to a new leaf, which breaks plugins listening for that kind, so the leaf's Javadoc names each kind it replaces.

Events under different Hadron parents aren't promoted together, since one listener couldn't handle them alike.

A data kind outside the firing bridge's claimed mods is refused at firing. Not built yet: the build refusing a bridge API event that extends nothing of Hadron's, which lands with the first bridge API. A promotion changes `api`, so it gets `@FiredBy(core = NONE, ...)`, an acceptance-kit check and `@Covers` like any bridge event.

## Pilots

Three mods, each stressing a different part, bridged by hand to test the vocabulary. Anything they do that no event fits becomes a new leaf in core, before more bridges are written.

- **Mekanism**: machines acting through fake players (the digital miner). Tests attribution.
- **AE2**: a storage network. Tests mod-specific state, and actions that skip vanilla.
- **Superb Warfare**: projectiles, vehicles with drivers, drones with remote operators, artillery fired from afar. Tests attribution hardest, and routing its blasts through `ExplosionEvent`.

Two plugin integrations come with them:

- **LuckPerms**: the permissions provider, on LuckPerms' API.
- **WorldEdit**: its `EditSessionEvent` lets a plugin wrap each edit, putting the editing player on the actor stack (and optionally limiting edits to regions they may build in). Without it, a staff member's WorldEdit work looks like changes by nobody. To check early: whether WorldEdit's NeoForge adapter goes through `Level.setBlock` or writes chunks directly, since a direct write never reaches core's chokepoint and the wrapper becomes the only place to see it.

## What's next

1. ~~The actor stack and core's events at their chokepoints~~, with the chokepoint benchmark ([above](#the-chokepoint-cost)).
2. ~~Areas of interest~~: done for block writes (`Areas.watch`, see "The chokepoint cost"). Entity moves in an area (`EntityMoveEvent`) come with the regions plugin.
3. ~~Claiming a mod~~ ([above](#claiming-a-mod)) and `bridge-depend:`.
4. ~~The acceptance test as a kit any bridge can run~~ ([above](#the-acceptance-kit)), without the conservation ledger, which comes with the Mekanism bridge.
5. The Mekanism bridge, then AE2 and Superb Warfare, then this document rewritten from what they taught.
6. The coverage report and the official bridge list, once there is a bridge to list.
7. The grouped list of proposed leaves ([promotion](#promotion-when-bridges-events-become-core)), once the pilots give it something to group.
