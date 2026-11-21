package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A region judges an event as another plugin's ordinary listener leaves it, not as it first stood. */
public final class JudgedLastTests {
    @HadronTest
    void aTeleportAnotherPluginRedirectsIntoARegionIsRefused(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "redirect-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag redirect-vault entry deny");
        TestPlayer bob = t.player("bob");
        Vec3 start = bob.player().position();
        BlockPos vault = t.pos(3, 0, 0);
        // A routing plugin, at the priority plugins use, sends every teleport of bob's into the vault.
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() == bob.player()) {
                    e.setTo(t.level(), new Vec3(vault.getX() + 0.5, vault.getY(), vault.getZ() + 0.5));
                }
            }
        });
        BlockPos asked = t.pos(-3, 0, 0);
        bob.teleport(asked.getX() + 0.5, asked.getY(), asked.getZ() + 0.5);
        t.assertEquals(start, bob.player().position(), "where bob stood, his teleport redirected into the vault refused");
        t.tick(3);
        t.assertTrue(bob.player().getBlockX() < t.pos(2, 0, 0).getX(),
                "bob should still be outside the vault, but stands at " + bob.player().blockPosition());
    }

    @HadronTest
    void aBlockAnotherPluginPutsBackInABlastIsStillSpared(TestContext t) {
        BlockPos kept = t.pos(2, 0, 0);
        t.setBlock(kept, Blocks.DIRT);
        RegionsTests.define(t, RegionsTests.admin(t), "blast-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        // A plugin, at the priority plugins use, makes sure the blast takes this block.
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionEvent e) {
                if (!e.blockList().contains(kept)) e.blockList().add(kept);
            }
        });
        // A blast outside the vault that nothing says the cause of, which the vault keeps out.
        BlockPos at = t.pos(0, 0, 0);
        t.level().explode(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 3f, Level.ExplosionInteraction.TNT);
        t.assertBlock(kept, Blocks.DIRT);
    }
}
