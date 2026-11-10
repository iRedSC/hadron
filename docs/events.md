# Events

Hadron's events are the vocabulary every plugin and every bridge share. A plugin written against them acts on modded content it has never heard of, because each bridge translates its mod's behaviour into these events. So the vocabulary is designed whole, up front, rather than grown one event at a time: an event added late is an event every existing plugin was blind to until then.

The catalog below was checked against the source of about sixty popular 1.21.1 mods across tech, magic, combat, vehicles, storage and multiplayer (Mekanism, Create, AE2, Refined Storage, Ars Nouveau, Botania, Iron's Spells, Superb Warfare, TACZ, Valkyrien Skies, FTB Chunks and Quests, Open Parties and Claims, Sophisticated Backpacks, Curios, Carry On and others). Every effect found either fits an event here or is listed under "Left out" with the reason.

## Rules

1. **Anything that touches the world, players, items or a mod's resources is one of these events.** A bridge expresses every effect its mod has as a core event. A bridge may publish its own events for extras, but never as the only way to see an effect, because a protection plugin will never listen to it.
2. **Events form a hierarchy, and a plugin listens at the level it means.** A protection plugin listens to `BlockEvent` and hears every break, place, burn, flow, data change and move, including leaves added later. Java delivers subclasses to a parent's listener, so coverage holds without anyone remembering to update it.
3. **A parent carries enough to act on.** When a bridge meets an effect with no fitting leaf it fires the nearest parent, so every parent defines the payload a plugin needs to decide without knowing the leaf: what is affected, where, before and after. A bridge that can't fill the parent's payload fails its acceptance test.
4. **Every action carries an actor** (see below). Events any actor can cause fire for all of them: a machine breaking a block fires `BlockBreakEvent` just as a player does.
5. **Who did it is `Optional`.** Every action has `getActingPlayer()`: the player behind it if online (the controller, otherwise the responsible player). Where Bukkit's `getPlayer()` means who did it (blocks, items, inventories), it is the same `Optional<ServerPlayer>`, so code that assumes a player was there doesn't compile; a deployer has no player in hand. Where the player is the subject (`PlayerDeathEvent`, every `PlayerEvent`), `getPlayer()` returns that `ServerPlayer`. Offline people are on the actor as UUIDs.
6. **Names follow Bukkit's where the meaning matches.** Bukkit's `Player` prefix is kept only where the actor is always a player (`PlayerJoinEvent`). Where anything can do it, the prefix goes: Bukkit's `PlayerDropItemEvent` is `ItemDropEvent`, `PlayerInteractEvent` on a block is `BlockUseEvent`. The skill lists each renamed Bukkit event so an agent's first guess is corrected in one step.
7. **An event ships in `api` only with its producers.** Each event is either **core** (every port fires it at a vanilla chokepoint) or **bridge** (no vanilla operation exists, such as mana or a claim's trust list; core defines the contract and the acceptance kit tests that bridges fire it). An event nobody fires looks like coverage and isn't.
8. **Events fire for committed attempts, never for simulations or reads.** Pipes and storage networks test a transfer before doing it; only the real one fires. A plugin never sees a phantom transfer or charges for a capacity probe.

## Operations and cancelling

Many effects are one action with many parts: a spell hits ten entities, a gadget builds a hundred blocks, a waystone charges a cost and then moves a player and their pets, a ship moves with everything on it.

- **Related events share an `Operation`**: an id, the actor, and every event it produced. A logger groups them; a rollback undoes them together.
- **An operation opens with a cancellable event fired before its first mutation.** Bridges fire it at the start of the mod's action, not at the last vanilla call inside it: Iron Chests and Carry On remove a block entity before calling `setBlock`, so cancelling at `setBlock` is already too late. Cancelling the opening event undoes nothing because nothing has happened yet, and refunds any cost.
- **Per-target events cancel only their target.** Denying one entity in an area spell spares that entity; the rest of the spell lands. The cost is not refunded, because the action happened. The one exception: core may put an item use's inventory back when its spawns were refused, even if the use did something else too.
- **Cancellable means fully undone for what it covers.** No item consumed, no durability lost, no half-moved stack, no fluid handed over while the block stays.
- **Expiry and revocation are not cancellable.** A flight grant can be refused; a flight expiry can only be observed, or temporary powers would become permanent. Such events are marked *(after)*.

## Actors

`Actor` is a sealed interface, so a `switch` over it is checked for every case:

| Actor | Who | Example |
|---|---|---|
| `Player` | a real player, directly | breaking a block by hand |
| `FakePlayer` | a fake player | Mekanism's digital miner, a deployer |
| `Machine` | a block entity at a position | a quarry, a turret block, a pump, a quest submission block |
| `EntityActor` | an identifiable entity | a drone, a vehicle, a projectile, a summon, a seagull stealing food, a boss |
| `Server` | the server on an operator's orders; trusted by protection | the console, RCON, a command block, a datapack function, a plugin's own task, `onEnable`, `onDisable` or handler for an event nobody is behind (`plugin <name>`) |
| `Nature` | the world on its own, with no entity behind it | fire spreading, water flowing, crops growing |
| `Unknown` | a mod acted and nothing said who | an unbridged mod's scheduled task, for a block change, seen only in a [watched area](bridges.md#the-chokepoint-cost); also a spawner's mob, a container moved with nobody behind it, and other actions Hadron can't trace |

Every actor except `Server`, `Nature` and `Unknown` carries up to three people, as UUIDs so they work while offline:

- **responsible**: who answers for it. A machine's owner, a summon's summoner, a gun's shooter, the player whose gadget queued the build.
- **controller**: who directs it right now, if different. A vehicle's driver, a drone's remote operator, the player who charmed a mob.
- **authority**: whose permissions it runs with, for commands. A quest reward command may run as the server.

Protection uses the controller where there is one, otherwise the responsible player. A charmed mob acts for its charmer, not its original owner, so stealing a creature never inherits the victim's claim rights.

**Attribution persists.** Core stores the causal actor on projectiles, summons, falling blocks, primed explosives and queued work, so a shell landing ten seconds after firing, a multi-tick explosion's last batch, or a Building Gadgets paste finishing over several ticks still carries who caused it. While a modded block entity or entity ticks, core makes it the actor; while a player's packet is handled, that player is. Bridges add owners and controllers.

An identifiable mob is always an `EntityActor`, even when it spawned naturally. `Nature` is reserved for processes with no entity, so a logger never loses which creature griefed.

## Touches

Rule 3 says a parent carries enough to act on. **Touches are that payload in one shape, on every action:** `ActionEvent.getTouches()` lists what the action touches, where, as it really happens, so protection needs one guard and a lookup in its own data, and no knowledge of Minecraft or any mod ([design](design.md), "Core states facts"). The actor already says who; touches say what and where.

```java
Guards.add(this, (touch, who) -> regions.refuses(touch, who));
```

**A guard runs after every listener that may change the event, on the final payload.** For each touch of each action, core asks every plugin's guard after `HIGHEST`; any that answers true refuses that touch. Then `MONITOR` listeners, which only look (that is their contract), see the outcome with the guards' verdict in it, so a logger records what really happened. No listener that may change the event runs after the guards, so a guard judges exactly what will happen: nothing can redirect, add a target or uncancel after it, and nothing has to be frozen or ordered by priority. `who` is whoever the touch asks about: for an `ENTER` of a carried entity, that entity (bob in alice's boat), otherwise the action's actor, so a guard never has to work out which. A guard can't change the event; it only answers. A plugin's guard goes when the plugin disables, as an area does. Listeners stay what they are, for plugins that react or change what happens.

A `Touch` is a record: a `Kind`, a `Place`, the entity it is about if any, and where it comes from (`List<Place>`, empty when it comes from no particular place). A `Place` is sealed:

- `Spot(level, box)`: one block or an area.
- `Level(level)`: a whole level (weather, time).
- `Nowhere`: known to have no place in a world (linked storage such as an ender chest, an account).
- `Unknown(bound)`: core or a bridge couldn't say exactly where (an inventory nothing identified, a bridge's operation aimed it can't say where, fire from an origin nobody saw). It carries the best bound known: at least the level and the actor's own position when the actor has one. Protection refuses it wherever that bound meets a region that forbids outsiders, as `bridges.md` asks of failing closed; with no bound at all (an `Actor.Unknown` acting on a place nobody knows) it meets every region.

Nothing is left out for want of a place; it is `Nowhere` or `Unknown`, and the two are never confused, so a plugin can let the first through and refuse the second without reading the event. A `CONTAINER` touch also carries its `InventoryHolder`, so linked storage keeps its owner and frequency (an ender chest is its owner's own), and a guard can tell someone's own storage from another's network.

| Kind | Means | From |
|---|---|---|
| `BUILD` | the world changes: a block, or an object that stands in for one (a frame and its item, a stand, a boat, a crystal, an item on the ground), is placed, changes or goes; the level as a whole (weather, time); an area's ticking or loading (an accelerator, a chunk loader) | for the world on its own (water, fire, a piston, a hopper nobody owns), where the change comes from |
| `USE` | a block or entity is interacted with, not harmed or changed: a door, a lever, trading, taming, breeding, leashing, shearing, riding, a beneficial effect, starting to mine | |
| `CONTAINER` | stored contents are seen or change: an inventory, a tank, a mod's energy or mana; one per block the storage stands on, so a double chest is two | the other storage, for contents moving between two |
| `ENTER` | an entity arrives in a block: walking, riding, landing, being set down; one per entity carried, players included | the block it left |
| `ATTACK` | a creature (a mob or a player) is harmed or forced: a hit, a harmful effect, being set alight, a push, being charmed or controlled; objects are `BUILD` | every place the attack comes from: the attacker, the projectile, the machine |
| `SPAWN` | a creature appears: a spawner, an egg, a summon, a natural spawn; objects appearing are `BUILD` | |

Each family gives every leaf, and a bridge firing the family's parent, at least one touch:

| Family | Touches |
|---|---|
| `BlockEvent` | `BUILD` per block changed (`getBlocks()`), from the source for the world on its own; `BlockUseEvent` and `BlockDamageEvent` `USE`; `BlockDropItemEvent` `BUILD` where each pending drop will land, not the break that already happened; a block or structure move (a piston, a contraption, a ship) also `BUILD` over its swept space and at each move's source and destination, block changes or not, and an `ENTER` per entity it carries |
| `EntityEvent` | by what the leaf does, per the kinds above: moves and dismounts `ENTER` for everyone carried; teleports `ENTER` for everyone who goes along (riders left behind by a move to another level aren't, and get their own dismount); mounting `ENTER` for the rider and `USE` on the mount; damage, combustion, knockback, velocity from someone else, control and targeting `ATTACK`; an effect by which way it changes the creature: adding or strengthening a harmful one, or removing or weakening a beneficial one, `ATTACK` (poisoning, stripping regeneration), the other way `USE` (healing, curing poison); spawns `SPAWN` or `BUILD`; removal `ATTACK` for a creature (a mod's instant kill) and `BUILD` for an object; hanging breaks, vehicle hits, frame and stand changes `BUILD`; use, taming, breeding, leashing, shearing `USE`; the parent alone `USE` on the entity |
| `ItemEvent` | an actor's own inventory and its own use of an item (changing hotbar slot, eating, drawing a bow, throwing a pearl) touch `Nowhere`, since they are the actor's own business, and only what reaches past them is placed: a pickup `BUILD` at the item on the ground and `CONTAINER` at the storage it goes into, a drop `BUILD` where it lands, an item used on something the touch of that use; an item in someone else's storage `CONTAINER` there, on the ground `BUILD` there |
| `InventoryEvent` | `CONTAINER` per spot each inventory involved stands on, except the actor's own inventory and its own crafting grid, which are `Nowhere` as above |
| `ResourceEvent` | `CONTAINER` at each end, `Nowhere` for an account |
| `WorldEvent` | an explosion `BUILD` per block and per object (an item on the ground, a frame, a boat) and `ATTACK` per creature, its prime `BUILD` over its reach; weather and time `BUILD` at `Level`; accelerators, chunk loaders, portals and lightning `BUILD` where they act. Loading, unloading and saving chunks and levels isn't anyone's action: those leaves move out of `ActionEvent` |

Two events a protection plugin needs sit outside these families today and move into them: `PlayerBedEnterEvent` (`USE` at the bed) and `DataRevealEvent` (`CONTAINER` at what is revealed). A self-test fires every event class core fires and fails on any action with no touches, naming it; another has an untrusted player in a refused box change hotbar slot, eat, move their own armour and craft in their own grid, which must all go through. Kinds serve a plugin's general rule; a plugin that treats placing differently from breaking listens to those leaves as well.

**Read from the payload, never stored.** `getTouches()` works out the touches from the event's own getters each time it is called, so it can't disagree with them, and a listener that redirects a teleport or trims an explosion changes the touches with it. Guards read them last. A redirect is exact: `EntityTeleportEvent.setTo` places the entity at the position given, with no settling, and its Javadoc changes to say so.

**Refusing a touch** stops what it describes: it cancels the action, except where its targets are independent (an explosion drops just that block or entity, an area spell spares just that entity, as "Per-target events" above already does by hand). A rider isn't an independent target: a vehicle can't leave one behind, so refusing a rider's `ENTER` stops the vehicle with everyone on it. An action nothing can stop (an *(after)* event) has its touches for listeners and loggers, but guards aren't asked.

**An opening event touches what the operation is aimed at**: a gadget's paste is the box it will build in, a waystone the place it sends you. Each per-target event after it carries its own. A bridge that can't say where its operation is aimed gives `Unknown`.

**True, not requested.** Where an action ends somewhere other than where it set out to, core works out the real place first and then does exactly that: a chorus fruit or random teleport picks its spot, settles it on the ground and checks it fits, as vanilla does, trying the next spot if not, and fires only for a spot it will really take, with that landing as `getTo()` and its `ENTER`, and moves there with no second roll; a dismount picks where it sets the rider down before asking. This is the committed attempt's own choice, not a simulation (rule 8). Cancelled, nothing moves. A forced dismount (the vehicle broken, gone, or left behind by a teleport) can't keep the rider on, but its `ENTER` can still be refused: the rider then stays exactly where they are, at their seat's position, which is always possible.

**Riders move with their vehicle's event.** The events that carry entities (`EntityMoveEvent`, `EntityTeleportEvent`, block and structure moves) gain `getCarried()`, a list of every entity carried, direct or nested, each with where it was and where it ends up, which is where its `ENTER` comes from; core also tracks a vehicle's rotation as well as its position. A vehicle carrying a player fires its move whenever it or anyone it carries changes block, even when the vehicle stays in its block (a rider's seat crossing a boundary as a boat turns), with an `ENTER` for each who did, from the block they left. That includes vehicles nobody drives (a minecart on rails); the actor is the driver, otherwise `Nature`, and entry asks about whoever enters, the touch's entity, not the actor. A vehicle carrying only mobs doesn't fire, keeping farms off this path. Cancelling puts the vehicle back where it was and as it was turned, so every rider is back in the block they left. `EntityMoveEvent`'s Javadoc and catalog row change to say so.

**A menu's inventories** are every container behind a slot of the menu other than the player's own, the ones a click can move items between, read from the menu itself. A mod's menu that moves items into storage with no slot (a storage network's terminal) is its bridge's to state, as a linked holder (`Nowhere`) or `Unknown`.

**Attribution reaches every tick.** Each effect instance, the hidden weaker ones Minecraft resumes included, and an entity's fire keep the actor that caused them, saved with the entity and cleared when they end. The credit changes only when the instance changes: an application that leaves it as it was credits nobody new. Fire has no instance, only time left to burn: an ignition that adds time takes the credit, nature's included (a pig bob set alight that walks into lava burns on as nature's), and one that doesn't leaves it. They also keep where they were applied. Their damage acts for that actor while its entity is still in the world, and otherwise for `Actor.Remembered`, a new actor for a cause that has gone (an offline player, a spent cloud) whose `actingFor()` still answers; either way its `ATTACK` comes from where the effect or fire was applied, not where the attacker stands now, so walking into a safe zone doesn't change whether the rest of a poisoning was allowed.

**Who checks the touches.** The acceptance kit compares the box itself: a refused step must leave it unchanged, which never reads touches, so a wrong or missing touch shows up as a change protection should have stopped. Today it compares blocks, inventories, entities' presence and items, and creatures' health. Step 3 below adds who stands in the box, active effects and burning, so `ENTER` and `ATTACK` are checked the same way; until then they are checked only by their own tests.

### Closing the known gaps

`MissingFactTests` holds each fact the regions plugin had to guess, as a `@KnownGap`. Each closes as follows, and its assertion moves to `getTouches()` as it does; the design is done when all are green, the acceptance kit protects with a guard instead of its own rules, and the regions plugin's protection is the guard above.

| Gap | Closed by | Asserted as |
|---|---|---|
| A teleport states where it settles | the landing worked out before firing | an `ENTER` for alice at the block she landed on |
| A rider is named when their vehicle carries them | an `ENTER` for every passenger, direct or nested, on a vehicle's move and teleport | an `ENTER` for bob at his new block, in a boat alice drives and in a minecart on rails nobody drives; refused, bob is back in his old block |
| A dismount states where the rider is set down | `EntityDismountEvent` gains the destination, worked out first | an `ENTER` for alice where she was set down |
| A shift-click names the inventory items move into | a click's touches are every inventory its menu moves items between | a `CONTAINER` at the chest |
| Opening a double chest names both halves | a `CONTAINER` per block an inventory stands on | a `CONTAINER` at each half |
| Poison and burning act for whoever caused them | the causal actor kept on each effect instance and on the fire | unchanged: the damage acts for bob; and nature's lava taking over a fire bob started |
| Starting to mine fires an event | `BlockDamageEvent` takes Bukkit's meaning, a player starting to mine (`USE`; refusing it stops the dig), and today's lasting-damage event, which only bridges fire, becomes `BlockCrackEvent` | a `USE` at the block, cancellable |

### Order of work

Each step lands on its own, with the gaps it closes going green. Until a gap closes, its touch is missing exactly where `MissingFactTests` says, as it is today; nothing a plugin sees gets worse.

1. `Touch`, `Place`, `Guards` and the family table, worked out from the payload each family has today; the bed and reveal events moved in and the lifecycle events moved out; the self-tests that every action has a touch and that a player's own business goes through.
2. The facts: landing, riders and passenger crossings for every vehicle carrying a player, dismount destination (forced ones refusable), entities carried by moving blocks, menu inventories, chest halves, attribution on effects and fire with `Actor.Remembered`, and `BlockDamageEvent`. Each closes its gap.
3. The acceptance kit protects with a guard, and its own placement rules go; it also compares who stands in the box, effects and burning, and `docs/bridges.md` says so.
4. The regions plugin's protection becomes the one guard; what it keeps is policy (which flag a kind reads, what `Nowhere` and a crossing mean).

## Catalog

**Core** means fired at a vanilla chokepoint, so mods that go through vanilla are covered with no bridge. **Bridge** means only a bridge can fire it. Where core's coverage stops and what bridges fire is in [bridge-hooks.md](bridge-hooks.md), generated from the code. All are cancellable unless marked *(after)*.

### Blocks: `BlockEvent`

`BlockEvent` is everything that happens to a block's space. Its payload: level, positions affected, and for each the state and block entity data before and after.

| Event | What | Core chokepoint |
|---|---|---|
| `BlockChangeEvent` | a block's state or its block entity's data changes | `Level.setBlock`, chunk section writes |
| ├ `BlockBreakEvent` | a block is removed by an actor | `destroyBlock`, player mining, `setBlock` to air with an actor |
| ├ `BlockPlaceEvent` | a block or fluid source is placed by an actor | `BlockItem.place`, `setBlock` with an actor |
| ├ `BlockMultiPlaceEvent` | several blocks at once (a door, a bed, a building gadget) | NeoForge's `EntityMultiPlaceEvent` |
| ├ `BlockTransformEvent` | an actor changes a block into another (machine conversion, stripping a log, upgrading a chest in place) | `setBlock` with an actor |
| ├ `BlockDataChangeEvent` | block entity data changes with the state unchanged: a spawner's mob, a drawer's lock, a terminal's binding, a name | bridge |
| ├ `BlockBurnEvent`, `BlockIgniteEvent` | fire destroys or starts | `FireBlock` |
| ├ `BlockFromToEvent` | a fluid flows into a block | `FlowingFluid.spreadTo` |
| ├ `BlockSpreadEvent`, `BlockFormEvent`, `BlockFadeEvent`, `BlockGrowEvent`, `LeavesDecayEvent` | natural changes | the vanilla block's tick |
| ├ `BlockFertilizeEvent` | bonemeal or a growth accelerator | `BoneMealItem` |
| └ `StructureGrowEvent` | a tree or structure grows from one block | `TreeGrower` |
| `BlockMoveEvent` | blocks relocate with their data and scheduled ticks; source to target mapping, and the space swept through | `PistonBaseBlock`, through its leaves |
| ├ `BlockPistonExtendEvent`, `BlockPistonRetractEvent` | | `PistonBaseBlock` |
| └ `StructureMoveEvent` | a contraption or ship assembles, moves, rotates or disassembles within one level; old and new transforms | bridge (Create, Valkyrien Skies) |
| `BlockDropItemEvent` | a broken block's drops are about to appear; the item entities and experience are editable, and a cancel drops nothing | `Block.dropResources` (NeoForge's `BlockDropsEvent`), so player mining, `destroyBlock` and blocks losing their support |
| `BlockUseEvent` | an actor right-clicks or activates a block, including remotely through a linked terminal | `useItemOn`, `useWithoutItem` |
| `BlockRedstoneEvent` | an actor changes the signal a block emits without changing its state | bridge (Integrated Dynamics, LaserIO) |
| `BlockDamageEvent` | a block takes lasting partial damage short of breaking | bridge (Create Big Cannons) |
| `SignChangeEvent` | sign text set | `SignBlockEntity` |
| `BucketEmptyEvent`, `BucketFillEvent` | fluid placed or taken from the world by an item | `BucketItem` |

Fluid placed or drained without a bucket (a Refined Storage constructor, a hose pulley) is a `BlockPlaceEvent` or `BlockBreakEvent` plus a `ResourceTransferEvent` in one operation.

As in Bukkit, `BlockMultiPlaceEvent` extends `BlockPlaceEvent`, `BlockSpreadEvent` extends `BlockFormEvent` which extends `BlockGrowEvent`, and the bucket events extend `BlockChangeEvent`, so a listener for the broader event never misses the narrower one. `BlockFromToEvent.getPos()` is the block flowed into; `getFromPos()` is the source. An explosion's blocks are reported once, in `ExplosionEvent`'s editable list, not as one break event each.

### Entities: `EntityEvent`

Payload: the entity and its position; each leaf adds what changed. A bridge whose change no shipped entity event describes, leaf or parent, fires `EntityDataChangeEvent`.

| Event | What | Core chokepoint |
|---|---|---|
| `EntitySpawnEvent` | an entity enters the world; reason (natural, spawner, egg, breeding, summoned, released from capture, actor). Not for dropped items, experience orbs or falling blocks, whose cancelling would destroy what they carry; see `ItemDropEvent` | `addFreshEntity` |
| `EntityRemoveEvent` | an entity leaves the world other than by dying, unloading or converting; cause (killed without damage, dismissed, converted in a mod's own code, captured, discarded) | `KillCommand`, for entities that don't die; `EndCrystal.hurt`, which shatters it; bridges for mods' own removals |
| └ `EntityCaptureEvent` | an entity is stored into an item, block or carrier (bucket, mob catcher, Carry On); where it goes | `Bucketable`; bridges for catchers and carrying |
| `EntityDamageEvent` | an entity is hurt; damage source, actor, and the weapon or spell | `hurt`, and `ArmorStand.hurt`, which skips it |
| `EntityDeathEvent` | an entity is dying; drops, experience and where each item goes are editable; not cancellable | `die` |
| └ `PlayerDeathEvent` | a player is dying; each item, including extra slots, is assigned to kept, dropped, grave or destroyed; the death line is editable and replaces vanilla's | `ServerPlayer.die` |
| `EntityRegainHealthEvent` | healed, including absorption | `heal`, `setAbsorptionAmount` |
| `EntityCombustEvent` | an entity is set on fire | `igniteForSeconds`, `setRemainingFireTicks` |
| `EntityPotionEffectEvent` | a registered effect added, changed or removed | `addEffect`, `removeEffect` |
| `EntityPotionEffectExpireEvent` *(after)* | an effect ran out | NeoForge's effect expiry |
| `EntityStatusChangeEvent` | a restriction outside the effect registry: stunned, frozen, AI disabled, casting interrupted, held | `Mob.setNoAi`, bridges |
| `EntityAttributeChangeEvent` | an attribute's base value or modifiers change; source of the modifier | not shipped yet: see "Not shipped yet" |
| `EntityVelocityEvent` | an actor pushes, pulls or launches an entity; old and new velocity | `setDeltaMovement` while something other than the entity acts |
| └ `EntityKnockbackEvent` | knockback from a hit or blast | `LivingEntity.knockback` |
| `EntityMoveEvent` | an entity with an actor or controller (a player, a vehicle) crosses into another block | movement ticks, only when a listener's area is involved |
| └ `PlayerMoveEvent` | | movement ticks |
| `EntityTeleportEvent` | an entity moves other than by walking; cause, and everything travelling with it (passengers, pets, leashed mobs); the destination (world, position, facing) is editable | `teleportTo`, `changeDimension` |
| `EntityTargetEvent` | a mob or turret picks a target | `setTarget` |
| `EntityControlChangeEvent` | who controls an entity changes without mounting: charmed, enthralled, a drone linked to a monitor, a guard assigned; expiry is *(after)* | bridge |
| `EntityControlExpireEvent` *(after)* | control ran out: a charm wore off, a drone's link timed out | bridge |
| `EntityDataChangeEvent` | lasting data a mod keeps on an entity changes and no other shipped entity event, leaf or parent, describes it: a colonist's job, a familiar's upgrades, a player's spell anchor; a kind id says whose | bridge |
| `EntityRelationshipChangeEvent` | reputation or a lasting relationship changes (villager gossip, an NPC's marriage) | not shipped yet: see "Not shipped yet" |
| `EntityUseEvent` | an actor right-clicks an entity | `interact` |
| `EntityMountEvent`, `EntityDismountEvent` | riding, including vehicles and a player carrying another | `startRiding`, `stopRiding` |
| `EntityLeashEvent` | an entity is put on a lead, or a held one tied to a fence; the new holder (a player or the knot) | `Entity.interact`, `LeadItem.bindPlayerMobs`, the knot's `interact`; bridges for mods' leads |
| `EntityUnleashEvent` | an actor takes a lead off | `Entity.interact`; bridges for mods' own unleashing |
| `EntityLeashBreakEvent` *(after)* | a lead comes off with nobody choosing it; reason (distance, holder gone, holder never found after loading or a portal, entity gone, mounted) | `Leashable.dropLeash`, judged from the state; boats and trader llamas just before they leave; `setRemoved` for a leashed entity destroyed; a saved holder given up on |
| `EntityShearEvent` | a ready mob is sheared, by hand or by a dispenser; the shears | `IShearable` checks in `ShearsItem` and the dispenser's shears; bridges for auto-shearers |
| `EntityTameEvent`, `EntityBreedEvent` | | `tame`, `spawnChildFromBreeding` |
| `EntityTransformEvent` | an entity becomes another (zombie villager, a mod's conversion) | `convertTo` |
| `EntityAirChangeEvent` | | `setAirSupply` |
| `FoodLevelChangeEvent` | | not shipped yet: see "Not shipped yet" |
| `ExperiencePickupEvent` | an experience orb is collected by a player or a machine (a magnet storing XP as fluid) | `ExperienceOrb.playerTouch`, bridges |
| `ProjectileLaunchEvent` | anything fired or thrown; the actor is the shooter | `Projectile` spawn |
| `ProjectileHitEvent` | a projectile, or a hitscan shot with no projectile, hits a block or entity; hit position, origin, weapon | `onHit`, bridges for hitscan |
| `HangingPlaceEvent`, `HangingBreakEvent` | item frames, paintings | `HangingEntity` |
| `VehicleDamageEvent` | a boat or minecart is hit; living mounts are `EntityDamageEvent` | `VehicleEntity.hurt`, `MinecartTNT.hurt` |
| `ItemFrameChangeEvent` | an item put in a frame, knocked out or turned, by any actor; breaking the frame is `HangingBreakEvent` | `ItemFrame.interact`, `ItemFrame.hurt` |
| `ArmorStandManipulateEvent` | equipment taken or placed on a stand by a right-click; the slot, what goes on and what comes off | `ArmorStand.swapItem`; bridges for mods' stand editors |

### Items: `ItemEvent`

| Event | What | Core chokepoint |
|---|---|---|
| `ItemDropEvent` | an item enters the world from an actor: a player throwing it, a dropper, a mod ejecting it. Cancelled, the item goes back where it came from | player drops, dispensers and droppers, mobs' throws (`BehaviorUtils.throwItem`); bridges for mods' ejects |
| `ItemPickupEvent` | an actor picks an item up: a player, a hopper, a magnet; the final destination, including inside a backpack | `ItemEntity.playerTouch`, hopper suck |
| `ItemDespawnEvent`, `ItemMergeEvent` | | `ItemEntity` |
| `ItemUseEvent` | an item used: eating starts, a weapon fires, a wand casts, a monitor links a drone | `useItem` |
| `ItemConsumeEvent` | eaten or drunk | `finishUsingItem` |
| `ItemChangeEvent` | an existing stack's count or data changes in place, before and after: ammo spent, a reagent used, a tool charged, a backpack's frequency set | `ItemStack.setCount`, `set`, `remove`, bridges for the enclosing action |
| ├ `ItemDamageEvent`, `ItemRepairEvent` | durability lost or restored | `hurtAndBreak`, `setDamageValue` |
| ├ `ItemEnchantEvent` | enchantments, affixes or modifiers added or removed, by a table, anvil, ritual or library | `EnchantmentMenu`, `AnvilMenu`, `EnchantmentHelper.setEnchantments` |
| └ `ItemBreakEvent` *(after)* | a tool breaks | `hurtAndBreak` |
| `ItemCraftEvent` | an actor turns inputs into outputs by a recipe: a table, an autocrafter, a cutting board, a forge ritual; every input, output and cost | `ResultSlot`, bridges |
| `ItemSmeltEvent` | a machine processes items, including world and belt items (Create fans); every input, catalyst and output | `AbstractFurnaceBlockEntity`, bridges |
| `PrepareAnvilEvent` | | `AnvilMenu` |
| `PrepareItemCraftEvent` | a crafting grid worked out its result; replace it, or set it empty to forbid; not cancellable | `CraftingMenu.slotChangedCraftingGrid` (2x2 grid, table, mods' menus on vanilla's grid), `CrafterBlock.dispenseFrom`, `CrafterMenu` |
| `TradeEvent` | an exchange with a villager or a mod's trader | `MerchantMenu` |
| `ItemHeldEvent` | a player changes hotbar slot | `ServerboundSetCarriedItemPacket` |
| `EquipEvent` | something is about to be worn: right-click equip, an armour-slot click, a dispenser, a mob picking up gear, or a mod's extra slot. Cancelled, it stays where it was | `ArmorItem.use`, `ElytraItem.use`, `ArmorSlot.mayPlace`, `ArmorItem.dispenseArmor`, `Mob.equipItemIfPossible`; bridges for extra slots |
| `EquipmentChangeEvent` *(after)* | armour, hands, or a mod's extra slot (Curios, Accessories) changed, whatever did it; with the attribute changes it brings | `setItemSlot`, bridges for extra slots |

### Containers: `InventoryEvent`

An inventory is identified by what holds it (a block, an entity, a player, an item inside another item, a frequency in linked storage, or one player's own copy of a block's or entity's inventory, such as a Lootr chest, which only that mod's bridge can name), not by where a player is standing.

| Event | What | Core chokepoint |
|---|---|---|
| `InventoryOpenEvent` | an actor opens a menu on a block, entity or item, including remotely | `openMenu` |
| `InventoryCloseEvent` *(after)* | | `closeContainer` |
| `InventoryClickEvent` | what a player asked for inside an open menu | `AbstractContainerMenu.clicked` |
| `InventoryChangeEvent` | the authoritative change to an inventory's slots, whatever caused it; before and after | hoppers, hopper minecarts and droppers (as `InventoryMoveItemEvent`); bridges for everything else |
| ├ `InventoryMoveItemEvent` | items move between two inventories: hoppers, pipes, export buses, a seagull taking food from a hand | `HopperBlockEntity`, item handler transfers |
| ├ `InventoryGenerateEvent` | items appear with no source: a quest reward, an ore laser, a creative source | bridge |
| ├ `InventoryDestroyEvent` | items vanish with no destination: a void upgrade, a trash can, a matter condenser | bridge |
| └ `InventorySortEvent` | contents rearranged in place (Quark sorting) | bridge |

### Resources: `ResourceEvent`

Everything measured in amounts that isn't an item stack: energy, fluids in tanks, chemicals, heat, mana, LP, aura, radiation, ore left in a virtual vein, skill points, money. A resource is a registry-style id (`hadron:energy`, `hadron:fluid/minecraft/water`, `botania:mana`, `hadron:currency/coins`; a colon can't appear in an id's path, so a fluid's namespace becomes a path segment) with a unit. A holder is a block, an entity, an item, a chunk, or an account.

| Event | What | Core chokepoint |
|---|---|---|
| `ResourceChangeEvent` | a holder's amount changes; old and new | core for currencies, through the produce and consume leaves; bridges for everything else |
| ├ `ResourceProduceEvent` | created: a generator, a reactor's waste, a quest's currency reward | core for currency deposits; bridges for everything else |
| ├ `ResourceConsumeEvent` | destroyed: a machine spending power, a spell's mana, a vein depleted | core for currency withdrawals; bridges for everything else |
| └ `ResourceConvertEvent` | one resource into another in one step: fuel to heat to steam | bridge |
| `ResourceTransferEvent` | an amount moves between holders: a cable, a pipe, a wireless charger reaching a player in another dimension, a payment | core for currency transfers; bridges for energy, fluids and mods' resources |

Economy plugins provide the `hadron:currency` holders, so a payment by any mod or plugin is one `ResourceTransferEvent`.

### World: `WorldEvent`

| Event | What | Core chokepoint |
|---|---|---|
| `ExplosionPrimeEvent` | an explosion is about to happen; radius and fire editable | `Level.explode` |
| `ExplosionEvent` | an explosion's affected blocks and entities, editable; fires for each batch of a multi-tick blast | `Explosion.explode`, bridges for overridden explosions |
| `LightningStrikeEvent` | | `LightningBolt` spawn |
| `WeatherChangeEvent` | rain, thunder, clear; actor for a ritual or weather machine | `ServerLevel.setWeatherParameters` |
| `TimeSkipEvent` | an actor changes the time or whether it advances | `ServerLevel.setDayTime`, `PrimaryLevelData.setDayTime` |
| `BiomeChangeEvent` | biome cells change, with or without blocks changing | not shipped yet: see "Not shipped yet" |
| `TickAccelerateEvent` | an actor speeds up blocks or entities in an area; one event per accelerator per change, never per extra tick | bridge (Torcherino, time bottles) |
| `ChunkLoadEvent`, `ChunkUnloadEvent` *(after)* | | `ChunkMap` |
| `ChunkForceLoadEvent` | an actor keeps chunks loaded: a chunk loader, an anchor, a claim | `ServerChunkCache` tickets, `setChunkForced` |
| `ChunkForceUnloadEvent` *(after)* | that actor releases them, including on expiry | `ServerChunkCache` tickets, `setChunkForced` |
| `PortalCreateEvent` | | `PortalShape` |
| `WorldLoadEvent`, `WorldUnloadEvent`, `WorldSaveEvent` *(after)* | | `MinecraftServer` |

### Players: `PlayerEvent` (always a player)

| Event | What | Core chokepoint |
|---|---|---|
| `PlayerLoginEvent` | before join; deny with a message | `ServerConfigurationPacketListenerImpl` |
| `PlayerJoinEvent`, `PlayerQuitEvent`, `PlayerKickEvent` | the join and quit lines are editable and replace vanilla's | NeoForge's login and logout events, `ServerCommonPacketListenerImpl.disconnect` |
| `PlayerChatEvent` | | NeoForge's `ServerChatEvent` |
| `PlayerRespawnEvent` | respawn location editable | NeoForge's `PlayerRespawnPositionEvent` |
| `PlayerChangedWorldEvent` *(after)* | | NeoForge's `PlayerChangedDimensionEvent` |
| `PlayerAbilitiesChangeEvent` | flight allowed or revoked (Flugel Tiara, a flight ritual), fly and walk speed; revocation is *(after)* | `ServerPlayer.onUpdateAbilities` |
| `PlayerFlightRevokeEvent` *(after)* | a player lost permission to fly; speeds changed at the same moment come with it | `ServerPlayer.onUpdateAbilities` |
| `PlayerToggleSneakEvent`, `PlayerToggleSprintEvent`, `PlayerToggleFlightEvent` | | `handlePlayerCommand`, `handlePlayerAbilities` |
| `PlayerGameModeChangeEvent` | | NeoForge's `PlayerChangeGameModeEvent` |
| `PlayerExpChangeEvent`, `PlayerLevelChangeEvent` | vanilla experience only; mod skill points are resources | NeoForge's `PlayerXpEvent.XpChange`, the player's tick |
| `PlayerAdvancementDoneEvent` *(after)* | | NeoForge's `AdvancementEarnEvent` |
| `PlayerSwapHandItemsEvent` | | NeoForge's `LivingSwapItemsEvent.Hands` |
| `PlayerBedEnterEvent`, `PlayerBedLeaveEvent` | | NeoForge's `CanPlayerSleepEvent`, `PlayerWakeUpEvent` |
| `PlayerFishEvent` | each step of fishing with any rod whose bobber is a `FishingHook`: cast, bite, escape, reel-in; the catch and its experience editable. An `EntityEvent` about the bobber, so it carries the actor (the player or a fake player) | `catchingFish`, `retrieve`, bridges |
| `PlayerModPacketEvent` | a mod's network message from this player; payload id. The catch-all for keybind actions only the server sees | NeoForge's `NetworkRegistry.handleModdedPayload` |

### Server: `ServerEvent`

| Event | What | Core chokepoint |
|---|---|---|
| `ServerLoadEvent` *(after)* | once per start, after plugins enable | server started |
| `ServerListPingEvent` | a client's server list asks about the server; MOTD, shown player counts and the hover sample editable; not an action, so no actor | status request |
| `CommandExecuteEvent` | any command about to run, from a player, the console, a quest reward, a mod or each line of a datapack function; actor and authority | `Commands.performCommand`, function lines |
| `AccessControlChangeEvent` | a claim, team, party, alliance or trust list changes: boundaries, members, flags; who changed it | bridge (claim and team mods) |
| `DataRevealEvent` | server data about a block, entity, inventory or account is about to be sent to a player who didn't open it (Jade, WTHIT, map radar, a sigil reading another player's network); fields removable | bridge (Jade, WTHIT, Blood Magic) |
| `PluginEnableEvent`, `PluginDisableEvent` *(after)* | | Hadron's plugin host |
| `ServiceRegisterEvent`, `ServiceUnregisterEvent` *(after)* | a provider appeared or left | Hadron's services manager |

## Not shipped yet

Designed but not in `api`, because core can't yet fire them for every actor without breaking rule 7 or making a cancel unsafe. A plugin can't listen for them, so none of them can look like coverage.

- Removal by expiry (a mob despawning, a timed summon, a temporary portal): will be an *(after)* event, so a cancel can't make it permanent. `EntityRemoveEvent` shipped without it, and core fires it only for `/kill`, buckets and end crystals shattering: refusing in Minecraft's one removal method could duplicate (a cancelled arrow pickup), so each cause needs its own hook.
- `EntityAttributeChangeEvent`, `EntityRelationshipChangeEvent`, `FoodLevelChangeEvent`: the vanilla classes that change don't know their entity.
- `BiomeChangeEvent`: biome writes are too hot and scattered for one hook.
- `ResourceTransferEvent` from the port's energy and fluid capabilities: no single point sees both ends; bridges fire it meanwhile.

## Left out

- **An event per spell school or damage type.** Damage, healing, effects, blocks, spawns and teleports already describe what spells do; the spell is on the operation.
- **An event per fuel tick.** Burning internal fuel is a `ResourceConsumeEvent` when the reserve changes, and acceleration is one event per change, so hot loops don't fire thousands of events.
- **Mod progression state** (Twilight Forest's conquered bosses, quest progress). It changes no world, player or item; plugins that need it use the bridge's extras.
- **Client-only data** (local waypoints, minimap settings).
- **Data tied to no place** (a computer's files, a mod's network database). Plugins guard it through the block or item that reaches it, and whatever it makes happen in the world fires its own events.
- **Creatures stored outside items** (Cobblemon's party and PC). Moving, trading, evolving or deleting them in storage are the bridge's own events until a second official bridge needs the same ([promotion](bridges.md#promotion-when-bridges-events-become-core)). Sending one out is an `EntitySpawnEvent`, and capture into storage is an `EntityCaptureEvent`, so every creature entering or leaving the world is seen.
