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
| `Remembered` | an actor that caused something and has since gone, kept as what it was (a namespaced kind, its UUID where it had one) and who was behind it | the poisoner who logged off, a spent area-effect cloud, a removed entity, a broken machine |
| `Server` | the server on an operator's orders; trusted by protection | the console, RCON, a command block, a datapack function, a plugin's own task, `onEnable`, `onDisable` or handler for an event nobody is behind (`plugin <name>`) |
| `Nature` | the world on its own, with no entity behind it | fire spreading, water flowing, crops growing |
| `Unknown` | a mod acted and nothing said who | an unbridged mod's scheduled task, for a block change, seen only in a [watched area](bridges.md#the-chokepoint-cost); also a spawner's mob, a container moved with nobody behind it, and other actions Hadron can't trace |

Every actor except `Server`, `Nature` and `Unknown` carries up to three people, as UUIDs so they work while offline:

- **responsible**: who answers for it. A machine's owner, a summon's summoner, a gun's shooter, the player whose gadget queued the build.
- **controller**: who directs it right now, if different. A vehicle's driver, a drone's remote operator, the player who charmed a mob.
- **authority**: whose permissions it runs with, for commands. A quest reward command may run as the server.

Protection uses the controller where there is one, otherwise the responsible player. A charmed mob acts for its charmer, not its original owner, so stealing a creature never inherits the victim's claim rights.

**Attribution persists.** Core stores the causal actor on projectiles, summons, falling blocks, primed explosives, queued work, every effect and an entity's fire, so a shell landing ten seconds after firing, a multi-tick explosion's last batch, or a Building Gadgets paste finishing over several ticks still carries who caused it. While a modded block entity or entity ticks, core makes it the actor; while a player's packet is handled, that player is. Bridges add owners and controllers.

An identifiable mob is always an `EntityActor`, even when it spawned naturally. `Nature` is reserved for processes with no entity, so a logger never loses which creature griefed.

## Facts

Rule 3 says a parent carries enough to act on. This section says what that means for the facts a plugin can't work out for itself: **where an action really happens, everything it carries, and who is behind it, stated by core and bridges, true, and never guessed by a plugin** ([design](design.md), "Core states facts"). It states facts, not meanings: whether eating is a player's own business or a machine's inventory is protected is policy, which plugins decide, and which a protection helper Hadron ships will decide once for the plugins that want it (its own design, after these facts land).

These are the principles. How each event meets them is settled in the step that builds it, by a test there; the open questions per event are tracked in the [facts checklist](https://github.com/iRedSC/hadron/issues/45).

### Places

Every action says where it happens: `getPlaces()`, declared once on `Placed`, which `ActionEvent` and `PlayerEvent` implement, and so do `DataRevealEvent`, `AccessControlChangeEvent` and `CommandExecuteEvent`. `ActionEvent` declares it abstract, so a bridge's own action type that doesn't say where it happens doesn't compile. It returns `Places`, a list of `Place`s that can only be built from at least one, read from the event's own payload each time it is called, so it can't disagree with its getters, and a listener that redirects a teleport or spares a block from an explosion changes the places with it. Each part of a payload that has a place of its own (a move's source and destination, each inventory a change touches, each entity named) keeps it, and `getPlaces()` is their union, so a reader can tell which place belongs to which part: `Place.of(InventoryHolder)` and `Place.of(ResourceHolder)` give each part's places (both halves of a connected double chest, read from the chest's block state), and `Place.of(Entity)` an entity's. A `Place` is sealed:

- `Spot(level, box)`: one block or an area. An entity's is the block it stands in.
- `Level(level)`: a whole level.
- `Nowhere(kind, id, owner)`: known to have no place in a world, with what it is: an ender chest's or a mod's linked storage (its network and frequency; an ender chest's owner is its player), an account (`hadron:account`), a player logging in (`hadron:player`).
- `Unknown(bound, reason)`: core or a bridge can't say exactly where. The bound contains every place it could be: a `Spot`, a `Level`, or `Anywhere`. The reason is one of a closed set: an inventory nothing identified, a mod's menu nothing describes, an operation that can't be placed, an origin nobody saw, a command, nobody behind it.

**Never guessed, never empty.** Where core can't know where something happens, it says `Unknown`, never a guessed spot and never an empty list: a spot that isn't true is a bug. The actor's own position is never a place of its action; the actor already says where it is. An item in someone's hand is where they are.

What each family states:

| Family | Places |
|---|---|
| `BlockEvent` | `getPos()`, then each space in `getBlocks()`; a move adds each block's source and target and its swept space; a flow both ends, a spread its source, a burn its fire, a drop where its items lie |
| `EntityEvent` | the block the entity was in, then each other entity it names: a mount, a lead's holder, a breeding's parents (not the breeder), a target, a projectile's hit (point, block, entity; a hitscan shot, whose entity is its shooter, only these), a teleport's destination and passengers, a capture's block or carrier, a fishing hook's player and catch. Whoever did it (a damager, a shooter, a tamer) is not a place |
| `ItemEvent` | where the stack is: the inventory holding it (crafting, smelting, an anvil, a trade, a change in place), the entity using, eating, holding or wearing it, where it lies (a merge, a despawn), where a drop lands, a pickup's item and destination. A leaf naming none is `Unknown` within its level |
| `InventoryEvent` | `getHolder()`; a change adds every inventory it touches; a click, an open and a close add every inventory behind the menu's slots (both halves of a double chest), and a slot that is the menu's own (a crafting grid) is where the menu says it is open (`getMenuHolder()`), `Unknown` for a menu nothing describes; an inventory nothing identifies (a double chest's half that isn't a chest) is its own `Unknown`, never another's place |
| `ResourceEvent` | each holder: a block, a chunk (its full height), an entity, an item where the inventory it is in is (`ResourceHolder.Item(stack, heldIn)`; `Unknown` when nothing says), an account (`Nowhere`) |
| `WorldEvent` | its area, or its whole level; an explosion its centre, each block and each entity, as the lists stand; weather and time the changed level, every level holding the same data, and, when that is the server's main data, every level whose data is a `DerivedLevelData` (vanilla wraps the main data in one for every level but the overworld) |
| `PlayerEvent` | the block the player stands in; a login `Nowhere` (the player); a bed both halves; a respawn where the player arrives; a change of world adds the block the player stood in in the world left (`getFrom()`, `getFromPosition()`) |
| `ServerEvent` | `DataRevealEvent` its target (never its viewer); `AccessControlChangeEvent` the group, `Nowhere` (its area isn't in the event); `CommandExecuteEvent` `Unknown`, anywhere, for a command: its effects place themselves |

Not stated yet: a drop doesn't say what it leaves; a respawn doesn't name the anchor it uses.

**Self-tests hold it.** `PlaceTests` runs vanilla fixtures (a chest, a double chest, a furnace, a piston, a boat, a teleport, a console command) and fails on any event whose places can't be read or leave out an entity it names, and on any `Unknown` but a command's; others check each family's places against its payload. The fast `PlacesTest` fails when an entity event leaf names an entity but doesn't override `getPlaces()`. The acceptance kit places events by `getPlaces()` alone: in the box when a `Spot` overlaps it or a `Level` is its level, out for `Nowhere` and places elsewhere, failing closed on an `Unknown` whose bound reaches the box. Motion and whole-level events (weather, time) stay out of its protection: they are meanings, not places.

### True outcomes

Where an action ends somewhere other than where it set out to (a teleport settling on the ground, a dismount setting the rider down), core works out the real outcome first, fires with it, and then does exactly that. Where it can't yet, the event says `Unknown` for that part rather than stating the requested outcome.

**Carrying has one shape.** An event that moves entities along with it lists them in `getCarried()`, each with where it was and where it ends up, and its places include each. What it can't know it carries is marked unknown, not left out.

**The actor is who caused it.** An entity moved by something else (pushed by a piston, carried by water, knocked back) acts for what moved it, not for itself.

### Attribution reaches every tick

An effect and an entity's fire keep the actor that caused them and where it was applied, saved with the entity. While they act, that actor is on the actor stack as an explicit cause, kept even when it is the entity itself, so everything they cause acts for it: damage, healing, what they spawn. A cause that has gone (an offline player, a spent cloud) is `Actor.Remembered`, whose `actingFor()` still answers.

- **An effect's cause** is who applied it: the source `addEffect` is given (a splash or lingering potion's thrower, a tipped arrow, an area cloud, a wither, the entity itself for its own splash potion), otherwise the actor on the stack (a mod's machine, the server for `/effect`), with the people it had then: a bridge's turret firing for its owner still acts for that owner while the turret is there. Each effect keeps its own, hidden ones included: credit moves to a new application only when it replaces the effect (a higher amplifier, or the same one for longer), and an application vanilla turns down or that changes nothing (the active instance passed in again) moves nothing; a weaker or shorter one waits underneath with its own cause, and resumes with it when the stronger one runs out.
- **Fire's cause** is who set the entity alight: credit moves only on an ignition that leaves more burning time than before. An extension taken back in the same tick gives it back: a refused flaming arrow's hit puts the burning time back as it was, and the earlier cause and place with it. Walking into a fire block or lava is nature's, as before: neither has anywhere to keep who placed it.
- **Where** is the block the entity stood in when the effect was applied or the fire lit. `EntityDamageEvent` and `EntityRegainHealthEvent` state it as `getAppliedAt()` for damage and healing an effect or fire deals to the entity it is on: a `Spot`, `Unknown` (an unseen origin) for one saved before Hadron kept causes, and empty for everything else. It is not one of the event's places: the action happens where the entity is now.
- **Saved** with the entity, beside vanilla's own data (`hadron:causes` on each effect, the `hadron:fire_cause` attachment), so a cause survives a restart and unloading; a world saved without them loads as before, with no cause. Nothing nature or an unknown mod applied has anyone to keep.

### Closing the known gaps

`MissingFactTests` holds each fact the regions plugin had to guess, as a `@KnownGap`. Each step below lands on its own and turns its gaps green; until then a gap is missing exactly where the tests say, as today.

| Gap | Closed by |
|---|---|
| A teleport states where it settles | the outcome worked out before firing |
| A rider is named when their vehicle carries them | `getCarried()` |
| A dismount states where the rider is set down | the outcome worked out before firing |
| ~~A shift-click names the inventory items move into~~ | the click's places (closed) |
| ~~Opening a double chest names both halves~~ | the inventory's places (closed) |
| ~~Poison and burning act for whoever caused them~~ | attribution on effects and fire (closed) |
| ~~Starting to mine fires an event~~ | Bukkit's `BlockDamageEvent` (closed); lasting damage became `BlockCrackEvent` |

1. ~~`Placed`, `Place` and `getPlaces()` for every family, with the self-tests above; the acceptance kit places events by `getPlaces()` and its own placement rules go.~~ Done.
2. Each fact, one or a few per PR, each closing its gap and the checklist items for the events it touches, by a test.
3. The protection helper, its own design and council: the meanings (kinds, whose business, what an attack is) over these facts, for the plugins that want them. The regions plugin becomes a policy over it.

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
| `BlockDamageEvent` | an actor starts mining a block, before it is hit; the tool and the face (Bukkit's meaning). Not in creative, where a hit breaks at once | `ServerPlayerGameMode.handleBlockBreakAction`; bridges for drills that mine over time |
| `BlockCrackEvent` | a block takes lasting partial damage short of breaking | bridge (Create Big Cannons) |
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
| `EntityDamageEvent` | an entity is hurt; damage source, actor, and the weapon or spell; where an effect or fire dealing it was applied | `hurt`, and `ArmorStand.hurt`, which skips it |
| `EntityDeathEvent` | an entity is dying; drops, experience and where each item goes are editable; not cancellable | `die` |
| └ `PlayerDeathEvent` | a player is dying; each item, including extra slots, is assigned to kept, dropped, grave or destroyed; the death line is editable and replaces vanilla's | `ServerPlayer.die` |
| `EntityRegainHealthEvent` | healed, including absorption; where an effect healing it was applied | `heal`, `setAbsorptionAmount` |
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

An inventory is identified by what holds it (a block, an entity, a player, an item inside another item, a frequency in linked storage, one player's own copy of a block's or entity's inventory, such as a Lootr chest, which only that mod's bridge can name, or a plugin's own container), not by where a player is standing. An open or a click also says which of the menu's slots are the viewer's own inventory, including a mod's menu that shows it through the loader's item handlers.

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

Everything measured in amounts that isn't an item stack: energy, fluids in tanks, chemicals, heat, mana, LP, aura, radiation, ore left in a virtual vein, skill points, money. A resource is a registry-style id (`hadron:energy`, `hadron:fluid/minecraft/water`, `botania:mana`, `hadron:currency/coins`; a colon can't appear in an id's path, so a fluid's namespace becomes a path segment) with a unit. A holder is a block, an entity, an item (with the inventory it is in), a chunk, or an account.

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
