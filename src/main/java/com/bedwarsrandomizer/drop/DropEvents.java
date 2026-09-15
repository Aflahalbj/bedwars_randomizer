package com.bedwarsrandomizer.drop;

import com.bedwarsrandomizer.BedwarsRandomizer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class DropEvents {
    private static final float DIRT_HARDNESS = 0.5F;

    /** Glazed terracotta normally needs a pickaxe to drop anything; random-drop blocks work by hand too. */
    @SubscribeEvent
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        if (event.getTargetBlock().is(ModTags.RANDOM_DROP_BLOCKS)) {
            event.setCanHarvest(true);
        }
    }

    /**
     * Random-drop blocks break as fast as dirt by hand; tools keep their speed bonus, so a pickaxe is faster still
     * (vanilla glazed terracotta would take ~2 s by hand, 1 s with a wooden pickaxe).
     */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        BlockState state = event.getState();
        if (!state.is(ModTags.RANDOM_DROP_BLOCKS)) return;
        float hardness = state.getDestroySpeed(event.getEntity().level(), event.getPosition().orElse(BlockPos.ZERO));
        if (hardness > DIRT_HARDNESS) {
            event.setNewSpeed(event.getNewSpeed() * hardness / DIRT_HARDNESS);
        }
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        RandomDropPool.invalidate();
    }

    private DropEvents() {}
}
