package com.bedwarsrandomizer.arena;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.drop.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Arena rules: players (outside creative) can only break blocks that players placed, plus beds and glazed terracotta.
 * Explosions only destroy player-placed blocks and glazed terracotta.
 */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class ArenaEvents {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Arena.ensurePasted(event.getServer());
    }

    /** Runs last and only for placements nothing cancelled. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Arena.isArena(level) || !(event.getEntity() instanceof Player)) return;
        ArenaData data = ArenaData.get(level);
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) data.markPlayerPlaced(snapshot.getPos());
        } else {
            data.markPlayerPlaced(event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Arena.isArena(level)) return;
        ArenaData data = ArenaData.get(level);
        BlockPos pos = event.getPos();
        if (event.getPlayer().getAbilities().instabuild || canPlayerBreak(data, pos, event.getState())) {
            data.unmark(pos);
        } else {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Arena.isArena(level)) return;
        ArenaData data = ArenaData.get(level);
        event.getAffectedBlocks().removeIf(pos -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return false;
            boolean destroyable = data.isPlayerPlaced(pos) || state.is(ModTags.RANDOM_DROP_BLOCKS);
            if (destroyable) data.unmark(pos);
            return !destroyable;
        });
    }

    private static boolean canPlayerBreak(ArenaData data, BlockPos pos, BlockState state) {
        return data.isPlayerPlaced(pos) || state.getBlock() instanceof BedBlock || state.is(ModTags.RANDOM_DROP_BLOCKS);
    }

    private ArenaEvents() {}
}
