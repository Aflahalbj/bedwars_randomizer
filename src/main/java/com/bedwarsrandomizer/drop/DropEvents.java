package com.bedwarsrandomizer.drop;

import com.bedwarsrandomizer.BedwarsRandomizer;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID)
public final class DropEvents {

    /** Glazed terracotta normally needs a pickaxe to drop anything; random-drop blocks work by hand too. */
    @SubscribeEvent
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        if (event.getTargetBlock().is(ModTags.RANDOM_DROP_BLOCKS)) {
            event.setCanHarvest(true);
        }
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        RandomDropPool.invalidate();
    }

    private DropEvents() {}
}
