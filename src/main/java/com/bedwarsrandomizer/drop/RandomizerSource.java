package com.bedwarsrandomizer.drop;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GlazedTerracottaBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * The randomizer blocks, each with its own drop pool: glazed terracotta (blocks and basic gear), warped hyphae (food,
 * potions, light enchantments) and note block (the strong stuff).
 */
public enum RandomizerSource {
    GLAZED_TERRACOTTA, WARPED_HYPHAE, NOTE_BLOCK;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Nullable
    public static RandomizerSource of(BlockState state) {
        if (state.getBlock() instanceof GlazedTerracottaBlock) return GLAZED_TERRACOTTA;
        if (state.is(Blocks.WARPED_HYPHAE)) return WARPED_HYPHAE;
        if (state.is(Blocks.NOTE_BLOCK)) return NOTE_BLOCK;
        return null;
    }
}
