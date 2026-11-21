# Regions

Boxes of the world an operator protects. Every act a region can decide has a **node** named after what happens (`block.break`, `entity.hurt.animal`, `inventory.move`), and each region grants or denies nodes per **subject**: its members, everyone else, the world on its own, and mods nobody named. The four flags of the first cut (`build`, `use`, `entry`, `pvp`) are kept as named bundles of nodes.

It protects through Hadron's events and actors, so it covers every mod whose bridge is installed, and refuses what an unbridged mod does inside a region rather than letting it through unseen. One listener (`Protection`) asks one table (`Nodes`) what each event is and one resolver (`Resolve`) whether the regions where it happens allow it.

## Nodes

A node is dotted segments: a root (`block`, `entity`, `item`, `inventory`, `resource`, `world`, `player`, `enter`, `other`), the act, and for most acts on an entity the **kind** of entity acted on: `player`, `monster`, `animal` or `misc`. The kind comes only from facts Minecraft states: being a player, or the entity type's `MobCategory` (`MONSTER`; `CREATURE`, `AMBIENT`, `AXOLOTLS` and the water categories are animals; `MISC` is villagers, golems, armor stands, boats, minecarts, frames, paintings and a mod's furniture). A mod's entity type declares its own category, so its mobs and furniture get the right kind without the plugin knowing the mod.

In a rule:

- a node covers everything below it: `entity.hurt` covers `entity.hurt.animal`;
- `*` stands for exactly one segment anywhere but the first: `entity.*.animal` is anything done to an animal;
- a flag name stands for every node in its bundle: `build`.

A pattern that matches no node is refused, with the nearest node and what is under the deepest valid prefix: ``entity.hurt.animals` isn't a node. Did you mean `entity.hurt.animal`? Under entity.hurt: animal, misc, monster, player``.

Every node, its flag, and the events asking it is in [the node table](#node-table) below. One event can ask more than one node: anything that carries players asks `enter.*` for each player aboard, as that player, besides its own node. An event a bridge fires whose class, and every parent up to `ActionEvent`, has no entry asks `other`, which is in `build`, so it fails closed.

## Subjects

| subject | who | when no rule in the region matches |
|---|---|---|
| `members` | owners and members, judged by who the actor acts for, so a member's turret or drone is a member | allowed |
| `everyone` | anyone else, online or not, and an outsider's machine | the region doesn't decide (abstains) |
| `world` | nobody behind it: nature, a machine or fake player nobody owns, an entity nobody is behind | the home rule |
| `unknown` | a mod acted and nothing said who | refused |

Owners are judged as members; owning only adds managing. The server, and a player with `/region bypass` on, are never asked. For `enter.*` the subject is the player being moved: a piston pushing a visitor in asks about the visitor. A move whose riders nobody can name is asked as everyone.

### The home rule

The world on its own sets out from somewhere: what its event states as the source (a flow's `getFromPos()`, a spread's `getSourcePos()`, a fire's igniting block; fire from nowhere known is outside every region), otherwise the block itself; a machine or fake player nobody owns from where it stands; an entity nobody is behind from where it entered the world (`Actors.origin`), wherever it is now; a remembered actor with nobody behind it, which has no place, from outside every region.

Where the world has no rule for a node, **it may act in a region only if every place it sets out from is inside that region**. So a hopper inside a region works, one outside can't feed a chest inside, water can't flow in, and a skeleton outside can't shoot a cow inside: its arrow entered the world where it was shot, whether the skeleton stays, moves away or is gone. A rule overrides it both ways: `world: {allow: [block.flow]}` lets water in, `world: {deny: [entity.spawn.monster]}` stops monsters spawning even inside.

## How a decision is made

Regions decide once other plugins' ordinary listeners are done (at `HIGHEST`), so a teleport another plugin redirects, a target it changes or a block it puts back in a blast is judged as it will happen. A plugin listening at `NORMAL` still hears an act regions then refuse: to act only on what goes ahead, listen at `MONITOR` and check `isCancelled()`.

For each place the event states (a place no one can pin down counts as every region its bound reaches), take the regions there:

1. Highest priority first; every region at that priority answers for the subject it judges the act as.
2. Within a region the most specific matching rule wins. Specificity is the number of segments named: `entity.hurt.animal` (3) beats `entity.*.animal` (2) and `entity.hurt` (2). A flag counts as 1, so anything named beats a flag. At equal specificity, deny wins. A rule beats the subject's own answer.
3. At one priority, any region refusing refuses; otherwise any allowing allows; if every region there abstains, the next priority down decides.
4. If every region abstains, it is allowed.

The act is refused if any place refuses. For entry, a region the player is already in never refuses, so anyone inside can walk out. A plot inside spawn only states what differs: its members are allowed before spawn is asked, and what it says nothing about passes down to spawn.

A hit on a player (`entity.hurt.player`, in `pvp`) is also decided where it set out from, as its event states it (`getSourceLocation()`): the attacker's block for a blow, where an arrow was shot from however far the shooter has gone since, a turret's block. So a no-PvP zone keeps hits out and keeps its own from leaving. A hit from somewhere nothing can say counts as every region its bound reaches, like any unknown place; harm nothing sent (a fall, a burning tick) adds nothing. Every other node is decided where it lands only: a visitor standing in spawn can still hit a cow or a zombie outside it.

A player's own inventory, and anything carried in it, stands outside every region, since it goes where they go. What they pick up off the ground is still asked where it lies.

## Flags

Each node names its flag in code, so a node added later lands in its flag and is protected from the day it exists:

- **build**: every `block.*` but `block.use` and `block.redstone`; `entity.place.*`, `entity.spawn.*`, `entity.break`; what is done to animals and objects; what is done to players beyond pvp (removing, capturing, leashing, changing); `world.*`; `other`.
- **use**: `block.use`, `block.redstone`, `entity.use.animal`, `entity.use.misc`, the `item.*` acts on what is kept here, `inventory.*`, `resource.*`, `player.sleep`.
- **entry**: `enter.*`.
- **pvp**: `entity.hurt.player`, `entity.ignite.player`, `entity.effect.player`. Judged at both ends wherever the event says where the act set out from, which today is a hit.
- **no flag**: everything done to monsters, `entity.use.player`, `entity.equip.player`, `entity.launch`, `entity.push.*`, `entity.target.*`, `item.pickup`, `item.drop`, `player.fish`. Allowed unless a region denies it by name.

`/region define` writes a fresh region's rules into the file, so what a region does is all there and a later version can't change it quietly:

```yaml
rules:
  everyone: {deny: [build, use]}
  world: {allow: [entity.*.player]}
```

The world rule lets a monster that wandered in hurt players inside; delete it for a mob-safe zone.

## regions.yml

```yaml
format: 1
regions:
  spawn:
    world: minecraft:overworld
    min: [-50, -64, -50]
    max: [50, 320, 50]
    priority: 0
    owners: []
    members: []
    rules:
      everyone:
        deny: [build, use, pvp]
        allow: [block.use, inventory.open]   # doors and buttons work; chests can be looked in, not taken from
      members:
        deny: [pvp]
      world:
        deny: [entity.spawn.monster]         # no monsters spawn here
        allow: [entity.*.player]
```

Any mistake stops the plugin with the file left as it is, naming the region, subject and list and saying the fix: a region without `rules` (write `rules: {}` for one that only marks an area), `flags` from the first cut (each says how to write it as rules), an unknown subject, `owners` as a subject (write `members`), `players` (per-player rules aren't in this version), a key under a subject other than `allow` or `deny`, a list that isn't a list of text, a node that isn't one, a leading `*`, an empty segment, capitals, an entry written twice, one both allowed and denied, and a key with nothing after it (write `[]` or `{}` for none, or remove the key). Saving writes subjects in order, deny before allow, flags first, so a hand edit round-trips unchanged.

## Commands

| command | what it does |
|---|---|
| `/region wand`, `define <name>`, `redefine <name>` | pick two corners, make a fresh region from them, move one |
| `/region allow\|deny\|unset <region> [subject] <node>...` | set or clear nodes or flags for a subject (everyone when none is named); replies with that subject's rules and an `[undo]` |
| `/region flag <region> <build\|use\|entry\|pvp> [allow\|deny]` | a flag for everyone (and members too for `pvp`); left off, it is cleared |
| `/region undo` | puts back the rules your last change replaced, unless they changed since |
| `/region why <node> [subject]` | traces each region at your feet from the top: `plot-1 (10): everyone, no rule, passes`, `spawn (0): everyone deny build ⊃ entity.hurt.animal → refused`, and outlines the one deciding |
| `/region watch` | for managers: every refusal within 32 blocks in regions you manage, marked by subject (red a person, blue the world, purple unknown) with `base · world · inventory.move · from outside` above the hotbar |
| `/region info`, `list`, `show`, `priority`, `addowner`, `addmember`, `removeplayer`, `remove`, `restore`, `bypass` | as before |

Tab completion walks the node tree one segment at a time. `world` names the subject right after the region, so to deny the `world` root to everyone write `/region deny spawn everyone world`.

A refused player sees smoke where it happened and the region's name above the hotbar. Someone who can change that region sees the decision instead (`spawn · everyone · entity.hurt.animal (build)`) and, once a minute per region and node, a chat line whose `[allow]` fills in the `/region allow` that would let it through.

## Known gaps

- **Setting a player alight or poisoning them is judged where it lands only.** `EntityCombustEvent` and `EntityPotionEffectEvent` don't say where the act set out from, so a splash potion thrown out of a safe zone takes hold on a player outside it. Their burning and poison ticks set out from nowhere, so they are judged where they land too.
- A rider swung across an edge by a boat turning in place, in a region starting above the boat's block (a known gap since the first cut).

Left out of this version: per-player rules (the `players` key is reserved), qualifiers beyond kind (an entity type id, a resource id), custom bundles, and testing as a visitor (`/region why <node> everyone` answers it on paper).

## Node table

<!-- node table: generated by NodeMappingTest; ./gradlew :plugins:regions:nodeTable -->
| node | flag | meaning | asked by |
|---|---|---|---|
| `block.break` | build | remove a block or fluid, or start to | BlockBreakEvent, BlockCrackEvent, BlockDamageEvent, BucketFillEvent |
| `block.place` | build | place a block or fluid | BlockMultiPlaceEvent, BlockPlaceEvent, BucketEmptyEvent |
| `block.change` | build | turn a block into another, or change its data | BlockChangeEvent, BlockDataChangeEvent, BlockTransformEvent, BucketEvent |
| `block.sign` | build | write on a sign | SignChangeEvent |
| `block.move` | build | move blocks: pistons, contraptions, ships | BlockMoveEvent, BlockPistonEvent, BlockPistonExtendEvent, BlockPistonRetractEvent, StructureMoveEvent |
| `block.explode` | build | a blast destroys this block | ExplosionEvent |
| `block.burn` | build | fire destroys a block | BlockBurnEvent |
| `block.ignite` | build | fire starts | BlockIgniteEvent |
| `block.flow` | build | fluid flows in | BlockFromToEvent |
| `block.spread` | build | grass, fire or a mod's block spreads | BlockSpreadEvent |
| `block.form` | build | snow, ice or a mod's block forms | BlockFormEvent |
| `block.fade` | build | ice melts, coral dies, a block fades | BlockFadeEvent |
| `block.grow` | build | a crop or tree grows | BlockGrowEvent, StructureGrowEvent |
| `block.decay` | build | leaves decay | LeavesDecayEvent |
| `block.fertilize` | build | bonemeal, growth accelerators | BlockFertilizeEvent |
| `block.use` | use | right-click or activate a block | BlockUseEvent |
| `block.redstone` | use | change a block's signal remotely | BlockRedstoneEvent |
| `entity.place.<kind>` | player: build, monster: build, animal: build, misc: build | put an entity here with an item: a boat, armor stand, frame, spawn egg, a mod's chair | EntitySpawnEvent, HangingPlaceEvent |
| `entity.spawn.<kind>` | player: build, monster: build, animal: build, misc: build | bring one in any other way: a summon, TNT lit, a mob spawning | EntitySpawnEvent, HangingPlaceEvent |
| `entity.launch` | open | fire or throw something; the hit is judged by what it does | EntitySpawnEvent, HangingPlaceEvent, ProjectileLaunchEvent |
| `entity.hurt.<kind>` | player: pvp, monster: open, animal: build, misc: build | hurt or hit it | EntityDamageEvent, VehicleDamageEvent |
| `entity.ignite.<kind>` | player: pvp, monster: open, animal: build, misc: build | set it alight | EntityCombustEvent |
| `entity.effect.<kind>` | player: pvp, monster: open, animal: build, misc: build | put a harmful effect on it | EntityPotionEffectEvent |
| `entity.break` | build | break a painting or frame | HangingBreakEvent |
| `entity.remove.<kind>` | player: build, monster: open, animal: build, misc: build | remove it outright | EntityRemoveEvent |
| `entity.capture.<kind>` | player: build, monster: open, animal: build, misc: build | capture it into an item, block or carrier | EntityCaptureEvent |
| `entity.change.<kind>` | player: build, monster: open, animal: build, misc: build | change its contents, status or a mod's data | EntityDataChangeEvent, EntityStatusChangeEvent, ItemFrameChangeEvent |
| `entity.equip.<kind>` | player: open, monster: open, animal: build, misc: build | dress or undress it | ArmorStandManipulateEvent, EquipEvent |
| `entity.use.<kind>` | player: open, monster: open, animal: use, misc: use | right-click it: trade, open a minecart chest, ride | EntityUseEvent |
| `entity.leash.<kind>` | player: build, monster: open, animal: build, misc: build | leash or unleash it | EntityLeashEvent, EntityUnleashEvent |
| `entity.shear.<kind>` | player: build, monster: open, animal: build, misc: build | shear it | EntityShearEvent |
| `entity.tame.<kind>` | player: build, monster: open, animal: build, misc: build | tame it | EntityTameEvent |
| `entity.breed.<kind>` | player: build, monster: open, animal: build, misc: build | breed it | EntityBreedEvent |
| `entity.transform.<kind>` | player: build, monster: open, animal: build, misc: build | turn it into another entity | EntityTransformEvent |
| `entity.control.<kind>` | player: build, monster: open, animal: build, misc: build | take or change control of it | EntityControlChangeEvent |
| `entity.push.<kind>` | player: open, monster: open, animal: open, misc: open | push, pull or knock it back | EntityKnockbackEvent, EntityVelocityEvent |
| `entity.target.<kind>` | player: open, monster: open, animal: open, misc: open | a mob or turret picks it as a target | EntityTargetEvent |
| `item.use` | use | use an item held here | ItemUseEvent |
| `item.consume` | use | eat or drink something held here | ItemConsumeEvent |
| `item.craft` | use | craft in a crafter here | ItemCraftEvent |
| `item.smelt` | use | smelt in a furnace or machine here | ItemSmeltEvent |
| `item.trade` | use | trade with a merchant here | TradeEvent |
| `item.change` | use | change, wear, repair or enchant an item kept here | ItemChangeEvent, ItemDamageEvent, ItemEnchantEvent, ItemRepairEvent |
| `item.pickup` | open | pick up an item or experience lying here | ExperiencePickupEvent, ItemPickupEvent |
| `item.drop` | open | drop an item here | ItemDropEvent |
| `inventory.open` | use | open an inventory here | InventoryOpenEvent |
| `inventory.click` | use | take from or put into an inventory here | InventoryClickEvent |
| `inventory.change` | use | change, fill, empty or sort an inventory here | InventoryChangeEvent, InventoryDestroyEvent, InventoryGenerateEvent, InventorySortEvent |
| `inventory.move` | use | move items between inventories: hoppers, pipes | InventoryMoveItemEvent |
| `resource.change` | use | make, spend or convert energy, fluid or a mod's resource here | ResourceChangeEvent, ResourceConsumeEvent, ResourceConvertEvent, ResourceProduceEvent |
| `resource.move` | use | move a resource between holders: cables, pipes | ResourceTransferEvent |
| `world.lightning` | build | lightning strikes | LightningStrikeEvent |
| `world.portal` | build | a portal is made | PortalCreateEvent |
| `world.accelerate` | build | a block's ticks are sped up | TickAccelerateEvent |
| `world.chunkload` | build | a chunk is kept loaded | ChunkForceLoadEvent |
| `player.sleep` | use | sleep in a bed here | PlayerBedEnterEvent |
| `player.fish` | open | fish here, or reel something in from here | PlayerFishEvent |
| `enter.move` | entry | a player walks, rides or is carried in | BlockMoveEvent, BlockPistonEvent, BlockPistonExtendEvent, BlockPistonRetractEvent, EntityMoveEvent, PlayerMoveEvent, StructureMoveEvent |
| `enter.teleport` | entry | a player is teleported in | EntityTeleportEvent |
| `enter.mount` | entry | a player is seated inside | EntityMountEvent |
| `enter.dismount` | entry | a player is set down inside | EntityDismountEvent |
| `other` | build | a mod's act with no node of its own; refused where a region keeps building | ActionEvent, BlockEvent, EntityEvent, InventoryEvent, ItemEvent, ResourceEvent, WorldEvent |

Not a region's:

| event | why |
|---|---|
| AccessControlChangeEvent | the server's own lists, not a place's |
| BlockDropItemEvent | drops follow the break that made them, which was already asked |
| CommandExecuteEvent | what the command does places itself, as its own events |
| DataRevealEvent | it shows, it changes nothing |
| EntityAirChangeEvent | breathing is the entity's own |
| EntityRegainHealthEvent | healing harms nobody |
| ExplosionPrimeEvent | judged by the blocks and entities the blast then touches |
| ItemDespawnEvent | an item running out of time is nobody's act |
| ItemHeldEvent | switching hotbar slots is the player's own |
| ItemMergeEvent | two stacks on the ground becoming one changes nothing anyone owns |
| PlayerAbilitiesChangeEvent | the player's own, wherever they are |
| PlayerAbilitiesEvent | the player's own, wherever they are |
| PlayerChatEvent | the player's own, wherever they are |
| PlayerEvent | the player's own, wherever they are |
| PlayerExpChangeEvent | the player's own, wherever they are |
| PlayerGameModeChangeEvent | the player's own, wherever they are |
| PlayerKickEvent | the player's own, wherever they are |
| PlayerModPacketEvent | the player's own, wherever they are |
| PlayerSwapHandItemsEvent | the player's own, wherever they are |
| PlayerToggleFlightEvent | the player's own, wherever they are |
| PlayerToggleSneakEvent | the player's own, wherever they are |
| PlayerToggleSprintEvent | the player's own, wherever they are |
| PrepareAnvilEvent | only shows an offer; taking it is an ItemRepairEvent or ItemEnchantEvent |
| ProjectileHitEvent | judged by the damage or block change it causes |
| ServerEvent | the server's own, not a place's |
| TimeSkipEvent | a whole world's |
| WeatherChangeEvent | a whole world's |
<!-- end of node table -->
