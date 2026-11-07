package dev.iredsc.hadron.selftest;

import dev.iredsc.hadron.api.data.NamespacedKey;
import dev.iredsc.hadron.api.data.PersistentData;
import dev.iredsc.hadron.api.data.PersistentDataContainer;
import dev.iredsc.hadron.api.data.PersistentDataType;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.economy.EconomyResult;
import dev.iredsc.hadron.api.net.Visibility;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.math.BigDecimal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
/** Hadron's platform APIs between real players: visibility, persistent data, the economy. */
public final class PlatformTests {
    @HadronTest
    @Covers(value = Visibility.class, method = "hidePlayer")
    @Covers(value = Visibility.class, method = "showPlayer")
    void hiddenPlayersAreNeverSentAndShowingSendsThem(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        t.assertTrue(bob.isShown(alice.player()), "bob should see alice before she's hidden");
        Visibility.hidePlayer(t.plugin(), bob.player(), alice.player());
        t.tick();
        t.assertFalse(bob.isShown(alice.player()), "hiding alice should remove her from bob's client");
        t.assertNotInTabList(bob, alice.player());

        // Leave bob's range and come back: a visible player would be sent again.
        bob.clearReceived();
        alice.teleport(alice.player().getX() + 200, alice.player().getY(), alice.player().getZ());
        t.tick(5);
        alice.teleport(t.pos(0, 0, 0).getX() + 0.5, t.pos(0, 0, 0).getY(), t.pos(0, 0, 0).getZ() + 0.5);
        t.tick(5);
        t.assertNotSent(bob, alice.player());
        t.assertNotInTabList(bob, alice.player());

        Visibility.showPlayer(t.plugin(), bob.player(), alice.player());
        t.tick();
        t.assertSent(bob, alice.player());
        t.assertInTabList(bob, alice.player());
    }

    @HadronTest
    @Covers(value = PersistentData.class, method = "of")
    @Covers(value = PersistentDataContainer.class, method = "set")
    @Covers(value = PersistentDataContainer.class, method = "has")
    void aBlocksMarkIsGoneOnceItsBroken(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.DIRT);
        NamespacedKey placedBy = new NamespacedKey(t.plugin(), "placed_by");
        PersistentData.of(t.level(), pos).set(placedBy, PersistentDataType.STRING, "alice");
        t.assertTrue(PersistentData.of(t.level(), pos).has(placedBy), "the mark should be there once set");
        alice.breakBlock(pos);
        t.assertBlock(pos, Blocks.AIR);
        PersistentDataContainer after = PersistentData.of(t.level(), pos);
        t.assertFalse(after.has(placedBy), "breaking the block should clear its mark, but it still holds "
                + after.get(placedBy, PersistentDataType.STRING));
    }

    @HadronTest
    @Covers(value = Economy.class, method = "get")
    @Covers(value = Economy.class, method = "deposit")
    @Covers(value = Economy.class, method = "transfer")
    @Covers(value = Economy.class, method = "getBalance")
    void moneyMovesBetweenTwoPlayers(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Economy economy = Economy.get();
        t.assertBalance(alice, "money", "0");
        economy.deposit(alice.uuid(), Economy.MONEY, new BigDecimal("10.00"));
        EconomyResult paid = economy.transfer(alice.uuid(), bob.uuid(), Economy.MONEY, new BigDecimal("4.25"));
        t.assertTrue(paid instanceof EconomyResult.Done, "the transfer should be done, but was " + paid);
        t.assertBalance(alice, "money", "5.75");
        t.assertBalance(bob, "money", "4.25");

        EconomyResult tooMuch = economy.transfer(bob.uuid(), alice.uuid(), Economy.MONEY, new BigDecimal("100"));
        t.assertTrue(tooMuch instanceof EconomyResult.NotEnoughMoney, "paying more than bob has should be "
                + "NotEnoughMoney, but was " + tooMuch);
        t.assertBalance(alice, "money", "5.75");
        t.assertBalance(bob, "money", "4.25");
    }

    @HadronTest
    void moneyResetsForTheNextTest(TestContext t) {
        // Runs right after moneyMovesBetweenTwoPlayers (tests run by name), which left alice with 5.75.
        TestPlayer alice = t.player("alice");
        t.assertBalance(alice, "money", "0");
        t.assertInventory(alice, net.minecraft.world.item.Items.DIRT, 0);
    }
}
