# What bridges must hook

The checklist a bridge author works through for their mod, and what the acceptance test ([bridges.md](bridges.md)) checks. Core fires every event in [events.md](events.md) where vanilla has a chokepoint; this file lists only what core can't see, so it's the whole of a bridge's job.

The tables below are generated from the `@FiredBy` on each event class, which the build checks against what core actually fires. To change a row, edit that annotation and run `./gradlew bridgeHooks`.

## Always: attribution

Every bridge does these, whatever its mod does. Without them, core still fires its events, but they say `Unknown` or name the wrong player.

| What the mod has | What the bridge does |
|---|---|
| Machines with an owner | `Actors.remember(blockEntity, Attribution.of(owner))` when placed or claimed. Core then ticks it as an owned `Machine`. |
| Fake players | Push `Actor.FakePlayer(fake, Attribution.of(owner))` around the fake player's work. |
| Projectiles, summons, turrets, drones | `Actors.remember(entity, …)` when created, with the shooter or summoner as responsible. |
| Vehicles, remote control, charm | Set `controller` on the attribution while someone drives, operates or controls it; fire `EntityControlChangeEvent` when that changes. |
| Work that runs later | Remember or carry the actor into queued work (a gadget's multi-tick build, a ritual's later ticks), and push it when the work runs. |
| Blocks moved by contraptions or frames | `PersistentData.move(level, from, to)` for each block, before it leaves `from`, so plugins' data on it travels. For a block out of the world for longer than a tick, `copyTo` its data into `PersistentData.newContainer()` and back. Pistons need nothing. |
| Multi-part actions | `Operation.begin(actor)` before the action's first change, so its events group and its opening event can be cancelled while nothing has happened. |

## The rule for firing

- **If the mod's change reaches a vanilla chokepoint (`Level.setBlock`, `hurt`, `addFreshEntity`, a hopper transfer), don't fire anything: push the actor and core fires the event.** Firing it too reports one effect twice.
- **If the mod skips vanilla, fire the core event yourself**, before the change, and honour a cancel by not making it.
- **Fire for committed changes only**, never for a simulated transfer or a capacity probe.
- **Cancelled means fully undone**: nothing spent, nothing half-moved. If the mod spends a cost before acting, fire before the cost.
- **Fire before the first change on either side.** Mods often fill the destination before draining the source, place the moved block before removing the old one, or clear a slot before spawning its item. A cancel at the last step then duplicates or loses things, so hook the enclosing action, not the step that looks like the event.
- **Check what the mod does after a vanilla call.** Many ignore the result of `addFreshEntity` or `hurt` and spend the cost, apply the effect or clear the stored copy anyway. Don't fire the event a second time around it: make the mod honour the vanilla call's result, skipping the cost when `addFreshEntity` returns false or `hurt` was refused.
- **A read can be a write.** Some mods break a block while listing its drops, or edit a sign while checking a position. Fire for those as the changes they are; never fire for a read that changes nothing.
- **Report stored amounts, not derived ones.** Air, not pressure; heat, not temperature. A derived value changes when a tank's volume does, so summing it looks conserved while being wrong.
- **Fire for the step a plugin could refuse, not for each tick.** A machine starting a job fires the job's own event once (an `ItemSmeltEvent` or `ItemCraftEvent` with the recipe's cost, a `BlockDataChangeEvent` for a reactor starting up or a field switching on); the per-tick spending of an accepted job is not reported as resource events, which would claim amounts the holder doesn't yet hold. Drift with no job behind it (heat settling, air leaking) is the holder's own state, not an event. Transfers between holders fire as the mod commits them. A plugin still stops a running machine through its effects outside its holder, each of which fires its own event; an observe-only summary of running costs, for metering, can be added when a plugin needs one.
- **No exact leaf? Fire the nearest parent** with its full payload, and propose the leaf for core.

## Events only bridges fire

Core never fires these; with no bridge, plugins hear nothing for them.

<!-- generated: bridge-only, from @FiredBy on each event class. Edit those and run ./gradlew bridgeHooks; edits here are overwritten. -->
| Event | Fire when the mod… | Must fill in |
|---|---|---|
| `BlockDamageEvent` | puts lasting partial damage on a block short of breaking it | damage before and after |
| `BlockDataChangeEvent` | changes a block entity's data with the block state unchanged (a spawner's mob, a drawer lock, a terminal binding, a name), or data it keeps for block spaces in its own storage (a wire, an oxygen or gravity field, a waystone's name) | the kind of data, every space it covers, and the data before and after |
| `BlockRedstoneEvent` | changes the signal a block emits without changing its state | old and new signal, face |
| `StructureMoveEvent` | assembles, moves, rotates or disassembles a contraption, ship or spatial transfer | source to target mapping, swept space, old and new transforms |
| `EntityControlChangeEvent`, `EntityControlExpireEvent` | changes who controls an entity without mounting (charm, thrall, drone link, guard assignment); expiry is after-only | old and new controller |
| `EntityDataChangeEvent` | changes lasting data it keeps on an entity that no other entity event describes (a colonist's job, a familiar's upgrades, a player's spell anchor) | the kind of data, and the data before and after |
| `InventoryDestroyEvent`, `InventoryGenerateEvent`, `InventorySortEvent` | makes items appear with no source, vanish with no destination, or rearranges contents in place | slots before and after |
| `ResourceConvertEvent` | turns one resource into another in one step (fuel to heat, heat to steam, mana to energy) | resource id and unit, holder, old and new amount of the input, and the output |
| `TickAccelerateEvent` | speeds up blocks or entities in an area | accelerator, area, rate; once per change, never per extra tick |
| `AccessControlChangeEvent` | changes a claim, team, party, alliance or trust list | what changed, who changed it |
| `DataRevealEvent` | is about to send block, entity, inventory or account data to a player who didn't open it (Jade, WTHIT, map radar, a sigil reading another player's network) | the data, with removable fields |
<!-- end generated: bridge-only -->

## Events core covers in part

Core fires these for vanilla's paths; the bridge covers its mod's.

<!-- generated: core-partial, from @FiredBy on each event class. Edit those and run ./gradlew bridgeHooks; edits here are overwritten. -->
| Event | Core sees | The bridge fires it for |
|---|---|---|
| `BlockChangeEvent` | every block state change, through `Level.setBlock` | block entity data changes, as `BlockDataChangeEvent`, and growth without bone meal, as `BlockFertilizeEvent` |
| `BlockDropItemEvent` | every drop through `Block.dropResources` (player mining, `destroyBlock`, blocks losing support) | drops the mod spawns itself when it breaks or harvests a block, before spawning them; honour the edited list and experience |
| `BlockFertilizeEvent` | bone meal | growth accelerators that grow without bone meal |
| `BlockMoveEvent` | pistons, through its piston leaves | contraptions, ships and spatial transfers, as `StructureMoveEvent` |
| `EntityStatusChangeEvent` | a mob's AI turned off or on | the mod's stuns, freezes, holds and interrupted casts, with its own status ids |
| `EntityTargetEvent` | mobs | turret blocks |
| `EntityVelocityEvent` | any velocity set while something other than the entity acts (commands, explosions, entities, machines, mods with an actor pushed); knockback as `EntityKnockbackEvent` | pushes the mod makes with nothing on the stack: push the actor instead, and core fires it |
| `ExperiencePickupEvent` | players and fake players | machines storing experience |
| `ProjectileHitEvent` | projectile entities | hitscan weapons with no projectile, with the shooter as the entity |
| `EquipEvent` | right-click equip, armour-slot clicks, dispensers, mobs picking up gear | the mod's equip paths and extra slots (Curios, Accessories), with the slot name |
| `EquipmentChangeEvent` | every vanilla slot, after the fact | the mod's extra slots |
| `ItemChangeEvent` | anvil and grindstone results | the enclosing action that changes a stack in place (a gun spending ammo, a wand spending a reagent), once per action |
| `ItemCraftEvent` | every vanilla workstation and the crafter | the mod's crafting, with every input, output and cost |
| `ItemDropEvent` | players, dispensers, droppers, mobs throwing (allays, villagers, piglins) | items the mod ejects from its own storage, before taking them out |
| `ItemEnchantEvent` | enchanting table, anvil, grindstone | the mod's enchanting |
| `ItemPickupEvent` | players, hoppers, hopper minecarts, mobs | magnets and vacuums, with the final destination (a backpack) |
| `ItemRepairEvent` | anvil, grindstone, Mending | the mod's repairs |
| `ItemSmeltEvent` | furnaces, campfires, brewing stands | the mod's machines, including items processed in the world or on belts |
| `PrepareItemCraftEvent` | the 2x2 grid, crafting tables, the crafter, and mods' menus built on vanilla's crafting grid | the mod's own crafting grids, when they work out a result; honour the result a listener sets |
| `TradeEvent` | every merchant menu | the mod's own trading |
| `InventoryChangeEvent` | hoppers, hopper minecarts and droppers, as `InventoryMoveItemEvent` | the mod's own slot writes: machine outputs, pipes, storage networks |
| `InventoryMoveItemEvent` | hoppers, hopper minecarts and droppers, into and out of vanilla containers and mods' item handlers | the mod's pipes and networks |
| `ResourceChangeEvent` | currencies, through its produce and consume leaves | an amount of energy, fluid in a tank, chemical, mana, heat, radiation, a vein's reserve or skill points that changes with no fitting leaf (set by a command), with resource id and unit, holder, old and new amount |
| `ResourceConsumeEvent` | currency withdrawals | energy, fluid, chemical, mana, heat, radiation, a vein's reserve or skill points the mod uses up, with resource id and unit, holder, old and new amount |
| `ResourceProduceEvent` | currency deposits | energy, fluid, chemical, mana, heat, radiation or skill points the mod makes from nothing, with resource id and unit, holder, old and new amount |
| `ResourceTransferEvent` | currency transfers between accounts | the mod's cables, pipes, wireless chargers and payments, with both holders and the amount actually accepted |
| `ExplosionEvent` | `Level.explode` and `Explosion` | explosions the mod computes without vanilla's `Explosion` |
| `PlayerFishEvent` | any rod whose bobber is a `FishingHook` | fishing that doesn't use a `FishingHook` (a mod's own bobber entity), at each step it has |
<!-- end generated: core-partial -->

## Not shipped yet

Events designed but not in `api` are listed in [events.md](events.md#not-shipped-yet). A bridge can't fire them until they ship.
