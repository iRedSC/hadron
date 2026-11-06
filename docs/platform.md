# Platform APIs

What the most popular Bukkit plugins need beyond events: shops, jobs and custom mobs need data stored on things and an economy; vanish, nametags, NPCs and anticheat need control over packets; Multiverse needs worlds made while the server runs. Each is a Hadron API plugins compile against, named after Bukkit's (or Vault's) where the meaning matches, and undone when the plugin that registered something disables.

## Persistent data

Bukkit's `PersistentDataContainer`, for everything a plugin wants to mark: a shop's chest, a custom mob, a custom item, a block a player placed (so jobs don't pay for breaking it again), a claim marker.

- **Holders:** item stacks, entities, block entities, single block positions (blocks without a block entity), chunks and levels. Plugins get a container with `PersistentData.of(...)`, since Hadron can't add methods to Minecraft's classes.
- **Keys** are `NamespacedKey(plugin, "name")`, so two plugins never collide and a plugin's data is found again after a reload.
- **Values** are typed with `PersistentDataType` (strings, numbers, booleans, UUIDs, byte arrays, lists, nested containers). Hadron owns the serialization, so the stored layout never changes under a plugin and a version port never migrates it.
- **Lifetime follows the holder:** data on an item travels with the stack (written straight into it, so there's nothing to save back), on an entity it is saved with the entity and kept across a player's death, and on a block position it is removed when that block is replaced by a different block (a door opening keeps it), and moves with it when a piston pushes it. Bridges that move blocks call `PersistentData.move` before the block leaves its old position.
- **Nested containers are live:** `get` with `TAG_CONTAINER` returns a container whose edits write through, unlike Bukkit's copy.

## Economy

Vault's `Economy`, with several currencies.

- **Currencies** are registered by plugins (`coins`, `gems`, a town's tokens), each with an id (`hadron:currency/<name>`), display names and decimal places, and unregistered when the plugin disables while balances stay stored. Hadron's own `money` always exists. Any plugin can use any currency: which plugin uses which is the server owner's choice, in each plugin's config.
- **The default** is chosen by the server owner in `hadron/economy.yml` (`default-currency: money`), never by a plugin, so two plugins can't fight over it. Methods without a currency use it.
- **Accounts** are keyed by UUID and hold every currency. A player's is created when they first join; non-player accounts (a town, a shop) with `createAccount`. Plugins get the economy with `Economy.get()`, `Hadron.getEconomy()`, or Vault's habit `getServicesManager().load(Economy.class)`.
- **Amounts are exact** (`BigDecimal`), never `double`, so balances never drift by a fraction of a cent.
- **Hadron's core owns the API, the currency registry and the events;** a provider plugin stores the accounts. Every deposit, withdrawal and transfer goes through core, so it always fires the resource events (`ResourceTransferEvent` with account holders) and a provider can't forget to. Hadron ships a simple provider that stores accounts in JSON, used when no other is installed.
- **Every operation returns a result** (done, not enough money, account missing, refused by a listener) instead of a boolean, so a shop can tell the buyer why.

## Packets and visibility

ProtocolLib's interception, and Bukkit's `hidePlayer`, for vanish, nametags, tab lists, NPCs and anticheat.

- **Packet events** for every vanilla packet in and out, carrying Minecraft's own packet classes: cancel one, or replace it with another. Outgoing packets can be changed per player, which is how nametags and fake entities work.
- **Visibility:** a player or entity hidden from a viewer is never sent to them at all: not spawned, out of the tab list and command suggestions, its sounds and interactions dropped. `hideFromAll` hides someone from everyone, including players who join later, and keeps them out of `/list` and the server list's count and sample. Chat, join, quit and death lines aren't hidden automatically; a vanish plugin blanks them through their events.
- **Display names:** a player's name in chat, death messages and the tab list can be set per server, for nicknames. The name above the head (`setNameTag`) rewrites the player's profile name for other viewers, so their clients also suggest the nickname in commands and below-name scores stop showing; for plain decoration, use vanilla scoreboard team prefixes instead.
- Packet handlers run on the network thread; the API says so and offers a way to hop to the server thread, since most Minecraft state can't be touched there.

## Worlds

Bukkit's `WorldCreator` and `createWorld`, for Multiverse.

- **Create** a world from a dimension type the server has (overworld, nether, end, or a datapack's or mod's) and a generator: a copy of an existing dimension's, flat, void, or a noise settings id, with its own seed.
- **Load, unload and delete** while the server runs (`Worlds.load` brings back a created world that is unloaded; `Worlds.created()` lists them all). Players in a world being unloaded are moved out first. Unloading without saving discards the changes. Time, weather and game rules follow the overworld; spawn is per world.
- **Created worlds come back on restart:** Hadron saves each world's definition and recreates it before plugins enable, so a plugin can rely on its worlds existing in `onEnable`.
- **Vanilla clients can join them**, with no client mod: a created world is an ordinary dimension to the client, and commands suggest it even to players who joined before it was created.
- A world belongs to the server, not to the plugin that made it: disabling the plugin doesn't unload it.
