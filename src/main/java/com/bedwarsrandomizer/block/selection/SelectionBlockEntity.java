package com.bedwarsrandomizer.block.selection;

import com.bedwarsrandomizer.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

public class SelectionBlockEntity extends BlockEntity {
    public static final String TAG_OFFSET = "SelectionOffset";
    public static final String TAG_SIZE = "SelectionSize";
    public static final String TAG_SHOW_BOX = "ShowBox";
    public static final String TAG_TEAM = "Team";
    public static final int MAX_SIZE = 128;
    public static final int MAX_OFFSET = 256;

    /**
     * Offset and size are local: they read like world axes when the block faces north and turn with the block
     * (facing east = rotated 90° clockwise, and so on).
     */
    public record Settings(BlockPos offset, BlockPos size, boolean showBox, @Nullable DyeColor team) {
        public static final Settings DEFAULT = new Settings(new BlockPos(0, 1, 0), BlockPos.ZERO, true, null);

        public Settings {
            offset = new BlockPos(clampOffset(offset.getX()), clampOffset(offset.getY()), clampOffset(offset.getZ()));
            size = new BlockPos(clampSize(size.getX()), clampSize(size.getY()), clampSize(size.getZ()));
        }

        public boolean hasVolume() {
            return size.getX() > 0 && size.getY() > 0 && size.getZ() > 0;
        }

        public Settings withSize(BlockPos newSize) {
            return new Settings(offset, newSize, showBox, team);
        }

        public static Settings read(CompoundTag tag) {
            int[] offset = tag.getIntArray(TAG_OFFSET);
            int[] size = tag.getIntArray(TAG_SIZE);
            return new Settings(
                    offset.length == 3 ? new BlockPos(offset[0], offset[1], offset[2]) : DEFAULT.offset,
                    size.length == 3 ? new BlockPos(size[0], size[1], size[2]) : DEFAULT.size,
                    !tag.contains(TAG_SHOW_BOX) || tag.getBoolean(TAG_SHOW_BOX),
                    tag.contains(TAG_TEAM) ? DyeColor.byName(tag.getString(TAG_TEAM), null) : null);
        }

        public CompoundTag write(CompoundTag tag) {
            tag.putIntArray(TAG_OFFSET, new int[]{offset.getX(), offset.getY(), offset.getZ()});
            tag.putIntArray(TAG_SIZE, new int[]{size.getX(), size.getY(), size.getZ()});
            tag.putBoolean(TAG_SHOW_BOX, showBox);
            if (team != null) tag.putString(TAG_TEAM, team.getName());
            return tag;
        }

        private static int clampOffset(int value) {
            return Mth.clamp(value, -MAX_OFFSET, MAX_OFFSET);
        }

        private static int clampSize(int value) {
            return Mth.clamp(value, 0, MAX_SIZE);
        }
    }

    private Settings settings = Settings.DEFAULT;

    public SelectionBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.SELECTION_BLOCK_ENTITY.get(), pos, state);
    }

    public Settings settings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public Direction facing() {
        return getBlockState().getValue(SelectionBlock.FACING);
    }

    public Rotation rotation() {
        return rotationOf(facing());
    }

    @Nullable
    public BoundingBox worldBox() {
        return worldBox(worldPosition, facing(), settings);
    }

    /** North is the unrotated frame. */
    public static Rotation rotationOf(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static Rotation inverse(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
    }

    /** The selected blocks in world coordinates, or null while the size is still zero. */
    @Nullable
    public static BoundingBox worldBox(BlockPos pos, Direction facing, Settings settings) {
        if (!settings.hasVolume()) return null;
        Rotation rotation = rotationOf(facing);
        BlockPos from = settings.offset();
        BlockPos to = from.offset(settings.size()).offset(-1, -1, -1);
        return BoundingBox.fromCorners(pos.offset(from.rotate(rotation)), pos.offset(to.rotate(rotation)));
    }

    public CompoundTag writeSettings(CompoundTag tag) {
        return settings.write(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        settings = Settings.read(tag);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        settings.write(tag);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public boolean onlyOpCanSetNbt() {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox() {
        return INFINITE_EXTENT_AABB;
    }
}
