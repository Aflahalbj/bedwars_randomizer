package com.bedwarsrandomizer.replay;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** A block that changed during {@code frame} of a replay. */
public record BlockChange(int frame, BlockPos pos, BlockState oldState, BlockState newState) {

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(frame);
        buf.writeBlockPos(pos);
        buf.writeVarInt(Block.getId(oldState));
        buf.writeVarInt(Block.getId(newState));
    }

    public static BlockChange read(FriendlyByteBuf buf) {
        return new BlockChange(buf.readVarInt(), buf.readBlockPos(),
                Block.stateById(buf.readVarInt()), Block.stateById(buf.readVarInt()));
    }
}
