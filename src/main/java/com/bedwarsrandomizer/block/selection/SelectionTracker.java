package com.bedwarsrandomizer.block.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Every selection block position in one dimension, so Save can find them even in unloaded chunks. */
public final class SelectionTracker extends SavedData {
    private static final String NAME = "bedwarsrandomizer_selection_blocks";

    private final Set<BlockPos> positions = new LinkedHashSet<>();

    public static SelectionTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SelectionTracker::load, SelectionTracker::new, NAME);
    }

    private static SelectionTracker load(CompoundTag tag) {
        SelectionTracker tracker = new SelectionTracker();
        for (long packed : tag.getLongArray("positions")) {
            tracker.positions.add(BlockPos.of(packed));
        }
        return tracker;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("positions", positions.stream().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }

    public void add(BlockPos pos) {
        if (positions.add(pos.immutable())) setDirty();
    }

    public void remove(BlockPos pos) {
        if (positions.remove(pos)) setDirty();
    }

    public List<BlockPos> positions() {
        return new ArrayList<>(positions);
    }
}
