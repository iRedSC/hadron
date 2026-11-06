# Platform APIs

What the most popular Bukkit plugins need beyond events: shops, jobs and custom mobs need data stored on things and an economy; vanish, nametags, NPCs and anticheat need control over packets; Multiverse needs worlds made while the server runs. Each is a Hadron API plugins compile against, named after Bukkit's (or Vault's) where the meaning matches, and undone when the plugin that registered something disables.

## Persistent data

Bukkit's `PersistentDataContainer`, for everything a plugin wants to mark: a shop's chest, a custom mob, a custom item, a block a player placed (so jobs don't pay for breaking it again), a claim marker.

- **Holders:** item stacks, entities, block entities, single block positions (blocks without a block entity), chunks and levels. Plugins get a container with `PersistentData.of(...)`, since Hadron can't add methods to Minecraft's classes.
- **Keys** are `NamespacedKey(plugin, "name")`, so two plugins never collide and a plugin's data is found again after a reload.
- **Values** are typed with `PersistentDataType` (strings, numbers, booleans, UUIDs, byte arrays, lists, nested containers). Hadron owns the serialization, so the stored layout never changes under a plugin and a version port never migrates it.
- **Lifetime follows the holder:** data on an item travels with the stack, on an entity it is saved with the entity, and on a block position it is removed when that block is broken or replaced, and moves with it when the block is moved (pistons, contraptions).

## Economy

Vault's `Economy`, with several currencies.

- **Currencies** are registered by plugins (`coins`, `gems`, a town's tokens), each with an id (`hadron:currency/<name>`), display names and decimal places. One is the default; methods without a currency use it.
- **Amounts are exact** (`BigDecimal`), never `double`, so balances never drift by a fraction of a cent.
- **Hadron's core owns the API, the currency registry and the events;** a provider plugin stores the accounts. Every deposit, withdrawal and transfer goes through core, so it always fires the resource events (`ResourceTransferEvent` with account holders) and a provider can't forget to. Hadron ships a simple provider that stores accounts in JSON, used when no other is installed.
- **Every operation returns a result** (done, not enough money, account missing, refused by a listener) instead of a boolean, so a shop can tell the buyer why.

## Packets and visibility

ProtocolLib's interception, and Bukkit's `hidePlayer`, for vanish, nametags, tab lists, NPCs and anticheat.

- **Packet events** for every vanilla packet in and out, carrying Minecraft's own packet classes: cancel one, or replace it with another. Outgoing packets can be changed per player, which is how nametags and fake entities work.
- **Visibility:** a player or entity hidden from a viewer is never sent to them at all, and its tab list entry goes with it, so a vanished player can't be seen by a modded client either.
- **Display names:** a player's name in chat, the tab list and above their head can be set per server, for nicknames.
- Packet handlers run on the network thread; the API says so and offers a way to hop to the server thread, since most Minecraft state can't be touched there.

## Worlds

Bukkit's `WorldCreator` and `createWorld`, for Multiverse.

- **Create** a world from a dimension type the server has (overworld, nether, end, or a datapack's or mod's) and a generator: a copy of an existing dimension's, flat, void, or a noise settings id, with its own seed.
- **Load, unload and delete** while the server runs. Players in a world being unloaded are moved out first.
- **Created worlds come back on restart:** Hadron saves each world's definition and recreates it before plugins enable, so a plugin can rely on its worlds existing in `onEnable`.
- **Vanilla clients can join them**, with no client mod: a created world is an ordinary dimension to the client.
- A world belongs to the server, not to the plugin that made it: disabling the plugin doesn't unload it.
