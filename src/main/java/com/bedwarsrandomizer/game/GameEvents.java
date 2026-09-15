package com.bedwarsrandomizer.game;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class GameEvents {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) BedwarsGame.get().tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BedwarsGame.get().reset();
    }

    /** Refill texts left in chunks that were unloaded when a game ended vanish as soon as those chunks load. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity().getTags().contains(RefillArea.DISPLAY_TAG)
                && !BedwarsGame.get().isCurrentRefillDisplay(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BedwarsGame.get().onDeath(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BedwarsGame.get().onRespawn(player);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (GameSettings.get().refresh(player)) GameSettings.get().save(); // keep the saved name and skin current
            BedwarsGame.get().onLogin(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BedwarsGame.get().onLogout(player);
    }

    /** Before the arena rules (which allow breaking beds): nobody breaks their own team's bed. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getState().getBlock() instanceof TeamBedBlock && event.getPlayer() instanceof ServerPlayer player
                && BedwarsGame.get().onBedBreak(player, event.getState())) {
            event.setCanceled(true);
        }
    }

    /** Note blocks are randomizer blocks: right-clicking doesn't tune them (placing blocks against them still works). */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().getBlockState(event.getPos()).is(Blocks.NOTE_BLOCK)) {
            event.setUseBlock(Event.Result.DENY);
        }
    }

    private GameEvents() {}
}
