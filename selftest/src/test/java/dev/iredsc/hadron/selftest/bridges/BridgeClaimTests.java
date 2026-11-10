package dev.iredsc.hadron.selftest.bridges;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.bridge.Bridge;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityDataChangeEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;

/**
 * A bridge proves which mod it is by claiming from its mod's constructor, which the loader reports. Once the server
 * runs no mod is constructing, so a plugin can't claim a bridge and fire events in a bridge's name.
 */
public final class BridgeClaimTests {
    @HadronTest
    @Covers(value = Bridge.class, method = "claim")
    void aPluginCannotClaimABridgeWhileTheServerRuns(TestContext t) {
        String refused = "no refusal: the claim went through";
        try {
            Bridge.claim("any_mod");
        } catch (IllegalStateException e) {
            refused = e.getMessage();
        }
        t.assertEquals("Bridge.claim was called outside a mod's constructor, so Hadron can't tell which bridge is "
                + "claiming: call it from your bridge mod's constructor", refused, "the refusal");
        t.assertFalse(Bridge.isBridged("any_mod"), "nothing was claimed");
    }

    @HadronTest
    @Covers(value = Bridge.class, method = "isBridged")
    void aBridgeClaimedInItsModsConstructorIsInstalled(TestContext t) {
        // The harness claims hadron_test_target from its constructor, as a real bridge does.
        t.assertTrue(Bridge.isBridged("hadron_test_target"), "the harness's claim should be installed");
        t.assertFalse(Bridge.isBridged("hadron_test"), "the claimant isn't itself bridged");
    }

    @HadronTest
    @Covers(value = Bridge.class, method = "fire")
    void aBridgeFiresDataKindsOnlyInItsOwnModsNamespace(TestContext t) {
        Bridge bridge = t.bridge();
        List<EntityDataChangeEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDataChangeEvent e) {
                heard.add(e);
            }
        });
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        Actor actor = new Actor.EntityActor(pig, Attribution.none());
        CompoundTag after = new CompoundTag();
        after.putInt("level", 2);
        EntityDataChangeEvent foreign = new EntityDataChangeEvent(pig,
                ResourceLocation.fromNamespaceAndPath("occultism", "familiar"), new CompoundTag(), after, actor);
        String refused = "no refusal: it fired";
        try {
            bridge.fire(foreign);
        } catch (IllegalArgumentException e) {
            refused = e.getMessage();
        }
        t.assertTrue(refused.contains("fired a data kind in occultism's namespace, but it bridges hadron_test_target"),
                refused);
        t.assertTrue(heard.isEmpty(), "a refused event reached a listener: " + heard);
        EntityDataChangeEvent own = new EntityDataChangeEvent(pig,
                ResourceLocation.fromNamespaceAndPath("hadron_test_target", "job"), new CompoundTag(), after, actor);
        t.assertTrue(bridge.fire(own) == own, "fire returns the event it fired");
        t.assertEquals(List.of(own), heard, "the events listeners heard");
    }

    @HadronTest
    void callEventRefusesWhatOnlyABridgeMayFire(TestContext t) {
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        CompoundTag after = new CompoundTag();
        after.putInt("level", 2);
        EntityDataChangeEvent event = new EntityDataChangeEvent(pig,
                ResourceLocation.fromNamespaceAndPath("occultism", "familiar"), new CompoundTag(), after,
                new Actor.EntityActor(pig, Attribution.none()));
        String refused = "no refusal: it fired";
        try {
            t.plugin().getServer().getPluginManager().callEvent(event);
        } catch (IllegalArgumentException e) {
            refused = e.getMessage();
        }
        t.assertTrue(refused.contains("fire it with BRIDGE.fire(event)"), refused);
    }
}
