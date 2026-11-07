package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.data.NamespacedKey;
import dev.iredsc.hadron.api.data.PersistentData;
import dev.iredsc.hadron.api.data.PersistentDataContainer;
import dev.iredsc.hadron.api.data.PersistentDataType;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockPlaceEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
/** PersistentData on every holder: where it lives, how long, and what a wrong read says. */
public final class DataTests {
    private static NamespacedKey key(TestContext t) {
        return new NamespacedKey(t.plugin(), "mark");
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "get")
    @Covers(value = PersistentDataContainer.class, method = "has")
    void itemDataTravelsWithCopiesOfTheStack(TestContext t) {
        TestPlayer alice = t.player("alice");
        ItemStack stack = new ItemStack(Items.DIAMOND);
        PersistentData.of(stack).set(key(t), PersistentDataType.STRING, "sold by the shop");
        alice.give(stack.copy());
        ItemStack held = alice.player().getInventory().getItem(0);
        t.assertEquals("sold by the shop", PersistentData.of(held).get(key(t), PersistentDataType.STRING),
                "the mark on the diamond in alice's inventory");
        t.assertFalse(PersistentData.of(new ItemStack(Items.DIAMOND)).has(key(t)),
                "a fresh diamond should carry no mark");
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "get")
    void entityDataIsSavedWithTheEntity(TestContext t) {
        Pig pig = EntityType.PIG.create(t.level());
        PersistentData.of(pig).set(key(t), PersistentDataType.INTEGER, 7);
        CompoundTag saved = new CompoundTag();
        pig.saveWithoutId(saved);
        Pig loaded = EntityType.PIG.create(t.level());
        loaded.load(saved);
        t.assertEquals(7, PersistentData.of(loaded).get(key(t), PersistentDataType.INTEGER),
                "the mark on a pig loaded from the saved one");
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "get")
    void blockEntityDataIsSavedWithTheBlockEntity(TestContext t) {
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        PersistentData.of(t.level().getBlockEntity(pos)).set(key(t), PersistentDataType.STRING, "shop");
        BlockEntity be = t.level().getBlockEntity(pos);
        CompoundTag saved = be.saveWithFullMetadata(t.level().registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(pos, be.getBlockState(), saved, t.level().registryAccess());
        t.assertTrue(loaded != null, "the chest should load from its saved data");
        t.assertEquals("shop", PersistentData.of(loaded).get(key(t), PersistentDataType.STRING),
                "the mark on a chest loaded from the saved one");
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "has")
    void positionDataSurvivesAStateChangeButNotAReplacement(TestContext t) {
        BlockPos pos = t.pos(1, 0, 0);
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        t.setBlock(pos, gate);
        PersistentData.of(t.level(), pos).set(key(t), PersistentDataType.STRING, "placed by alice");
        t.setBlock(pos, gate.setValue(FenceGateBlock.OPEN, true));
        t.assertTrue(PersistentData.of(t.level(), pos).has(key(t)), "opening the gate should keep its mark");
        t.setBlock(pos, Blocks.STONE);
        t.assertFalse(PersistentData.of(t.level(), pos).has(key(t)), "replacing the gate with stone should clear "
                + "its mark");
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "get")
    @Covers(value = PersistentDataContainer.class, method = "has")
    void positionDataMovesWithAPushedBlock(TestContext t) {
        dev.iredsc.hadron.selftest.items.Support.watch(t);
        BlockPos piston = t.pos(1, 0, 0);
        BlockPos from = t.pos(2, 0, 0);
        BlockPos to = t.pos(3, 0, 0);
        t.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        t.setBlock(from, Blocks.DIRT);
        PersistentData.of(t.level(), from).set(key(t), PersistentDataType.STRING, "placed by alice");
        t.setBlock(t.pos(1, 0, 1), Blocks.REDSTONE_BLOCK);
        t.await("the piston to push the dirt", () -> t.getBlock(to).is(Blocks.DIRT), 60);
        t.assertEquals("placed by alice", PersistentData.of(t.level(), to).get(key(t), PersistentDataType.STRING),
                "the mark where the dirt landed");
        t.assertFalse(PersistentData.of(t.level(), from).has(key(t)), "the mark should have left the old position");
    }

    @HadronTest
    @Covers(BlockPlaceEvent.class)
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "get")
    void cancelledPlacementKeepsThePositionsData(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        PersistentData.of(t.level(), pos).set(key(t), PersistentDataType.STRING, "reserved");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockPlaceEvent e) {
                e.setCancelled(true);
            }
        });
        alice.hold(new ItemStack(Items.DIRT));
        alice.placeBlock(pos);
        t.assertBlock(pos, Blocks.AIR);
        t.assertEquals("reserved", PersistentData.of(t.level(), pos).get(key(t), PersistentDataType.STRING),
                "the position's mark after a cancelled placement");
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "get")
    @Covers(value = PersistentDataContainer.class, method = "has")
    void chunkAndWorldDataStayOnTheirOwnHolder(TestContext t) {
        BlockPos here = t.pos(0, 0, 0);
        BlockPos farAway = here.offset(64, 0, 0);
        PersistentData.of(t.level().getChunkAt(here)).set(key(t), PersistentDataType.STRING, "claimed");
        PersistentData.of(t.level()).set(key(t), PersistentDataType.STRING, "arena");
        t.assertEquals("claimed", PersistentData.of(t.level().getChunkAt(here)).get(key(t), PersistentDataType.STRING),
                "the chunk's mark");
        t.assertFalse(PersistentData.of(t.level().getChunkAt(farAway)).has(key(t)), "another chunk should have no "
                + "mark");
        t.assertEquals("arena", PersistentData.of(t.level()).get(key(t), PersistentDataType.STRING), "the world's mark");
        t.assertFalse(PersistentData.of(t.server().overworld()).has(key(t)), "the overworld should have no mark");
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "get")
    void readingWithTheWrongTypeSaysHowToFixIt(TestContext t) {
        PersistentDataContainer data = PersistentData.of(t.level());
        data.set(key(t), PersistentDataType.STRING, "text");
        try {
            data.get(key(t), PersistentDataType.INTEGER);
            t.fail("reading a string as an integer should throw");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            t.assertTrue(message.contains(key(t).toString()) && message.contains("Read it with the type it was set "
                    + "with"), "the message should name the key and the fix, but was: " + message);
        }
    }
}
