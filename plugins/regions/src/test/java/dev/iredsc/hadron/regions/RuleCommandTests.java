package dev.iredsc.hadron.regions;

import static dev.iredsc.hadron.regions.RegionsTests.admin;
import static dev.iredsc.hadron.regions.RegionsTests.define;

import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;

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
}
