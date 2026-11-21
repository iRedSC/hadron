package dev.iredsc.hadron.regions;

import static dev.iredsc.hadron.regions.Bundle.BUILD;
import static dev.iredsc.hadron.regions.Bundle.ENTRY;
import static dev.iredsc.hadron.regions.Bundle.OPEN;
import static dev.iredsc.hadron.regions.Bundle.PVP;
import static dev.iredsc.hadron.regions.Bundle.USE;

/**
 * Every act a region can decide, named after what happens: the closed list of nodes. Each says which flag holds it,
 * as a constructor argument, so a node can't exist without one; an act done to an entity also takes the
 * {@link Kind} of entity as its last segment, with a flag for each kind. Code names nodes only through this enum, so a
 * typo doesn't compile; text from regions.yml or a command reaches it only through {@link Pattern#parse}.
 */
enum Action {
    BLOCK_BREAK("block.break", BUILD, "remove a block or fluid, or start to"),
    BLOCK_PLACE("block.place", BUILD, "place a block or fluid"),
    BLOCK_CHANGE("block.change", BUILD, "turn a block into another, or change its data"),
    BLOCK_SIGN("block.sign", BUILD, "write on a sign"),
    BLOCK_MOVE("block.move", BUILD, "move blocks: pistons, contraptions, ships"),
    BLOCK_EXPLODE("block.explode", BUILD, "a blast destroys this block"),
    BLOCK_BURN("block.burn", BUILD, "fire destroys a block"),
    BLOCK_IGNITE("block.ignite", BUILD, "fire starts"),
    BLOCK_FLOW("block.flow", BUILD, "fluid flows in"),
    BLOCK_SPREAD("block.spread", BUILD, "grass, fire or a mod's block spreads"),
    BLOCK_FORM("block.form", BUILD, "snow, ice or a mod's block forms"),
    BLOCK_FADE("block.fade", BUILD, "ice melts, coral dies, a block fades"),
    BLOCK_GROW("block.grow", BUILD, "a crop or tree grows"),
    BLOCK_DECAY("block.decay", BUILD, "leaves decay"),
    BLOCK_FERTILIZE("block.fertilize", BUILD, "bonemeal, growth accelerators"),
    BLOCK_USE("block.use", USE, "right-click or activate a block"),
    BLOCK_REDSTONE("block.redstone", USE, "change a block's signal remotely"),

    ENTITY_PLACE("entity.place", BUILD, BUILD, BUILD, BUILD, "put an entity here with an item: a boat, armor stand, "
            + "frame, spawn egg, a mod's chair"),
    ENTITY_SPAWN("entity.spawn", BUILD, BUILD, BUILD, BUILD, "bring one in any other way: a summon, TNT lit, a mob "
            + "spawning"),
    ENTITY_LAUNCH("entity.launch", OPEN, "fire or throw something; the hit is judged by what it does"),
    ENTITY_HURT("entity.hurt", PVP, OPEN, BUILD, BUILD, "hurt or hit it"),
    ENTITY_IGNITE("entity.ignite", PVP, OPEN, BUILD, BUILD, "set it alight"),
    ENTITY_EFFECT("entity.effect", PVP, OPEN, BUILD, BUILD, "put a harmful effect on it"),
    ENTITY_BREAK("entity.break", BUILD, "break a painting or frame"),
    ENTITY_REMOVE("entity.remove", BUILD, OPEN, BUILD, BUILD, "remove it outright"),
    ENTITY_CAPTURE("entity.capture", BUILD, OPEN, BUILD, BUILD, "capture it into an item, block or carrier"),
    ENTITY_CHANGE("entity.change", BUILD, OPEN, BUILD, BUILD, "change its contents, status or a mod's data"),
    ENTITY_EQUIP("entity.equip", OPEN, OPEN, BUILD, BUILD, "dress or undress it"),
    ENTITY_USE("entity.use", OPEN, OPEN, USE, USE, "right-click it: trade, open a minecart chest, ride"),
    ENTITY_LEASH("entity.leash", BUILD, OPEN, BUILD, BUILD, "leash or unleash it"),
    ENTITY_SHEAR("entity.shear", BUILD, OPEN, BUILD, BUILD, "shear it"),
    ENTITY_TAME("entity.tame", BUILD, OPEN, BUILD, BUILD, "tame it"),
    ENTITY_BREED("entity.breed", BUILD, OPEN, BUILD, BUILD, "breed it"),
    ENTITY_TRANSFORM("entity.transform", BUILD, OPEN, BUILD, BUILD, "turn it into another entity"),
    ENTITY_CONTROL("entity.control", BUILD, OPEN, BUILD, BUILD, "take or change control of it"),
    ENTITY_PUSH("entity.push", OPEN, OPEN, OPEN, OPEN, "push, pull or knock it back"),
    ENTITY_TARGET("entity.target", OPEN, OPEN, OPEN, OPEN, "a mob or turret picks it as a target"),

    ITEM_USE("item.use", USE, "use an item held here"),
    ITEM_CONSUME("item.consume", USE, "eat or drink something held here"),
    ITEM_CRAFT("item.craft", USE, "craft in a crafter here"),
    ITEM_SMELT("item.smelt", USE, "smelt in a furnace or machine here"),
    ITEM_TRADE("item.trade", USE, "trade with a merchant here"),
    ITEM_CHANGE("item.change", USE, "change, wear, repair or enchant an item kept here"),
    ITEM_PICKUP("item.pickup", OPEN, "pick up an item or experience lying here"),
    ITEM_DROP("item.drop", OPEN, "drop an item here"),

    INVENTORY_OPEN("inventory.open", USE, "open an inventory here"),
    INVENTORY_CLICK("inventory.click", USE, "take from or put into an inventory here"),
    INVENTORY_CHANGE("inventory.change", USE, "change, fill, empty or sort an inventory here"),
    INVENTORY_MOVE("inventory.move", USE, "move items between inventories: hoppers, pipes"),

    RESOURCE_CHANGE("resource.change", USE, "make, spend or convert energy, fluid or a mod's resource here"),
    RESOURCE_MOVE("resource.move", USE, "move a resource between holders: cables, pipes"),

    WORLD_LIGHTNING("world.lightning", BUILD, "lightning strikes"),
    WORLD_PORTAL("world.portal", BUILD, "a portal is made"),
    WORLD_ACCELERATE("world.accelerate", BUILD, "a block's ticks are sped up"),
    WORLD_CHUNKLOAD("world.chunkload", BUILD, "a chunk is kept loaded"),

    PLAYER_SLEEP("player.sleep", USE, "sleep in a bed here"),
    PLAYER_FISH("player.fish", OPEN, "fish here, or reel something in from here"),

    ENTER_MOVE("enter.move", ENTRY, "a player walks, rides or is carried in"),
    ENTER_TELEPORT("enter.teleport", ENTRY, "a player is teleported in"),
    ENTER_MOUNT("enter.mount", ENTRY, "a player is seated inside"),
    ENTER_DISMOUNT("enter.dismount", ENTRY, "a player is set down inside"),

    OTHER("other", BUILD, "a mod's act with no node of its own; refused where a region keeps building");

    private final String key;
    private final boolean kinded;
    private final Bundle player;
    private final Bundle monster;
    private final Bundle animal;
    private final Bundle misc;
    private final String meaning;

    /** An act with no kind: one flag holds it. */
    Action(String key, Bundle bundle, String meaning) {
        this(key, false, bundle, bundle, bundle, bundle, meaning);
    }

    /** An act on an entity, ending in its {@link Kind}, with the flag holding it for each kind. */
    Action(String key, Bundle player, Bundle monster, Bundle animal, Bundle misc, String meaning) {
        this(key, true, player, monster, animal, misc, meaning);
    }

    private Action(String key, boolean kinded, Bundle player, Bundle monster, Bundle animal, Bundle misc,
            String meaning) {
        this.key = key;
        this.kinded = kinded;
        this.player = player;
        this.monster = monster;
        this.animal = animal;
        this.misc = misc;
        this.meaning = meaning;
    }

    /** The node without its kind: {@code entity.hurt}. */
    String key() {
        return key;
    }

    /** Whether its nodes end in a {@link Kind}. */
    boolean kinded() {
        return kinded;
    }

    /** What it means, in one line, for the node table and {@code /region why}. */
    String meaning() {
        return meaning;
    }

    /** The flag holding this act done to {@code kind}; for an act with no kind, any kind gives its one flag. */
    Bundle bundle(Kind kind) {
        return switch (kind) {
            case PLAYER -> player;
            case MONSTER -> monster;
            case ANIMAL -> animal;
            case MISC -> misc;
        };
    }
}
