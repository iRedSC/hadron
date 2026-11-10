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

## Facts

Rule 3 says a parent carries enough to act on. This section says what that means for the facts a plugin can't work out for itself: **where an action really happens, everything it carries, and who is behind it, stated by core and bridges, true, and never guessed by a plugin** ([design](design.md), "Core states facts"). It states facts, not meanings: whether eating is a player's own business or a machine's inventory is protected is policy, which plugins decide, and which a protection helper Hadron ships will decide once for the plugins that want it (its own design, after these facts land).

### Places

Every action, and every player event that touches something outside the player (fishing an entity in, getting into a bed), says where it happens: `getPlaces()`, declared once on `Placed`, which `ActionEvent` and `PlayerEvent` implement, and so does every `ServerEvent` with a target or a source in a world (a reveal's target, a claim change's area). An event about only the player, such as a sneak, is the player's own spot; fishing an entity in, getting into a bed and respawning at an anchor also name what they touch; a login, fired before the player is placed in any world, is `Nowhere` (their account). A command is `Unknown(anywhere)` with the reason that it is a command: its source (the console's sits at spawn) isn't where it acts, and what it does places itself through its own events. a list of `Place`s, read from the event's own payload each time it is called, so it can't disagree with its getters, and a listener that redirects a teleport or trims an explosion changes the places with it. Each family works them out once, so a bridge firing a parent gets them for free. A `Place` is sealed:

- `Spot(level, box)`: one block or an area.
- `Level(level)`: a whole level (weather, time); one per level a change reaches, so weather or time changed on data other levels share or wrap names each of them.
- `Nowhere(identity)`: known to have no place in a world, with what it is: linked storage by its network, frequency and owner where there is one (an ender chest's player), an account by its holder.
- `Unknown(bound, reason)`: core or a bridge couldn't say exactly where, and why, from a closed set (an unidentified inventory, an unbridged mod's menu, an unplaced operation, an unseen origin): (an inventory nothing identified, an operation a bridge can't place, fire from an origin nobody saw), with a bound that contains every place the target could be: a level or an area only where the target's own level or extent is known, otherwise `anywhere`. The actor's position isn't a bound on the target (a remote terminal, a wireless charger across levels); the actor already says where it is.

Nothing is left out for want of a place; it is `Nowhere` or `Unknown`, and the two are never confused. A self-test fires every `Placed` class core fires and fails on any with no places; another runs vanilla fixtures (a chest, a double chest, an ender chest, a furnace, a piston, a boat, a teleport) through every event they cause and fails on any `Unknown`, whatever its class, so an `Unknown` is only ever what its reason says. Per-family tests check the places against the payload (both halves of a chest, both ends of a move).

Places name every spot: each block a block event changes, the swept space and both ends of each move of a block or structure, every inventory an inventory event involves (both halves of a double chest; for a click, the inventories its slots belong to, and for a shift-click, each inventory it could move into, which a vanilla menu fixes by its slot ranges even though it picks the slot as it moves; `Unknown` only for a mod's menu no bridge describes), both ends of an item's way (where a pickup was and the storage it goes into), each block and entity an explosion reaches, where an entity was and arrives.

### True, not requested

Where an action ends somewhere other than where it set out to, core works out the real outcome first and then does exactly that.

- **Teleports.** A chorus fruit or random teleport picks its spot, settles it on the ground and checks it fits, as vanilla does, trying the next spot if not, and fires only for a spot it will really take, with that landing as `getTo()`. A rider is dismounted after that search, with the landing as its dismount's `getTo()`, or vanilla's dismount spot when no spot fits. A redirect is exact: `EntityTeleportEvent.setTo` places the entity at the position given, and its Javadoc says so.
- **Dismounts.** `EntityDismountEvent` gains `getTo()`, where the rider will be set down, worked out before asking. A forced dismount can't keep the rider on. When the vehicle forces it (broken, gone, left behind by a move to another level), cancelling leaves the rider exactly where they are, at their seat, and `getTo()` becomes that seat. When the rider forces it, `getTo()` is where the rider really goes: a teleport's destination, or where a rider who died or was removed was. `isForced()`'s Javadoc lists each cause and what cancelling does.
- **Carried entities.** The events that carry entities (`EntityMoveEvent`, `EntityTeleportEvent`, block and structure moves) gain `getCarried()`: every entity carried, direct or nested, with where it was and where it ends up. A teleport lists only those who go along, worked out from its current destination on every call, so a redirect to another level that takes riders along names them; riders left behind get their own dismount. `EntityTeleportEvent.getPassengers()` goes: its direct riders are in `getCarried()`. A vehicle carrying a player fires its move whenever it or anyone it carries changes block, including one nobody drives (a minecart on rails, acting as itself: an `EntityActor` with whoever placed or last pushed it); cancelling puts it back where it was and as it was turned, which core now tracks, so every rider is back in their block. A block or structure move lists what it carries when it starts (for a piston, every entity on or in the path of its blocks then), so refusing it is clean. An entity that steps onto a piston's moving blocks during their two ticks of travel isn't in it; that is listed under "Not shipped yet". Carrying has this one shape: a rider or a carried entity is in its carrier's `getCarried()`, and the carrier's places include where each ends up, so a listener on places sees it whatever carried it.
- **Starting to mine.** `BlockDamageEvent` takes Bukkit's meaning, a player starting to mine a block (cancelling stops the dig); today's lasting-damage event, which only bridges fire, becomes `BlockCrackEvent`. Hadron's API isn't released yet, so the only code that listens for the old meaning is in this repository, and that step moves each use to `BlockCrackEvent` along with its catalog row, Javadoc, `BridgeContractTests` and `bridge-hooks.md`; the skill lists it among Bukkit's names.

### Attribution reaches every tick

Each effect instance (the hidden weaker ones Minecraft resumes included) and an entity's fire keep the actor that caused them and where it was applied, saved with the entity and cleared when they end. An application that leaves an instance as it was credits nobody new. Fire's credit moves only to an ignition that leaves more burning time than the stored cause's would have. Fire blocks and lava have nowhere to keep who placed them, so burning they start stays `Nature`'s for now; that is listed under "Not shipped yet". An effect's credit moves only when an application raises its potency or duration, not for a change of particles or icon, and each hidden instance keeps its own cause, so the weaker poison Minecraft resumes is still its own poisoner's; tests cover replacement and resumption. While an effect or fire acts, its actor is on the actor stack as an explicit cause, so everything it causes acts for it: its damage, a regeneration's healing, Infested's silverfish. An explicit cause is kept even when it is the entity itself (a player's own poison or regeneration); the rule that drops an actor matching its subject applies only where no cause was stored. Damage or healing from an effect or fire also says where it was applied (`getAppliedAt()`, with its level), which a logger can read after everyone has moved. The actor is the stored actor while its entity is still in the world, and otherwise `Actor.Remembered`, a new actor for a cause that has gone (an offline player, a spent cloud) whose `actingFor()` still answers.

### Closing the known gaps

`MissingFactTests` holds each fact the regions plugin had to guess, as a `@KnownGap`. Each step below lands on its own and turns its gaps green; until then a gap is missing exactly where the tests say, as today.

| Gap | Closed by | Asserted as |
|---|---|---|
| A teleport states where it settles | settling worked out before firing | the teleport's `getTo()` is the block alice landed on |
| A rider is named when their vehicle carries them | `getCarried()`, and vehicles firing for riders' crossings | the boat's move carries bob to his new block, in a boat alice drives and a minecart nobody drives; refused, bob is back in his old block |
| A dismount states where the rider is set down | `EntityDismountEvent.getTo()` | the dismount's `getTo()` is where alice was set down |
| A shift-click names the inventory items move into | a shift-click's places are each inventory it could move into | the click's `getPlaces()` include the chest |
| Opening a double chest names both halves | an inventory's places are every block it stands on | the open's `getPlaces()` include both halves |
| Poison and burning act for whoever caused them | the cause kept on each effect instance and on the fire | the damage acts for bob, and a player's own poison for themselves |
| Starting to mine fires an event | `BlockDamageEvent` | a cancellable `BlockDamageEvent` at the block |

1. `Place` and `getPlaces()` for every family, with the self-tests above. Where today's payload lacks a location its producer knows, the payload gains it here (a resource held in an item gains the holder it's in), tested with a remote transfer whose actor and target stand in different places. The acceptance kit places events by `getPlaces()` and its own placement rules go.
2. Each fact above, one or a few per PR, each closing its gap and updating its catalog row (`EntityTeleportEvent`'s says riders go along by `getCarried()`, and pets and leashed mobs don't, as vanilla leaves them).
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
- An entity that steps onto a piston's moving blocks during their two ticks of travel: the piston's event lists only what it carries when it starts, and refusing a pick-up mid-move would leave the entity in the block's way.
- Who placed a fire block or lava: neither has a block entity to keep it in, so burning they start is `Nature`'s.
- `ResourceTransferEvent` from the port's energy and fluid capabilities: no single point sees both ends; bridges fire it meanwhile.

## Left out

- **An event per spell school or damage type.** Damage, healing, effects, blocks, spawns and teleports already describe what spells do; the spell is on the operation.
- **An event per fuel tick.** Burning internal fuel is a `ResourceConsumeEvent` when the reserve changes, and acceleration is one event per change, so hot loops don't fire thousands of events.
- **Mod progression state** (Twilight Forest's conquered bosses, quest progress). It changes no world, player or item; plugins that need it use the bridge's extras.
- **Client-only data** (local waypoints, minimap settings).
- **Data tied to no place** (a computer's files, a mod's network database). Plugins guard it through the block or item that reaches it, and whatever it makes happen in the world fires its own events.
- **Creatures stored outside items** (Cobblemon's party and PC). Moving, trading, evolving or deleting them in storage are the bridge's own events until a second official bridge needs the same ([promotion](bridges.md#promotion-when-bridges-events-become-core)). Sending one out is an `EntitySpawnEvent`, and capture into storage is an `EntityCaptureEvent`, so every creature entering or leaving the world is seen.
