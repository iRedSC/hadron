package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** What a blast does to the entities it hits: each one a region keeps is spared, the rest of the blast goes off. */
public final class BlastTests {
    /** A fresh region from x 2 to 4, which keeps everyone else from building there. */
    private static TestPlayer vault(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "vault", t.pos(2, 0, -1), t.pos(4, 3, 1));
        return alice;
    }

    /** Puts {@code entity} in the world as the test's setup, which no region asks about. */
    private static void add(TestContext t, Entity entity) {
        try (Actors.Scope scope = Actors.push(new Actor.Server("test setup"))) {
            t.level().addFreshEntity(entity);
        }
    }

    /** A diamond lying at {@code at}, which nobody picks up and which never despawns. */
    private static ItemEntity diamond(TestContext t, BlockPos at) {
        ItemEntity item = new ItemEntity(t.level(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                new ItemStack(Items.DIAMOND));
        item.setUnlimitedLifetime();
        item.setNeverPickUp();
        add(t, item);
        return item;
    }

    private static <T extends Mob> T mob(TestContext t, EntityType<T> type, BlockPos at) {
        T mob = type.create(t.level());
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setNoAi(true);
        add(t, mob);
        return mob;
    }

    /** TNT {@code owner} lit at {@code at}, as they would light it, gone off. */
    private static void tnt(TestContext t, TestPlayer owner, BlockPos at) {
        PrimedTnt tnt = new PrimedTnt(t.level(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, owner.player());
        tnt.setFuse(1);
        try (Actors.Scope scope = Actors.push(new Actor.Player(owner.player()))) {
            t.level().addFreshEntity(tnt);
        }
        t.await("the TNT to go off", () -> !tnt.isAlive(), 20);
        t.tick();
    }

    @HadronTest
    void aBlastOutsideSparesADiamondInsideButNotOneOutside(TestContext t) {
        TestPlayer alice = vault(t);
        alice.command("rg deny vault everyone entity.hurt.misc entity.remove.misc");
        alice.command("rg deny vault world entity.hurt.misc entity.remove.misc");
        ItemEntity inside = diamond(t, t.pos(3, 0, 0));
        ItemEntity outside = diamond(t, t.pos(-2, 0, 0));
        t.assertTrue(t.level().getEntity(outside.getId()) == outside, "the diamond outside should be there first");
        BlockPos blast = t.pos(0, 0, 0);
        t.level().explode(null, blast.getX() + 0.5, blast.getY(), blast.getZ() + 0.5, 5f,
                Level.ExplosionInteraction.TNT);
        t.assertFalse(inside.isRemoved(), "the diamond inside the vault should survive a blast from outside");
        t.assertTrue(outside.isRemoved(), "the diamond outside the vault should be destroyed by the blast");
    }

    @HadronTest
    void anOutsidersTntSparesACowInsideButNotAZombie(TestContext t) {
        vault(t);
        TestPlayer bob = t.player("bob");
        bob.moveTo(t.pos(-4, 0, 0));
        LivingEntity cow = mob(t, EntityType.COW, t.pos(3, 0, 0));
        LivingEntity zombie = mob(t, EntityType.ZOMBIE, t.pos(3, 0, 1));
        tnt(t, bob, t.pos(0, 0, 0));
        t.assertEquals(cow.getMaxHealth(), cow.getHealth(), "the cow's health, kept from bob's blast by the vault");
        t.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "the zombie should be hurt: the vault leaves "
                + "monsters open, but its health is " + zombie.getHealth());
    }

    @HadronTest
    void aMembersBlastInsideTheirOwnRegionGoesOff(TestContext t) {
        TestPlayer alice = vault(t);
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember vault carol");
        carol.moveTo(t.pos(-4, 0, 0));
        BlockPos dirt = t.pos(4, 0, 0);
        try (Actors.Scope scope = Actors.push(new Actor.Server("test setup"))) {
            t.setBlock(dirt, Blocks.DIRT);
        }
        t.assertBlock(dirt, Blocks.DIRT);
        ItemEntity item = diamond(t, t.pos(2, 0, 0));
        t.assertTrue(t.level().getEntity(item.getId()) == item, "the diamond inside should be there first");
        tnt(t, carol, t.pos(3, 0, 0));
        t.assertTrue(t.level().getBlockState(dirt).isAir(), "carol's blast in her own region should break the dirt");
        t.assertTrue(item.isRemoved(), "carol's blast in her own region should destroy the diamond");
    }
}
