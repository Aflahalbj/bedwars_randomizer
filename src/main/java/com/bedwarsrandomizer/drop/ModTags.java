package com.bedwarsrandomizer.drop;

import com.bedwarsrandomizer.BedwarsRandomizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class ModTags {
    /** Blocks whose drops are replaced by a random roll (all glazed terracotta by default). */
    public static final TagKey<Block> RANDOM_DROP_BLOCKS =
            BlockTags.create(ResourceLocation.fromNamespaceAndPath(BedwarsRandomizer.MOD_ID, "random_drop_blocks"));

    /** Items that can never be rolled. */
    public static final TagKey<Item> RANDOM_DROP_BLACKLIST =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath(BedwarsRandomizer.MOD_ID, "random_drop_blacklist"));

    /** Items that are allowed but get an extra rarity penalty (TNT, ender chest, ...). */
    public static final TagKey<Item> RANDOM_DROP_RARE =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath(BedwarsRandomizer.MOD_ID, "random_drop_rare"));

    private ModTags() {}
}
