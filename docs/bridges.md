# Bridges

A bridge makes one mod behave like vanilla for plugins. Nothing here is built yet; this is the target the core and the first bridges are built toward.

## The goal

**With a mod's bridge installed, everything that mod does to the world, players and items can be attributed to someone, intercepted, and gated by permissions, by the same plugins that handle vanilla.**

A bridge is done when it passes the acceptance test: run the mod's machines, weapons and tools inside a protected region, and a protection plugin denies what the region forbids while a logging plugin records the right player for each change. Neither plugin knows the mod exists.

## Core intercepts, bridges attribute

Per-mod events don't scale: plugins would have to learn every mod, and a protection plugin would only protect against the mods its author had heard of. So the work splits:

- **Core intercepts.** It owns a small set of generic actions and fires them at the vanilla chokepoints every mod passes through: a block changes, an entity is hurt, something explodes, items move, a block is used, a menu opens. They are Hadron events, the same on every loader.
- **Bridges attribute.** The question core can't answer is who is behind a machine, a turret, a projectile or a shared fake player. A bridge pushes an **actor** onto a stack when the mod's code starts acting for someone, and pops it after; every generic action carries the actor on top. An actor is a player, a fake player acting for an owner, a machine with an owner, or nobody (nature, or unknown).

Foundation already did this for OpenModularTurrets: `TurretShots.firing()` marks the turret's owner while its shot lands, and the ordinary damage path reads it.

A bridge adds a mod-specific event only where the mod skips vanilla entirely, so no chokepoint sees it: Foundation's `ModBlastEvent` for DefenseTech's explosives, or AE2 moving items between storage cells.

### The chokepoint cost

`Level.setBlock` and its neighbours run millions of times. A generic action fires only when someone listens and either an actor is on the stack or the position matters to a listener (a region, a logged area). Otherwise a server without bridges would get slower for nothing. This gets a benchmark before any bridge is built.

## What core covers with no bridge

| Concern | Core, for every mod | A bridge adds |
|---|---|---|
| Permissions | `command.<name>` for every command; NeoForge's PermissionAPI answered by the one permissions source; `use.`, `place.`, `break.`, `open.`, `attack.` by registry id; `packet.<modid>.<payload>` for mods' network messages | Nodes for features that skip all of those, such as a keybind action checked only on the server |
| Interception | Generic actions at vanilla chokepoints; fake players fire the ordinary events | Mod-specific events where the mod skips vanilla |
| Attribution | Players, and fake players carrying a real player's id | Machines, turrets, projectiles, vehicles, drones, and shared fake players with no owner |
| Ownership and trust | Nothing | "Who owns this, and does it trust that player?", so claims and factions plugins can answer |
| State | Item handlers through capabilities; registry ids | What plugins want to read: an AE2 network's contents, a machine's progress |

## Plugin or bridge

- **The mod has an API** that does what's needed: write a **plugin** with `mod-depend`. It reloads, and needs no mixins. LuckPerms and WorldEdit integrations are plugins.
- **The mod has no hooks** for it: write a **bridge** mod, with mixins.

A bridge has two parts:

- **Its API**: loader-neutral interfaces and events, compiled against vanilla Minecraft and Hadron's `api`. Official plugins depend on it with `bridge-depend: [<mod id>]` and stay write-once.
- **Its implementation**: one small mod per loader. Mixin works the same on every loader, so a bridge into a mod that ships for several loaders shares its mixins and keeps only the per-loader glue separate.

Each bridge owns exactly one mod id, so two bridges never compete to define the same mod's events.

## Pilots

Three mods, each stressing a different part, bridged by hand before anything is generalised; the generic actions are whatever the three turn out to share.

- **Mekanism**: machines acting through fake players (the digital miner). Tests attribution.
- **AE2**: a storage network. Tests mod-specific state, and actions that skip vanilla.
- **Superb Warfare**: projectiles, vehicles with drivers, drones with remote operators, artillery fired from afar. Tests attribution hardest; its blasts show whether it needs mod-specific explosion events.

Two plugin integrations come with them:

- **LuckPerms**: the permissions provider, on LuckPerms' API.
- **WorldEdit**: its `EditSessionEvent` lets a plugin wrap each edit, putting the editing player on the actor stack (and optionally limiting edits to regions they may build in). Without it, a staff member's WorldEdit work looks like changes by nobody. To check early: whether WorldEdit's NeoForge adapter goes through `Level.setBlock` or writes chunks directly, since a direct write never reaches core's chokepoint and the wrapper becomes the only place to see it.

## What's next

1. Core's generic actions and the actor stack, with the chokepoint benchmark.
2. The acceptance test as a kit any bridge can run.
3. The three pilot bridges, then this document rewritten from what they taught.
