package com.bedwarsrandomizer.block.teambed;

import com.bedwarsrandomizer.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** A vanilla bed entity under its own type (vanilla's only accepts vanilla beds), so the vanilla bed renderer works. */
public class TeamBedBlockEntity extends BedBlockEntity {

    public TeamBedBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public BlockEntityType<?> getType() {
        return ModBlocks.TEAM_BED_ENTITY.get();
    }
}
