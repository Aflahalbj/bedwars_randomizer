package com.bedwarsrandomizer.game;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.arena.Arena;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Like Hypixel Bed Wars: TNT placed during a game (or in the arena) lights itself right away. Creative players are exempt. */
@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class TntEvents {
    /** Players measured about 3 seconds on Hypixel (vanilla: 4). */
    public static final int FUSE_TICKS = 60;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !event.getPlacedBlock().is(Blocks.TNT)) return;
        if (!(event.getEntity() instanceof Player player) || player.getAbilities().instabuild) return;
        if (!Arena.isArena(level) && !BedwarsGame.get().isActive()) return;

        BlockPos pos = event.getPos().immutable();
        MinecraftServer server = level.getServer();
        // after the placement has finished; removing the block inside the event would leave a ghost block on the client
        server.tell(new TickTask(server.getTickCount(), () -> ignite(level, pos, player)));
    }

    private static void ignite(ServerLevel level, BlockPos pos, Player player) {
        if (!level.getBlockState(pos).is(Blocks.TNT)) return;
        level.removeBlock(pos, false);
        PrimedTnt tnt = new PrimedTnt(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player);
        tnt.setFuse(FUSE_TICKS);
        level.addFreshEntity(tnt);
        level.playSound(null, tnt.getX(), tnt.getY(), tnt.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(player, GameEvent.PRIME_FUSE, pos);
    }

    private TntEvents() {}
}
