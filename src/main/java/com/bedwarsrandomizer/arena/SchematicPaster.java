package com.bedwarsrandomizer.arena;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Pastes a Sponge schematic (WorldEdit {@code .schem}) the way {@code //paste} would with the player standing at the
 * origin. Air is skipped, so it's meant for an empty (void) area.
 */
public final class SchematicPaster {

    public static int paste(ServerLevel level, BlockPos origin, CompoundTag root) {
        return paste(level, origin, root, state -> true);
    }

    /** The area the schematic covers when pasted at {@code origin}. */
    public static BoundingBox bounds(CompoundTag root, BlockPos origin) {
        CompoundTag schematic = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        BlockPos min = origin.offset(pasteOffset(schematic, schematic.getInt("Version")));
        return new BoundingBox(min.getX(), min.getY(), min.getZ(),
                min.getX() + (schematic.getShort("Width") & 0xFFFF) - 1,
                min.getY() + (schematic.getShort("Height") & 0xFFFF) - 1,
                min.getZ() + (schematic.getShort("Length") & 0xFFFF) - 1);
    }

    /** Only blocks accepted by {@code filter} are placed (and only their block entity data is loaded). */
    public static int paste(ServerLevel level, BlockPos origin, CompoundTag root, Predicate<BlockState> filter) {
        CompoundTag schematic = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        int version = schematic.getInt("Version");
        int width = schematic.getShort("Width") & 0xFFFF;
        int height = schematic.getShort("Height") & 0xFFFF;
        int length = schematic.getShort("Length") & 0xFFFF;

        CompoundTag blocks = version >= 3 ? schematic.getCompound("Blocks") : schematic;
        byte[] data = version >= 3 ? blocks.getByteArray("Data") : schematic.getByteArray("BlockData");
        BlockPos min = origin.offset(pasteOffset(schematic, version));

        Map<Integer, BlockState> palette = readPalette(level, blocks.getCompound("Palette"));
        int placed = 0;
        int index = 0;
        int i = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);

            int x = index % width;
            int z = (index / width) % length;
            int y = index / (width * length);
            index++;
            BlockState state = palette.getOrDefault(value, Blocks.AIR.defaultBlockState());
            if (state.isAir() || y >= height || !filter.test(state)) continue;
            // UPDATE_KNOWN_SHAPE: keep fence/wall/stair shapes exactly as saved
            level.setBlock(min.offset(x, y, z), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed++;
        }

        ListTag blockEntities = blocks.getList("BlockEntities", Tag.TAG_COMPOUND);
        for (int e = 0; e < blockEntities.size(); e++) {
            CompoundTag entry = blockEntities.getCompound(e);
            int[] pos = entry.getIntArray("Pos");
            if (pos.length != 3) continue;
            BlockPos at = min.offset(pos[0], pos[1], pos[2]);
            BlockEntity blockEntity = level.getBlockEntity(at);
            if (blockEntity == null || !filter.test(blockEntity.getBlockState())) continue;
            CompoundTag tag = version >= 3 ? entry.getCompound("Data").copy() : entry.copy();
            tag.remove("Pos");
            tag.remove("Id");
            tag.putString("id", entry.getString("Id"));
            tag.putInt("x", at.getX());
            tag.putInt("y", at.getY());
            tag.putInt("z", at.getZ());
            blockEntity.load(tag);
            blockEntity.setChanged();
        }
        return placed;
    }

    /** Corner of the schematic relative to the copy origin. */
    private static BlockPos pasteOffset(CompoundTag schematic, int version) {
        CompoundTag metadata = schematic.getCompound("Metadata");
        if (metadata.contains("WEOffsetX")) {
            return new BlockPos(metadata.getInt("WEOffsetX"), metadata.getInt("WEOffsetY"), metadata.getInt("WEOffsetZ"));
        }
        int[] offset = schematic.getIntArray("Offset");
        return version >= 3 && offset.length == 3 ? new BlockPos(offset[0], offset[1], offset[2]) : BlockPos.ZERO;
    }

    private static Map<Integer, BlockState> readPalette(ServerLevel level, CompoundTag paletteTag) {
        HolderLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteTag.getAllKeys()) {
            BlockState state;
            try {
                state = BlockStateParser.parseForBlock(lookup, key, false).blockState();
            } catch (CommandSyntaxException e) {
                BedwarsRandomizer.LOGGER.warn("Unknown block in arena map: {}", key);
                state = Blocks.AIR.defaultBlockState();
            }
            palette.put(paletteTag.getInt(key), state);
        }
        return palette;
    }

    private SchematicPaster() {}
}
