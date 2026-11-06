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
- **Per-target events cancel only their target.** Denying one entity in an area spell spares that entity; the rest of the spell lands. The cost is not refunded, because the action happened.
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
| `Server` | the server on an operator's orders; trusted by protection | the console, RCON, a command block, a datapack function |
| `Nature` | the world on its own, with no entity behind it | fire spreading, water flowing, crops growing |
| `Unknown` | a mod acted and nothing said who | an unbridged mod's scheduled task |

Every actor except `Server`, `Nature` and `Unknown` carries up to three people, as UUIDs so they work while offline:

- **responsible**: who answers for it. A machine's owner, a summon's summoner, a gun's shooter, the player whose gadget queued the build.
- **controller**: who directs it right now, if different. A vehicle's driver, a drone's remote operator, the player who charmed a mob.
- **authority**: whose permissions it runs with, for commands. A quest reward command may run as the server.

Protection uses the controller where there is one, otherwise the responsible player. A charmed mob acts for its charmer, not its original owner, so stealing a creature never inherits the victim's claim rights.

**Attribution persists.** Core stores the causal actor on projectiles, summons, falling blocks, primed explosives and queued work, so a shell landing ten seconds after firing, a multi-tick explosion's last batch, or a Building Gadgets paste finishing over several ticks still carries who caused it. While a modded block entity or entity ticks, core makes it the actor; while a player's packet is handled, that player is. Bridges add owners and controllers.

An identifiable mob is always an `EntityActor`, even when it spawned naturally. `Nature` is reserved for processes with no entity, so a logger never loses which creature griefed.

## Catalog

**Core** means fired at a vanilla chokepoint, so mods that go through vanilla are covered with no bridge. **Bridge** means only a bridge can fire it. All are cancellable unless marked *(after)*.

### Blocks: `BlockEvent`

`BlockEvent` is everything that happens to a block's space. Its payload: level, positions affected, and for each the state and block entity data before and after.

| Event | What | Core chokepoint |
|---|---|---|
| `BlockChangeEvent` | a block's state or its block entity's data changes | `Level.setBlock`, chunk section writes |
| ├ `BlockBreakEvent` | a block is removed by an actor | `destroyBlock`, player mining, `setBlock` to air with an actor |
| ├ `BlockPlaceEvent` | a block or fluid source is placed by an actor | `BlockItem.place`, `setBlock` with an actor |
| ├ `BlockMultiPlaceEvent` | several blocks at once (a door, a bed, a building gadget) | |
| ├ `BlockTransformEvent` | an actor changes a block into another (machine conversion, stripping a log, upgrading a chest in place) | `setBlock` with an actor |
| ├ `BlockDataChangeEvent` | block entity data changes with the state unchanged: a spawner's mob, a drawer's lock, a terminal's binding, a name | bridge |
| ├ `BlockBurnEvent`, `BlockIgniteEvent` | fire destroys or starts | `FireBlock` |
| ├ `BlockFromToEvent` | a fluid flows into a block | `FlowingFluid.spreadTo` |
| ├ `BlockSpreadEvent`, `BlockFormEvent`, `BlockFadeEvent`, `BlockGrowEvent`, `LeavesDecayEvent` | natural changes | the vanilla block's tick |
| ├ `BlockFertilizeEvent` | bonemeal or a growth accelerator | `BoneMealItem` |
| └ `StructureGrowEvent` | a tree or structure grows from one block | `TreeGrower` |
| `BlockMoveEvent` | blocks relocate with their data and scheduled ticks; source to target mapping, and the space swept through | |
| ├ `BlockPistonExtendEvent`, `BlockPistonRetractEvent` | | `PistonBaseBlock` |
| └ `StructureMoveEvent` | a contraption, ship or spatial transfer assembles, moves, rotates or disassembles; old and new transforms | bridge (Create, Valkyrien Skies, AE2 spatial IO) |
| `BlockUseEvent` | an actor right-clicks or activates a block, including remotely through a linked terminal | `useItemOn`, `useWithoutItem` |
| `BlockRedstoneEvent` | an actor changes the signal a block emits without changing its state | bridge (Integrated Dynamics, LaserIO) |
| `BlockDamageEvent` | a block takes lasting partial damage short of breaking | bridge (Create Big Cannons) |
| `SignChangeEvent` | sign text set | `SignBlockEntity` |
| `BucketEmptyEvent`, `BucketFillEvent` | fluid placed or taken from the world by an item | `BucketItem` |

Fluid placed or drained without a bucket (a Refined Storage constructor, a hose pulley) is a `BlockPlaceEvent` or `BlockBreakEvent` plus a `ResourceTransferEvent` in one operation.

As in Bukkit, `BlockMultiPlaceEvent` extends `BlockPlaceEvent`, `BlockSpreadEvent` extends `BlockFormEvent` which extends `BlockGrowEvent`, and the bucket events extend `BlockChangeEvent`, so a listener for the broader event never misses the narrower one. `BlockFromToEvent.getPos()` is the block flowed into; `getFromPos()` is the source. An explosion's blocks are reported once, in `ExplosionEvent`'s editable list, not as one break event each.

### Entities: `EntityEvent`

Payload: the entity, its position, and what changed.

| Event | What | Core chokepoint |
|---|---|---|
| `EntitySpawnEvent` | an entity enters the world; reason (natural, spawner, egg, breeding, summoned, released from capture, actor). Not for dropped items, experience orbs or falling blocks, whose cancelling would destroy what they carry; see `ItemDropEvent` | `addFreshEntity` |
| `EntityRemoveEvent` | an entity leaves the world other than by dying or unloading; reason (killed without damage, dismissed, converted, discarded) | `Entity.remove` with a reason other than unload |
| └ `EntityCaptureEvent` | an entity is stored into an item, block or player's carry state (bucket, mob catcher, Carry On) | `Bucketable`, bridges |
| `EntityDamageEvent` | an entity is hurt; damage source, actor, and the weapon or spell | `hurt` |
| `EntityDeathEvent` | an entity is dying; drops, experience and where each item goes are editable; not cancellable | `die` |
| └ `PlayerDeathEvent` | a player is dying; each item, including extra slots, is assigned to kept, dropped, grave or destroyed | |
| `EntityRegainHealthEvent` | healed, including absorption | `heal`, `setAbsorptionAmount` |
| `EntityCombustEvent` | an entity is set on fire | `igniteForSeconds`, `setRemainingFireTicks` |
| `EntityPotionEffectEvent` | a registered effect added, changed or removed | `addEffect`, `removeEffect` |
| `EntityStatusChangeEvent` | a restriction outside the effect registry: stunned, frozen, AI disabled, casting interrupted, held | `Mob.setNoAi`, bridges |
| `EntityAttributeChangeEvent` | an attribute's base value or modifiers change; source of the modifier | `AttributeInstance` setters |
| `EntityVelocityEvent` | an actor pushes, pulls or launches an entity; old and new velocity | `setDeltaMovement`, `push` with an actor |
| └ `EntityKnockbackEvent` | knockback from a hit or blast | `LivingEntity.knockback` |
| `EntityMoveEvent` | an entity with an actor or controller (a player, a vehicle) crosses into another block | movement ticks, only when a listener's area is involved |
| └ `PlayerMoveEvent` | | |
| `EntityTeleportEvent` | an entity moves other than by walking; cause, and everything travelling with it (passengers, pets, leashed mobs) | `teleportTo`, `changeDimension` |
| `EntityTargetEvent` | a mob or turret picks a target | `setTarget` |
| `EntityControlChangeEvent` | who controls an entity changes without mounting: charmed, enthralled, a drone linked to a monitor, a guard assigned; expiry is *(after)* | bridge |
| `EntityRelationshipChangeEvent` | reputation or a lasting relationship changes (villager gossip, an NPC's marriage) | `GossipContainer`, bridges |
| `EntityUseEvent` | an actor right-clicks an entity | `interact` |
| `EntityMountEvent`, `EntityDismountEvent` | riding, including vehicles and a player carrying another | `startRiding`, `stopRiding` |
| `EntityLeashEvent`, `EntityUnleashEvent` | | `Leashable` |
| `EntityShearEvent` | | `Shearable` |
| `EntityTameEvent`, `EntityBreedEvent` | | `tame`, `spawnChildFromBreeding` |
| `EntityTransformEvent` | an entity becomes another (zombie villager, a mod's conversion) | `convertTo` |
| `EntityAirChangeEvent`, `FoodLevelChangeEvent` | | `setAirSupply`, `FoodData` |
| `ExperiencePickupEvent` | an experience orb is collected by a player or a machine (a magnet storing XP as fluid) | `ExperienceOrb.playerTouch`, bridges |
| `ProjectileLaunchEvent` | anything fired or thrown; the actor is the shooter | `Projectile` spawn |
| `ProjectileHitEvent` | a projectile, or a hitscan shot with no projectile, hits a block or entity; hit position, origin, weapon | `onHit`, bridges for hitscan |
| `HangingPlaceEvent`, `HangingBreakEvent` | item frames, paintings | `HangingEntity` |
| `ArmorStandManipulateEvent` | equipment taken or placed on a stand | `ArmorStand.interactAt` |

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
| `TradeEvent` | an exchange with a villager or a mod's trader | `MerchantMenu` |
| `ItemHeldEvent` | a player changes hotbar slot | `ServerboundSetCarriedItemPacket` |
| `EquipEvent` | something is about to be worn: right-click equip, an armour-slot click, a dispenser, a mob picking up gear, or a mod's extra slot. Cancelled, it stays where it was | `ArmorItem.use`, `ElytraItem.use`, `ArmorSlot.mayPlace`, `ArmorItem.dispenseArmor`, `Mob.equipItemIfPossible`; bridges for extra slots |
| `EquipmentChangeEvent` *(after)* | armour, hands, or a mod's extra slot (Curios, Accessories) changed, whatever did it; with the attribute changes it brings | `setItemSlot`, bridges for extra slots |

### Containers: `InventoryEvent`

An inventory is identified by what holds it (a block, an entity, a player, an item inside another item, a frequency in linked storage), not by where a player is standing.

| Event | What | Core chokepoint |
|---|---|---|
| `InventoryOpenEvent` | an actor opens a menu on a block, entity or item, including remotely | `openMenu` |
| `InventoryCloseEvent` *(after)* | | `closeContainer` |
| `InventoryClickEvent` | what a player asked for inside an open menu | `AbstractContainerMenu.clicked` |
| `InventoryChangeEvent` | the authoritative change to an inventory's slots, whatever caused it; before and after | `Container.setItem`, `Inventory.add`, `Slot.set`, bridges for item handlers |
| ├ `InventoryMoveItemEvent` | items move between two inventories: hoppers, pipes, export buses, a seagull taking food from a hand | `HopperBlockEntity`, item handler transfers |
| ├ `InventoryGenerateEvent` | items appear with no source: a quest reward, an ore laser, a creative source | |
| ├ `InventoryDestroyEvent` | items vanish with no destination: a void upgrade, a trash can, a matter condenser | |
| └ `InventorySortEvent` | contents rearranged in place (Quark sorting) | |

### Resources: `ResourceEvent`

Everything measured in amounts that isn't an item stack: energy, fluids in tanks, chemicals, heat, mana, LP, aura, radiation, ore left in a virtual vein, skill points, money. A resource is a registry-style id (`hadron:energy`, `hadron:fluid/minecraft/water`, `botania:mana`, `hadron:currency/coins`; a colon can't appear in an id's path, so a fluid's namespace becomes a path segment) with a unit. A holder is a block, an entity, an item, a chunk, or an account.

| Event | What | Core chokepoint |
|---|---|---|
| `ResourceChangeEvent` | a holder's amount changes; old and new | bridge |
| ├ `ResourceProduceEvent` | created: a generator, a reactor's waste, a quest's currency reward | bridge |
| ├ `ResourceConsumeEvent` | destroyed: a machine spending power, a spell's mana, a vein depleted | bridge |
| └ `ResourceConvertEvent` | one resource into another in one step: fuel to heat to steam | bridge |
| `ResourceTransferEvent` | an amount moves between holders: a cable, a pipe, a wireless charger reaching a player in another dimension, a payment | the port's energy and fluid capability transfers, bridges |

Economy plugins provide the `hadron:currency` holders, so a payment by any mod or plugin is one `ResourceTransferEvent`.

### World: `WorldEvent`

| Event | What | Core chokepoint |
|---|---|---|
| `ExplosionPrimeEvent` | an explosion is about to happen; radius and fire editable | `Level.explode` |
| `ExplosionEvent` | an explosion's affected blocks and entities, editable; fires for each batch of a multi-tick blast | `Explosion.explode`, bridges for overridden explosions |
| `LightningStrikeEvent` | | `LightningBolt` spawn |
| `WeatherChangeEvent` | rain, thunder, clear; actor for a ritual or weather machine | `ServerLevel.setWeatherParameters` |
| `TimeSkipEvent` | an actor changes the time or whether it advances | `ServerLevel.setDayTime`, `PrimaryLevelData.setDayTime` |
| `BiomeChangeEvent` | biome cells change, with or without blocks changing | `PalettedContainer` writes, bridges |
| `TickAccelerateEvent` | an actor speeds up blocks or entities in an area; one event per accelerator per change, never per extra tick | bridge (Torcherino, time bottles) |
| `ChunkLoadEvent`, `ChunkUnloadEvent` *(after)* | | `ChunkMap` |
| `ChunkForceLoadEvent` | an actor keeps chunks loaded: a chunk loader, an anchor, a claim | `ServerChunkCache` tickets, `setChunkForced` |
| `ChunkForceUnloadEvent` *(after)* | that actor releases them, including on expiry | |
| `PortalCreateEvent` | | `PortalShape` |
| `WorldLoadEvent`, `WorldUnloadEvent`, `WorldSaveEvent` *(after)* | | `MinecraftServer` |

### Players: `PlayerEvent` (always a player)

| Event | What |
|---|---|
| `PlayerLoginEvent` | before join; deny with a message |
| `PlayerJoinEvent`, `PlayerQuitEvent`, `PlayerKickEvent` | |
| `PlayerChatEvent` | |
| `PlayerRespawnEvent` | respawn location editable |
| `PlayerChangedWorldEvent` *(after)* | |
| `PlayerAbilitiesChangeEvent` | flight allowed or revoked (Flugel Tiara, a flight ritual), fly and walk speed; revocation is *(after)* |
| `PlayerToggleSneakEvent`, `PlayerToggleSprintEvent`, `PlayerToggleFlightEvent` | |
| `PlayerGameModeChangeEvent` | |
| `PlayerExpChangeEvent`, `PlayerLevelChangeEvent` | vanilla experience only; mod skill points are resources |
| `PlayerAdvancementDoneEvent` *(after)* | |
| `PlayerSwapHandItemsEvent` | |
| `PlayerBedEnterEvent`, `PlayerBedLeaveEvent` | |
| `PlayerModPacketEvent` | a mod's network message from this player; payload id. The catch-all for keybind actions only the server sees |

### Server: `ServerEvent`

| Event | What |
|---|---|
| `ServerLoadEvent` *(after)* | once per start, after plugins enable |
| `CommandExecuteEvent` | any command about to run, from a player, the console, a quest reward or a mod; actor and authority |
| `AccessControlChangeEvent` | a claim, team, party, alliance or trust list changes: boundaries, members, flags; who changed it |
| `DataRevealEvent` | server data about a block, entity or inventory is about to be sent to a player who didn't open it (Jade, WTHIT, map radar); fields removable |
| `PluginEnableEvent`, `PluginDisableEvent` *(after)* | |
| `ServiceRegisterEvent`, `ServiceUnregisterEvent` *(after)* | a provider appeared or left |

## Not shipped yet

Designed but not in `api`, because core can't yet fire them for every actor without breaking rule 7 or making a cancel unsafe. A plugin can't listen for them, so none of them can look like coverage.

- `EntityRemoveEvent`, `EntityCaptureEvent`: cancelling at `Entity.remove` can duplicate (a cancelled arrow pickup); each cause needs its own hook.
- `EntityLeashEvent`, `EntityUnleashEvent`, `EntityShearEvent`, `ArmorStandManipulateEvent`: need hooks inside interface methods or per-path logic.
- `EntityAttributeChangeEvent`, `EntityRelationshipChangeEvent`, `FoodLevelChangeEvent`: the vanilla classes that change don't know their entity.
- `BiomeChangeEvent`: biome writes are too hot and scattered for one hook.
- `ResourceTransferEvent` from the port's energy and fluid capabilities: no single point sees both ends; bridges fire it meanwhile.

## Left out

- **An event per spell school or damage type.** Damage, healing, effects, blocks, spawns and teleports already describe what spells do; the spell is on the operation.
- **An event per fuel tick.** Burning internal fuel is a `ResourceConsumeEvent` when the reserve changes, and acceleration is one event per change, so hot loops don't fire thousands of events.
- **Mod progression state** (Twilight Forest's conquered bosses, quest progress). It changes no world, player or item; plugins that need it use the bridge's extras.
- **Client-only data** (local waypoints, minimap settings).
