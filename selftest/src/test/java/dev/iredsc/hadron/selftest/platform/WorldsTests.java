package dev.iredsc.hadron.selftest.platform;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Worlds made, entered, unloaded, reloaded and deleted while the server runs. */
public final class WorldsTests {
    @HadronTest
    void aCreatedWorldTakesPlayersUnloadsThemOutAndReloadsWithItsBlocks(TestContext t) {
        TestPlayer alice = t.player("alice");
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("selftest", "lifecycle");
        BlockPos spawn = new BlockPos(0, 64, 0);
        BlockPos marker = new BlockPos(2, 64, 2);
        try {
            ServerLevel world = Worlds.create(new WorldCreator(key).generator(WorldGenerator.voidWorld()).spawn(spawn));
            t.assertTrue(Worlds.get(key).orElse(null) == world, "Worlds.get should find the new world");
            world.setBlockAndUpdate(spawn.below(), Blocks.BEDROCK.defaultBlockState());
            world.setBlockAndUpdate(marker, Blocks.DIAMOND_BLOCK.defaultBlockState());

            alice.player().teleportTo(world, 0.5, 64, 0.5, 0, 0);
            t.tick(3);
            t.assertTrue(alice.player().serverLevel() == world, "alice should be in the new world, but is in "
                    + alice.player().serverLevel().dimension().location());
            t.assertTrue(alice.isConnected(), "alice should still be connected after changing worlds, but: "
                    + alice.disconnectReason());

            Worlds.unload(world, true);
            t.assertTrue(alice.player().serverLevel() != world, "unloading should move alice out first");
            t.tick(2);
            t.assertTrue(Worlds.get(key).isEmpty(), "the world should be unloaded");
            t.assertTrue(Worlds.isCreated(key), "an unloaded world should still be listed as created");
            t.assertTrue(alice.isConnected(), "alice should still be connected after being moved out");

            ServerLevel reloaded = Worlds.load(key);
            t.assertTrue(reloaded.getBlockState(marker).is(Blocks.DIAMOND_BLOCK), "the reloaded world should keep "
                    + "the diamond block saved on unload, but has " + reloaded.getBlockState(marker));
        } finally {
            if (Worlds.isCreated(key)) Worlds.delete(key);
        }
        t.tick(2);
        t.assertFalse(Worlds.isCreated(key), "a deleted world should be gone from Worlds.created()");
        t.assertTrue(Worlds.get(key).isEmpty(), "a deleted world should not be loaded");
    }

    @HadronTest
    void aNestedWorldIdIsRefusedWithTheFix(TestContext t) {
        try {
            new WorldCreator(ResourceLocation.fromNamespaceAndPath("selftest", "maps/arena"));
            t.fail("a world id with a / in its path should be refused");
        } catch (IllegalArgumentException e) {
            t.assertTrue(e.getMessage().contains("selftest:maps_arena"), "the message should suggest selftest:maps_arena,"
                    + " but was: " + e.getMessage());
        }
    }

    @HadronTest
    void dimensionArgumentsAskTheServerForSuggestions(TestContext t) {
        TestPlayer bob = t.player("bob");
        bob.setOp(true); // operators get /execute in <dimension> in their command tree
        t.tick();
        List<ClientboundCommandsPacket> trees = bob.received(ClientboundCommandsPacket.class);
        t.assertTrue(!trees.isEmpty(), "bob should have been sent a command tree");
        CommandBuildContext context = CommandBuildContext.simple(t.server().registryAccess(),
                t.server().getWorldData().enabledFeatures());
        RootCommandNode<SharedSuggestionProvider> root = trees.get(trees.size() - 1).getRoot(context);
        var askServer = SuggestionProviders.getProvider(ResourceLocation.withDefaultNamespace("ask_server"));
        int found = 0;
        Set<CommandNode<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CommandNode<SharedSuggestionProvider>> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            CommandNode<SharedSuggestionProvider> node = todo.pop();
            if (!seen.add(node)) continue;
            if (node instanceof ArgumentCommandNode<SharedSuggestionProvider, ?> arg
                    && arg.getType() instanceof DimensionArgument) {
                found++;
                t.assertTrue(arg.getCustomSuggestions() == askServer, "the dimension argument \"" + arg.getName()
                        + "\" should ask the server for suggestions, but suggests from "
                        + arg.getCustomSuggestions());
            }
            todo.addAll(node.getChildren());
        }
        t.assertTrue(found > 0, "the operator's command tree should have a dimension argument (execute in)");
    }
}
