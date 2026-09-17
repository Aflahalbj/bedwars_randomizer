package com.bedwarsrandomizer.game;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import com.bedwarsrandomizer.arena.Arena;
import com.bedwarsrandomizer.replay.ReplayRecorder;
import com.bedwarsrandomizer.replay.ReplayStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
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

    /** A fresh start, like restarting a server: no registrations, rounds or replays from before, and a clean map. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        BedwarsGame.get().reset();
        GameSettings.get().unregisterAll();
        ReplayRecorder.get().reset();
        ReplayStorage.clear();
        server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(false, server);
        Arena.ensurePasted(server);
        Arena.resetMap(server);
    }

    /** Quitting the world / stopping the server ends the round (players back to the lobby) and forgets everything. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BedwarsGame.get().shutdown(event.getServer());
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
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (state.is(Blocks.NOTE_BLOCK)) {
            event.setUseBlock(Event.Result.DENY);
            return;
        }
        // during a round only storage opens: no crafting table, furnace, anvil, enchanting table...
        if (event.getLevel() instanceof ServerLevel level && Arena.isArena(level) && BedwarsGame.get().isActive()
                && !event.getEntity().getAbilities().instabuild && !isStorage(state)
                && state.getMenuProvider(level, event.getPos()) != null) {
            event.setUseBlock(Event.Result.DENY);
            event.getEntity().displayClientMessage(Component.literal("You can't use that during a round.").withStyle(ChatFormatting.RED), true);
        }
    }

    private static boolean isStorage(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof EnderChestBlock
                || state.getBlock() instanceof BarrelBlock || state.getBlock() instanceof ShulkerBoxBlock;
    }

    private GameEvents() {}
}
