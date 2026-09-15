package com.bedwarsrandomizer.drop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;

/** Items that come in all dye colors (white_wool ... black_wool), found by their registry names. */
public final class ColoredBlocks {
    /** light_gray before gray etc., so the longest color name wins. */
    private static final DyeColor[] COLORS = Arrays.stream(DyeColor.values())
            .sorted(Comparator.comparingInt((DyeColor color) -> color.getName().length()).reversed())
            .toArray(DyeColor[]::new);

    /** "wool" for red_wool, or null if the item has no white and black variant. */
    @Nullable
    public static String family(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) return null;
        DyeColor color = colorOf(id.getPath());
        if (color == null) return null;
        String rest = id.getPath().substring(color.getName().length() + 1);
        return exists(id.getNamespace(), "white_" + rest) && exists(id.getNamespace(), "black_" + rest) ? rest : null;
    }

    /** The same item in another color, or the item itself if it isn't colored. */
    public static Item withColor(Item item, DyeColor color) {
        String family = family(item);
        if (family == null) return item;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        Item colored = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), color.getName() + "_" + family));
        return colored == null || colored == Items.AIR ? item : colored;
    }

    /** Uncolored items, and the white one of each colored family (the one the pools list as "on"). */
    public static boolean isUncoloredOrWhite(Item item) {
        return family(item) == null || ForgeRegistries.ITEMS.getKey(item).getPath().startsWith("white_");
    }

    @Nullable
    public static DyeColor colorOf(Item item) {
        return family(item) == null ? null : colorOf(ForgeRegistries.ITEMS.getKey(item).getPath());
    }

    /** For glazed terracotta: red_glazed_terracotta → red. */
    @Nullable
    public static DyeColor colorOf(Block block) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id == null ? null : colorOf(id.getPath());
    }

    @Nullable
    private static DyeColor colorOf(String path) {
        for (DyeColor color : COLORS) {
            if (path.startsWith(color.getName() + "_")) return color;
        }
        return null;
    }

    private static boolean exists(String namespace, String path) {
        return ForgeRegistries.ITEMS.containsKey(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    private ColoredBlocks() {}
}
