package dev.iredsc.hadron.regions;

import static dev.iredsc.hadron.regions.RegionsTests.admin;
import static dev.iredsc.hadron.regions.RegionsTests.define;

import dev.iredsc.hadron.api.plugin.CommandExecutor;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Blocks;

/** Setting nodes from chat, taking a change back, and asking why. */
public final class RuleCommandTests {
    /** A husk: a monster that doesn't burn in daylight, so only a hit lowers its health. */
    private static Husk zombie(TestContext t) {
        Husk z = EntityType.HUSK.create(t.level());
        z.moveTo(t.pos(1, 0, 1).getX() + 0.5, t.pos(1, 0, 1).getY(), t.pos(1, 0, 1).getZ() + 0.5);
        z.setNoAi(true);
        z.setPersistenceRequired();
        t.level().addFreshEntity(z);
        return z;
    }

    private static boolean hits(TestContext t, TestPlayer who, Husk z) {
        float before = z.getHealth();
        t.tick(20);
        who.attack(z);
        t.tick(2);
        return z.getHealth() < before;
    }

    @HadronTest
    void aDenyFromChatRefusesAtOnceSaysTheNewRulesAndUndoPutsThemBack(TestContext t) {
        TestPlayer alice = admin(t);
        Husk z = zombie(t);
        define(t, alice, "arena", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        // Bare hands: a point of damage a hit, so the husk outlives every hit the test makes.
        t.assertTrue(hits(t, bob, z), "a fresh region leaves monsters to be fought");
        alice.command("rg deny arena entity.hurt.monster");
        t.assertChat(alice, "arena · everyone: deny [build, use, entity.hurt.monster] · allow [] [undo]");
        t.assertFalse(hits(t, bob, z), "arena now denies everyone entity.hurt.monster");
        alice.command("rg undo");
        t.assertChat(alice, "Undone · arena · everyone: deny [build, use]");
        t.assertTrue(hits(t, bob, z), "undo put the rules back");
        alice.command("rg deny arena entity.hurt");
        alice.command("rg allow arena entity.hurt.monster");
        t.assertTrue(hits(t, bob, z), "entity.hurt.monster (3) beats entity.hurt (2)");
        alice.command("rg unset arena entity.hurt.monster");
        t.assertFalse(hits(t, bob, z), "with the allow gone, entity.hurt decides");
    }

    @HadronTest
    void aMistypedNodeInChatIsRefusedWithTheNearestNodeAndNothingChanges(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "arena", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        Rules before = ((RegionsPlugin) t.plugin()).regions().get("arena").orElseThrow().rules();
        alice.command("rg deny arena entity.hurt.animals");
        t.assertChat(alice, "`entity.hurt.animals` isn't a node. Did you mean `entity.hurt.animal`?");
        t.assertEquals(before, ((RegionsPlugin) t.plugin()).regions().get("arena").orElseThrow().rules(),
                "arena's rules after a refused deny");
        alice.command("rg deny arena visitors build");
        t.assertChat(alice, "`visitors` isn't a node");
    }

    @HadronTest
    void whyTracesEachRegionAtYourFeetTopDown(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "plot-1", t.pos(-2, 0, -2), t.pos(2, 3, 2));
        WorkedExampleTests.put(t, "spawn", t.pos(-6, 0, -6), t.pos(6, 3, 6));
        alice.command("rg priority plot-1 10");
        alice.command("rg allow plot-1 entity.hurt");
        alice.moveTo(t.pos(0, 1, 0));
        alice.command("rg why entity.hurt.animal");
        t.assertChat(alice, "plot-1 (10): everyone allow entity.hurt ⊃ entity.hurt.animal → allowed");
        t.assertChat(alice, "spawn (0): everyone deny build ⊃ entity.hurt.animal → refused");
        t.assertChat(alice, "→ allowed by plot-1");
        alice.command("rg why entity.push.monster");
        t.assertChat(alice, "plot-1 (10): everyone, no rule, passes");
        t.assertChat(alice, "→ allowed: no region here decides entity.push.monster");
        alice.command("rg why entity.hurt");
        t.assertChat(alice, "Name one node, such as entity.hurt.animal: entity.hurt covers several");
        List<String> chat = alice.chat();
        t.assertTrue(chat.stream().noneMatch(l -> l.contains("Exception")), "no command threw: " + chat);
    }

    @HadronTest
    void whyDecidesAsProtectionDoesAndHidesARegionYouDontManage(TestContext t) {
        TestPlayer alice = admin(t);
        // Set before any region exists: once one does, the test's own writes are refused.
        t.setBlock(t.pos(1, 0, 1), Blocks.DIRT);
        define(t, alice, "plot", t.pos(-2, 0, -2), t.pos(2, 3, 2));
        WorkedExampleTests.put(t, "town", t.pos(-6, 0, -6), t.pos(6, 3, 6));
        alice.command("rg priority town 10");
        TestPlayer bob = t.player("bob");
        alice.command("rg addowner plot bob");
        t.assertChat(alice, "bob owns plot");
        bob.moveTo(t.pos(0, 1, 0));
        t.assertBlock(t.pos(1, 0, 1), Blocks.DIRT);
        bob.breakBlock(t.pos(1, 0, 1));
        t.assertBlock(t.pos(1, 0, 1), Blocks.DIRT);
        int seen = bob.chat().size();
        bob.command("rg why block.break");
        List<String> why = bob.chat().subList(seen, bob.chat().size());
        t.assertTrue(why.stream().anyMatch(l -> l.contains("plot (0): members, a member → allowed")),
                "bob's own plot should answer: " + why);
        t.assertTrue(why.stream().anyMatch(l -> l.contains("→ refused by a region you don't manage")),
                "town refuses bob's break: " + why);
        t.assertTrue(why.stream().noneMatch(l -> l.contains("town")), "bob doesn't manage town: " + why);
        alice.moveTo(t.pos(0, 1, 0));
        alice.command("rg why block.break");
        t.assertChat(alice, "town (10): everyone deny build ⊃ block.break → refused");
        t.assertChat(alice, "→ refused by town");
    }

    /** The command the newest {@code [undo]} sent to them runs when clicked, as they would type it. */
    private static String newestUndoButton(TestPlayer p) {
        List<ClientboundSystemChatPacket> sent = p.received(ClientboundSystemChatPacket.class);
        for (int i = sent.size() - 1; i >= 0; i--) {
            for (Component part : sent.get(i).content().getSiblings()) {
                ClickEvent click = part.getStyle().getClickEvent();
                if (click != null && part.getString().equals("[undo]")) return click.getValue().substring(1);
            }
        }
        throw new AssertionError(p.name() + " was never sent an [undo]: " + p.chat());
    }

    @HadronTest
    void undoSaysTheSubjectItRestored(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "east", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        alice.command("rg deny east world block.break");
        t.assertChat(alice, "east · world: deny [block.break]");
        alice.command("rg undo");
        t.assertChat(alice, "Undone · east · world: deny [] ·");
    }

    @HadronTest
    void anOlderUndoButtonDoesntUndoALaterChange(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "east", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        define(t, alice, "west", t.pos(3, 0, -2), t.pos(4, 1, -1));
        RegionStore store = ((RegionsPlugin) t.plugin()).regions();
        alice.command("rg deny east entity.hurt.monster");
        String eastButton = newestUndoButton(alice);
        alice.command("rg deny west entity.hurt.monster");
        String westButton = newestUndoButton(alice);
        Rules eastNow = store.get("east").orElseThrow().rules();
        Rules westNow = store.get("west").orElseThrow().rules();
        alice.command(eastButton);
        t.assertChat(alice, "That [undo] is for an older change, so nothing was undone");
        t.assertEquals(westNow, store.get("west").orElseThrow().rules(), "west's rules after east's old [undo]");
        t.assertEquals(eastNow, store.get("east").orElseThrow().rules(), "east's rules after its old [undo]");
        alice.command(westButton);
        t.assertChat(alice, "Undone · west · everyone: deny [build, use] ·");
    }

    /** A command as a fresh load of the plugin makes it: it remembers no earlier change. */
    private static RegionCommand freshCommand(TestContext t) throws RegionsFile.Problem {
        RegionsPlugin plugin = (RegionsPlugin) t.plugin();
        Feedback feedback = new Feedback(plugin);
        return new RegionCommand(plugin, plugin.regions(), new Wand(feedback), feedback, Bypass.load(plugin));
    }

    @HadronTest
    void anUndoButtonFromBeforeAReloadDoesntUndoALaterChange(TestContext t) throws RegionsFile.Problem {
        TestPlayer alice = admin(t);
        define(t, alice, "east", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        define(t, alice, "west", t.pos(3, 0, -2), t.pos(4, 1, -1));
        RegionStore store = ((RegionsPlugin) t.plugin()).regions();
        PluginCommand region = Objects.requireNonNull(t.plugin().getCommand("region"));
        CommandExecutor loaded = region.getExecutor();
        try {
            // Each load's first change: a counted id would give both buttons the same one.
            region.setExecutor(freshCommand(t));
            alice.command("rg deny east entity.hurt.monster");
            String eastButton = newestUndoButton(alice);
            region.setExecutor(freshCommand(t));
            alice.command("rg deny west entity.hurt.monster");
            Rules westNow = store.get("west").orElseThrow().rules();
            alice.command(eastButton);
            t.assertChat(alice, "That [undo] is for an older change, so nothing was undone");
            t.assertEquals(westNow, store.get("west").orElseThrow().rules(), "west's rules after east's old [undo]");
        } finally {
            region.setExecutor(loaded);
        }
    }
}
