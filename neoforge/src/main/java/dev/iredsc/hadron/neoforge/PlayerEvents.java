package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.player.PlayerAdvancementDoneEvent;
import dev.iredsc.hadron.api.event.player.PlayerBedEnterEvent;
import dev.iredsc.hadron.api.event.player.PlayerBedLeaveEvent;
import dev.iredsc.hadron.api.event.player.PlayerChangedWorldEvent;
import dev.iredsc.hadron.api.event.player.PlayerChatEvent;
import dev.iredsc.hadron.api.event.player.PlayerExpChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerGameModeChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerJoinEvent;
import dev.iredsc.hadron.api.event.player.PlayerQuitEvent;
import dev.iredsc.hadron.api.event.player.PlayerRespawnEvent;
import dev.iredsc.hadron.api.event.player.PlayerSwapHandItemsEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;

/**
 * Hadron's player events from NeoForge's. Login, kick, abilities, toggles, level changes and mods' packets come from
 * the world mixins instead.
 */
final class PlayerEvents {
    private PlayerEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(PlayerEvents::join);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::quit);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::chat);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::respawn);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::changedWorld);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::gameMode);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::experience);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::advancement);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::swapHands);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::bedEnter);
        NeoForge.EVENT_BUS.addListener(PlayerEvents::bedLeave);
    }

    /** After {@code placeNewPlayer}'s join line was held back; the event's line replaces it. */
    private static void join(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        PlayerJoinEvent hadron = Hooks.post(new PlayerJoinEvent(p, PlayerMessages.takeJoin(p)));
        PlayerMessages.join(p, hadron.getJoinMessage());
    }

    /** After {@code removePlayerFromWorld}'s quit line was held back; the event's line replaces it. */
    private static void quit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        PlayerQuitEvent hadron = Hooks.post(new PlayerQuitEvent(p, PlayerMessages.takeQuit(p)));
        PlayerMessages.quit(p, hadron.getQuitMessage());
    }

    private static void chat(ServerChatEvent event) {
        if (!Hooks.listens(PlayerChatEvent.class)) return;
        PlayerChatEvent hadron = Hooks.post(new PlayerChatEvent(event.getPlayer(), event.getRawText(),
                event.getMessage()));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else if (hadron.getMessage() != event.getMessage()) {
            event.setMessage(hadron.getMessage());
        }
    }

    /** PlayerList.respawn, for every respawn. */
    private static void respawn(PlayerRespawnPositionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !Hooks.listens(PlayerRespawnEvent.class)) return;
        DimensionTransition to = event.getDimensionTransition();
        PlayerRespawnEvent hadron = Hooks.post(new PlayerRespawnEvent(p, to.newLevel(), to.pos(), to.yRot(),
                to.xRot(), event.isFromEndFight()));
        if (hadron.getRespawnLevel() != to.newLevel() || !hadron.getRespawnPosition().equals(to.pos())
                || hadron.getYaw() != to.yRot() || hadron.getPitch() != to.xRot()) {
            event.setDimensionTransition(new DimensionTransition(hadron.getRespawnLevel(),
                    hadron.getRespawnPosition(), to.speed(), hadron.getYaw(), hadron.getPitch(),
                    to.missingRespawnBlock(), to.postDimensionTransition()));
        }
    }

    private static void changedWorld(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !Hooks.listens(PlayerChangedWorldEvent.class)) return;
        ServerLevel from = p.server.getLevel(event.getFrom());
        if (from != null) Hooks.post(new PlayerChangedWorldEvent(p, from));
    }

    /** ServerPlayer.setGameMode, for every caller. */
    private static void gameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !Hooks.listens(PlayerGameModeChangeEvent.class)) return;
        if (Hooks.post(new PlayerGameModeChangeEvent(p, event.getCurrentGameMode(), event.getNewGameMode()))
                .isCancelled()) {
            event.setCanceled(true);
        }
    }

    /** Player.giveExperiencePoints: orbs, /xp add, mods paying out. */
    private static void experience(PlayerXpEvent.XpChange event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !Hooks.listens(PlayerExpChangeEvent.class)) return;
        PlayerExpChangeEvent hadron = Hooks.post(new PlayerExpChangeEvent(p, event.getAmount()));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else if (hadron.getAmount() != event.getAmount()) {
            event.setAmount(hadron.getAmount());
        }
    }

    private static void advancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && Hooks.listens(PlayerAdvancementDoneEvent.class)) {
            Hooks.post(new PlayerAdvancementDoneEvent(p, event.getAdvancement()));
        }
    }

    /** The swap-hands key; fired before either hand changes. */
    private static void swapHands(LivingSwapItemsEvent.Hands event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !Hooks.listens(PlayerSwapHandItemsEvent.class)) return;
        PlayerSwapHandItemsEvent hadron = Hooks.post(new PlayerSwapHandItemsEvent(p,
                event.getItemSwappedToMainHand(), event.getItemSwappedToOffHand()));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
            return;
        }
        event.setItemSwappedToMainHand(hadron.getMainHandItem());
        event.setItemSwappedToOffHand(hadron.getOffHandItem());
    }

    /** ServerPlayer.startSleepInBed; only when vanilla would let them sleep. */
    private static void bedEnter(CanPlayerSleepEvent event) {
        if (event.getProblem() != null || !Hooks.listens(PlayerBedEnterEvent.class)) return;
        if (Hooks.post(new PlayerBedEnterEvent(event.getEntity(), event.getPos())).isCancelled()) {
            event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
        }
    }

    private static void bedLeave(PlayerWakeUpEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && Hooks.listens(PlayerBedLeaveEvent.class)) {
            Hooks.post(new PlayerBedLeaveEvent(p, p.getSleepingPos().orElse(null)));
        }
    }
}
