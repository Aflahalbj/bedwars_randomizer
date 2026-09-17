package com.bedwarsrandomizer.arena;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.drop.ModTags;
import com.bedwarsrandomizer.fireball.BwrFireball;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Arena rules: players (outside creative) can only break blocks that players placed, plus beds and randomizer
 * blocks. Explosions only destroy player-placed blocks and randomizer blocks, and never glass or what glass shields
 * (blast-proof glass, like Hypixel).
 */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class ArenaEvents {
    private static final double RAY_STEP = 0.3;

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
        Vec3 center = event.getExplosion().getPosition();
        event.getAffectedBlocks().removeIf(pos -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return false;
            if (BwrFireball.isBlastProofGlass(state) || shieldedByGlass(level, center, pos)) return true;
            boolean destroyable = data.isPlayerPlaced(pos) || state.is(ModTags.RANDOM_DROP_BLOCKS);
            if (destroyable) data.unmark(pos);
            return !destroyable;
        });
    }

    /** Whether glass stands between the explosion and {@code target}. */
    private static boolean shieldedByGlass(ServerLevel level, Vec3 center, BlockPos target) {
        Vec3 delta = Vec3.atCenterOf(target).subtract(center);
        int steps = (int) Math.ceil(delta.length() / RAY_STEP);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i < steps; i++) {
            Vec3 point = center.add(delta.scale(i / (double) steps));
            pos.set(point.x, point.y, point.z);
            if (!pos.equals(target) && BwrFireball.isBlastProofGlass(level.getBlockState(pos))) return true;
        }
        return false;
    }

    private static boolean canPlayerBreak(ArenaData data, BlockPos pos, BlockState state) {
        return data.isPlayerPlaced(pos) || state.getBlock() instanceof BedBlock || state.is(ModTags.RANDOM_DROP_BLOCKS);
    }

    private ArenaEvents() {}
}
